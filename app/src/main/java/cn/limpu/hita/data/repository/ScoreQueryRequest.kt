package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.ScoreQueryResult
import com.limpu.component.data.DataState
import kotlinx.coroutines.flow.Flow

/** Cold score query owned by one captured EAS session and cache key. */
internal class ScoreQueryRequest(
    val session: EasQuerySession,
    private val query: Flow<DataState<ScoreQueryResult>>,
) {
    fun states(): Flow<DataState<ScoreQueryResult>> = query
}
