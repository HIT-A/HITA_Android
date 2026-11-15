package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.campus.*
import cn.limpu.hita.data.source.web.campus.CampusCardHistoryRange
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.math.BigDecimal
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.coroutineContext

/** One shared local ledger. Month and all-history views never own separate copies. */
internal data class CampusCardLedger(
    val summary: CampusCardSummary,
    val rows: List<CampusCardTransaction> = emptyList(),
    val updatedAt: Long? = null,
    val initialComplete: Boolean = false,
    val nextInitialPage: Int = 1,
    val initialCutoff: Long,
    val remoteTotal: Int = 0,
    val pendingSinceMillis: Long? = null,
    val initialRows: List<CampusCardTransaction> = emptyList(),
) {
    fun query(monthOnly: Boolean, page: Int, now: Instant): CampusCardTransactions = query(
        if (monthOnly) CampusCardHistoryRange.MONTH else CampusCardHistoryRange.ALL,
        YearMonth.from(now.atZone(ZONE)), page,
    )

    fun query(range: CampusCardHistoryRange, period: YearMonth, page: Int): CampusCardTransactions {
        val date = if (range == CampusCardHistoryRange.YEAR) period.withMonth(1).atDay(1) else period.atDay(1)
        val start = date.atStartOfDay(ZONE).toInstant().toEpochMilli()
        val end = (if (range == CampusCardHistoryRange.YEAR) date.plusYears(1) else date.plusMonths(1))
            .atStartOfDay(ZONE).toInstant().toEpochMilli()
        val selected = rows.asSequence().filter { range == CampusCardHistoryRange.ALL ||
            it.occurredAtMillis?.let { time -> time >= start && time < end } == true }
            .sortedWith(compareByDescending<CampusCardTransaction> { it.occurredAtMillis ?: Long.MIN_VALUE }.thenBy { it.id }).toList()
        return CampusCardTransactions(selected.drop((page.coerceAtLeast(1) - 1) * 20).take(20), selected.size)
    }

    fun periodSpending(range: CampusCardHistoryRange, period: YearMonth): String {
        val date = if (range == CampusCardHistoryRange.YEAR) period.withMonth(1).atDay(1) else period.atDay(1)
        val start = date.atStartOfDay(ZONE).toInstant().toEpochMilli()
        val end = (if (range == CampusCardHistoryRange.YEAR) date.plusYears(1) else date.plusMonths(1))
            .atStartOfDay(ZONE).toInstant().toEpochMilli()
        val cents = rows.asSequence().filter { row -> row.amountCents < 0 &&
            row.occurredAtMillis?.let { it >= start && it < end } == true }
            .sumOf { -it.amountCents.toLong() }
        return BigDecimal.valueOf(cents, 2).toPlainString()
    }

    fun availableYears(now: Instant): List<Int> = (rows.mapNotNull { row ->
        row.occurredAtMillis?.let { Instant.ofEpochMilli(it).atZone(ZONE).year }
    } + now.atZone(ZONE).year).distinct().sortedDescending()

    /** Gross debit spending from real local rows; credits, top-ups and undated rows are excluded. */
    fun spending(now: Instant): CampusCardSpending {
        val today = now.atZone(ZONE).toLocalDate()
        val startToday = today.atStartOfDay(ZONE).toInstant().toEpochMilli()
        val startYesterday = today.minusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
        val startWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(ZONE).toInstant().toEpochMilli()
        val startMonth = today.withDayOfMonth(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
        var yesterday = 0L
        var week = 0L
        var month = 0L
        for (row in rows) {
            val time = row.occurredAtMillis ?: continue
            if (row.amountCents >= 0 || time > now.toEpochMilli()) continue
            val debit = -row.amountCents.toLong()
            if (time >= startYesterday && time < startToday) yesterday += debit
            if (time >= startWeek) week += debit
            if (time >= startMonth) month += debit
        }
        fun yuan(cents: Long) = BigDecimal.valueOf(cents, 2).toPlainString()
        return CampusCardSpending(yuan(yesterday), yuan(week), yuan(month))
    }

    /** Inclusive overlap covers equal timestamps and recently posted records; merging is idempotent. */
    fun updateSince(): Instant? = pendingSinceMillis?.let(Instant::ofEpochMilli) ?: rows.mapNotNull { it.occurredAtMillis }.maxOrNull()?.let {
        Instant.ofEpochMilli(it).atZone(ZONE).toLocalDate().minusDays(1).atStartOfDay(ZONE).toInstant()
    }

    companion object {
        private val ZONE = ZoneId.of("Asia/Shanghai")

        /** Count equal rows instead of collapsing two identical legitimate payments into one. */
        fun merge(existing: List<CampusCardTransaction>, incomingSnapshot: List<CampusCardTransaction>): List<CampusCardTransaction> {
            fun keyed(rows: List<CampusCardTransaction>): Map<String, CampusCardTransaction> {
                val counts = mutableMapOf<String, Int>()
                return rows.associate { row ->
                    // Length-prefixed fields avoid ambiguity even when merchant names contain separators.
                    val fields = listOf(row.occurredAtText, row.amountCents.toString(), row.merchant, row.method)
                    val fingerprint = CampusCardLedgerStore.digest(fields.joinToString("") { "${it.length}:$it" })
                    val occurrence = counts.getOrDefault(fingerprint, 0)
                    counts[fingerprint] = occurrence + 1
                    val id = "$fingerprint-$occurrence"
                    id to row.copy(id = id)
                }
            }
            return (keyed(existing) + keyed(incomingSnapshot)).values.toList()
        }
    }
}

/** Private app storage, excluded from backups. No credentials are persisted. */
internal class CampusCardLedgerStore(private val directory: File) {
    private val gson = Gson()
    suspend fun read(sessionKey: String): CampusCardLedger? = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            runCatching {
                val file = File(directory, "ledger-$sessionKey.json")
                if (!file.isFile || file.length() > MAX_BYTES) return@synchronized null
                gson.fromJson(file.readText(), CampusCardLedger::class.java)?.takeIf {
                    requireNotNull(it.summary)
                    it.nextInitialPage > 0 && it.initialCutoff > 0 && it.rows.size <= MAX_ROWS && it.rows.all { row ->
                        row.id.isNotBlank() && row.merchant.length <= 4096 && row.method.length <= 4096 && row.occurredAtText.length <= 4096
                    }
                }
            }.getOrNull()
        }
    }

    /** One-time import of the previous version's page snapshots for this exact browser session. */
    suspend fun migrateLegacy(identity: String, now: Instant): CampusCardLedger? {
        val old = CampusCardHistoryCache(directory)
        val groups = listOf(false, true).map { month ->
            (1..100).mapNotNull { page -> old.read(CampusCardHistoryCache.key(identity, month, page, now)) }
        }
        val snapshots = groups.flatten()
        val latest = snapshots.maxByOrNull { it.transactionsUpdatedAt ?: 0 } ?: return null
        val rows = groups.fold(emptyList<CampusCardTransaction>()) { all, pages ->
            CampusCardLedger.merge(all, pages.flatMap { it.transactions.items })
        }
        return CampusCardLedger(latest.summary, rows, latest.transactionsUpdatedAt,
            initialCutoff = now.toEpochMilli(), remoteTotal = snapshots.maxOf { it.transactions.total })
    }

    suspend fun write(sessionKey: String, ledger: CampusCardLedger) = withContext(Dispatchers.IO) {
        val caller = coroutineContext
        synchronized(LOCK) {
            caller.ensureActive()
            check(ledger.rows.size <= MAX_ROWS) { "Local history limit reached" }
            directory.mkdirs()
            val json = gson.toJson(ledger)
            check(json.toByteArray().size <= MAX_BYTES) { "Local history size limit reached" }
            val temp = File(directory, "ledger-$sessionKey.tmp")
            temp.writeText(json)
            check(temp.renameTo(File(directory, "ledger-$sessionKey.json"))) { "Cannot save local history" }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { synchronized(LOCK) { directory.deleteRecursively(); Unit } }

    companion object {
        private val LOCK = Any()
        private const val MAX_BYTES = 32 * 1024 * 1024
        private const val MAX_ROWS = 100_000
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
