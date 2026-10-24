package com.zues007.lxservermobile

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            binding.etPassword.setText(prefs.password)

            binding.btnSave.setOnClickListener { save() }
            binding.btnTest.setOnClickListener { testConnection() }
        } catch (t: Throwable) {
            showStartupCrash(t)
        }
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
        prefs.password = binding.etPassword.text.toString()
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
