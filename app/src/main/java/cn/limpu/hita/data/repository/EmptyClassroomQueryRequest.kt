package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomCacheEntity
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.web.eas.EmptyClassroomQuerySnapshot
import cn.limpu.hita.utils.LogUtils
import com.limpu.component.data.DataState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** A captured query whose cache and remote work starts only when its states are collected. */
internal class EmptyClassroomQueryRequest(
    private val query: Flow<DataState<List<ClassroomItem>>>,
) {
    fun states(): Flow<DataState<List<ClassroomItem>>> = query

    companion object {
        fun forToken(
            token: EASToken,
            term: TermItem,
            buildingItem: BuildingItem,
            week: Int,
            useCache: Boolean = true,
            readCache: (TermItem, BuildingItem, Int) -> List<ClassroomCacheEntity>,
            saveCache: suspend (TermItem, BuildingItem, Int, List<ClassroomItem>) -> Unit,
            queryRemote: suspend (EASToken, TermItem, BuildingItem, List<String>) -> DataState<List<ClassroomItem>>,
        ): EmptyClassroomQueryRequest {
            val loggedIn = token.isLogin()
            val hasInitialState = token.campus != EASToken.Campus.SHENZHEN
            val captured = EmptyClassroomQuerySnapshot(term, buildingItem, listOf(week.toString()))
            return EmptyClassroomQueryRequest(flow {
                currentCoroutineContext().ensureActive()
                if (!loggedIn) {
                    emit(DataState(DataState.STATE.NOT_LOGGED_IN))
                    return@flow
                }

                val cached = if (useCache) {
                    try {
                        withContext(Dispatchers.IO) {
                            currentCoroutineContext().ensureActive()
                            val rows = readCache(captured.term, captured.building, week)
                            currentCoroutineContext().ensureActive()
                            ClassroomQueryCache.decode(rows).also {
                                currentCoroutineContext().ensureActive()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        LogUtils.e("Unable to read classroom cache", failure)
                        emptyList()
                    }
                } else emptyList()

                currentCoroutineContext().ensureActive()
                if (cached.isNotEmpty()) {
                    emit(DataState(cached).setFromCache(true))
                } else if (hasInitialState) {
                    emit(DataState(DataState.STATE.NOTHING))
                }
                currentCoroutineContext().ensureActive()
                val remote = withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    queryRemote(token, captured.term, captured.building, captured.weeks).also {
                        currentCoroutineContext().ensureActive()
                    }
                }
                currentCoroutineContext().ensureActive()
                if (remote.state == DataState.STATE.SUCCESS) {
                    try {
                        withContext(Dispatchers.IO) {
                            currentCoroutineContext().ensureActive()
                            saveCache(captured.term, captured.building, week, remote.data.orEmpty())
                            currentCoroutineContext().ensureActive()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        LogUtils.e("Unable to save classroom cache", failure)
                    }
                    currentCoroutineContext().ensureActive()
                    emit(remote)
                } else if (cached.isEmpty()) {
                    currentCoroutineContext().ensureActive()
                    emit(remote)
                }
            })
        }
    }
}
