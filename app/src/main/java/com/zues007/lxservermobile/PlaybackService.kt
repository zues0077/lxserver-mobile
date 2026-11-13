package com.zues007.lxservermobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * 媒体播放前台服务。
 *
 * 音频本身仍然由 WebView 里的 <audio> 播放，本服务只负责两件事：
 *  1) 把本进程登记成「正在播放媒体」的前台进程。Android 8+ 的后台限制、Doze、
 *     以及各家 ROM（realme / OPPO / 小米…）的省电策略都会在息屏后冻结或回收
 *     普通后台进程，这就是「听着听着 App 自己没了」的直接原因；只有前台服务
 *     能把进程优先级提到不容易被杀的那一档。同时持有 PARTIAL_WAKE_LOCK +
 *     WifiLock，避免息屏后 CPU 休眠 / Wi-Fi 省电模式把音频流掐断。
 *  2) 提供通知栏 / 锁屏播放控制，把「上一首 / 播放暂停 / 下一首」通过
 *     [WebViewBridge] 转发回网页里的 playPrev() / togglePlay() / playNext()。
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

    override fun onCreate() {
        super.onCreate()
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

            ACTION_TOGGLE -> WebViewBridge.runJs("typeof togglePlay==='function'&&togglePlay()")
            ACTION_NEXT -> WebViewBridge.runJs("typeof playNext==='function'&&playNext()")
            ACTION_PREV -> WebViewBridge.runJs("typeof playPrev==='function'&&playPrev()")
            ACTION_STOP -> stopSafely()
        }
        // 不用 START_STICKY：进程被重建时 WebView 已经没了，空跑的服务毫无意义。
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
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
            // Android 12+ 在后台不允许启动前台服务；此时只能退化成普通服务，
            // 至少不会把 App 弄崩。
            CrashLogger.logEvent(this, "start_foreground_failed", t.toString())
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title.ifEmpty { getString(R.string.app_name) })
            .setContentText(
                if (artist.isNotEmpty()) artist else getString(
                    if (playing) R.string.notif_playing else R.string.notif_paused
                )
            )
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

    // ------------------------------------------------------------- MediaSession

    private fun initMediaSession() {
        val session = MediaSession(this, "LXServerMobile")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                WebViewBridge.runJs("typeof togglePlay==='function'&&togglePlay()")
            }

            override fun onPause() {
                WebViewBridge.runJs("typeof togglePlay==='function'&&togglePlay()")
            }

            override fun onSkipToNext() {
                WebViewBridge.runJs("typeof playNext==='function'&&playNext()")
            }

            override fun onSkipToPrevious() {
                WebViewBridge.runJs("typeof playPrev==='function'&&playPrev()")
            }
        })
        session.setSessionActivity(openAppIntent())
        session.isActive = true
        mediaSession = session
    }

    private fun refreshSessionAndNotification() {
        val session = mediaSession
        if (session != null) {
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .build()
            session.setMetadata(metadata)
            val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_STOP
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1f
                    )
                    .build()
            )
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Throwable) {
        }
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
