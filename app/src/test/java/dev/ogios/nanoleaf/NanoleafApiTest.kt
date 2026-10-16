package dev.ogios.nanoleaf

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class NanoleafApiTest {
    private val endpoint = Endpoint("192.168.0.106")
    private val device = Device(endpoint, "testToken123", "NL72K1")

    private class FakeTransport(vararg responses: ApiResponse) : Transport {
        val pending = ArrayDeque(responses.toList())
        val calls = mutableListOf<Triple<String, String, String?>>()
        override fun request(method: String, url: String, body: String?): ApiResponse {
            calls.add(Triple(method, url, body))
            return pending.removeFirst()
        }
    }

    @Test fun onlyLiteralLocalAddressesAndValidPortsAreAccepted() {
        for (ip in listOf("10.0.0.1", "172.16.2.3", "172.31.255.1", "192.168.0.1", "169.254.1.2")) {
            Endpoint(ip)
        }
        for (ip in listOf("8.8.8.8", "127.0.0.1", "172.32.0.1", "localhost", "192.168.0.2:80",
            "192.168.0.2/path", "192.168.00.2", "192.168.0.256", "192.168.0")) {
            assertThrows(IllegalArgumentException::class.java) { Endpoint(ip) }
        }
        assertThrows(IllegalArgumentException::class.java) { Endpoint("192.168.0.1", 0) }
        assertThrows(IllegalArgumentException::class.java) { Endpoint("192.168.0.1", 65536) }
    }

    @Test fun tokenCannotInjectPathsOrQueries() {
        for (token in listOf("", "a/b", "a?b", "a#b", "a\nb", "a".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { Device(endpoint, token) }
        }
        assertFalse(device.toString().contains(device.token))
    }

    @Test fun supportsObservedPowerResponseShapes() {
        for (body in listOf("{\"on\":true}", "{\"on\":{\"value\":true}}", "{\"value\":true}")) {
            assertTrue(NanoleafApi(FakeTransport(ApiResponse(200, body))).read(device))
        }
    }

    @Test fun malformedPowerIsUnknown() {
        for (body in listOf("garbage", "{}", "{\"on\":\"false\"}", "{\"on\":null}")) {
            assertThrows(ControlException::class.java) {
                NanoleafApi(FakeTransport(ApiResponse(200, body))).read(device)
            }
        }
    }

    @Test fun toggleReadsActualStateWritesOppositeAndVerifies() {
        val transport = FakeTransport(ApiResponse(200, "{\"on\":true}"), ApiResponse(204, ""),
            ApiResponse(200, "{\"on\":false}"))
        assertFalse(NanoleafApi(transport).toggle(device))
        assertEquals(listOf("GET", "PUT", "GET"), transport.calls.map { it.first })
        assertEquals(listOf("${device.url}/state/on", "${device.url}/state", "${device.url}/state/on"),
            transport.calls.map { it.second })
        assertEquals("{\"on\":{\"value\":false}}", transport.calls[1].third)
    }

    @Test fun failedReadNeverWrites() {
        val transport = FakeTransport(ApiResponse(403, ""))
        assertThrows(ControlException::class.java) { NanoleafApi(transport).toggle(device) }
        assertEquals(listOf("GET"), transport.calls.map { it.first })
    }

    @Test fun successfulPutWithoutConfirmedChangeIsNotReportedAsSuccess() {
        val transport = FakeTransport(ApiResponse(204, ""), ApiResponse(200, "{\"on\":false}"))
        assertThrows(ControlException::class.java) { NanoleafApi(transport).setPower(device, true) }
    }

    @Test fun authorizationUsesDiscoveredPortAndValidatesToken() {
        val transport = FakeTransport(ApiResponse(200, "{\"auth_token\":\"abc123\"}"))
        val result = NanoleafApi(transport).authorize(Endpoint(endpoint.ip, 12345), "NL72K1")
        assertEquals("abc123", result.token)
        assertEquals(Triple("POST", "http://${endpoint.ip}:12345/api/v1/new", null), transport.calls.single())
        assertThrows(ControlException::class.java) {
            NanoleafApi(FakeTransport(ApiResponse(200, "{\"auth_token\":\"../bad\"}"))).authorize(endpoint, "")
        }
    }

    @Test fun closedPairingWindowHasActionableErrorWithoutResponseBody() {
        val error = assertThrows(ControlException::class.java) {
            NanoleafApi(FakeTransport(ApiResponse(403, "secretResponse"))).authorize(endpoint, "")
        }
        assertTrue(error.message!!.contains("Connect to API"))
        assertFalse(error.message!!.contains("secretResponse"))
    }

    @Test fun followUpReadFailureIsNotReportedAsSuccessfulSwitch() {
        val transport = FakeTransport(ApiResponse(200, "{\"on\":false}"), ApiResponse(204, ""), ApiResponse(500, ""))
        assertThrows(ControlException::class.java) { NanoleafApi(transport).toggle(device) }
    }

    @Test fun httpTransportSendsJsonAndNeverFollowsRedirects() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var redirected = false
        server.createContext("/state") { exchange ->
            assertEquals("PUT", exchange.requestMethod)
            assertEquals("application/json", exchange.requestHeaders.getFirst("Content-Type"))
            assertEquals("{\"on\":true}", exchange.requestBody.bufferedReader().readText())
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/destination")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/destination") { exchange ->
            redirected = true
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(204, HttpTransport().request("PUT", "$base/state", "{\"on\":true}").status)
            assertEquals(302, HttpTransport().request("GET", "$base/redirect", null).status)
            assertFalse(redirected)
        } finally { server.stop(0) }
    }

    @Test fun oversizedHttpResponseIsRejected() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = ByteArray(65537)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            assertThrows(ControlException::class.java) {
                HttpTransport().request("GET", "http://127.0.0.1:${server.address.port}/", null)
            }
        } finally { server.stop(0) }
    }

    @Test fun deviceContractRejectsOldWriteAndAcceptsToggleOnAndOff() {
        // Contract from the official Matter WiFi Essentials API and Python
        // nanoleafapi, independent of the URL/body produced by NanoleafApi.
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var on = false
        server.createContext("/api/v1/testToken123/state") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val desired = runCatching { JSONObject(body).getJSONObject("on").get("value") }.getOrNull()
            when {
                exchange.requestMethod == "GET" && path == "/api/v1/testToken123/state/on" -> {
                    val bytes = "{\"on\":{\"value\":$on}}".toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.requestMethod == "PUT" && path == "/api/v1/testToken123/state" && desired is Boolean -> {
                    on = desired
                    exchange.sendResponseHeaders(204, -1)
                }
                else -> exchange.sendResponseHeaders(400, -1)
            }
            exchange.close()
        }
        server.start()
        try {
            val localBase = "http://127.0.0.1:${server.address.port}"
            val http = HttpTransport()
            val statePath = "$localBase/api/v1/testToken123/state"
            assertEquals(400, http.request("PUT", "$statePath/on", "{\"on\":true}").status)
            assertEquals(400, http.request("PUT", statePath, "{\"on\":true}").status)
            assertEquals(400, http.request("PUT", "$statePath/on", "{\"on\":{\"value\":true}}").status)
            val api = NanoleafApi(Transport { method, url, body ->
                http.request(method, url.replace("http://${endpoint.ip}:${endpoint.port}", localBase), body)
            })
            assertFalse(api.read(device))
            assertTrue(api.toggle(device))
            assertFalse(api.toggle(device))
            assertTrue(api.setPower(device, true))
            assertFalse(api.setPower(device, false))
        } finally { server.stop(0) }
    }
}
