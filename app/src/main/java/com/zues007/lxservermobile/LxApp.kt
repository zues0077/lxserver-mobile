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
        // diagnosing layout / console errors and renderer deaths on a real device.
        // Debug builds always allow it; release builds only when the user turns on
        // 「允许 WebView 调试」in the settings screen.
        val prefs = AppPreferences(this)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG || prefs.webViewDebug)
    }
}
