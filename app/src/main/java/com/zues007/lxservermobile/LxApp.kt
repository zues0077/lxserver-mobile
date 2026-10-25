package com.zues007.lxservermobile

import android.app.Application
import android.webkit.WebView

/** Installs the crash logger as early as possible. */
class LxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // Lets you inspect the app's WebView from a desktop Chrome at
        // chrome://inspect (phone on USB, USB debugging on) — handy for
        // diagnosing layout / console errors on the real device.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }
}
