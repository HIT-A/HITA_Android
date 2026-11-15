package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.campus.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class CampusCardLedgerStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-11-15T00:00:00Z")
    private val summary = CampusCardSummary(1000, null, null, null)
    private fun row(index: Int, time: String = "2026-11-14T00:00:00Z") = CampusCardTransaction(
        "remote-page-$index", -100, time, Instant.parse(time).toEpochMilli(), "食堂$index", "消费")
    private fun ledger(rows: List<CampusCardTransaction>) = CampusCardLedger(summary, rows,
        initialCutoff = now.toEpochMilli())

    @Test fun `month and all views share rows and paginate locally`() {
        val rows = (1..42).map { row(it) } + row(43, "2026-10-31T15:59:59Z")
        val ledger = ledger(rows)
        assertEquals(43, ledger.query(false, 1, now).total)
        assertEquals(42, ledger.query(true, 1, now).total)
        assertEquals(20, ledger.query(true, 1, now).items.size)
        assertEquals(20, ledger.query(true, 2, now).items.size)
        assertEquals(2, ledger.query(true, 3, now).items.size)
        assertTrue(ledger.query(true, 1, now).items.none { it.id in ledger.query(true, 2, now).items.map { row -> row.id } })
    }

    @Test fun `month boundaries use Shanghai time and unknown dates remain in all history`() {
        val first = row(1, "2026-10-31T16:00:00Z")
        val unknown = row(2).copy(occurredAtMillis = null)
        val data = ledger(listOf(first, unknown))
        assertEquals(listOf(first), data.query(true, 1, now).items)
        assertEquals(2, data.query(false, 1, now).total)
    }

    @Test fun `overlapping updates are idempotent preserve duplicate payments and ignore page ids`() {
        val payment = row(1)
        val duplicate = payment.copy(id = "different-page-index")
        val existing = CampusCardLedger.merge(emptyList(), listOf(payment, duplicate))
        assertEquals(2, existing.size)
        assertEquals(existing, CampusCardLedger.merge(existing, listOf(payment)))
        val updated = CampusCardLedger.merge(existing, listOf(payment, duplicate, row(3)))
        assertEquals(3, updated.size)
        assertEquals(updated, CampusCardLedger.merge(updated, listOf(payment, duplicate, row(3))))
    }

    @Test fun `initial resume preserves equal payments crossing a page boundary`() {
        val firstPage = List(20) { row(it) }
        val secondPage = listOf(firstPage.last().copy(id = "next-page"))
        assertEquals(21, CampusCardLedger.merge(emptyList(), firstPage + secondPage).size)
    }

    @Test fun `incremental query starts near latest transaction rather than entire history`() {
        val data = ledger(listOf(row(1, "2020-01-01T00:00:00Z"), row(2)))
        assertEquals(Instant.parse("2026-11-12T16:00:00Z"), data.updateSince())
    }

    @Test fun `interrupted update keeps original interval even after newer rows have been saved`() {
        val original = Instant.parse("2020-01-01T00:00:00Z")
        val partial = ledger(listOf(row(1))).copy(pendingSinceMillis = original.toEpochMilli())
        assertEquals(original, partial.updateSince())
        assertNotEquals(original, partial.copy(pendingSinceMillis = null).updateSince())
    }

    @Test fun `local ledger and incomplete sync checkpoint survive process recreation and signout clears them`() = runBlocking {
        val dir = temporary.newFolder()
        val store = CampusCardLedgerStore(dir)
        val key = CampusCardLedgerStore.digest("account-session")
        val data = ledger(listOf(row(1))).copy(nextInitialPage = 2, remoteTotal = 42,
            pendingSinceMillis = 12345L, initialRows = listOf(row(1)))
        store.write(key, data)
        val reopened = CampusCardLedgerStore(dir)
        assertEquals(data, reopened.read(key))
        assertNull(reopened.read(CampusCardLedgerStore.digest("other-session")))
        reopened.clear()
        assertNull(reopened.read(key))
    }

    @Test fun `previous month and all page caches migrate into one ledger without duplicated rows`() = runBlocking {
        val dir = temporary.newFolder()
        val old = CampusCardHistoryCache(dir)
        val page = CampusCardPage(summary, CampusCardTransactions(listOf(row(1)), 42),
            transactionsUpdatedAt = 12345L)
        old.write(CampusCardHistoryCache.key("identity", true, 1, now), page)
        old.write(CampusCardHistoryCache.key("identity", false, 1, now), page)
        val migrated = CampusCardLedgerStore(dir).migrateLegacy("identity", now)!!
        assertEquals(1, migrated.rows.size)
        assertFalse(migrated.initialComplete)
        assertEquals(1, migrated.nextInitialPage)
        assertEquals(42, migrated.remoteTotal)
        assertNull(CampusCardLedgerStore(dir).migrateLegacy("other-account", now))
    }

    @Test fun `corrupt local data does not crash loading`() = runBlocking {
        val dir = temporary.newFolder()
        val store = CampusCardLedgerStore(dir)
        for (text in listOf("broken", "{}", "{\"rows\":null}")) {
            File(dir, "ledger-test.json").writeText(text)
            assertNull(store.read("test"))
        }
        store.write("test", ledger(emptyList()))
        assertNotNull(store.read("test"))
    }
}
