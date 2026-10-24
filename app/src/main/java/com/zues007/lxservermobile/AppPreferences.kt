package com.zues007.lxservermobile

import android.content.Context
import android.content.SharedPreferences

/**
 * Typed wrapper around SharedPreferences that stores the user's lxserver
 * connection details entered in the Settings screen.
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value.trim()).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    /** When true the app loads the bundled frontend from assets and proxies API
     *  calls to [serverUrl]; when false it loads [serverUrl] directly. */
    var useBuiltin: Boolean
        get() = prefs.getBoolean(KEY_USE_BUILTIN, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_BUILTIN, value).apply()

    var firstRun: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN, true)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_RUN, value).apply()

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val PREFS_NAME = "lxserver_mobile_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_USE_BUILTIN = "use_builtin"
        private const val KEY_FIRST_RUN = "first_run"
    }
}
