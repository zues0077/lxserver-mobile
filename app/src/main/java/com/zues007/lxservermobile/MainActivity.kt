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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefs = AppPreferences(this)
        webView = binding.webview

        setupWebView()
        loadInitial()
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
            serverBase = normalizeBase(prefs.serverUrl),
            useBuiltin = prefs.useBuiltin,
            username = prefs.username,
            password = prefs.password
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

    private fun loadInitial() {
        hideError()
        when {
            prefs.useBuiltin -> {
                webView.loadUrl("file:///android_asset/webplayer/index.html")
            }
            prefs.serverUrl.isEmpty() -> {
                // Nothing configured yet: send the user to Settings.
                openSettings()
            }
            else -> {
                webView.loadUrl(normalizeBase(prefs.serverUrl))
            }
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
