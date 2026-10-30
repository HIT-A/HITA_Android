package cn.limpu.hita.data.repository.homework

import android.app.Application
import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.data.model.homework.HomeworkItem
import cn.limpu.hita.utils.LogUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class HomeworkState(
    val items: List<HomeworkItem> = emptyList(),
    val ready: Boolean = false,
    val failed: Boolean = false,
)

@Singleton
class HomeworkRepository @Inject constructor(application: Application) {
    // Both lazy preference initialization and durable commits run on IO.
    private val preferences by lazy {
        application.getSharedPreferences("timeline_homework_v1", Context.MODE_PRIVATE)
    }
    private val store = HomeworkStore(
        read = { preferences.getString("items", null) },
        write = { preferences.edit().putString("items", it).commit() },
    )
    private val mutex = Mutex()
    private var current = HomeworkState()
    private val mutableState = MutableLiveData(current)
    val state: LiveData<HomeworkState> = mutableState

    suspend fun reload(): Boolean = execute { store.load() }
    suspend fun add(title: String, date: String? = null): Boolean = execute { store.add(title, plannedDate = date) }
    suspend fun edit(id: String, title: String, date: String): Boolean = execute { store.edit(id, title, date) }
    suspend fun delete(id: String): Boolean = execute { store.delete(id) }
    suspend fun setCompleted(id: String, completed: Boolean): Boolean =
        execute { store.setCompleted(id, completed) }
    suspend fun clearCompleted(): Boolean = execute { store.clearCompleted() }

    private suspend fun execute(action: () -> List<HomeworkItem>): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                current = HomeworkState(items = action(), ready = true)
                mutableState.postValue(current)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LogUtils.e("Homework persistence failed", error)
                current = current.copy(failed = true)
                mutableState.postValue(current)
                false
            }
        }
    }
}
