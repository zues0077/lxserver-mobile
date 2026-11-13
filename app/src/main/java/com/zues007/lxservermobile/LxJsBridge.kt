package com.zues007.lxservermobile

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * 暴露给网页的原生桥（window.LXNative）。
 *
 * 只注入到用户自己配置的 lxserver 页面里，不做跨域页面加载，因此接口面保持最小：
 *  - setPlayback(playing, title, artist)：页面 <audio> 的播放状态/歌名变化
 *  - log(message)：页面侧的异常，落到 logcat 便于 chrome://inspect 配合排查
 *
 * 注意：@JavascriptInterface 的方法运行在 WebView 的 JS 线程，必须 post 回主线程，
 * 否则启动服务、更新通知会踩到 "thread not main" 的坑。
 */
class LxJsBridge(
    private val onPlayback: (playing: Boolean, title: String, artist: String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun setPlayback(playing: Boolean, title: String?, artist: String?) {
        val t = title ?: ""
        val a = artist ?: ""
        mainHandler.post { onPlayback(playing, t, a) }
    }

    @JavascriptInterface
    fun log(message: String?) {
        Log.i(TAG, message ?: "")
    }

    private companion object {
        const val TAG = "LXSERVER_JS"
    }
}

/**
 * 前台服务 -> WebView 的反向通道。通知栏/锁屏按下按钮时，通过这里把指令
 * 送回网页（调用页面已有的全局函数 togglePlay / playNext / playPrev）。
 */
object WebViewBridge {

    @Volatile
    private var runner: ((String) -> Unit)? = null

    fun attach(runJs: (String) -> Unit) {
        runner = runJs
    }

    fun detach() {
        runner = null
    }

    fun runJs(script: String) {
        val current = runner ?: return
        try {
            current(script)
        } catch (t: Throwable) {
            Log.w("LXSERVER_BRIDGE", "runJs failed: ${t.message}")
        }
    }
}
