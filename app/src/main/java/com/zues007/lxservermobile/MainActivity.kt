package com.zues007.lxservermobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.zues007.lxservermobile.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences

    /** 渲染进程被系统回收后会被整个换掉，所以这里不能是 lateinit/val。 */
    private var webView: WebView? = null
    private var loadedUrl: String? = null
    private var recoveringRenderer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)

            prefs = AppPreferences(this)
            // savedInstanceState != null 说明上一次进程是被系统杀掉后重建的，
            // 这条记录和「渲染进程异常退出」日志配合，能把「被内存回收」和
            // 「真的崩了」彻底区分开。
            CrashLogger.logEvent(
                this,
                "activity_create",
                "recreated=${savedInstanceState != null}"
            )

            attachNewWebView()
            maybeOpenSettings()
            ensureNotificationPermission()
        } catch (t: Throwable) {
            showStartupCrash(t)
        }
    }

    /**
     * If anything throws during startup we render the stack trace on screen
     * (instead of a silent crash) so the user can screenshot it, and persist it
     * to a file that can be shared.
     */
    private fun showStartupCrash(t: Throwable) {
        val where = CrashLogger.save(this, t)
        val tv = android.widget.TextView(this).apply {
            setPadding(48, 96, 48, 48)
            textSize = 11f
            text = "启动失败：\n\n" + android.util.Log.getStackTraceString(t) +
                "\n\n日志已保存到：\n" + (where ?: "（保存失败）")
        }
        setContentView(android.widget.ScrollView(this).apply { addView(tv) })
    }

    // ------------------------------------------------------------- WebView 装配

    private fun attachNewWebView() {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        configureWebView(wv)
        // index 0：让进度条和错误提示仍然叠在网页之上。
        binding.webContainer.addView(wv, 0)
        webView = wv

        // 前台服务 / 通知栏按钮 -> 网页
        WebViewBridge.attach { js ->
            runOnUiThread {
                try {
                    webView?.evaluateJavascript(js, null)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun configureWebView(wv: WebView) {
        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        // --- phone-friendliness ---
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadsImagesAutomatically = true
        settings.setNeedInitialFocus(false)
        // Parts of the player open links in a new window (rankings, playlists…).
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)

        settings.userAgentString =
            "${WebSettings.getDefaultUserAgent(this)} LXServerMobile/${BuildConfig.VERSION_NAME}"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)

        // 页面 -> 原生：播放状态 / 歌名
        wv.addJavascriptInterface(
            LxJsBridge { playing, title, artist -> onPlaybackState(playing, title, artist) },
            JS_BRIDGE_NAME
        )

        wv.webViewClient = LxWebViewClient(
            credentials = { prefs.credentials() },
            onMainFrameError = { message -> showError(message) },
            onRendererGone = { detail -> onRendererGone(detail) }
        )

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressbar.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
                binding.progressbar.progress = newProgress
            }

            // Reuse this WebView for windows the page tries to open, otherwise a
            // new-window click would just do nothing on a single-WebView shell.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (view == null || resultMsg == null) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }
        }

        wv.setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
            startDownload(url, contentDisposition, mimeType)
        })
    }

    // ------------------------------------------------------- 渲染进程死亡自愈

    /**
     * WebView 的网页跑在独立渲染进程里。系统内存紧张时可能直接杀它，它自己也可能
     * 崩掉。**不处理这个回调（默认返回 false）的话，WebView 会按渲染进程的死因把
     * 整个 App 一起干掉**，而且是 native 层 kill：不抛 Java 异常、CrashLogger 抓不到，
     * 用户看到的就是「App 没有任何提示突然消失」。
     *
     * 官方要求：旧实例必须从视图树移除并销毁、不能复用；新实例才能继续显示网页。
     * 这里返回 true → App 活下来，页面自动重新加载。
     */
    private fun onRendererGone(detail: RenderProcessGoneDetail?): Boolean {
        val didCrash = detail?.didCrash() ?: false
        CrashLogger.logEvent(this, "render_process_gone", "didCrash=$didCrash")

        if (recoveringRenderer) return true
        recoveringRenderer = true
        try {
            val dead = webView
            webView = null
            WebViewBridge.detach()
            if (dead != null) {
                try {
                    (dead.parent as? ViewGroup)?.removeView(dead)
                } catch (_: Throwable) {
                }
                try {
                    dead.destroy()
                } catch (_: Throwable) {
                }
            }

            loadedUrl = null
            attachNewWebView()

            val url = normalizeBase(prefs.serverUrl)
            if (url.isNotEmpty()) {
                loadedUrl = url
                webView?.post { webView?.loadUrl(url) }
            }
            hideError()
            Toast.makeText(
                this,
                if (didCrash) R.string.renderer_crashed_reloaded else R.string.renderer_reclaimed_reloaded,
                Toast.LENGTH_LONG
            ).show()
        } catch (t: Throwable) {
            CrashLogger.logEvent(this, "renderer_recovery_failed", t.toString())
        } finally {
            recoveringRenderer = false
        }
        return true
    }

    // -------------------------------------------------------------- 播放状态

    /**
     * 网页里的 <audio> 一旦真正开始播放，就拉起前台服务把进程钉住；
     * 暂停/播完则交给服务在宽限期后自行收掉。
     */
    private fun onPlaybackState(playing: Boolean, title: String, artist: String) {
        if (playing) {
            PlaybackService.onPlaying(this, title, artist)
        } else {
            PlaybackService.onPaused(this)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }

    // ------------------------------------------------------------- 生命周期

    /**
     * Load (or reload) whenever the configured URL differs from what is currently
     * shown. Doing it in onResume fixes two issues at once:
     *  - coming back from the Settings screen never reloaded the new URL (blank page);
     *  - loadUrl() in onCreate could paint blank until a manual refresh.
     */
    override fun onResume() {
        super.onResume()
        val wv = webView ?: return
        val url = normalizeBase(prefs.serverUrl)
        if (url.isNotEmpty() && url != loadedUrl) {
            loadedUrl = url
            hideError()
            wv.post { wv.loadUrl(url) }
        }
    }

    override fun onPause() {
        super.onPause()
        // lxserver keeps its login in a cookie; flush it so the session survives
        // an app restart instead of prompting for the password again.
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        WebViewBridge.detach()
        if (isFinishing) {
            // 用户真的退出 App：关掉前台服务和音频，避免留下幽灵通知。
            PlaybackService.requestStop(this)
        }
        val wv = webView
        webView = null
        if (wv != null) {
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
            } catch (_: Throwable) {
            }
            try {
                wv.destroy()
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }

    private fun maybeOpenSettings() {
        if (normalizeBase(prefs.serverUrl).isEmpty()) {
            openSettings()
        }
    }

    private fun normalizeBase(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            url = "http://$url"
        }
        return url.trimEnd('/')
    }

    private fun showError(message: String) {
        runOnUiThread {
            binding.errorText.visibility = View.VISIBLE
            binding.errorText.text = getString(R.string.error_load, message)
        }
    }

    private fun hideError() {
        binding.errorText.visibility = View.GONE
    }

    private fun startDownload(
        url: String,
        contentDisposition: String,
        mimeType: String
    ) {
        val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
        request.setNotificationVisibility(
            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        )
        request.setTitle("LXServer 下载")
        val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        request.setDestinationInExternalPublicDir(
            android.os.Environment.DIRECTORY_DOWNLOADS,
            name
        )
        val dm = getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                openSettings()
                true
            }
            R.id.action_refresh -> {
                webView?.reload()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 有历史就后退；没有历史时退到后台而不是 finish —— 音乐类 App 按返回键
     * 应该继续播，而不是把正在听的东西直接掐掉。
     */
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            moveTaskToBack(true)
        }
    }

    private companion object {
        const val JS_BRIDGE_NAME = "LXNative"
        const val REQUEST_NOTIFICATIONS = 2001
    }
}
