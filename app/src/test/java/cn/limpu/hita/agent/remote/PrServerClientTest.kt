package cn.limpu.hita.agent.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrServerClientTest {
    @Test
    fun `submit key is content derived and stable`() {
        val ops = listOf(mapOf("op" to "add_lecturer_review", "content" to "讲得很好"))
        val first = buildSubmitIdempotencyKey("COMP3003", "shenzhen", ops)
        val second = buildSubmitIdempotencyKey("COMP3003", "shenzhen", ops)

        assertEquals(first, second)
        assertTrue(first.startsWith("android-v1-"))
    }

    @Test
    fun `submit key changes when review content changes`() {
        val a = buildSubmitIdempotencyKey("COMP3003", "shenzhen", listOf<Map<String, Any>>(mapOf("content" to "A")))
        val b = buildSubmitIdempotencyKey("COMP3003", "shenzhen", listOf<Map<String, Any>>(mapOf("content" to "B")))

        assertNotEquals(a, b)
    }

    @Test
    fun `submit key is independent of op ordering`() {
        val ops = listOf<Map<String, Any>>(mapOf("content" to "A"), mapOf("content" to "B"))

        assertEquals(
            buildSubmitIdempotencyKey("COMP3003", "shenzhen", ops),
            buildSubmitIdempotencyKey("COMP3003", "shenzhen", ops.reversed()),
        )
    }

    @Test
    fun `submit key ignores course code casing and separators`() {
        assertEquals(
            buildSubmitIdempotencyKey("Comp:3003 / PR", "shenzhen", listOf<Map<String, Any>>(mapOf("op" to "x"))),
            buildSubmitIdempotencyKey("comp-3003-pr", "shenzhen", listOf<Map<String, Any>>(mapOf("op" to "x"))),
        )
    }

    @Test
    fun `prefers nested error message from json body`() {
        val error = buildPrHttpError(500, "{" +
                "\"error\":{\"code\":\"INTERNAL\",\"message\":\"github create pr failed\"}}")
        assertEquals("HTTP_500", error.code)
        assertEquals("HTTP 500: github create pr failed", error.message)
    }

    @Test
    fun `falls back to detail field when error object missing`() {
        val error = buildPrHttpError(400, "{\"detail\":\"invalid course code\"}")
        assertEquals("HTTP 400: invalid course code", error.message)
    }

    @Test
    fun `falls back to raw body for plain text responses`() {
        val error = buildPrHttpError(500, "internal server error")
        assertEquals("HTTP 500: internal server error", error.message)
    }

    @Test
    fun `keeps plain status when body missing`() {
        val error = buildPrHttpError(500, null)
        assertEquals("HTTP 500", error.message)
    }
}
