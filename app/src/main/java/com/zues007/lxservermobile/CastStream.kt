package com.zues007.lxservermobile

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
 * 鉴权：走 WebView 里的会话 Cookie（player.enableAuth 打开时必需）。
 */
object CastStream {

    private const val TAG = "LXSERVER_CAST"

    /** 取 URL 的真实 Content-Type（服务端中转会把上游的 Content-Type 原样回传）。 */
    fun contentTypeOf(url: String, cookie: String?): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.requestMethod = "HEAD"
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
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
     * @return 可投放的绝对地址；失败返回 null。
     */
    fun mint(
        serverOrigin: String,
        cookie: String?,
        sourceUrl: String,
        title: String,
        artist: String,
        durationMs: Long,
        contentType: String,
        format: String?
    ): String? {
        if (serverOrigin.isEmpty() || sourceUrl.isEmpty()) return null
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
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            if (code !in 200..299) {
                Log.w(TAG, "签发地址失败 HTTP $code: ${text.take(200)}")
                return null
            }
            val json = JSONObject(text)
            val data = json.optJSONObject("data")
            val streamUrl = data?.optString("streamUrl") ?: ""
            streamUrl.ifEmpty { null }
        } catch (t: Throwable) {
            Log.w(TAG, "签发地址异常: ${t.message}")
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
