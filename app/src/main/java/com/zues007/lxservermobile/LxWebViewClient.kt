package com.zues007.lxservermobile

import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Minimal WebViewClient for loading the user's own lxserver web UI:
 *  - keeps every navigation inside the WebView;
 *  - answers HTTP Basic auth challenges with the stored credentials;
 *  - auto-fills the lxserver admin login form (if one is shown) so the user does
 *    not have to type the password on every launch;
 *  - surfaces main-frame load errors.
 */
class LxWebViewClient(
    private val usernameProvider: () -> String,
    private val passwordProvider: () -> String,
    private val onMainFrameError: (String) -> Unit
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        // Keep all navigation inside the WebView.
        return false
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: android.webkit.HttpAuthHandler?,
        host: String?,
        realm: String?
    ) {
        val user = usernameProvider()
        val pwd = passwordProvider()
        if (user.isNotEmpty() && pwd.isNotEmpty()) {
            handler?.proceed(user, pwd)
        } else {
            handler?.cancel()
        }
    }

    /**
     * Auto-login: lxserver's console login form (see public/index.html) is
     *   <input id="access-password"> + <button id="login-btn">
     * When it is visible and empty but we have a stored password, fill it and
     * submit. Only acts when the form is actually shown, so it never interferes
     * with the normal player UI or an already-logged-in session.
     */
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val pwd = passwordProvider()
        if (view == null || pwd.isEmpty()) return
        view.evaluateJavascript(autoLoginScript(pwd), null)
    }

    private fun autoLoginScript(pwd: String): String {
        val escaped = pwd
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        return """
            (function(){
              try {
                var p = document.getElementById('access-password');
                var b = document.getElementById('login-btn');
                if (!p || !b) return;                    // not the login screen
                if (p.offsetParent === null) return;     // form hidden -> already logged in
                if (p.value && p.value.length) return;   // already filled
                p.value = '$escaped';
                p.dispatchEvent(new Event('input', {bubbles:true}));
                setTimeout(function(){ try { b.click(); } catch(e){} }, 250);
              } catch(e) {}
            })();
        """.trimIndent()
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
