package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.campus.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class CampusCardHistoryCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-11-15T00:00:00Z")
    private val row = CampusCardTransaction("1", -100, "2026-11-15", null, "食堂", "消费")
    private val page = CampusCardPage(CampusCardSummary(500, null, null, null),
        CampusCardTransactions(listOf(row), 42), transactionsUpdatedAt = 12345L)

    @Test fun `disk snapshot survives recreation and signout clears all pages`() = runBlocking {
        val dir = temporary.newFolder()
        val key = CampusCardHistoryCache.key("session-a", true, 1, now)
        CampusCardHistoryCache(dir).write(key, page)
        val reopened = CampusCardHistoryCache(dir)
        assertEquals(page, reopened.read(key))
        assertNull(reopened.read(CampusCardHistoryCache.key("session-b", true, 1, now)))
        assertNull(reopened.read(CampusCardHistoryCache.key("session-a", true, 2, now)))
        assertNull(reopened.read(CampusCardHistoryCache.key("session-a", false, 1, now)))
        assertNull(reopened.read(CampusCardHistoryCache.key("session-a", true, 1, Instant.parse("2026-12-01T00:00:00Z"))))
        reopened.clear()
        assertNull(reopened.read(key))
    }

    @Test fun `month cache rolls over at Shanghai midnight while all history survives`() {
        val before = Instant.parse("2026-11-30T15:59:59Z")
        val after = before.plusSeconds(1)
        assertNotEquals(CampusCardHistoryCache.key("s", true, 1, before), CampusCardHistoryCache.key("s", true, 1, after))
        assertEquals(CampusCardHistoryCache.key("s", false, 1, before), CampusCardHistoryCache.key("s", false, 1, after))
    }

    @Test fun `corrupt incomplete and oversized snapshots are cache misses`() = runBlocking {
        val dir = temporary.newFolder()
        val cache = CampusCardHistoryCache(dir)
        for (text in listOf("broken", "{}", "{\"transactions\":null}", " ".repeat(300000))) {
            File(dir, "test.json").writeText(text)
            assertNull(cache.read("test"))
        }
        cache.write("test", page)
        assertEquals(page, cache.read("test"))
    }
}
