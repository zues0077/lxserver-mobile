package com.zues007.lxservermobile

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 投放（DLNA）状态机。
 *
 * 音频由渲染器自己从 lxserver 回源拉流，手机只发控制指令：
 *  - start()  推地址 + Play
 *  - toggle() 通知栏/锁屏的播放暂停
 *  - pushTrack() 页面切歌后重新推一次地址
 *  - stop()   停设备，并把设备上的进度带回来给本机续播
 *
 * 进度靠每 2 秒 GetPositionInfo 回填到 MediaSession，这样锁屏进度条在投放时也准。
 */
object CastController {

    private const val TAG = "LXSERVER_CAST"

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var isCasting: Boolean = false
        private set

    @Volatile
    private var playing: Boolean = false

    private var device: DlnaDevice? = null
    private var currentUri: String = ""
    private var pollRunnable: Runnable? = null

    /** 设备最近一次上报的进度，断投时用它给本机续播。 */
    @Volatile
    private var lastPositionMs: Long = 0

    fun deviceName(): String = device?.friendlyName ?: ""

    fun isPlaying(): Boolean = playing

    fun start(
        target: DlnaDevice,
        uri: String,
        title: String,
        artist: String,
        done: (Boolean, String) -> Unit
    ) {
        if (uri.isEmpty()) {
            done(false, "当前没有可投放的播放地址")
            return
        }
        Thread {
            val ok = try {
                DlnaControl.setUri(target, uri, title, artist) && DlnaControl.play(target)
            } catch (t: Throwable) {
                Log.w(TAG, "投放失败: ${t.message}")
                false
            }
            if (ok) {
                device = target
                currentUri = uri
                playing = true
                lastPositionMs = 0
                isCasting = true
                startPolling()
                PlaybackService.pushPlayingState(true)
            }
            handler.post { done(ok, if (ok) "" else "设备没有接受播放指令") }
        }.start()
    }

    fun toggle() {
        val target = device ?: return
        val wantPlay = !playing
        Thread {
            val ok = if (wantPlay) DlnaControl.play(target) else DlnaControl.pause(target)
            if (ok) {
                playing = wantPlay
                PlaybackService.pushPlayingState(wantPlay)
            }
        }.start()
    }

    fun resume() {
        val target = device ?: return
        Thread {
            if (DlnaControl.play(target)) {
                playing = true
                PlaybackService.pushPlayingState(true)
            }
        }.start()
    }

    fun seekTo(positionMs: Long) {
        val target = device ?: return
        lastPositionMs = positionMs
        Thread { DlnaControl.seek(target, positionMs) }.start()
    }

    /**
     * 页面切歌（或用户点了播放）后，把新地址推给设备。
     * 地址没变说明只是「继续播放」，直接 Play 即可，避免打断设备上的缓冲。
     */
    fun pushTrack(uri: String, title: String, artist: String) {
        val target = device ?: return
        if (uri.isEmpty()) return
        if (uri == currentUri) {
            resume()
            return
        }
        currentUri = uri
        lastPositionMs = 0
        Thread {
            val ok = DlnaControl.setUri(target, uri, title, artist) && DlnaControl.play(target)
            if (ok) {
                playing = true
                PlaybackService.pushPlayingState(true)
            } else {
                Log.w(TAG, "切歌推送失败: $title")
            }
        }.start()
    }

    /** 停止投放，回调带回设备上的播放进度（毫秒），供本机续播。 */
    fun stop(done: ((Long) -> Unit)? = null) {
        val target = device
        val position = lastPositionMs
        stopPolling()
        device = null
        currentUri = ""
        isCasting = false
        playing = false
        Thread {
            if (target != null) {
                try {
                    DlnaControl.stop(target)
                } catch (_: Throwable) {
                }
            }
            handler.post { done?.invoke(position) }
        }.start()
    }

    private fun startPolling() {
        stopPolling()
        val runnable = object : Runnable {
            override fun run() {
                if (!isCasting) return
                val target = device ?: return
                Thread {
                    val info = try {
                        DlnaControl.getPosition(target)
                    } catch (t: Throwable) {
                        null
                    }
                    if (info != null && isCasting) {
                        lastPositionMs = info.first
                        PlaybackService.pushProgress(info.first, info.second)
                    }
                }.start()
                handler.postDelayed(this, 2000)
            }
        }
        pollRunnable = runnable
        handler.postDelayed(runnable, 1200)
    }

    private fun stopPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
    }
}
