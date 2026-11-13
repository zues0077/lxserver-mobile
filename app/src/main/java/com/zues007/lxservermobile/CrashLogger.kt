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

    /**
     * 追加一条事件日志（不崩的异常也记）。
     *
     * 为什么需要它：WebView 渲染进程被系统杀掉、或者 App 被 LMK 直接 kill 时，
     * 整个进程是被 native 层结束的，[install] 里的 uncaught handler **不会执行**。
     * 所以「App 消失」这种问题光靠崩溃栈永远查不到，必须把关键节点（进程重建、
     * 渲染进程死亡、前台服务启动失败）主动落到文件里。
     *
     * 文件位置：Android/data/<包名>/files/logs/events.log（超过 256KB 自动重来）。
     */
    fun logEvent(context: Context, event: String, detail: String = "") {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "$ts [$event] $detail"
        Log.i(TAG, line)
        try {
            val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
            dir.mkdirs()
            val file = File(dir, "events.log")
            if (file.length() > 256 * 1024) file.delete()
            file.appendText(line + "\n")
        } catch (_: Throwable) {
            // 日志本身失败绝不能影响主流程
        }
    }

    /** 事件日志路径，设置页展示用。 */
    fun eventsLogPath(context: Context): String {
        val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
        return File(dir, "events.log").absolutePath
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
