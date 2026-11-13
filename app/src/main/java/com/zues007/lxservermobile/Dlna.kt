package com.zues007.lxservermobile

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

private const val TAG = "LXSERVER_DLNA"

/** 局域网里的一台 DLNA 渲染器（音箱 / 电视 / 功放）。 */
data class DlnaDevice(
    val friendlyName: String,
    val modelName: String,
    val location: String,
    /** AVTransport 的 controlURL（已解析成绝对地址）。 */
    val controlUrl: String
) {
    val displayName: String
        get() = if (modelName.isNotEmpty() && modelName != friendlyName) {
            "$friendlyName（$modelName）"
        } else {
            friendlyName
        }
}

/**
 * 最小可用的 DLNA/UPnP 控制点发现：SSDP M-SEARCH 找 MediaRenderer，
 * 拉设备描述 XML，取 AVTransport 的 controlURL。
 *
 * 为什么要 MulticastLock：部分机型在息屏/省电模式下会丢弃组播包，
 * 不持锁经常「一台设备都搜不到」。
 */
object DlnaDiscovery {

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val ST_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    fun discover(context: Context, timeoutMs: Long = 3000): List<DlnaDevice> {
        val locations = LinkedHashSet<String>()
        var lock: WifiManager.MulticastLock? = null
        var socket: DatagramSocket? = null
        try {
            try {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                lock = wifi?.createMulticastLock("lxserver:dlna")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Throwable) {
            }

            socket = DatagramSocket()
            socket.soTimeout = 800
            val request = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 2\r\n")
                append("ST: $ST_RENDERER\r\n")
                append("\r\n")
            }.toByteArray()
            val group = InetAddress.getByName(SSDP_ADDR)
            // UDP 丢包很常见，发两遍
            repeat(2) {
                socket.send(DatagramPacket(request, request.size, InetSocketAddress(group, SSDP_PORT)))
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    val location = parseLocation(text)
                    if (location != null) locations.add(location)
                } catch (_: SocketTimeoutException) {
                    // 继续等到 deadline
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "SSDP 搜索失败: ${t.message}")
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
            try {
                lock?.takeIf { it.isHeld }?.release()
            } catch (_: Throwable) {
            }
        }

        val result = ArrayList<DlnaDevice>()
        for (location in locations) {
            val device = loadDevice(location) ?: continue
            // 同一台设备可能被多个 LOCATION / 多次响应命中
            if (result.any { it.controlUrl == device.controlUrl }) continue
            result.add(device)
        }
        return result
    }

    private fun parseLocation(response: String): String? {
        for (line in response.split("\r\n", "\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val header = line.substring(0, idx).trim()
            if (!header.equals("LOCATION", ignoreCase = true)) continue
            val value = line.substring(idx + 1).trim()
            if (value.startsWith("http", ignoreCase = true)) return value
        }
        return null
    }

    private fun loadDevice(location: String): DlnaDevice? {
        val xml = httpGet(location) ?: return null
        return parseDeviceXml(xml, location)
    }

    private fun parseDeviceXml(xml: String, location: String): DlnaDevice? {
        var friendlyName = ""
        var modelName = ""
        var controlUrl = ""
        var urlBase = ""
        var insideAvTransport = false
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase()) {
                        "urlbase" -> urlBase = readText(parser)
                        "friendlyname" -> if (friendlyName.isEmpty()) friendlyName = readText(parser)
                        "modelname" -> if (modelName.isEmpty()) modelName = readText(parser)
                        "service" -> insideAvTransport = false
                        "servicetype" -> {
                            val type = readText(parser)
                            insideAvTransport = type.contains("AVTransport", ignoreCase = true)
                        }
                        "controlurl" -> {
                            val value = readText(parser)
                            if (insideAvTransport && value.isNotEmpty() && controlUrl.isEmpty()) {
                                controlUrl = value
                            }
                        }
                    }
                }
                event = parser.nextEvent()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "解析设备描述失败: ${t.message}")
            return null
        }
        if (controlUrl.isEmpty()) return null
        val base = if (urlBase.isNotEmpty()) urlBase else location
        return DlnaDevice(
            friendlyName = friendlyName.ifEmpty { "DLNA 设备" },
            modelName = modelName,
            location = location,
            controlUrl = resolveUrl(base, controlUrl)
        )
    }

    /** nextText() 只适用于叶子节点；这里做一层防御。 */
    private fun readText(parser: XmlPullParser): String = try {
        parser.nextText().trim()
    } catch (t: Throwable) {
        ""
    }

    fun resolveUrl(base: String, reference: String): String {
        if (reference.startsWith("http", ignoreCase = true)) return reference
        return try {
            URI(base).resolve(reference).toString()
        } catch (t: Throwable) {
            reference
        }
    }

    fun httpGet(url: String, timeoutMs: Int = 4000): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) {
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            try {
                conn.disconnect()
            } catch (_: Throwable) {
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "HTTP GET 失败 $url: ${t.message}")
        null
    }
}

/**
 * AVTransport / RenderingControl 的 SOAP 调用。
 * 播放地址由设备自己回源拉流，手机只是遥控器 —— 因此地址必须是设备能访问的
 * 局域网绝对地址（lxserver 的 /api/music/download 中转接口无鉴权，正好合适）。
 */
object DlnaControl {

    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"

    fun setUri(device: DlnaDevice, uri: String, title: String, artist: String): Boolean {
        val metadata = didl(title, artist, uri)
        val body = buildString {
            append("<u:SetAVTransportURI xmlns:u=\"$AVT\">")
            append("<InstanceID>0</InstanceID>")
            append("<CurrentURI>").append(escapeXml(uri)).append("</CurrentURI>")
            append("<CurrentURIMetaData>").append(escapeXml(metadata)).append("</CurrentURIMetaData>")
            append("</u:SetAVTransportURI>")
        }
        return soap(device.controlUrl, AVT, "SetAVTransportURI", body) != null
    }

    fun play(device: DlnaDevice): Boolean {
        val body = "<u:Play xmlns:u=\"$AVT\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>"
        return soap(device.controlUrl, AVT, "Play", body) != null
    }

    fun pause(device: DlnaDevice): Boolean {
        val body = "<u:Pause xmlns:u=\"$AVT\"><InstanceID>0</InstanceID></u:Pause>"
        return soap(device.controlUrl, AVT, "Pause", body) != null
    }

    fun stop(device: DlnaDevice): Boolean {
        val body = "<u:Stop xmlns:u=\"$AVT\"><InstanceID>0</InstanceID></u:Stop>"
        return soap(device.controlUrl, AVT, "Stop", body) != null
    }

    fun seek(device: DlnaDevice, positionMs: Long): Boolean {
        val body = buildString {
            append("<u:Seek xmlns:u=\"$AVT\">")
            append("<InstanceID>0</InstanceID>")
            append("<Unit>REL_TIME</Unit>")
            append("<Target>").append(formatTime(positionMs)).append("</Target>")
            append("</u:Seek>")
        }
        return soap(device.controlUrl, AVT, "Seek", body) != null
    }

    /** 返回 (当前位置 ms, 总时长 ms)；失败返回 null。 */
    fun getPosition(device: DlnaDevice): Pair<Long, Long>? {
        val body = "<u:GetPositionInfo xmlns:u=\"$AVT\"><InstanceID>0</InstanceID></u:GetPositionInfo>"
        val response = soap(device.controlUrl, AVT, "GetPositionInfo", body) ?: return null
        val position = parseTime(tagValue(response, "RelTime"))
        val duration = parseTime(tagValue(response, "TrackDuration"))
        return Pair(position, duration)
    }

    private fun soap(controlUrl: String, service: String, action: String, innerBody: String): String? {
        val envelope = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body>").append(innerBody).append("</s:Body>")
            append("</s:Envelope>")
        }
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(controlUrl).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 3000
            c.readTimeout = 5000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"$service#$action\"")
            c.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code in 200..299) {
                c.inputStream.bufferedReader().use { it.readText() }
            } else {
                val detail = try {
                    c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Throwable) {
                    ""
                }
                Log.w(TAG, "$action 返回 HTTP $code: $detail")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$action 失败: ${t.message}")
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun tagValue(xml: String, tag: String): String {
        val match = Regex("<" + tag + ">([^<]*)</" + tag + ">").find(xml) ?: return ""
        return match.groupValues[1].trim()
    }

    private fun parseTime(value: String): Long {
        val text = value.trim()
        if (text.isEmpty() || text.contains("NOT_IMPLEMENTED", ignoreCase = true)) return 0
        val parts = text.split(":")
        if (parts.size != 3) return 0
        val hours = parts[0].toLongOrNull() ?: 0
        val minutes = parts[1].toLongOrNull() ?: 0
        val seconds = parts[2].toDoubleOrNull() ?: 0.0
        return (hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong()
    }

    private fun formatTime(positionMs: Long): String {
        val total = if (positionMs > 0) positionMs / 1000 else 0
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun didl(title: String, artist: String, uri: String): String = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
        append("<item id=\"1\" parentID=\"0\" restricted=\"1\">")
        append("<dc:title>").append(escapeXml(title)).append("</dc:title>")
        append("<dc:creator>").append(escapeXml(artist)).append("</dc:creator>")
        append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        append("<res protocolInfo=\"http-get:*:audio/mpeg:*\">").append(escapeXml(uri)).append("</res>")
        append("</item></DIDL-Lite>")
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
