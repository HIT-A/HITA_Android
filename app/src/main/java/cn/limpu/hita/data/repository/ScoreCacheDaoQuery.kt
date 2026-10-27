package cn.limpu.hita.data.repository

import com.google.gson.Gson
import java.lang.reflect.Type
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.data.model.eas.ScoreCacheEntity
import cn.limpu.hita.data.model.eas.ScoreQueryResult
import cn.limpu.hita.data.model.eas.ScoreSummary
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.dao.ScoreCacheDao
import cn.limpu.hita.data.source.web.service.EASService

internal class ScoreCacheDaoQuery(
    private val dao: ScoreCacheDao,
    private val gson: Gson,
    private val scoreListType: Type,
) {
    fun read(owner: String, term: TermItem, testType: EASService.TestType): ScoreQueryResult? =
        dao.getScoreSync(owner, term.yearCode, term.termCode, testType.name)?.let { entity ->
            runCatching {
                ScoreQueryResult(
                    items = gson.fromJson<List<CourseScoreItem>>(entity.scoresJson, scoreListType).orEmpty(),
                    summary = entity.summaryJson?.let { gson.fromJson(it, ScoreSummary::class.java) },
                )
            }.getOrNull()
        }

    fun save(
        owner: String,
        term: TermItem,
        testType: EASService.TestType,
        result: ScoreQueryResult,
        previous: ScoreQueryResult?,
        canSave: () -> Boolean,
    ) {
        val now = System.currentTimeMillis()
        val summary = result.summary ?: previous?.summary
        dao.saveScoreIfCurrentSync(
            ScoreCacheEntity(
                ownerKey = owner,
                termYearCode = term.yearCode,
                termTermCode = term.termCode,
                testType = testType.name,
                scoresJson = gson.toJson(result.items),
                summaryJson = summary?.let(gson::toJson),
                cachedAt = now,
            ),
            now - 365L * 24L * 60L * 60L * 1000L,
            canSave,
        )
    }
}
