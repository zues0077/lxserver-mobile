package com.zues007.lxservermobile

import android.os.Build
import android.util.Base64
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * WebViewClient for the lxserver client.
 *
 * - In remote mode it simply loads the user's server URL.
 * - In built-in mode the page is served from `file:///android_asset/webplayer/`.
 *   The web player uses relative API paths (`/api/...`, `/rest`, ...) which would
 *   otherwise resolve to the file:// origin and fail. This client intercepts those
 *   requests and proxies them to the real server ([serverBase]) so the bundled UI
 *   works fully offline-bundled while talking to the user's backend.
 * - Supplies HTTP Basic credentials when the server asks for them.
 */
class LxWebViewClient(
    private val serverBase: String,
    private val useBuiltin: Boolean,
    private val username: String,
    private val password: String,
    private val onMainFrameError: (String) -> Unit
) : WebViewClient() {

    // Paths that must be forwarded to the real lxserver backend.
    private val apiPrefixes = listOf(
        "/api/", "/rest", "/webdav", "/health",
        "/socket.io", "/sync", "/subsonic", "/auth"
    )

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        // Keep all navigation inside the WebView.
        return false
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: android.webkit.HttpAuthHandler?,
        host: String?,
        realm: String?
    ) {
        if (username.isNotEmpty() && password.isNotEmpty()) {
            handler?.proceed(username, password)
        } else {
            handler?.cancel()
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (!useBuiltin || request == null) return null
        val url = request.url
        if (url.scheme == "file") {
            val path = url.path ?: return null
            if (apiPrefixes.any { path.startsWith(it) }) {
                return proxyToServer(request, path)
            }
        }
        return null
    }

    private fun proxyToServer(request: WebResourceRequest, path: String): WebResourceResponse? {
        return try {
            val target = StringBuilder(serverBase).append(path)
            val query = request.url.query
            if (!query.isNullOrEmpty()) target.append("?").append(query)

            val conn = URL(target.toString()).openConnection() as HttpURLConnection
            conn.requestMethod = request.method
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false

            request.requestHeaders?.forEach { (key, value) ->
                if (key.equals("Host", ignoreCase = true)) return@forEach
                try {
                    conn.setRequestProperty(key, value)
                } catch (_: Exception) {
                    // ignore unsupported header
                }
            }

            if (username.isNotEmpty() && password.isNotEmpty()) {
                val auth = "$username:$password"
                val encoded = Base64.encodeToString(
                    auth.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                conn.setRequestProperty("Authorization", "Basic $encoded")
            }

            val method = request.method.uppercase(Locale.ROOT)
            if (method == "POST" || method == "PUT") {
                // NOTE: WebResourceRequest does not expose the request body, so a
                // POST/PUT payload cannot be forwarded from the bundled UI. Keep
                // doOutput so the backend still receives a well-formed POST/PUT.
                conn.doOutput = true
            }

            conn.connect()

            val status = conn.responseCode
            val mime = conn.contentType ?: "application/octet-stream"
            val encoding = conn.contentEncoding ?: "UTF-8"
            val stream: InputStream = if (status in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream ?: conn.inputStream
            }
            val headers = mutableMapOf<String, String>()
            conn.headerFields?.forEach { (key, values) ->
                if (key != null && !values.isNullOrEmpty()) {
                    headers[key] = values.joinToString(",")
                }
            }
            WebResourceResponse(mime, encoding, status, conn.responseMessage ?: "", headers, stream)
        } catch (e: IOException) {
            WebResourceResponse(
                "text/plain", "UTF-8", 502, "Bad Gateway", emptyMap(),
                ByteArrayInputStream(("proxy error: " + (e.message ?: "io")).toByteArray())
            )
        } catch (e: Exception) {
            WebResourceResponse(
                "text/plain", "UTF-8", 500, "Error", emptyMap(),
                ByteArrayInputStream((e.message ?: "error").toByteArray())
            )
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request?.isForMainFrame == true) {
            onMainFrameError(error?.description?.toString() ?: "load error")
        }
        super.onReceivedError(view, request, error)
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        if (request?.isForMainFrame == true) {
            onMainFrameError("HTTP ${errorResponse?.statusCode}")
        }
        super.onReceivedHttpError(view, request, errorResponse)
    }
}
