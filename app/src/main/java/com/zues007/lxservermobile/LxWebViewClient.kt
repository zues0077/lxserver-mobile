package com.zues007.lxservermobile

import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * WebViewClient for loading the user's own lxserver web UI:
 *  - keeps every navigation inside the WebView;
 *  - answers HTTP Basic auth challenges with the stored credentials;
 *  - auto-logins whenever a known login screen is displayed, using the matching
 *    password (admin / player / ordinary user are three separate things);
 *  - surfaces main-frame load errors.
 */
class LxWebViewClient(
    private val credentials: () -> Credentials,
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
        val c = credentials()
        val user = c.username
        val pwd = c.userPassword.ifEmpty { c.adminPassword }
        if (user.isNotEmpty() && pwd.isNotEmpty()) {
            handler?.proceed(user, pwd)
        } else {
            handler?.cancel()
        }
    }

    /**
     * Auto-login. Two possible login screens exist:
     *  1) admin console  (public/index.html)
     *       #access-password  +  #login-btn
     *  2) music player   (public/music/login.html) — two tabs:
     *       user mode    : #auth-username-input + #auth-user-password-input
     *       player mode  : #auth-player-password-input   (tab: #btn-tab-player)
     *       both submit via #submit-btn
     * Only acts when the relevant form is actually visible, so it never touches
     * the player UI nor an already-logged-in session.
     */
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val c = credentials()
        if (view == null) return
        if (c.adminPassword.isEmpty() && c.playerPassword.isEmpty() && c.userPassword.isEmpty()) return
        view.evaluateJavascript(autoLoginScript(c), null)
    }

    private fun esc(s: String) = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")

    private fun autoLoginScript(c: Credentials): String {
        val admin = esc(c.adminPassword)
        val user = esc(c.username)
        val userPwd = esc(c.userPassword)
        val playerPwd = esc(c.playerPassword)
        return """
            (function(){
              try {
                // 1) admin console
                var ap = document.getElementById('access-password');
                var ab = document.getElementById('login-btn');
                if (ap && ab && ap.offsetParent !== null && !ap.value && '$admin') {
                  ap.value = '$admin';
                  ap.dispatchEvent(new Event('input', {bubbles:true}));
                  setTimeout(function(){ try { ab.click(); } catch(e){} }, 250);
                  return;
                }
                // 2) music player login
                var sb = document.getElementById('submit-btn');
                if (!sb) return;
                var un = document.getElementById('auth-username-input');
                var up = document.getElementById('auth-user-password-input');
                var pp = document.getElementById('auth-player-password-input');
                function setVal(el, v){ el.value = v; el.dispatchEvent(new Event('input', {bubbles:true})); }
                function clickSubmit(){ setTimeout(function(){ try { sb.click(); } catch(e){} }, 300); }
                if (up && up.offsetParent !== null) {
                  if ('$user' && '$userPwd') {
                    if (un && !un.value) setVal(un, '$user');
                    if (!up.value) setVal(up, '$userPwd');
                    clickSubmit();
                    return;
                  }
                  if ('$playerPwd') {
                    var tp = document.getElementById('btn-tab-player');
                    if (tp) {
                      tp.click();
                      setTimeout(function(){
                        if (pp && !pp.value) setVal(pp, '$playerPwd');
                        clickSubmit();
                      }, 350);
                    }
                    return;
                  }
                  return;
                }
                if (pp && pp.offsetParent !== null && '$playerPwd') {
                  if (!pp.value) setVal(pp, '$playerPwd');
                  clickSubmit();
                }
              } catch(e){}
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
