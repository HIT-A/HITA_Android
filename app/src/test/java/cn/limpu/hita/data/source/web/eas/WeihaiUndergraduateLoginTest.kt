package cn.limpu.hita.data.source.web.eas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class WeihaiUndergraduateLoginTest {
    private val vpn = WeihaiEasEntryPolicy.Entry.VPN
    private val teachingBase = WeihaiEasEntryPolicy.VPN_EAS_PREFIX
    private val ticket = mapOf("wengine_vpn_ticketwebvpn_hitwh_edu_cn" to "synthetic-ticket")
    private val term = "<select name=xnxq><option value=2026-20271>秋季学期</option></select>"

    @Test
    fun `WebVPN can synchronize a teaching landing with only a gateway ticket`() {
        for (url in listOf("$teachingBase/", "$teachingBase/kbcx/queryGrkb", "$teachingBase/loginCAS?ticket=ST-fixture")) {
            assertTrue(url, WeihaiEasEntryPolicy.canStartUndergraduateVerification(vpn, url, ticket))
        }
        assertFalse(WeihaiEasEntryPolicy.canStartUndergraduateVerification(
            vpn, "$teachingBase/kbcx/queryGrkb", mapOf("JSESSIONID" to "stale"),
        ))
        assertFalse(WeihaiEasEntryPolicy.canStartUndergraduateVerification(
            vpn, "$teachingBase/kbcx/queryGrkb", ticket.mapValues { "" },
        ))
    }

    @Test
    fun `portal CAS and MFA pages cannot hand off a cached session`() {
        val cookies = ticket + ("JSESSIONID" to "stale")
        for (url in listOf(
            WeihaiEasEntryPolicy.VPN_LOGIN,
            "$teachingBase/loginCAS",
            "$teachingBase/loginCAS?other=ticket=ST-fixture",
            "$teachingBase/authserver/login",
            "$teachingBase/authserver/reAuthCheck/phone",
            "$teachingBase-other/kbcx/queryGrkb",
        )) {
            assertFalse(url, WeihaiEasEntryPolicy.canStartUndergraduateVerification(vpn, url, cookies))
        }
    }

    @Test
    fun `WebVPN exchange replaces stale browser teaching cookies but not the gateway ticket`() {
        val raw = "jsessionid=fresh; HIT=student; TWFID=id=with=equals; route=discard; ${ticket.keys.single()}=discard"
        for (body in listOf(raw, "\"$raw\"")) {
            val cookies = (ticket + mapOf("jsessionid" to "stale", "route" to "browser-route")).toMutableMap()
            WeihaiUndergraduateLogin.mergeExchangedCookies(cookies, WeihaiUndergraduateLogin.parseExchangedCookies(body))
            assertEquals(ticket + mapOf(
                "JSESSIONID" to "fresh", "HIT" to "student", "TWFID" to "id=with=equals", "route" to "browser-route",
            ), cookies)
        }
    }

    @Test
    fun `timetable request accepts a server-side session even without an inner cookie`() {
        assertTrue(verifyResponse(200, term))
    }

    @Test
    fun `HTTP 200 login pages and placeholder term selects do not verify a session`() {
        assertFalse(verifyResponse(200, "<form><input type=password></form>$term"))
        assertFalse(verifyResponse(200, "<select name=xnxq><option value=''>请选择</option></select>"))
        assertFalse(verifyResponse(200, "<html>WebVPN 页面</html>"))
        assertFalse(verifyResponse(403, term))
    }

    @Test
    fun `a redirect cannot establish the timetable session`() {
        assertFalse(verifyResponse(302, "", location = "/login"))
    }

    private fun verifyResponse(status: Int, body: String, location: String? = null): Boolean =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val url = "http://127.0.0.1:${server.localPort}"
            val exchange = FutureTask {
                server.accept().use { socket ->
                    // This fixture serves exactly one request. An unexpected redirect cannot be followed.
                    server.close()
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val requestLine = reader.readLine()
                    var cookie: String? = null
                    while (true) {
                        val header = reader.readLine()
                        if (header.isNullOrEmpty()) break
                        if (header.startsWith("Cookie:", ignoreCase = true)) {
                            cookie = header.substringAfter(':').trim()
                        }
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val responseHeaders = buildString {
                        append("HTTP/1.1 $status Fixture\r\n")
                        append("Content-Type: text/html; charset=UTF-8\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        if (location != null) append("Location: $location\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    socket.getOutputStream().apply {
                        write(responseHeaders.toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                    requestLine to cookie
                }
            }
            Thread(exchange, "weihai-timetable-fixture").apply {
                isDaemon = true
                start()
            }
            val response = WeihaiUndergraduateLogin.requestTimetable(url, ticket, 3000)
            assertEquals(
                "GET /kbcx/queryGrkb HTTP/1.1" to "${ticket.keys.single()}=synthetic-ticket",
                exchange.get(3, TimeUnit.SECONDS),
            )
            WeihaiUndergraduateLogin.isVerifiedTimetable(response)
        }
}
