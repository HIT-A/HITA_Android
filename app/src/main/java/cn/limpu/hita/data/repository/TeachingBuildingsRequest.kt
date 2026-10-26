package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One query and its initial UI state, captured from the same login and campus selection. */
internal class TeachingBuildingsRequest(
    val initialState: DataState<List<BuildingItem>>?,
    private val query: suspend () -> DataState<List<BuildingItem>>,
) {
    suspend fun execute(): DataState<List<BuildingItem>> {
        currentCoroutineContext().ensureActive()
        return query()
    }

    companion object {
        fun forToken(
            token: EASToken,
            query: suspend (EASToken) -> DataState<List<BuildingItem>>,
        ): TeachingBuildingsRequest {
            if (!token.isLogin()) {
                return TeachingBuildingsRequest(null) { DataState(DataState.STATE.NOT_LOGGED_IN) }
            }
            val initial = if (token.campus == EASToken.Campus.SHENZHEN) null
                else DataState<List<BuildingItem>>(DataState.STATE.NOTHING)
            return TeachingBuildingsRequest(initial) { query(token) }
        }
    }
}
