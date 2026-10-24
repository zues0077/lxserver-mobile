package com.zues007.lxservermobile

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions and saves the stack trace so the user can report
 * a crash without needing adb. On Android 10+ the trace is written to the public
 * Download folder via MediaStore (easy to find from the phone's file manager);
 * otherwise it falls back to the app-specific external files directory.
 */
object CrashLogger {

    private const val TAG = "LXSERVER_CRASH"

    /** Installs a global handler so any uncaught crash is persisted. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            save(app, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Saves [throwable] to a text file and returns a human-readable location. */
    fun save(context: Context, throwable: Throwable): String? {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val text = buildString {
            appendLine("time=$ts")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("app=${context.packageName} ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
            appendLine("------------ stack ------------")
            append(Log.getStackTraceString(throwable))
        }
        Log.e(TAG, text)
        val fileName = "lxserver-crash-$ts.txt"

        // 1) Public Download via MediaStore (Android 10+): visible in 文件管理→下载
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    return "下载/$fileName"
                }
            }
        } catch (_: Throwable) {
            // fall through to the external-files fallback
        }

        // 2) Fallback: app external files dir (no permission required)
        return try {
            val dir = context.getExternalFilesDir(null) ?: File(context.filesDir, "crash")
            dir.mkdirs()
            val f = File(dir, fileName)
            f.writeText(text)
            f.absolutePath
        } catch (_: Throwable) {
            null
        }
    }
}
