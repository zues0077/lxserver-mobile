package com.zues007.lxservermobile

import android.app.Application

/** Installs the crash logger as early as possible. */
class LxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
