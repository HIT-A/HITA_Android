package cn.limpu.hita.ui.subject

import cn.limpu.hita.data.model.resource.CourseResourceItem
import cn.limpu.hita.data.model.resource.ExternalCourseItem
import cn.limpu.hita.data.model.resource.ExternalResourceEntry
import cn.limpu.hita.data.model.resource.ResourceSource
import cn.limpu.hita.utils.CourseResourceLinker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread state owner shared across Activity recreation, with one request per expanded card. */
class SubjectResourceCoordinator(
    private val gateway: SubjectResourceGateway,
    private val text: SubjectResourceText,
    private val backfill: (SubjectResourceKey, String) -> Unit,
) {
    private val mutableState = MutableStateFlow(SubjectResourceState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var closed = false
    private var search: SubjectRequestSlot? = null
    private val readmes = mutableMapOf<String, SubjectRequestSlot>()
    private val directories = mutableMapOf<String, SubjectRequestSlot>()
    private val stacks = mutableMapOf<String, List<DirectoryLocation>>()
    private val resolver = SubjectCandidateResolver(gateway, ::begin, ::resolved)

    fun load(key: SubjectResourceKey) {
        if (!closed) resolver.load(key)
    }

    private fun begin(key: SubjectResourceKey) {
        cancelCards()
        mutableState.value = SubjectResourceState(key = key)
        val query = key.name.trim().ifBlank { key.code?.trim().orEmpty() }
        if (query.isBlank()) return
        val owner = generation
        val request = SubjectRequestSlot().also { search = it }
        request.attach(gateway.search(query) { result ->
            if (owner == generation && request.finish()) {
                mutableState.value = state.value.copy(externalCourses = result.getOrDefault(emptyList()).map { it.copy() })
            }
        })
    }

    private fun resolved(key: SubjectResourceKey, candidates: List<CourseResourceItem>) {
        if (state.value.key != key || closed) return
        val snapshot = candidates.map { it.copy(teachers = it.teachers.toList(), aliases = it.aliases.toList()) }
        mutableState.value = state.value.copy(
            candidates = snapshot,
            readme = SubjectReadmeUiState.Message(if (snapshot.isEmpty()) text.missing else text.chooseCard),
        )
        if (key.code.isNullOrBlank()) {
            CourseResourceLinker.uniqueExactCourseCodeForName(snapshot, key.name)?.let { backfill(key, it) }
        }
    }

    fun toggleCandidate(item: CourseResourceItem) {
        val key = itemKey(item)
        val captured = state.value.candidates.firstOrNull { itemKey(it) == key } ?: return
        val ownerKey = state.value.key ?: return
        val expanded = state.value.candidateExpanded[key] != true
        mutableState.value = state.value.copy(
            candidateExpanded = state.value.candidateExpanded + (key to expanded),
            selectedCandidate = if (expanded) captured else state.value.selectedCandidate,
        )
        if (!expanded) {
            readmes.remove(key)?.cancel()
            state.value.candidateReadmes[key]?.takeIf { it.loading }?.let {
                putReadme(key, it.copy(loading = false))
            }
            return
        }
        if (state.value.candidateReadmes[key]?.loaded == true) return
        val repo = captured.repoName.trim()
        if (repo.isBlank()) {
            putReadme(key, CandidateReadmeState(message = text.failed))
            return
        }
        readmes.remove(key)?.cancel()
        putReadme(key, CandidateReadmeState(loading = true))
        val owner = generation
        val request = SubjectRequestSlot().also { readmes[key] = it }
        request.attach(gateway.readme(repo, ownerKey.hoaCampus) { result ->
            if (owner != generation || readmes[key] !== request || !request.finish()) return@readme
            readmes.remove(key)
            putReadme(key, result.fold(
                onSuccess = { data -> CandidateReadmeState(
                    loaded = true,
                    markdown = SubjectReadmeFormatter.format(data.markdown, SubjectMatchSnapshot(ownerKey.code, ownerKey.name), captured, text.detailsFallbackTitle),
                    source = data.source,
                ) },
                onFailure = { CandidateReadmeState(message = it.message?.takeIf(String::isNotBlank) ?: text.failed) },
            ))
        })
    }

    fun toggleExternal(item: ExternalCourseItem) {
        val key = itemKey(item)
        val captured = state.value.externalCourses.firstOrNull { itemKey(it) == key } ?: return
        val expanded = state.value.externalExpanded[key] != true
        mutableState.value = state.value.copy(externalExpanded = state.value.externalExpanded + (key to expanded))
        if (!expanded) {
            directories.remove(key)?.cancel()
            state.value.directories[key]?.takeIf { it.loading }?.let {
                putDirectory(key, it.copy(loading = false))
            }
            return
        }
        val stack = stacks.getOrPut(key) { listOf(DirectoryLocation(captured.path, captured.source, captured.courseName)) }
        if (state.value.directories[key]?.loaded != true) loadDirectory(key, stack.last())
    }

    fun backExternal(item: ExternalCourseItem) {
        val key = itemKey(item)
        if (state.value.externalExpanded[key] != true) return
        val stack = stacks[key] ?: return
        if (stack.size <= 1) return
        stacks[key] = stack.dropLast(1)
        loadDirectory(key, stack[stack.lastIndex - 1])
    }

    /** Returns an authorized file selection for the platform launcher; directories remain here. */
    fun selectEntry(key: String, entry: ExternalResourceEntry): ExternalResourceEntry? {
        if (state.value.externalExpanded[key] != true) return null
        val captured = state.value.directories[key]?.entries?.firstOrNull { it == entry } ?: return null
        if (!captured.isDir) return captured.copy()
        val stack = stacks[key] ?: return null
        val location = DirectoryLocation(captured.path, captured.source, "${stack.last().breadcrumb} / ${captured.name}")
        stacks[key] = stack + location
        loadDirectory(key, location)
        return null
    }

    private fun loadDirectory(key: String, location: DirectoryLocation) {
        directories.remove(key)?.cancel()
        putDirectory(key, ExternalBrowseUiState(location.breadcrumb, loading = true, entries = emptyList(), message = ""))
        val owner = generation
        val request = SubjectRequestSlot().also { directories[key] = it }
        request.attach(gateway.directory(location.path, location.source) { result ->
            if (owner != generation || directories[key] !== request || !request.finish()) return@directory
            directories.remove(key)
            putDirectory(key, result.fold(
                onSuccess = { values ->
                    val entries = values.map { it.copy() }
                    ExternalBrowseUiState(location.breadcrumb, false, entries, if (entries.isEmpty()) text.emptyDirectory else "", loaded = true)
                },
                onFailure = { ExternalBrowseUiState(location.breadcrumb, false, emptyList(), it.message?.takeIf(String::isNotBlank) ?: text.failed) },
            ))
        })
    }

    private fun putReadme(key: String, value: CandidateReadmeState) {
        mutableState.value = state.value.copy(candidateReadmes = state.value.candidateReadmes + (key to value))
    }

    private fun putDirectory(key: String, value: ExternalBrowseUiState) {
        mutableState.value = state.value.copy(directories = state.value.directories + (key to value))
    }

    fun reset() {
        resolver.clear()
        cancelCards()
        mutableState.value = SubjectResourceState()
    }

    fun close() {
        closed = true
        reset()
    }

    private fun cancelCards() {
        generation++
        search?.cancel()
        search = null
        readmes.values.forEach(SubjectRequestSlot::cancel)
        directories.values.forEach(SubjectRequestSlot::cancel)
        readmes.clear()
        directories.clear()
        stacks.clear()
    }

    private data class DirectoryLocation(val path: String, val source: ResourceSource, val breadcrumb: String)
}
