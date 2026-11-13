package com.zues007.lxservermobile

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 投放（DLNA）状态机。
 *
 * 音频由渲染器自己从 lxserver 回源拉流，手机只发控制指令：
 *  - start()  依次尝试若干候选地址（服务端签发的原格式 / mp3 转码版 / 原始中转地址），
 *             每个地址推下去后用 GetTransportInfo 回查是否真的起播 —— 只看 SOAP 返回 200
 *             是不够的，很多设备对拉不动的地址照样回 200，然后自己报「播放遇到问题」。
 *  - toggle() 通知栏/锁屏的播放暂停
 *  - pushTrack() 页面切歌后重新签发地址并推送
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

    /** 切歌时用来重新申请地址（由 MainActivity 注入）。 */
    private var resolver: ((String, String) -> List<() -> String?>)? = null

    /** 设备最近一次上报的进度，断投时用它给本机续播。 */
    @Volatile
    private var lastPositionMs: Long = 0

    fun deviceName(): String = device?.friendlyName ?: ""

    fun isPlaying(): Boolean = playing

    fun setResolver(r: (String, String) -> List<() -> String?>) {
        resolver = r
    }

    fun start(
        target: DlnaDevice,
        title: String,
        artist: String,
        providers: List<() -> String?>,
        done: (Boolean, String, String) -> Unit
    ) {
        Thread {
            val startedUri = tryProviders(target, title, artist, providers)
            if (startedUri != null) {
                device = target
                currentUri = startedUri
                playing = true
                lastPositionMs = 0
                isCasting = true
                startPolling()
                PlaybackService.pushPlayingState(true)
                handler.post { done(true, "", startedUri) }
            } else {
                handler.post {
                    done(false, "设备没有开始播放（地址拉不动或格式不支持）", "")
                }
            }
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
     * 页面切歌后重新投放：地址要重新签发（签名带过期时间，且新歌是新的 URL）。
     */
    fun pushTrack(title: String, artist: String) {
        val target = device ?: return
        val resolve = resolver ?: return
        Thread {
            val startedUri = tryProviders(target, title, artist, resolve(title, artist)) ?: return@Thread
            currentUri = startedUri
            lastPositionMs = 0
            playing = true
            PlaybackService.pushPlayingState(true)
        }.start()
    }

    /**
     * 停止投放，回调带回设备上的播放进度（毫秒），供本机续播。
     *
     * 两处容易踩坑的地方：
     *  1. 不能直接用 [lastPositionMs]——那是最多 2 秒前轮询到的值，设备若一直没回过
     *     GetPositionInfo（或刚投放就停）它就是 0，本机会从头播。这里在停设备前
     *     **主动再查一次**，拿不到才回落到轮询值。
     *  2. 本机续播不能等设备 Stop 的 SOAP 往返：设备响应慢时手机会干等好几秒。
     *     所以先回调让本机立刻播起来，Stop 放到同一后台线程的后半段去做。
     */
    fun stop(done: ((Long) -> Unit)? = null) {
        val target = device
        val fallback = lastPositionMs
        stopPolling()
        device = null
        currentUri = ""
        isCasting = false
        playing = false
        Thread {
            var position = fallback
            if (target != null) {
                try {
                    val info = DlnaControl.getPosition(target)
                    if (info != null && info.first > 0) position = info.first
                } catch (_: Throwable) {
                    // 设备已经掉线/不响应：保留轮询值，不要让本机卡住
                }
            }
            // 先让本机从设备进度续播，不等 Stop
            handler.post { done?.invoke(position) }
            if (target != null) {
                try {
                    DlnaControl.stop(target)
                } catch (_: Throwable) {
                }
            }
        }.start()
    }

    // --------------------------------------------------------------- 内部

    /**
     * 按顺序尝试候选地址，返回第一个「真的起播」的地址。
     * 每个候选都是阻塞操作（可能含服务端签发 + 转码预取），所以必须跑在后台线程。
     */
    private fun tryProviders(
        target: DlnaDevice,
        title: String,
        artist: String,
        providers: List<() -> String?>
    ): String? {
        var lastState = ""
        for (provider in providers) {
            val url = try {
                provider()
            } catch (t: Throwable) {
                Log.w(TAG, "候选地址生成失败: ${t.message}")
                null
            }
            if (url.isNullOrEmpty()) continue
            lastState = pushAndWait(target, url, title, artist)
            Log.i(TAG, "候选地址 ${url.take(90)} → $lastState")
            if (isPlayable(lastState)) return url
        }
        Log.w(TAG, "所有候选地址都没起播，最后一次状态=$lastState")
        return null
    }

    /** 推地址 + Play，然后在 6 秒内轮询传输状态，直到进入播放态。 */
    private fun pushAndWait(
        target: DlnaDevice,
        url: String,
        title: String,
        artist: String
    ): String {
        if (!DlnaControl.setUri(target, url, title, artist)) return "SETURI_FAILED"
        DlnaControl.play(target)
        val deadline = System.currentTimeMillis() + 6000
        var state = ""
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(700)
            } catch (_: InterruptedException) {
                break
            }
            state = DlnaControl.getTransportState(target)
            if (isPlayable(state)) return state
        }
        return state
    }

    private fun isPlayable(state: String): Boolean =
        state == "PLAYING" || state == "TRANSITIONING"

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
