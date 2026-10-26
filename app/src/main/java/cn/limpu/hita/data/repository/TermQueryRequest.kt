package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.utils.LogUtils
import com.limpu.component.data.DataState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Creating a request is cold; only actual collection revokes an older persistence ticket. */
internal class TermQueryRequest(
    val session: EasQuerySession,
    private val query: Flow<DataState<List<TermItem>>>,
) {
    fun states(): Flow<DataState<List<TermItem>>> = query

    companion object {
        fun create(
            session: EasQuerySession,
            kind: TermQueryKind,
            useCache: Boolean,
            begin: () -> EasQueryCoordinator.Ticket?,
            prepareCache: () -> Boolean,
            cacheIsReady: () -> Boolean,
            readCache: () -> List<TermItem>,
            saveCache: (List<TermItem>, () -> Boolean) -> Unit,
            queryRemote: suspend () -> DataState<List<TermItem>>,
        ): TermQueryRequest = TermQueryRequest(session, flow {
            val context = currentCoroutineContext()
            fun ensureCurrent() { context.ensureActive(); session.ensureCurrent() }
            ensureCurrent()
            val ticket = if (kind == TermQueryKind.ACADEMIC) begin() else null
            val caches = useCache && kind == TermQueryKind.ACADEMIC && session.ownerKey != null
            val cached = if (caches) {
                try {
                    withContext(Dispatchers.IO) {
                        ensureCurrent()
                        if (prepareCache()) readCache().also { ensureCurrent() } else emptyList()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LogUtils.e("Unable to read term cache", failure)
                    emptyList()
                }
            } else emptyList()
            ensureCurrent()
            if (cached.isNotEmpty()) emit(DataState(cached).setFromCache(true))
            if (kind == TermQueryKind.ACADEMIC && !session.loggedIn) {
                if (cached.isEmpty()) emit(DataState(DataState.STATE.NOT_LOGGED_IN))
                return@flow
            }
            if (cached.isEmpty() && kind == TermQueryKind.ACADEMIC && session.campus != EASToken.Campus.SHENZHEN) {
                emit(DataState(DataState.STATE.NOTHING))
            }
            ensureCurrent()
            val remote = session.whileCurrent {
                withContext(Dispatchers.IO) { queryRemote().also { ensureCurrent() } }
            }
            ensureCurrent()
            if (remote.state == DataState.STATE.SUCCESS && remote.data != null) {
                if (caches) {
                    try {
                        withContext(Dispatchers.IO) {
                            ensureCurrent()
                            if (prepareCache()) saveCache(remote.data.orEmpty()) {
                                ensureCurrent()
                                cacheIsReady() && ticket?.isCurrent() == true
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        LogUtils.e("Unable to save term cache", failure)
                    }
                }
                ensureCurrent()
                emit(remote)
            } else if (cached.isEmpty()) {
                ensureCurrent()
                emit(remote)
            }
        })
    }
}
