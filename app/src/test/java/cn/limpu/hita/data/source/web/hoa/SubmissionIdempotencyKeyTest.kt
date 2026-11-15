package cn.limpu.hita.data.source.web.hoa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmissionIdempotencyKeyTest {

    @Test
    fun `same content yields same key`() {
        val a = SubmissionIdempotencyKey.derive("shenzhen", "CS101", "ops-a")
        val b = SubmissionIdempotencyKey.derive("shenzhen", "CS101", "ops-a")
        assertEquals(a, b)
    }

    @Test
    fun `key is prefixed and hex`() {
        val key = SubmissionIdempotencyKey.derive("shenzhen", "CS101", "ops-a")
        assertTrue(key.startsWith("android-v1-"))
        assertTrue(key.removePrefix("android-v1-").matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `content change changes key`() {
        val a = SubmissionIdempotencyKey.derive("shenzhen", "CS101", "ops-a")
        val b = SubmissionIdempotencyKey.derive("shenzhen", "CS101", "ops-b")
        assertNotEquals(a, b)
    }

    @Test
    fun `campus and course code are normalized`() {
        val a = SubmissionIdempotencyKey.derive("Shenzhen", "CS-101", "ops")
        val b = SubmissionIdempotencyKey.derive("shenzhen", "cs 101", "ops")
        assertEquals(a, b)
    }

    @Test
    fun `canonical ops ignores element order`() {
        val first = SubmissionIdempotencyKey.derive(
            "shenzhen", "CS101", SubmissionIdempotencyKey.canonicalOps(listOf("b", "a")),
        )
        val second = SubmissionIdempotencyKey.derive(
            "shenzhen", "CS101", SubmissionIdempotencyKey.canonicalOps(listOf("a", "b")),
        )
        assertEquals(first, second)
    }

    @Test
    fun `canonical ops distinguishes duplicates from single entry`() {
        assertNotEquals(
            SubmissionIdempotencyKey.canonicalOps(listOf("a")),
            SubmissionIdempotencyKey.canonicalOps(listOf("a", "a")),
        )
    }

    @Test
    fun `different campuses do not collide`() {
        assertNotEquals(
            SubmissionIdempotencyKey.derive("shenzhen", "CS101", "ops"),
            SubmissionIdempotencyKey.derive("weihai", "CS101", "ops"),
        )
    }
}
