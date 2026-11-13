package com.zues007.lxservermobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zues007.lxservermobile.databinding.ActivitySettingsBinding
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivitySettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = getString(R.string.title_settings)

            prefs = AppPreferences(this)
            binding.etServerUrl.setText(prefs.serverUrl)
            binding.etUsername.setText(prefs.username)
            binding.etUserPassword.setText(prefs.userPassword)
            binding.etAdminPassword.setText(prefs.adminPassword)
            binding.etPlayerPassword.setText(prefs.playerPassword)

            binding.btnSave.setOnClickListener { save() }
            binding.btnTest.setOnClickListener { testConnection() }
            binding.btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }

            binding.cbWebviewDebug.isChecked = prefs.webViewDebug
            binding.cbWebviewDebug.setOnCheckedChangeListener { _, checked ->
                prefs.webViewDebug = checked
                // 立即生效；已经打开的 WebView 建议下拉刷新一次页面再观察。
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG || checked)
            }

            showWebViewInfo()
            binding.tvLogPath.text = getString(
                R.string.settings_log_path,
                CrashLogger.eventsLogPath(this)
            )
        } catch (t: Throwable) {
            showStartupCrash(t)
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统电池设置页返回，回到前台时刷新一次状态。
        if (::binding.isInitialized) updateBatteryStatus()
    }

    private fun showStartupCrash(t: Throwable) {
        val where = CrashLogger.save(this, t)
        val tv = android.widget.TextView(this).apply {
            setPadding(48, 96, 48, 48)
            textSize = 11f
            text = "设置页启动失败：\n\n" + android.util.Log.getStackTraceString(t) +
                "\n\n日志已保存到：\n" + (where ?: "（保存失败）")
        }
        setContentView(android.widget.ScrollView(this).apply { addView(tv) })
    }

    // -------------------------------------------------------------- 后台保活

    private fun updateBatteryStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            binding.tvBattery.text = ""
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignored = pm.isIgnoringBatteryOptimizations(packageName)
        binding.tvBattery.text = getString(
            if (ignored) R.string.battery_ignored else R.string.battery_not_ignored
        )
    }

    /**
     * 打开系统「忽略电池优化」确认框。国内 ROM 上这个 Intent 有可能被拦，
     * 所以失败时退回到电池优化列表页，再不行就提示用户手动去设置。
     */
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(this, R.string.action_battery_whitelist, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t2: Throwable) {
                Toast.makeText(this, R.string.battery_not_ignored, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showWebViewInfo() {
        val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()
        } else {
            null
        }
        binding.tvWebview.text = getString(
            R.string.settings_webview_version,
            pkg?.versionName ?: "未知",
            pkg?.packageName ?: "-"
        )
    }

    // ------------------------------------------------------------------ 连接

    private fun normalize(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            url = "http://$url"
        }
        return url.trimEnd('/')
    }

    private fun save() {
        val url = normalize(binding.etServerUrl.text.toString())
        prefs.serverUrl = url
        prefs.username = binding.etUsername.text.toString()
        prefs.userPassword = binding.etUserPassword.text.toString()
        prefs.adminPassword = binding.etAdminPassword.text.toString()
        prefs.playerPassword = binding.etPlayerPassword.text.toString()
        prefs.firstRun = false
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()

        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    private fun testConnection() {
        val url = normalize(binding.etServerUrl.text.toString())
        if (url.isEmpty()) {
            binding.tvStatus.text = getString(R.string.test_empty)
            return
        }
        binding.tvStatus.text = getString(R.string.testing)
        Thread {
            try {
                val conn = URL("$url/health").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                val ok = code in 200..399
                Handler(Looper.getMainLooper()).post {
                    binding.tvStatus.text = if (ok) {
                        getString(R.string.test_ok, code)
                    } else {
                        getString(R.string.test_fail, code)
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    binding.tvStatus.text = getString(R.string.test_error, e.message ?: "unknown")
                }
            }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
