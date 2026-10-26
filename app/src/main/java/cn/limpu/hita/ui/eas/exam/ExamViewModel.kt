package cn.limpu.hita.ui.eas.exam

import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.limpu.component.data.DataState
import com.limpu.component.data.Trigger
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.repository.ExamImportRepository
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.source.preference.ExamMemoCodec
import cn.limpu.hita.data.source.preference.ExamMemoStore
import cn.limpu.hita.ui.eas.EASViewModel
import cn.limpu.hita.utils.LogUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ExamViewModel internal constructor(
    easRepo: EASRepository,
    private val context: Context,
    private val examImportRepository: ExamImportRepository,
    private val inputs: ExamPageInputs,
) : EASViewModel(easRepo) {
    @Inject constructor(
        easRepo: EASRepository,
        @ApplicationContext context: Context,
        examImportRepository: ExamImportRepository,
    ) : this(
        easRepo, context, examImportRepository,
        ExamPageInputs({ easRepo.getAllTerms() }, easRepo::getExamInfo),
    )
    /**
     * LiveData区
     */
    private val pageController = MutableLiveData<Trigger>()
    private val termLoadGate = ExamTermLoadGate()

    val termsLiveData: LiveData<DataState<List<TermItem>>> = pageController.switchMap {
        return@switchMap inputs.getTerms()
    }

    val selectedTermLiveData: MutableLiveData<TermItem> = MutableLiveData()
    val selectedExamTypeLiveData: MutableLiveData<ExamType> = MutableLiveData()
    private val examMemoStore = ExamMemoStore(context)
    private val rawExamLiveData = MutableLiveData<DataState<List<ExamItem>>>()
    private var lastRemoteExams: List<ExamItem> = emptyList()
    val examInfoLiveData = MediatorLiveData<DataState<List<ExamItem>>>()

    init {
        // 初始化默认值，避免筛选时把所有数据都过滤掉
        selectedExamTypeLiveData.value = ExamType.ALL
        // Term loading follows the screen lifecycle rather than a permanent observer.
        examInfoLiveData.addSource(termsLiveData) { handleTermLoad(it) }
        examInfoLiveData.addSource(rawExamLiveData) { publishFilteredExamState() }
        examInfoLiveData.addSource(selectedTermLiveData) { publishFilteredExamState() }
        examInfoLiveData.addSource(selectedExamTypeLiveData) { publishFilteredExamState() }
        rawExamLiveData.value = mergeExamDataState(lastRemoteExams, examMemoStore.getAll(), null)
    }

    /**
     * 方法区
     */

    /**
     * 判断是否需要显示考试类型筛选
     *
     * 不同校区的差异：
     * - 深圳校区：API返回的所有考试都是"期末"类型，没有实际分类意义，不显示筛选器
     * - 本部：有明确的期中期末分类，显示筛选器
     * - 威海：暂不支持考试查询
     */
    fun shouldShowExamTypeFilter(): Boolean {
        return easRepo.getCurrentCampus() == EASToken.Campus.BENBU
    }
    fun startRefresh() {
        LogUtils.d("=== 🔄 startRefresh called ===", "ExamViewModel")

        val currentTime = inputs.nowMillis()
        val selectedTerm = selectedTermLiveData.value
        val termJustChanged = (selectedTerm != null && !selectedTerm.equals(previousTerm))

        LogUtils.d("🌐 current selected term: ${selectedTerm?.name}, previous term: ${previousTerm?.name}, termJustChanged: $termJustChanged", "ExamViewModel")

        // 防止重复调用，但如果学期刚变化了则允许刷新
        if (!termJustChanged && currentTime - lastRefreshTime < 1000) {
            LogUtils.d("⚠️ Refresh called too frequently, skipping", "ExamViewModel")
            return
        }
        lastRefreshTime = currentTime
        previousTerm = selectedTerm

        if (selectedTerm == null) {
            termLoadGate.begin()
            rawExamLiveData.value = DataState(
                ExamMemoCodec.merge(lastRemoteExams, examMemoStore.getAll()),
                DataState.STATE.LOADING
            )
            pageController.value = Trigger.actioning
            return
        }
        termLoadGate.cancel()
        rawExamLiveData.value = DataState(
            ExamMemoCodec.merge(lastRemoteExams, examMemoStore.getAll()),
            DataState.STATE.LOADING
        )

        pageController.value = Trigger.actioning

        // 调用API获取服务器考试数据，传递学期参数
        LogUtils.d("🌐 calling getExamInfo API with term: ${selectedTerm.name}", "ExamViewModel")

        // 获取新的 LiveData（每次调用 getExamInfo 返回新实例，只取一次避免双重网络请求）
        val operation = inputs.beginOperation(UsageAnalyticsEvent.EXAMS_REFRESH_STARTED)
        val newLiveData = inputs.getExams(selectedTerm)

        // 从旧的 LiveData 上移除旧的观察者
        examInfoObserver?.let {
            currentExamSource?.removeObserver(it)
        }

        // 创建新的观察者并保存引用
        val newObserver = androidx.lifecycle.Observer<DataState<List<ExamItem>>> { result ->
            when (result.state) {
                DataState.STATE.SUCCESS -> inputs.finishOperation(operation, UsageAnalyticsEvent.EXAMS_REFRESH_SUCCEEDED, emptyMap())
                DataState.STATE.NOTHING, DataState.STATE.LOADING -> Unit
                else -> inputs.finishOperation(operation, UsageAnalyticsEvent.EXAMS_REFRESH_FAILED, mapOf("error_category" to "unknown"))
            }
            LogUtils.d("📥 API response received: state=${result.state}, data size=${result.data?.size}", "ExamViewModel")
            when (result.state) {
                DataState.STATE.SUCCESS -> {
                    val serverExams = result.data ?: emptyList()
                    LogUtils.d("✅ got ${serverExams.size} exams from server", "ExamViewModel")
                    lastRemoteExams = serverExams.filterNot(ExamItem::isMemo)
                    publishMergedExamState(result)
                }
                DataState.STATE.FETCH_FAILED, DataState.STATE.NOT_LOGGED_IN -> {
                    LogUtils.e("❌ API failed: ${result.message}", null, "ExamViewModel")
                    publishMergedExamState(result)
                }
                else -> {
                    // 其他状态
                    LogUtils.d("⚠️ other state: ${result.state}", "ExamViewModel")
                    rawExamLiveData.postValue(result)
                }
            }
        }

        newLiveData.observeForever(newObserver)
        currentExamSource = newLiveData
        examInfoObserver = newObserver
    }

    private fun handleTermLoad(state: DataState<List<TermItem>>) {
        when (val outcome = termLoadGate.consume(state)) {
            is ExamTermLoadOutcome.Selected -> {
                selectedTermLiveData.value = outcome.term
                startRefresh()
            }
            is ExamTermLoadOutcome.Failed -> {
                rawExamLiveData.value = DataState(
                    ExamMemoCodec.merge(lastRemoteExams, examMemoStore.getAll()),
                    outcome.state
                ).apply {
                    message = outcome.message?.takeIf(String::isNotBlank)
                        ?: context.getString(cn.limpu.hita.R.string.exam_term_load_failed)
                }
            }
            ExamTermLoadOutcome.Waiting, ExamTermLoadOutcome.Ignored -> Unit
        }
    }

    fun saveExamMemo(item: ExamItem): String? {
        return runCatching {
            examMemoStore.upsert(item)
            publishMergedExamState()
        }.exceptionOrNull()?.message
    }

    fun hasExamMemos(): Boolean = examMemoStore.getAll().isNotEmpty()

    fun deleteExamMemo(item: ExamItem): String? {
        val memoId = item.memoId?.trim().orEmpty()
        if (memoId.isEmpty()) return "这不是本地考试备忘录"
        return runCatching {
            examMemoStore.delete(memoId)
            publishMergedExamState()
        }.exceptionOrNull()?.message
    }

    fun defaultMemoCampusName(): String = when (easRepo.getCurrentCampus()) {
        EASToken.Campus.SHENZHEN -> "深圳校区"
        EASToken.Campus.BENBU -> "哈尔滨本部"
        EASToken.Campus.WEIHAI -> "威海校区"
    }

    private fun publishMergedExamState(remoteState: DataState<List<ExamItem>>? = null) {
        rawExamLiveData.postValue(
            mergeExamDataState(lastRemoteExams, examMemoStore.getAll(), remoteState)
        )
    }

    private fun publishFilteredExamState() {
        val state = rawExamLiveData.value ?: return
        val data = state.data
        if (state.state != DataState.STATE.SUCCESS || data.isNullOrEmpty()) {
            examInfoLiveData.value = state
            return
        }
        val term = selectedTermLiveData.value
        val type = selectedExamTypeLiveData.value
        val filtered = data.filter { item ->
            matchTerm(item, term) && matchType(item.examType, type)
        }
        examInfoLiveData.value = DataState(filtered, state.state).apply {
            message = state.message
        }
    }

    private var currentExamSource: LiveData<DataState<List<ExamItem>>>? = null
    private var examInfoObserver: androidx.lifecycle.Observer<DataState<List<ExamItem>>>? = null
    private var lastRefreshTime: Long = 0
    private var previousTerm: TermItem? = null

    override fun onCleared() {
        termLoadGate.cancel()
        super.onCleared()
        examInfoObserver?.let { observer ->
            currentExamSource?.removeObserver(observer)
        }
        currentExamSource = null
        examInfoObserver = null
    }

    /**
     * 学期匹配逻辑 - 使用统一的ID进行精确匹配
     *
     * 设计原则：
     * 1. 使用 termId 和 id 进行精确匹配，不使用 name 或 termName
     * 2. 避免字符串匹配带来的问题（格式不一致、边界情况等）
     * 3. 三个校区的数据统一使用相同的匹配逻辑
     *
     * 为什么使用ID匹配：
     * - API返回的termName可能是"2026春季"、"2026Spring"等不同格式
     * - TermItem.name可能是"2025-2026 2026春季"，与API数据不一致
     * - 使用ID匹配可以避免所有这些格式问题
     *
     * 注意事项：
     * - 确保考试数据解析时正确设置了termId（格式：yearCode-termCode）
     * - 确保学期数据初始化时正确设置了id（格式：yearCode-termCode）
     *
     * @param item 考试项，必须包含有效的termId
     * @param term 选中的学期，必须包含有效的id
     * @return true表示该考试属于选中的学期
     */
    private fun matchTerm(item: ExamItem, term: TermItem?): Boolean {
        if (term == null) return true
        if (item.isMemo() && item.termId.isNullOrBlank()) return true

        // 使用统一的termId进行匹配
        val itemTermId = item.termId
        val selectedTermId = term.id

        LogUtils.d("🔍 matchTerm: item.termId='$itemTermId', selected term.id='$selectedTermId'", "ExamViewModel")

        val result = itemTermId != null && itemTermId == selectedTermId
        LogUtils.d("🔍 matchTerm: ID匹配结果=$result", "ExamViewModel")

        return result
    }

    private fun matchType(examType: String?, type: ExamType?): Boolean {
        if (type == null || type == ExamType.ALL) return true
        val raw = (examType ?: "").trim()
        if (raw.isBlank()) return true
        return when (type) {
            ExamType.MIDTERM -> raw.contains("期中")
            ExamType.FINAL -> raw.contains("期末")
            ExamType.ALL -> true
        }
    }

    enum class ExamType {
        ALL,
        MIDTERM,
        FINAL
    }

    /**
     * 批量导入考试到用户确认的课表的结果
     */
    data class ImportResult(
        val totalCount: Int,
        val successCount: Int,
        val skippedCount: Int
    )

    /**
     * 批量导入考试到用户确认的课表
     *
     * 功能：
     * 1. 验证用户选择的目标课表仍存在
     * 2. 在目标课表内按统一考试身份键检查是否已导入
     * 3. 只导入未导入的考试
     * 4. 返回导入统计结果
     *
     * 线程安全：此方法应在后台线程调用
     *
     * @param exams 要导入的考试列表
     * @return ImportResult 包含总数、成功数、跳过数
     */
    fun importAllExams(exams: List<ExamItem>, timetableId: String): ImportResult {
        val result = examImportRepository.importIntoTimetable(exams, timetableId)
        return ImportResult(
            totalCount = result.totalCount,
            successCount = result.importedCount,
            skippedCount = result.skippedCount,
        )
    }

}

internal fun mergeExamDataState(
    remoteExams: List<ExamItem>,
    memoExams: List<ExamItem>,
    remoteState: DataState<List<ExamItem>>?
): DataState<List<ExamItem>> {
    val merged = ExamMemoCodec.merge(remoteExams, memoExams)
    if (remoteState?.state == DataState.STATE.NOT_LOGGED_IN) {
        return DataState(merged, DataState.STATE.NOT_LOGGED_IN).apply {
            message = remoteState.message
        }
    }
    return if (
        merged.isNotEmpty() ||
        remoteState == null ||
        remoteState.state == DataState.STATE.SUCCESS
    ) {
        DataState(merged, DataState.STATE.SUCCESS).apply {
            message = remoteState?.message
        }
    } else {
        remoteState
    }
}
