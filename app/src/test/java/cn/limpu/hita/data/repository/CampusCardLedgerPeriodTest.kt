package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.campus.CampusCardSummary
import cn.limpu.hita.data.model.campus.CampusCardTransaction
import cn.limpu.hita.data.source.web.campus.CampusCardHistoryRange
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.YearMonth

class CampusCardLedgerPeriodTest {
    private fun row(id: String, time: String, amount: Int = -100) = CampusCardTransaction(
        id, amount, time, Instant.parse(time).toEpochMilli(), "食堂", "交易")
    private fun ledger(vararg rows: CampusCardTransaction) = CampusCardLedger(
        CampusCardSummary(0, null, null, null), rows.toList(), initialCutoff = 1L)

    @Test fun `selected historical month and year use same local ledger with Shanghai boundaries`() {
        val a = row("jan", "2025-01-31T15:59:59Z")
        val b = row("feb", "2025-01-31T16:00:00Z")
        val c = row("next", "2025-12-31T16:00:00Z")
        val data = ledger(a, b, c)
        assertEquals(listOf(b), data.query(CampusCardHistoryRange.MONTH, YearMonth.of(2025, 2), 1).items)
        assertEquals(setOf(a, b), data.query(CampusCardHistoryRange.YEAR, YearMonth.of(2025, 8), 1).items.toSet())
        assertEquals(listOf(c), data.query(CampusCardHistoryRange.YEAR, YearMonth.of(2026, 1), 1).items)
        assertEquals(3, data.query(CampusCardHistoryRange.ALL, YearMonth.of(2025, 2), 1).total)
    }

    @Test fun `year view supports local pagination and empty months`() {
        val data = ledger(*(1..42).map { row(it.toString(), "2025-02-01T00:00:00Z") }.toTypedArray())
        assertEquals(42, data.query(CampusCardHistoryRange.YEAR, YearMonth.of(2025, 12), 1).total)
        assertEquals(2, data.query(CampusCardHistoryRange.YEAR, YearMonth.of(2025, 12), 3).items.size)
        assertTrue(data.query(CampusCardHistoryRange.MONTH, YearMonth.of(2025, 3), 1).items.isEmpty())
        assertEquals(listOf(2026, 2025), data.availableYears(Instant.parse("2026-11-15T00:00:00Z")))
    }

    @Test fun `spending counts yesterday calendar week and month from real debits only`() {
        val now = Instant.parse("2026-11-16T01:00:00Z") // Monday 09:00 Shanghai
        val data = ledger(
            row("yesterday", "2026-11-15T12:00:00Z", -1234),
            row("today", "2026-11-16T00:00:00Z", -567),
            row("month", "2026-11-01T00:00:00Z", -100),
            row("older", "2026-10-31T15:59:59Z", -999),
            row("credit", "2026-11-16T00:00:00Z", 5000),
            row("refund", "2026-11-16T00:00:00Z", 1234),
            row("future", "2026-11-16T02:00:00Z", -888),
            row("unknown", "2026-11-15T00:00:00Z", -999).copy(occurredAtMillis = null),
        )
        val spending = data.spending(now)
        assertEquals("12.34", spending.yesterdayText)
        assertEquals("5.67", spending.weekText)
        assertEquals("19.01", spending.monthText)
    }

    @Test fun `spending crosses month and week boundaries independently without integer overflow`() {
        val data = ledger(row("a", "2026-02-28T16:00:00Z", Int.MIN_VALUE),
            row("b", "2026-03-01T00:00:00Z", Int.MIN_VALUE))
        val spending = data.spending(Instant.parse("2026-03-01T12:00:00Z"))
        assertEquals("0.00", spending.yesterdayText)
        assertEquals("42949672.96", spending.monthText)
        assertEquals("42949672.96", spending.weekText)
    }

    @Test fun `empty local ledger has genuine zero spending`() {
        val spending = ledger().spending(Instant.parse("2026-11-15T00:00:00Z"))
        assertEquals("0.00", spending.yesterdayText)
        assertEquals("0.00", spending.weekText)
        assertEquals("0.00", spending.monthText)
    }
    @Test fun `historical month and year totals follow selected range not current calendar`() {
        val data = ledger(row("oldMonth", "2025-11-15T00:00:00Z", -1234),
            row("otherMonth", "2025-12-15T00:00:00Z", -567),
            row("newYear", "2026-11-15T00:00:00Z", -999),
            row("topUp", "2025-11-15T00:00:00Z", 10000))
        assertEquals("12.34", data.periodSpending(CampusCardHistoryRange.MONTH, YearMonth.of(2025, 11)))
        assertEquals("18.01", data.periodSpending(CampusCardHistoryRange.YEAR, YearMonth.of(2025, 11)))
        assertEquals("0.00", data.periodSpending(CampusCardHistoryRange.MONTH, YearMonth.of(2025, 10)))
    }

}
