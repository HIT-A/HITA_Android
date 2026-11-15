package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.campus.CampusCardPage
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.security.MessageDigest

/** Private, non-backed-up snapshots, isolated by browser session and query page. */
internal class CampusCardHistoryCache(private val directory: File) {
    private val gson = Gson()

    suspend fun read(key: String): CampusCardPage? = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            runCatching {
                val file = File(directory, "$key.json")
                if (!file.isFile || file.length() > MAX_BYTES) return@synchronized null
                gson.fromJson(file.readText(), CampusCardPage::class.java)?.takeIf {
                    requireNotNull(it.summary)
                    it.transactions.items.size <= 20 && it.transactions.total >= 0 &&
                        it.transactionsUpdatedAt != null && it.transactionsUpdatedAt > 0 &&
                        it.historyPage >= 1 &&
                        it.transactions.items.all { row ->
                            row.id.isNotBlank() && row.merchant.length <= 4096 &&
                                row.method.length <= 4096 && row.occurredAtText.length <= 4096
                        }
                }
            }.getOrNull()
        }
    }

    suspend fun write(key: String, page: CampusCardPage) = withContext(Dispatchers.IO) {
        val caller = coroutineContext
        synchronized(LOCK) {
            // A cancelled page request must not recreate data after sign-out cleared it.
            caller.ensureActive()
            runCatching {
                directory.mkdirs()
                val json = gson.toJson(page)
                if (json.toByteArray().size > MAX_BYTES) return@runCatching
                val temp = File(directory, "$key.tmp")
                temp.writeText(json)
                check(temp.renameTo(File(directory, "$key.json")))
                // Bound storage even after many months or browser-session rotations.
                directory.listFiles()?.filter { it.extension == "json" }
                    ?.sortedByDescending { it.lastModified() }?.drop(100)?.forEach { it.delete() }
            }
            Unit
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(LOCK) { directory.deleteRecursively(); Unit }
    }

    companion object {
        private val LOCK = Any()
        private const val MAX_BYTES = 256 * 1024

        fun key(session: String, monthOnly: Boolean, page: Int, now: Instant): String {
            val range = if (monthOnly) now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString().take(7) else "all"
            return MessageDigest.getInstance("SHA-256")
                .digest("v1|$session|$range|$page".toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
