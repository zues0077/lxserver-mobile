package com.zues007.lxservermobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * 媒体播放前台服务。
 *
 * 音频仍然由 WebView 里的 <audio>（或投放时的 DLNA 设备）播放，本服务负责：
 *  1) 把进程登记成「正在播放媒体」的前台进程，避免息屏/后台被冻结回收；
 *     同时持 PARTIAL_WAKE_LOCK + WifiLock，防止 CPU 休眠 / Wi-Fi 省电断流。
 *  2) 维护 MediaSession：标题、歌手、封面、总时长、实时进度 —— 锁屏/通知栏
 *     的进度条和拖拽都靠它，缺 duration 时系统只能画成 "----"。
 *  3) 通知栏 / 锁屏的上一首-播放暂停-下一首：本地模式转发给网页的
 *     playPrev/togglePlay/playNext；投放模式转发给 DLNA 设备。
 */
class PlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mediaSession: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null

    private var playing = false
    private var title = ""
    private var artist = ""
    private var durationMs = 0L
    private var positionMs = 0L
    private var coverUrl = ""
    private var coverBitmap: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        initMediaSession()
        acquireLocks()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 显式捕获非空引用：when (intent?.action) 不会把 intent 智能转换成非空。
        val command = intent ?: return START_NOT_STICKY
        when (command.action) {
            ACTION_START -> {
                playing = true
                userDismissed = false
                command.getStringExtra(EXTRA_TITLE)?.let { title = it }
                command.getStringExtra(EXTRA_ARTIST)?.let { artist = it }
                cancelStop()
                goForeground()
                refreshSessionAndNotification()
            }

            ACTION_PAUSE -> {
                playing = false
                goForeground()
                refreshSessionAndNotification()
                // 留 8 秒宽限：切歌时页面会先 pause 再 play，避免服务反复起停。
                scheduleStop(8000)
            }

            ACTION_TOGGLE -> {
                if (CastController.isCasting) {
                    CastController.toggle()
                } else {
                    WebViewBridge.runJs("typeof togglePlay==='function'&&togglePlay()")
                }
            }

            ACTION_NEXT -> WebViewBridge.runJs("typeof playNext==='function'&&playNext()")
            ACTION_PREV -> WebViewBridge.runJs("typeof playPrev==='function'&&playPrev()")
            ACTION_STOP -> {
                if (CastController.isCasting) CastController.stop()
                // 用户主动关掉通知：记下来，避免页面心跳把服务立刻又拉起来。
                userDismissed = true
                stopSafely()
            }
        }
        // 不用 START_STICKY：进程被重建时 WebView 已经没了，空跑的服务毫无意义。
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        releaseLocks()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Throwable) {
        }
        mediaSession = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------ 进度 / 状态

    /**
     * 页面（或投放轮询）上报进度。只有总时长变化时才重建 metadata，
     * 每秒的 position 只更新 PlaybackState，不刷通知（否则通知栏一直闪）。
     */
    private fun applyProgress(newPositionMs: Long, newDurationMs: Long, fromCast: Boolean) {
        if (!isRunning) return
        if (CastController.isCasting && !fromCast) return
        positionMs = if (newPositionMs > 0) newPositionMs else 0
        if (newDurationMs > 0 && newDurationMs != durationMs) {
            durationMs = newDurationMs
            mediaSession?.setMetadata(buildMetadata())
        }
        updatePlaybackState()
    }

    private fun applyPlaying(value: Boolean) {
        if (!isRunning) return
        playing = value
        updatePlaybackState()
        notifySafely()
    }

    /**
     * 封面：页面把封面 URL 传过来，这里带 WebView 的 Cookie 去拉（同一个
     * lxserver 的封面接口可能要登录态）。失败就不设，保持原来的占位图标。
     */
    private fun applyCover(url: String) {
        if (url == coverUrl) return
        coverUrl = url
        if (url.isEmpty() || !url.startsWith("http")) {
            coverBitmap = null
            return
        }
        Thread {
            val bitmap = loadBitmap(url)
            if (bitmap == null) return@Thread
            handler.post {
                if (url == coverUrl && isRunning) {
                    coverBitmap = bitmap
                    mediaSession?.setMetadata(buildMetadata())
                }
            }
        }.start()
    }

    private fun loadBitmap(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
            val bytes = conn.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            while (largest > 0 && largest / sample > 720) sample *= 2
            val options = BitmapFactory.Options()
            options.inSampleSize = sample
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            try {
                conn.disconnect()
            } catch (_: Throwable) {
            }
        }
    } catch (t: Throwable) {
        null
    }

    private fun buildMetadata(): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
        if (durationMs > 0) {
            builder.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        }
        coverBitmap?.let { builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        return builder.build()
    }

    private fun updatePlaybackState() {
        val session = mediaSession ?: return
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    positionMs,
                    1f
                )
                .build()
        )
    }

    // ---------------------------------------------------------------- 前台通知

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    // LOW：不出声、不弹横幅，但通知条常驻，这是前台服务的硬性要求。
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    description = getString(R.string.notif_channel_desc)
                }
            )
        }
    }

    private fun goForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // Android 12+ 在后台不允许启动前台服务；退化成普通服务，至少不崩。
            CrashLogger.logEvent(this, "start_foreground_failed", t.toString())
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val subtitle = when {
            CastController.isCasting && CastController.deviceName().isNotEmpty() ->
                getString(R.string.notif_casting_to, CastController.deviceName())

            artist.isNotEmpty() -> artist
            else -> getString(if (playing) R.string.notif_playing else R.string.notif_paused)
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title.ifEmpty { getString(R.string.app_name) })
            .setContentText(subtitle)
            .setContentIntent(openAppIntent())
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (playing) {
            builder.addAction(
                android.R.drawable.ic_media_previous,
                getString(R.string.notif_prev),
                serviceAction(ACTION_PREV, 11)
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notif_pause),
                serviceAction(ACTION_TOGGLE, 12)
            )
            builder.addAction(
                android.R.drawable.ic_media_next,
                getString(R.string.notif_next),
                serviceAction(ACTION_NEXT, 13)
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_play,
                getString(R.string.notif_resume),
                serviceAction(ACTION_TOGGLE, 14)
            )
        }
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.notif_close),
            serviceAction(ACTION_STOP, 15)
        )
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, 1, intent, pendingFlags())
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun refreshSessionAndNotification() {
        mediaSession?.setMetadata(buildMetadata())
        updatePlaybackState()
        notifySafely()
    }

    private fun notifySafely() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------- MediaSession

    private fun initMediaSession() {
        val session = MediaSession(this, "LXServerMobile")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (CastController.isCasting) {
                    CastController.resume()
                } else {
                    WebViewBridge.runJs("typeof togglePlay==='function'&&togglePlay()")
                }
            }

            override fun onPause() {
                if (CastController.isCasting) {
                    CastController.toggle()
                } else {
                    WebViewBridge.runJs("typeof togglePlay==='function'&&togglePlay()")
                }
            }

            override fun onSkipToNext() {
                WebViewBridge.runJs("typeof playNext==='function'&&playNext()")
            }

            override fun onSkipToPrevious() {
                WebViewBridge.runJs("typeof playPrev==='function'&&playPrev()")
            }

            // 锁屏拖动进度条：投放时 seek 设备，本地时 seek 页面 <audio>。
            override fun onSeekTo(pos: Long) {
                if (CastController.isCasting) {
                    CastController.seekTo(pos)
                } else {
                    val seconds = pos / 1000.0
                    WebViewBridge.runJs(
                        "(function(){try{var a=document.getElementById('audio-player')" +
                            "||document.querySelector('audio');if(a){a.currentTime=$seconds;}}" +
                            "catch(e){}})()"
                    )
                }
            }
        })
        session.setSessionActivity(openAppIntent())
        session.isActive = true
        mediaSession = session
    }

    // ------------------------------------------------------------------- 锁

    private fun acquireLocks() {
        if (wakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "lxserver:playback"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Throwable) {
            }
        }
        if (wifiLock == null) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "lxserver:wifi"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        wifiLock = null
    }

    // ------------------------------------------------------------------- 停止

    private fun scheduleStop(delayMs: Long) {
        cancelStop()
        val runnable = Runnable { stopSafely() }
        stopRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun cancelStop() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
    }

    private fun stopSafely() {
        cancelStop()
        // minSdk 26，STOP_FOREGROUND_REMOVE（API 24）可直接用。
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "lxserver_playback"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.zues007.lxservermobile.action.PLAYBACK_START"
        const val ACTION_PAUSE = "com.zues007.lxservermobile.action.PLAYBACK_PAUSE"
        const val ACTION_STOP = "com.zues007.lxservermobile.action.PLAYBACK_STOP"
        const val ACTION_TOGGLE = "com.zues007.lxservermobile.action.PLAYBACK_TOGGLE"
        const val ACTION_NEXT = "com.zues007.lxservermobile.action.PLAYBACK_NEXT"
        const val ACTION_PREV = "com.zues007.lxservermobile.action.PLAYBACK_PREV"

        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"

        @Volatile
        var isRunning: Boolean = false

        /** 用户从通知栏点了「关闭」；页面心跳不再自动拉起服务，直到下一次真实播放。 */
        @Volatile
        var userDismissed: Boolean = false

        @Volatile
        private var instance: PlaybackService? = null

        /** 页面 <audio> 的进度（本地播放）。投放中忽略，避免覆盖设备进度。 */
        fun updateLocalProgress(positionMs: Long, durationMs: Long) {
            instance?.applyProgress(positionMs, durationMs, false)
        }

        /** 投放设备的进度。 */
        fun pushProgress(positionMs: Long, durationMs: Long) {
            instance?.applyProgress(positionMs, durationMs, true)
        }

        fun pushPlayingState(playing: Boolean) {
            instance?.applyPlaying(playing)
        }

        fun updateCover(url: String) {
            instance?.applyCover(url)
        }

        /** 页面开始播放：拉起（或更新）前台服务。 */
        fun onPlaying(context: Context, title: String, artist: String) {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_ARTIST, artist)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                CrashLogger.logEvent(context, "start_playback_service_failed", t.toString())
            }
        }

        /** 页面暂停/播完：宽限后自动收掉服务。 */
        fun onPaused(context: Context) {
            if (!isRunning) return
            try {
                context.startService(
                    Intent(context, PlaybackService::class.java).setAction(ACTION_PAUSE)
                )
            } catch (_: Throwable) {
                // 服务已在后台且不允许再启动：它自己有 8 秒超时会收掉，忽略即可。
            }
        }

        fun requestStop(context: Context) {
            if (!isRunning) return
            try {
                context.startService(
                    Intent(context, PlaybackService::class.java).setAction(ACTION_STOP)
                )
            } catch (_: Throwable) {
            }
        }
    }
}
