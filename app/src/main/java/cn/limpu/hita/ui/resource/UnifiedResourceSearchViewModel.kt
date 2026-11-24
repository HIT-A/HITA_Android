package cn.limpu.hita.ui.resource

import androidx.lifecycle.Observer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.resource.ExternalResourceEntry
import cn.limpu.hita.data.model.resource.ResourceSource
import cn.limpu.hita.data.model.resource.ResourceSourceStatus
import cn.limpu.hita.data.model.resource.SearchSource
import cn.limpu.hita.data.model.resource.UnifiedResourceItem
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.ExternalResourceRepository
import cn.limpu.hita.data.repository.HoaRepository
import com.limpu.component.data.DataState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ResourceFolderKey(val source: ResourceSource, val path: String)
internal data class ResourceFolder(val key: ResourceFolderKey, val title: String)

/** 一次检索的稳定身份：查询词与当时的校区。迟到的旧键结果不得写进新键。 */
internal data class ResourceSearchKey(val query: String, val campus: String?)

internal data class ResourceSearchSourceState(
    val items: List<UnifiedResourceItem> = emptyList(),
    val loading: Boolean = true,
    val status: ResourceSourceStatus? = null,
) {
    /** 有内容但本次刷新未完成或失败：可继续读，且必须标明陈旧。 */
    val stale: Boolean get() = items.isNotEmpty() && (loading || status == ResourceSourceStatus.FAILED)

    fun finish(items: List<UnifiedResourceItem>): ResourceSearchSourceState = ResourceSearchSourceState(
        items = items,
        loading = false,
        status = if (items.isEmpty()) ResourceSourceStatus.EMPTY else ResourceSourceStatus.OK,
    )

    /** 失败保留同键旧行：内容可读，但状态明确为失败。 */
    fun fail(): ResourceSearchSourceState = copy(loading = false, status = ResourceSourceStatus.FAILED)
}

internal data class ResourceSearchSnapshot(
    val key: ResourceSearchKey,
    val sources: Map<SearchSource, ResourceSearchSourceState>,
) {
    val items = sources.values.flatMap { it.items }.sortedBy { it.displayName }
    val loading = sources.values.any { it.loading }
    val failed = sources.filterValues { !it.loading && it.status == ResourceSourceStatus.FAILED }.keys
    val stale = sources.values.any { it.stale }
    /** 只有每个来源都成功且为空，才是可展示的「没有结果」。 */
    val empty = sources.isNotEmpty() && sources.values.all {
        !it.loading && it.status == ResourceSourceStatus.EMPTY
    }
}

internal data class ResourceFolderSnapshot(
    val key: ResourceFolderKey,
    val entries: List<ExternalResourceEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

internal data class ResourceSearchScreenState(
    val stack: List<ResourceFolder> = emptyList(),
    /** 仅当键与当前选择一致时对外暴露，异键快照不得驱动列表或写操作。 */
    val search: ResourceSearchSnapshot? = null,
    val directory: ResourceFolderSnapshot? = null,
)

@HiltViewModel
internal class UnifiedResourceSearchViewModel @Inject constructor(
    private val hoaRepository: HoaRepository,
    private val externalRepository: ExternalResourceRepository,
    private val easRepository: EASRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        ResourceSearchScreenState(
            stack = savedState.get<String>(KEY_STACK)?.split('\n')?.mapNotNull(::decodeFolder).orEmpty(),
        )
    )
    val state = mutableState.asStateFlow()

    private var searchJob: Job? = null
    private var directoryJob: Job? = null
    private var generation = 0L

    /**
     * 开始一次检索。旧查询的每个来源任务一起取消，结果按 [generation] 丢弃，
     * 因此界面只可能看到当前查询的终态。
     */
    fun search(query: String) {
        val normalized = query.trim()
        val key = ResourceSearchKey(normalized, easRepository.getHoaCampus())
        generation++
        val owner = generation
        searchJob?.cancel()
        val snapshot = ResourceSearchSnapshot(
            key = key,
            sources = SearchSource.entries.associateWith { ResourceSearchSourceState() },
        )
        update { it.copy(stack = emptyList(), directory = null, search = snapshot) }
        if (normalized.isBlank()) return
        val operation = UsageAnalyticsClient.begin(UsageAnalyticsEvent.RESOURCE_SEARCH_STARTED)
        searchJob = viewModelScope.launch {
            val hoa = mutableState.value.search?.sources?.get(SearchSource.HOA) ?: return@launch
            launchSource(owner, key, SearchSource.HOA) {
                hoaRepository.searchCourses(normalized, key.campus).await { it.data.orEmpty() }
                    .map { course ->
                        UnifiedResourceItem.HoaCourse(
                            repoName = course.repoName,
                            courseName = course.courseName,
                            courseCode = course.courseCode,
                            repoType = course.repoType,
                            teachers = course.teachers,
                        )
                    }
            }
            for (source in ResourceSource.entries) {
                launchSource(owner, key, source.toSearchSource()) {
                    externalRepository.searchSource(normalized, source).await { it.data.orEmpty() }
                        .map { course ->
                            UnifiedResourceItem.ExternalCourse(
                                courseName = course.courseName,
                                category = course.category,
                                source = course.source,
                                path = course.path,
                            )
                        }
                }
            }
            finishAnalytics(owner, key, operation)
        }
    }

    fun browse(path: String, source: ResourceSource, title: String? = null) {
        val key = ResourceFolderKey(source, path)
        val current = mutableState.value.stack.lastOrNull()
        // 同一目录的重试不叠加一层历史。
        if (current?.key == key) {
            update { it.copy(directory = ResourceFolderSnapshot(key)) }
            loadDirectory(key)
            return
        }
        val folder = ResourceFolder(key, title?.takeIf(String::isNotBlank) ?: path.substringAfterLast('/').ifBlank { source.name })
        update { it.copy(stack = it.stack + folder, search = null, directory = ResourceFolderSnapshot(key)) }
        loadDirectory(key)
    }

    /** 返回上一级目录；已在根目录时返回 false，由调用方退回列表。 */
    fun back(): Boolean {
        val stack = mutableState.value.stack
        if (stack.size <= 1) return false
        val next = stack.dropLast(1)
        val folder = next.last()
        update { it.copy(stack = next, directory = ResourceFolderSnapshot(folder.key)) }
        loadDirectory(folder.key)
        return true
    }

    /** 离开浏览模式，回到检索列表；已存在的检索快照不被销毁。 */
    fun exitBrowse() {
        directoryJob?.cancel()
        update { it.copy(stack = emptyList(), directory = null) }
    }

    /** 首次进入（含重建）时按 Intent 恢复：优先浏览目录，否则重放查询。 */
    internal fun restore(initialQuery: String, initialFolder: ResourceFolder?) {
        if (mutableState.value.search != null || mutableState.value.directory != null) return
        if (initialFolder != null) {
            update { it.copy(stack = listOf(initialFolder), directory = ResourceFolderSnapshot(initialFolder.key)) }
            loadDirectory(initialFolder.key)
        } else {
            search(initialQuery.ifBlank { savedState.get<String>(KEY_QUERY).orEmpty() })
        }
    }

    private fun launchSource(
        owner: Long,
        key: ResourceSearchKey,
        source: SearchSource,
        fetch: suspend () -> List<UnifiedResourceItem>,
    ) {
        viewModelScope.launch {
            val result = runCatching { fetch() }
            if (owner != generation) return@launch
            val current = mutableState.value.search?.takeIf { it.key == key } ?: return@launch
            val previous = current.sources[source] ?: ResourceSearchSourceState()
            val next = result.fold(onSuccess = previous::finish, onFailure = { previous.fail() })
            update { it.copy(search = current.copy(sources = current.sources + (source to next))) }
        }
    }

    private fun finishAnalytics(owner: Long, key: ResourceSearchKey, operation: UsageAnalyticsClient.Operation?) {
        if (owner != generation) return
        val snapshot = mutableState.value.search?.takeIf { it.key == key } ?: return
        if (snapshot.loading) return
        when {
            snapshot.items.isNotEmpty() -> UsageAnalyticsClient.finish(operation, UsageAnalyticsEvent.RESOURCE_SEARCH_SUCCEEDED)
            snapshot.failed.isNotEmpty() -> UsageAnalyticsClient.finish(
                operation,
                UsageAnalyticsEvent.RESOURCE_SEARCH_FAILED,
                mapOf("error_category" to "unknown"),
            )
            else -> UsageAnalyticsClient.finish(operation, UsageAnalyticsEvent.RESOURCE_SEARCH_NO_RESULTS)
        }
    }

    private fun loadDirectory(key: ResourceFolderKey) {
        directoryJob?.cancel()
        directoryJob = viewModelScope.launch {
            val result = runCatching {
                externalRepository.listDirectory(key.path, key.source).await { it.data.orEmpty() }
            }
            val current = mutableState.value.directory?.takeIf { it.key == key } ?: return@launch
            update {
                it.copy(
                    directory = result.fold(
                        onSuccess = { entries -> current.copy(entries = entries, loading = false, failed = false) },
                        onFailure = { current.copy(loading = false, failed = true) },
                    )
                )
            }
        }
    }

    private fun update(transform: (ResourceSearchScreenState) -> ResourceSearchScreenState) {
        val next = transform(mutableState.value)
        mutableState.value = next
        savedState[KEY_STACK] = next.stack.joinToString("\n") { encodeFolder(it) }
        savedState[KEY_QUERY] = next.search?.key?.query.orEmpty()
    }

    private fun encodeFolder(folder: ResourceFolder): String =
        "${folder.key.source.name}\u0001${folder.key.path}\u0001${folder.title}"

    private fun decodeFolder(raw: String): ResourceFolder? {
        val parts = raw.split('\u0001')
        if (parts.size != 3) return null
        val source = runCatching { ResourceSource.valueOf(parts[0]) }.getOrNull() ?: return null
        return ResourceFolder(ResourceFolderKey(source, parts[1]), parts[2])
    }

    private companion object {
        const val KEY_STACK = "resource_stack"
        const val KEY_QUERY = "resource_query"
    }
}

/** 等待 LiveData 的终态；先摘观察者再交付，迟到结果不会覆盖后续查询。 */
private suspend fun <T, R> androidx.lifecycle.LiveData<DataState<T>>.await(
    map: (DataState<T>) -> List<R>,
): List<R> = suspendCancellableCoroutine { continuation ->
    var active = true
    lateinit var observer: Observer<DataState<T>>
    fun detach() {
        if (!active) return
        active = false
        removeObserver(observer)
    }
    observer = Observer { value ->
        if (!active) return@Observer
        if (value.state == DataState.STATE.NOTHING || value.state == DataState.STATE.LOADING) return@Observer
        detach()
        if (!continuation.isActive) return@Observer
        if (value.state == DataState.STATE.SUCCESS || value.data != null) continuation.resume(map(value))
        else continuation.resumeWithException(IllegalStateException(value.message.orEmpty()))
    }
    continuation.invokeOnCancellation { detach() }
    observeForever(observer)
}

private fun ResourceSource.toSearchSource(): SearchSource = when (this) {
    ResourceSource.HITCS -> SearchSource.HITCS
    ResourceSource.FIREWORKS -> SearchSource.FIREWORKS
}
