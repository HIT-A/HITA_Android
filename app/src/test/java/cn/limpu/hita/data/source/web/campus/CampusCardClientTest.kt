package cn.limpu.hita.data.source.web.campus

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CampusCardClientTest {
    @Test
    fun `captured portal ticket is exchanged without repeating CAS login`() = runBlocking {
        var requests = 0
        val client = CampusCardClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val request = chain.request()
            assertEquals("/portal-api/ssologin", request.url.encodedPath)
            assertEquals("T captured", request.url.queryParameter("t"))
            response(request, body = """{"code":0,"data":"test-token"}""")
        }.build())
        assertEquals("test-token", client.acquireToken({ "" }, "T captured"))
        assertEquals(1, requests)
    }

    @Test
    fun `redirect cookies keep domain and path scope including token exchange`() = runBlocking {
        var step = 0
        val client = CampusCardClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (step++) {
                0 -> {
                    assertEquals("CASTGC=test-tgt", request.header("Cookie"))
                    response(request, 302, location = "/authserver/resume",
                        cookie = "casHop=cas-only; Path=/authserver; Secure")
                }
                1 -> {
                    assertEquals("CASTGC=test-tgt; casHop=cas-only", request.header("Cookie"))
                    response(request, 302,
                        location = "https://dxcdp.hitsz.edu.cn:443/portal-api/login_cas")
                }
                2 -> {
                    assertNull(request.header("Cookie"))
                    response(request, 302, location = "/portal/?t=T-fresh",
                        cookie = "portalHop=portal-only; Path=/portal-api; Secure")
                }
                else -> {
                    assertEquals("/portal-api/ssologin", request.url.encodedPath)
                    assertEquals("portalHop=portal-only", request.header("Cookie"))
                    response(request, body = """{"code":0,"data":"test-token"}""")
                }
            }
        }.build())
        assertEquals("test-token", client.acquireToken(cookieHeader = { origin ->
            if (origin == CampusCardSessionPolicy.CAS_ORIGIN) "CASTGC=test-tgt" else ""
        }))
        assertEquals(4, step)
    }

    @Test
    fun `history pagination sends selected page size and stable month bounds`() = runBlocking {
        val client = CampusCardClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("2", request.url.queryParameter("pageNum"))
            assertEquals("20", request.url.queryParameter("pageSize"))
            assertEquals("2026/11/01 00:00:00", request.url.queryParameter("beginTime"))
            assertEquals("2026/11/30 23:59:59", request.url.queryParameter("endTime"))
            response(request, body = """{"code":0,"rows":[],"total":42}""")
        }.build())
        val result = client.fetchTransactions("test-token", page = 2,
            now = java.time.Instant.parse("2026-11-15T00:00:00Z"))
        assertEquals(42, result.total)
    }

    @Test
    fun `incremental update sends only requested interval on subsequent pages`() = runBlocking {
        val client = CampusCardClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("2", request.url.queryParameter("pageNum"))
            assertEquals("2026/11/13 00:00:00", request.url.queryParameter("beginTime"))
            assertEquals("2026/11/15 08:00:00", request.url.queryParameter("endTime"))
            response(request, body = """{"code":0,"rows":[],"total":21}""")
        }.build())
        val result = client.fetchTransactions("test-token", page = 2, range = CampusCardHistoryRange.ALL,
            since = java.time.Instant.parse("2026-11-12T16:00:00Z"),
            until = java.time.Instant.parse("2026-11-15T00:00:00Z"))
        assertEquals(21, result.total)
    }

    private fun response(
        request: Request,
        code: Int = 200,
        location: String? = null,
        cookie: String? = null,
        body: String = "",
    ): Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(code).message("test").body(body.toResponseBody())
        .apply {
            location?.let { header("Location", it) }
            cookie?.let { header("Set-Cookie", it) }
        }.build()
}
