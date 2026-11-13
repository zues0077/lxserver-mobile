package com.zues007.lxservermobile

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * 向 lxserver 申请「可投放的播放地址」。
 *
 * 音箱/电视是**按 URL 后缀挑解码器**的（lxserver 自己的小爱投送也是这么处理的：
 * `streamToken.ts` 会按真实 Content-Type 给地址补 `.mp3/.flac/.m4a`）。而播放器
 * 实际用的是 `/api/music/download?url=…`，路径没有后缀，设备会判成无法播放
 * （小爱实测提示「播放遇到问题，稍后再试」）。
 *
 * 这里复用服务端已有的签名流通道 `POST /api/music/xiaoai/stream-url`：
 * 返回 `/api/music/xiaoai/stream.<ext>?token=…`，带正确后缀、可被设备直接拉取。
 * 只签发地址，不涉及任何小米账号/设备。
 *
 * 鉴权有两条路，两条都带上：
 *  - `x-frontend-auth: <管理员密码>`（isXiaoAiRequestAuthorized 认这个，最可靠，不依赖 Cookie）
 *  - WebView 的会话 Cookie（player.enableAuth 打开时生效）
 * 这台库的 player.enableAuth = true，匿名请求会 401，所以管理员密码那条是主路。
 */
object CastStream {

    private const val TAG = "LXSERVER_CAST"

    /** 最近一次失败原因（给 UI 提示用）。 */
    @Volatile
    var lastError: String = ""
        private set

    private fun fail(context: Context?, reason: String) {
        lastError = reason
        Log.w(TAG, reason)
        if (context != null) CrashLogger.logEvent(context, "cast_mint_failed", reason)
    }

    /** 取 URL 的真实 Content-Type（服务端中转会把上游的 Content-Type 原样回传）。 */
    fun contentTypeOf(url: String, cookie: String?, authHeader: String?): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.requestMethod = "HEAD"
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
            if (!authHeader.isNullOrEmpty()) conn.setRequestProperty("x-frontend-auth", authHeader)
            val type = conn.contentType ?: ""
            type.split(';')[0].trim().lowercase()
        } catch (t: Throwable) {
            Log.w(TAG, "HEAD 取 Content-Type 失败: ${t.message}")
            ""
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 申请签名播放地址。
     *
     * @param format "mp3" 表示让服务端统一转 mp3（设备只吃 mp3 时用）；null = 原格式。
     * @return 可投放的绝对地址；失败返回 null（原因见 [lastError]）。
     */
    fun mint(
        context: Context?,
        serverOrigin: String,
        cookie: String?,
        authHeader: String?,
        sourceUrl: String,
        title: String,
        artist: String,
        durationMs: Long,
        contentType: String,
        format: String?
    ): String? {
        lastError = ""
        if (serverOrigin.isEmpty() || sourceUrl.isEmpty()) {
            fail(context, "服务器地址或播放地址为空")
            return null
        }
        val payload = JSONObject().apply {
            put("url", sourceUrl)
            put("title", title)
            put("artist", artist)
            put("durationMs", durationMs)
            put("contentType", contentType)
            if (format != null) put("format", format)
        }.toString()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("$serverOrigin/api/music/xiaoai/stream-url").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 20000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
            if (!authHeader.isNullOrEmpty()) conn.setRequestProperty("x-frontend-auth", authHeader)
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            if (code !in 200..299) {
                val hint = when (code) {
                    401 -> "鉴权失败(401)：请在设置里填「管理员密码」"
                    404 -> "服务器没有签发接口(404)：装的是旧版 lxserver"
                    else -> "HTTP $code"
                }
                fail(context, "签发地址失败 $hint @ $serverOrigin ${text.take(120)}")
                null
            } else {
                val data = JSONObject(text).optJSONObject("data")
                val streamUrl = data?.optString("streamUrl") ?: ""
                streamUrl.ifEmpty {
                    fail(context, "签发接口没返回 streamUrl @ $serverOrigin")
                    null
                }
            }
        } catch (t: Throwable) {
            fail(context, "签发地址异常: ${t.message} @ $serverOrigin")
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** 从用户填的服务器地址里取出 scheme://host:port（接口都在根路径下）。 */
    fun originOf(serverUrl: String): String = try {
        val uri = URI(serverUrl.trim())
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "${uri.scheme}://${uri.host}$port"
    } catch (t: Throwable) {
        serverUrl.trim().trimEnd('/')
    }
}
