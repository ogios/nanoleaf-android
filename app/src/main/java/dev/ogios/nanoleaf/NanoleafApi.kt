package dev.ogios.nanoleaf

import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ControlException(message: String) : IOException(message)

data class Endpoint(val ip: String, val port: Int = 16021) {
    init {
        val parts = ip.split('.')
        val octets = parts.map { part ->
            part.toIntOrNull()?.takeIf { it in 0..255 && it.toString() == part }
        }
        require(parts.size == 4 && octets.all { it != null }) { "请输入有效的局域网 IPv4 地址" }
        require(octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 169 && octets[1] == 254)) { "只支持局域网 IPv4 地址" }
        require(port in 1..65535) { "端口应为 1–65535" }
    }
    val baseUrl: String get() = "http://$ip:$port/api/v1"
}

// Deliberately avoid data-class toString(), which would expose the credential.
class Device(val endpoint: Endpoint, val token: String, val model: String = "未知型号") {
    init {
        require(token.matches(Regex("[A-Za-z0-9]{1,128}"))) { "授权令牌必须为 1–128 位字母或数字" }
    }
    val url: String get() = "${endpoint.baseUrl}/$token"
}

data class ApiResponse(val status: Int, val body: String)

fun interface Transport {
    fun request(method: String, url: String, body: String?): ApiResponse
}

class HttpTransport : Transport {
    override fun request(method: String, url: String, body: String?): ApiResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 65536) throw ControlException("设备响应过大")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            return ApiResponse(status, bytes.toString(Charsets.UTF_8))
        } catch (error: ControlException) {
            throw error
        } catch (_: IOException) {
            // IOException messages can contain the URL, including the token.
            throw ControlException("无法连接灯带，请检查 Wi-Fi、设备 IP 和端口")
        } finally {
            connection.disconnect()
        }
    }
}

class NanoleafApi(private val transport: Transport = HttpTransport()) {
    private fun request(method: String, url: String, body: String? = null, pairing: Boolean = false): String {
        val response = transport.request(method, url, body)
        if (response.status == 401 || response.status == 403) {
            throw ControlException(if (pairing) "请在 Nanoleaf App 的设备设置中点击 Connect to API，并在 30 秒内重试"
                else "授权已失效，请重新连接设备")
        }
        if (response.status !in 200..299) throw ControlException("设备拒绝请求（HTTP ${response.status}）")
        return response.body
    }

    private fun json(body: String): JSONObject = try {
        JSONObject(body)
    } catch (_: Exception) {
        throw ControlException("设备响应格式无效")
    }

    fun authorize(endpoint: Endpoint, model: String): Device {
        val token = json(request("POST", "${endpoint.baseUrl}/new", pairing = true)).opt("auth_token")
        if (token !is String) throw ControlException("授权响应缺少令牌")
        return try { Device(endpoint, token, model) }
        catch (_: IllegalArgumentException) { throw ControlException("设备返回了无效的授权令牌") }
    }

    fun read(device: Device): Boolean {
        val response = json(request("GET", "${device.url}/state/on"))
        // Essentials and older controllers have shipped different response shapes.
        val on = response.opt("on")
        val value = when (on) {
            is Boolean -> on
            is JSONObject -> on.opt("value")
            else -> response.opt("value")
        }
        return value as? Boolean ?: throw ControlException("设备未返回有效的开关状态")
    }

    fun model(device: Device): String = json(request("GET", device.url)).optString("model", device.model)
        .take(80).ifBlank { device.model }

    fun setPower(device: Device, on: Boolean): Boolean {
        // Writes use the state endpoint and nested value, matching nanoleafapi
        // and the Matter WiFi Essentials API. /state/on is a read endpoint.
        request("PUT", "${device.url}/state", "{\"on\":{\"value\":$on}}")
        val actual = read(device)
        if (actual != on) throw ControlException("命令已发送，但灯带尚未确认目标状态，请刷新")
        return actual
    }

    fun toggle(device: Device): Boolean = setPower(device, !read(device))
}
