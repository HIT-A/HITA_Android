package cn.limpu.hita.ui.timetable.manager

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.FriendTimetableRepository
import cn.limpu.hita.feature.timetableshare.protocol.*
import cn.limpu.hita.ui.timetable.friend.FriendImportUiState
import cn.limpu.hita.ui.timetable.manager.import.shareErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.DisplayScopeSelector
import cn.limpu.hita.data.repository.IcsImportResult
import cn.limpu.hita.data.repository.TimetableRepository
import com.limpu.component.data.DataState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.InputStream
import javax.inject.Inject

@HiltViewModel
class TimetableManagerViewModel @Inject constructor(
    private val timetableRepository: TimetableRepository,
    private val scopeSelector: DisplayScopeSelector,
    private val sharingRepository: cn.limpu.hita.data.repository.TimetableSharingRepository,
    private val exportRepository: cn.limpu.hita.data.repository.TimetableExportRepository,
    private val friendRepository: FriendTimetableRepository,
) : ViewModel() {
    val timetablesLiveData:LiveData<List<Timetable>> = timetableRepository.getTimetables()
    val groupsLiveData = timetableRepository.getGroups()

    var tokenSheetOpen by mutableStateOf(false)
    var pendingIcsFile by mutableStateOf<Pair<android.net.Uri, String>?>(null)
    var tokenForm by mutableStateOf(FriendImportUiState())
        private set
    var pendingScopeImport by mutableStateOf<Pair<String, String>?>(null)
    var importMessage by mutableStateOf<Int?>(null)
        private set
    private var tokenParsingJob: Job? = null

    fun consumeImportMessage() { importMessage = null }

    fun editTokenInput(input: String) {
        tokenParsingJob?.cancel()
        tokenForm = tokenForm.edit(input)
    }

    fun showFriendConfirmation(preview: FriendImportPreview) {
        editTokenInput("")
        tokenForm = tokenForm.accept(tokenForm.revision, preview)
        tokenSheetOpen = true
    }

    fun parseToken(input: String) {
        if (tokenForm.saving || tokenForm.loading) return
        tokenForm = tokenForm.begin()
        val revision = tokenForm.revision
        tokenParsingJob = viewModelScope.launch {
            try {
                val preview = friendRepository.preview(input)
                tokenForm = tokenForm.accept(revision, preview)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ShareFormatException) {
                tokenForm = tokenForm.fail(revision, shareErrorText(error.error))
            } catch (_: Exception) {
                tokenForm = tokenForm.fail(revision, R.string.timetable_import_failed)
            }
        }
    }

    fun importTokenAsFriend() {
        if (!tokenForm.canConfirm) return
        val current = tokenForm
        val preview = current.preview ?: return
        tokenForm = current.beginSave()
        viewModelScope.launch {
            try {
                when (val result = friendRepository.confirm(preview)) {
                    is FriendSaveResult.Saved, is FriendSaveResult.Unchanged -> {
                        editTokenInput("")
                        tokenSheetOpen = false
                        importMessage = R.string.ics_import_friend_saved
                    }
                    is FriendSaveResult.NeedsConfirmation -> {
                        tokenForm = tokenForm.accept(current.revision, result.preview)
                        importMessage = R.string.friend_import_changed
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                tokenForm = tokenForm.fail(current.revision, R.string.timetable_import_failed)
            }
        }
    }

    fun importTokenAsMine() {
        if (!tokenForm.canConfirm) return
        val snapshot = tokenForm.preview?.snapshot ?: return
        if (snapshot.mode != ShareMode.FULL) return
        val revision = tokenForm.revision
        tokenForm = tokenForm.beginSave()
        viewModelScope.launch {
            try {
                val result = awaitPersonalImport(timetableRepository.importShareSnapshotAsTimetable(snapshot))
                editTokenInput("")
                tokenSheetOpen = false
                pendingScopeImport = result.timetableId to result.timetableName
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                tokenForm = tokenForm.fail(revision, R.string.timetable_import_failed)
            }
        }
    }

    /** 导入完成后把课表加入既有组合,并切显示目标过去。 */
    fun joinGroup(timetableId: String, groupId: String) {
        timetableRepository.actionAddToGroup(timetableId, groupId) { added ->
            if (added) scopeSelector.select(DisplayScope.Group(groupId))
        }
    }

    /** 导入完成后新建组合(含同屏目标与新课表),并切显示目标过去。 */
    fun createGroupWith(targetTimetableId: String, newTimetableId: String) {
        timetableRepository.actionCreateGroupWithMembers(listOf(targetTimetableId, newTimetableId)) { groupId ->
            if (groupId != null) scopeSelector.select(DisplayScope.Group(groupId))
        }
    }

    /** 按导出面板勾选项生成口令：组合整体一个合并口令，独立课表各自一个（FULL）。 */
    suspend fun exportTokens(keys: List<String>, nickname: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (key in keys) {
            if (key.startsWith(GROUP_KEY_PREFIX)) {
                result += sharingRepository.generateGroupToken(key.removePrefix(GROUP_KEY_PREFIX), nickname)
            } else {
                result += sharingRepository.tokensForMine(listOf(key), nickname)
            }
        }
        return result
    }

    /** 按导出面板勾选项写出 ICS：组合整体一个合并文件，独立课表各自一个。 */
    suspend fun exportIcsFiles(keys: List<String>): List<java.io.File> {
        val files = mutableListOf<java.io.File>()
        for (key in keys) {
            if (key.startsWith(GROUP_KEY_PREFIX)) {
                files += exportRepository.groupIcs(key.removePrefix(GROUP_KEY_PREFIX))
            } else {
                files += exportRepository.mineIcs(listOf(key))
            }
        }
        return files
    }


    init {
        timetableRepository.actionPrepareTimetableList()
    }

    fun startDeleteTimetables(timetables:List<Timetable>){
        timetableRepository.actionDeleteTimetables(timetables)
    }

    fun startNewTimetable(){
        timetableRepository.actionNewTimetable()
    }

    /** 重命名我的课表；非法名字回调 false。 */
    fun renameTimetable(timetableId: String, name: String, onResult: (Boolean) -> Unit) {
        timetableRepository.actionRenameTimetable(timetableId, name, onResult)
    }

    fun renameGroup(groupId: String, name: String, onResult: (Boolean) -> Unit) {
        timetableRepository.actionRenameGroup(groupId, name, onResult)
    }

    fun dissolveGroup(groupId: String) {
        timetableRepository.actionDissolveGroup(groupId)
    }

    /**
     * 从 ICS 文件导入课表
     */
    fun importFromICSAsNewTimetable(
        inputStream: InputStream,
        sourceName: String?
    ): LiveData<DataState<IcsImportResult>> {
        val operation = timetableRepository.importFromICSAsNewTimetable(inputStream, sourceName)
        viewModelScope.launch {
            try {
                val result = awaitPersonalImport(operation)
                pendingScopeImport = result.timetableId to result.timetableName
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                importMessage = R.string.timetable_import_failed
            }
        }
        return operation
    }

    private suspend fun awaitPersonalImport(operation: LiveData<DataState<IcsImportResult>>): IcsImportResult {
        val state = operation.asFlow().first {
            it.state == DataState.STATE.SUCCESS || it.state == DataState.STATE.FETCH_FAILED
        }
        check(state.state == DataState.STATE.SUCCESS) { "personal timetable import failed" }
        return checkNotNull(state.data)
    }

    companion object {
        const val GROUP_KEY_PREFIX = "group:"
        fun groupItemKey(groupId: String) = GROUP_KEY_PREFIX + groupId
    }
}
