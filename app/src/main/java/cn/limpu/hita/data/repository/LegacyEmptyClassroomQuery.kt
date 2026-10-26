package cn.limpu.hita.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomCacheEntity
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.utils.LiveDataUtils
import cn.limpu.hita.utils.LogUtils
import com.limpu.component.data.DataState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Keeps the eager LiveData entry point used by synchronous AI tools. */
internal class LegacyEmptyClassroomQuery(
    private val readCache: (TermItem, BuildingItem, Int) -> List<ClassroomCacheEntity>,
    private val saveCache: (TermItem, BuildingItem, Int, List<ClassroomItem>) -> Unit,
    private val queryRemote: (EASToken, TermItem, BuildingItem, List<String>) -> LiveData<DataState<List<ClassroomItem>>>,
) {
    fun query(
        token: EASToken,
        term: TermItem,
        buildingItem: BuildingItem,
        week: Int,
        useCache: Boolean = true,
    ): LiveData<DataState<List<ClassroomItem>>> {
        LogUtils.d("queryEmptyClassroom: isLogin=${token.isLogin()}, term=${term.getCode()}, building=${buildingItem.name}")
        if (token.isLogin()) {
            val result = MediatorLiveData<DataState<List<ClassroomItem>>>()
            val hasCachedResult = AtomicBoolean(false)
            if (useCache) {
                thread(name = "classroom-cache-load") {
                    val cached = readCache(term, buildingItem, week)
                    if (cached.isNotEmpty()) {
                        hasCachedResult.set(true)
                        result.postValue(
                            DataState(ClassroomQueryCache.decode(cached)).setFromCache(true)
                        )
                    }
                }
            }
            val remote = queryRemote(token, term, buildingItem, listOf(week.toString()))
            result.addSource(remote) { state ->
                if (useCache && hasCachedResult.get() && state.state != DataState.STATE.SUCCESS) {
                    return@addSource
                }
                result.value = state
                if (state.state == DataState.STATE.SUCCESS) {
                    val classrooms = state.data.orEmpty()
                    thread(name = "classroom-cache-save") {
                        saveCache(term, buildingItem, week, classrooms)
                    }
                }
            }
            return result
        }
        LogUtils.w("queryEmptyClassroom: not logged in")
        return LiveDataUtils.getMutableLiveData(DataState(DataState.STATE.NOT_LOGGED_IN))
    }
}
