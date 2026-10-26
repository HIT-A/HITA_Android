package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.ScoreTermCacheEntity
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.dao.ScoreCacheDao

internal class TermQueryCache(private val dao: ScoreCacheDao) {
    fun read(owner: String): List<TermItem> = dao.getTermsSync(owner).map { row ->
        TermItem(row.termYearCode, row.yearName, row.termTermCode, row.termName)
            .also { it.isCurrent = row.isCurrent }
    }

    fun save(owner: String, terms: List<TermItem>, canReplace: () -> Boolean) {
        val now = System.currentTimeMillis()
        // Complete conversion before entering the transaction or deleting the previous batch.
        val rows = terms.map {
            ScoreTermCacheEntity(owner, it.yearCode, it.yearName, it.termCode,
                it.termName, it.isCurrent, now)
        }
        dao.replaceTermsByOwnerSync(owner, rows, now - RETENTION_MS, canReplace)
    }

    companion object {
        private const val RETENTION_MS = 365L * 24L * 60L * 60L * 1000L
    }
}
