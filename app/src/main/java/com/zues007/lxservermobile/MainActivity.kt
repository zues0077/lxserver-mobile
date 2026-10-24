package com.zues007.lxservermobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zues007.lxservermobile.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences
    private lateinit var webView: WebView
    private var loadedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)

            prefs = AppPreferences(this)
            webView = binding.webview

            setupWebView()
            maybeOpenSettings()
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

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        // NOTE: setAppCacheEnabled() was removed from the SDK (AppCache is dead);
        // calling it fails to compile against compileSdk 34.
        settings.userAgentString =
            "${WebSettings.getDefaultUserAgent(this)} LXServerMobile/${BuildConfig.VERSION_NAME}"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = LxWebViewClient(
            usernameProvider = { prefs.username },
            passwordProvider = { prefs.password }
        ) { message -> showError(message) }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressbar.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
                binding.progressbar.progress = newProgress
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            startDownload(url, contentDisposition, mimeType)
        }
    }

    /**
     * Load (or reload) whenever the configured URL differs from what is currently
     * shown. Doing it in onResume fixes two issues at once:
     *  - coming back from the Settings screen never reloaded the new URL (blank page);
     *  - loadUrl() in onCreate could paint blank until a manual refresh.
     */
    override fun onResume() {
        super.onResume()
        if (!::webView.isInitialized) return
        val url = normalizeBase(prefs.serverUrl)
        if (url.isNotEmpty() && url != loadedUrl) {
            loadedUrl = url
            hideError()
            // post() defers until after the first layout pass.
            webView.post { webView.loadUrl(url) }
        }
    }

    override fun onPause() {
        super.onPause()
        // lxserver keeps its login in a cookie; flush it so the session survives
        // an app restart instead of prompting for the password again.
        CookieManager.getInstance().flush()
    }

    private fun maybeOpenSettings() {
        // On startup only: nothing configured yet -> send the user to Settings.
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
                webView.reload()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
