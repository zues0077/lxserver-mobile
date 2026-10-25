package com.zues007.lxservermobile

import android.content.Context
import android.content.SharedPreferences

/**
 * Typed wrapper around SharedPreferences storing the lxserver connection details.
 *
 * lxserver really has THREE independent passwords — they are NOT interchangeable:
 *  - adminPassword  : lxserver config `admin.password` ("管理员密码"), used to log
 *                     into the admin console AND the player's admin mode.
 *  - playerPassword : lxserver config `player.password` ("Web 播放器访问密码"), only
 *                     used when `player.enableAuth` is on.
 *  - userPassword   : the password of an ordinary login user, paired with `username`.
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

    /** lxserver `admin.password` — admin console / player admin. */
    var adminPassword: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    /** Password of an ordinary login user (paired with [username]). */
    var userPassword: String
        get() = prefs.getString(KEY_USER_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_PASSWORD, value).apply()

    /** lxserver `player.password` — used when player auth is enabled. */
    var playerPassword: String
        get() = prefs.getString(KEY_PLAYER_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PLAYER_PASSWORD, value).apply()

    var firstRun: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN, true)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_RUN, value).apply()

    /** Snapshot handed to the WebViewClient for auto-login. */
    fun credentials() = Credentials(
        username = username,
        userPassword = userPassword,
        adminPassword = adminPassword,
        playerPassword = playerPassword
    )

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val PREFS_NAME = "lxserver_mobile_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_USER_PASSWORD = "user_password"
        private const val KEY_PLAYER_PASSWORD = "player_password"
        private const val KEY_FIRST_RUN = "first_run"
    }
}

data class Credentials(
    val username: String,
    val userPassword: String,
    val adminPassword: String,
    val playerPassword: String
)
