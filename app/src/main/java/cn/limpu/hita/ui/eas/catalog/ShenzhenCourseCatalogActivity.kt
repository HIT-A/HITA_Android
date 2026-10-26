package cn.limpu.hita.ui.eas.catalog

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.limpu.component.data.DataState
import com.limpu.style.widgets.PopUpCheckableList
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.CourseSelectionCourseStatus
import cn.limpu.hita.data.model.eas.CourseSelectionJob
import cn.limpu.hita.data.model.eas.CourseSelectionJobStatus
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogPage
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogSource
import cn.limpu.hita.data.model.eas.ShenzhenCourseAttachment
import cn.limpu.hita.data.model.eas.ShenzhenCourseAttachmentKind
import cn.limpu.hita.data.model.eas.ShenzhenHistoricalFailureReport
import cn.limpu.hita.data.model.eas.ShenzhenTeacherFailureRate
import cn.limpu.hita.data.model.eas.ShenzhenSelectionPool
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.repository.CourseSelectionDraft
import cn.limpu.hita.data.repository.CourseSelectionJobPolicy
import cn.limpu.hita.data.work.CourseSelectionAlarmScheduler
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaGlassCardBorder
import cn.limpu.hita.ui.design.hitaGlassCardColors
import cn.limpu.hita.ui.design.hitaGlassCardModifier
import cn.limpu.hita.ui.eas.EASActivity
import cn.limpu.hita.ui.eas.login.PopUpLoginEAS
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.TermNameFormatter
import cn.limpu.hita.utils.TermUtils
import cn.limpu.hita.ui.widgets.WidgetUtils
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

@AndroidEntryPoint
class ShenzhenCourseCatalogActivity :
    EASActivity<ShenzhenCourseCatalogViewModel, ComposeViewBinding>() {

    override val viewModel: ShenzhenCourseCatalogViewModel by viewModels()
    @Inject
    lateinit var courseSelectionAlarmScheduler: CourseSelectionAlarmScheduler

    private var terms by mutableStateOf<List<TermItem>>(emptyList())
    private var uiState by mutableStateOf<CatalogUiState>(CatalogUiState.Loading)
    private var attachmentDialog by mutableStateOf<CourseAttachmentDialogState?>(null)
    private var historicalFailureDialog by mutableStateOf<HistoricalFailureDialogState?>(null)
    private var isShowingCoursePlanPreview by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(true)
    private var pendingDownload: ShenzhenCourseAttachment? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
        if (!granted) {
            Toast.makeText(
                this,
                R.string.course_selection_notification_permission_required,
                Toast.LENGTH_LONG
            ).show()
        }
    }
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val attachment = pendingDownload
        pendingDownload = null
        if (granted && attachment != null) {
            enqueueAttachmentDownload(attachment)
        } else if (!granted) {
            Toast.makeText(this, "需要存储权限才能保存到下载目录", Toast.LENGTH_LONG).show()
        }
    }

    override fun initViewBinding(): ComposeViewBinding = ComposeViewBinding(ComposeView(this))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun initViews() {
        super.initViews()
        notificationPermissionGranted = hasNotificationPermission()
        bindLiveData()
        (binding.root as ComposeView).setContent {
            HitaComposeTheme {
                if (isShowingCoursePlanPreview) {
                    CoursePlanPreviewScreen(
                        viewModel = viewModel,
                        onBack = { isShowingCoursePlanPreview = false },
                        onShowInTimetable = { viewModel.setCoursePlanProjectionEnabled(true) },
                        onHideFromTimetable = { viewModel.setCoursePlanProjectionEnabled(false) },
                        onClearDraft = { viewModel.clearCoursePlan() }
                    )
                } else {
                    val source by viewModel.sourceLiveData.observeAsState(
                        ShenzhenCourseCatalogSource.AVAILABLE
                    )
                    val termDisplay by viewModel.selectedTermDisplay.observeAsState()
                    val term = termDisplay?.toTermItem()
                    val pool by viewModel.selectedPoolLiveData.observeAsState()
                    val studentType by viewModel.studentTypeLiveData.observeAsState("1")
                    val query by viewModel.queryLiveData.observeAsState()
                    val pageState by viewModel.coursesLiveData.observeAsState()
                    val followedSectionIds by viewModel.followedSectionIdsLiveData.observeAsState(emptySet())
                    val coursePlanDraft by viewModel.courseSelectionDraftLiveData.observeAsState()
                    val selectedCoursesState by viewModel.selectedCoursesLiveData.observeAsState()
                    val selectedForSubmission by viewModel.selectedForSubmissionLiveData.observeAsState(emptySet())
                    val selectedSubmissionCourses by
                        viewModel.selectedSubmissionCoursesLiveData.observeAsState(emptyList())
                    val selectionJobs by viewModel.selectionJobsLiveData.observeAsState(emptyList())

                    ShenzhenCourseCatalogScreen(
                        uiState = uiState,
                        source = source,
                        term = term,
                        pool = pool,
                        studentType = studentType,
                        query = query,
                        page = pageState?.data,
                        followedSectionIds = followedSectionIds,
                        coursePlanDraft = coursePlanDraft,
                        selectedCourses = selectedCoursesState?.data.orEmpty(),
                        selectedForSubmission = selectedForSubmission,
                        selectedSubmissionCourses = selectedSubmissionCourses,
                        selectionJobs = selectionJobs,
                        notificationPermissionGranted = notificationPermissionGranted,
                        onBack = { finish() },
                        onRefresh = { refresh() },
                        onConnectWeb = { connectWebSession() },
                        onSelectSource = viewModel::selectSource,
                        onSearch = viewModel::search,
                        onPreviousPage = viewModel::previousPage,
                        onNextPage = viewModel::nextPage,
                        onSelectTerm = { showTermPicker() },
                        onSelectPool = { showPoolPicker() },
                        onSelectStudentType = { showStudentTypePicker() },
                        onRecommend = { openCourseRecommendation() },
                        onShowCoursePlan = { showCoursePlan() },
                        onCoursePlanConflict = viewModel::coursePlanConflict,
                        onToggleFollow = { course ->
                            if (!viewModel.toggleFollow(course)) {
                                Toast.makeText(
                                    this,
                                    R.string.course_selection_planning_data_loading,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onToggleCoursePlan = { course ->
                            if (!viewModel.toggleCoursePlanCourse(course)) {
                                Toast.makeText(
                                    this,
                                    R.string.course_selection_choose_term_first,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onToggleSubmission = { viewModel.toggleCourseForSubmission(it) },
                        onCreateImmediateSelection = { viewModel.createImmediateSelectionJob() },
                        onCreateScheduledSelection = { viewModel.createScheduledSelectionJob(it) },
                        onCancelSelectionJob = { jobId ->
                            if (viewModel.cancelSelectionJob(jobId)) {
                                Toast.makeText(
                                    this,
                                    R.string.course_selection_job_cancelled,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onConfirmSelectionJob = { jobId ->
                            if (viewModel.confirmSelectionJob(jobId)) {
                                Toast.makeText(
                                    this,
                                    R.string.course_selection_reconfirmation_started,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onRequestNotificationPermission = { requestNotificationPermission() },
                        canScheduleExactAlarms = courseSelectionAlarmScheduler::canScheduleExactAlarms,
                        onOpenExactAlarmSettings = {
                            startActivity(courseSelectionAlarmScheduler.exactAlarmSettingsIntent())
                        },
                        attachmentDialog = attachmentDialog,
                        historicalFailureDialog = historicalFailureDialog,
                        onCourseClick = { showCourseActions(it) },
                        onDismissAttachments = { attachmentDialog = null },
                        onRetryAttachments = { retryCourseAttachments() },
                        onDownloadAttachment = { downloadAttachment(it) },
                        onDismissHistoricalFailure = { historicalFailureDialog = null },
                        onRetryHistoricalFailure = { retryHistoricalFailureRates() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationPermissionGranted = hasNotificationPermission()
    }

    private fun bindLiveData() {
        viewModel.termsLiveData.observe(this) { state ->
            when (state.state) {
                DataState.STATE.SUCCESS -> {
                    terms = state.data.orEmpty()
                    uiState = CatalogUiState.Ready(refreshing = viewModel.coursesLiveData.value?.state in
                        listOf(null, DataState.STATE.NOTHING, DataState.STATE.LOADING))
                    viewModel.reconcileTerms(terms)
                }
                DataState.STATE.NOT_LOGGED_IN -> {
                    uiState = CatalogUiState.NeedsWebLogin(state.message)
                }
                DataState.STATE.FETCH_FAILED -> {
                    uiState = CatalogUiState.Error(state.message ?: "学期列表加载失败")
                }
                DataState.STATE.NOTHING -> Unit
                else -> {
                    uiState = CatalogUiState.Error(state.message ?: "学期列表暂不可用")
                }
            }
        }
        viewModel.queryLiveData.observe(this) {
            uiState = CatalogUiState.Ready(refreshing = true)
        }
        viewModel.coursesLiveData.observe(this) { state ->
            when (state.state) {
                DataState.STATE.SUCCESS -> {
                    uiState = CatalogUiState.Ready(
                        message = if (state.data?.items.isNullOrEmpty()) {
                            "当前筛选条件下没有课程"
                        } else {
                            null
                        }
                    )
                    resetSessionRetryState()
                }
                DataState.STATE.NOT_LOGGED_IN -> {
                    uiState = CatalogUiState.NeedsWebLogin(state.message)
                }
                DataState.STATE.FETCH_FAILED -> {
                    uiState = CatalogUiState.Ready(
                        message = state.message ?: "课程数据加载失败"
                    )
                }
                DataState.STATE.NOTHING -> Unit
                else -> uiState = CatalogUiState.Ready(
                    message = state.message ?: "课程数据暂不可用"
                )
            }
        }
        viewModel.attachmentsLiveData.observe(this) { state ->
            val dialog = attachmentDialog ?: return@observe
            when (state.state) {
                DataState.STATE.SUCCESS -> {
                    attachmentDialog = dialog.copy(
                        loading = false,
                        attachments = state.data.orEmpty(),
                        error = null
                    )
                    resetSessionRetryState()
                }
                DataState.STATE.NOT_LOGGED_IN -> {
                    if (!handleSessionExpired {
                            attachmentDialog = attachmentDialog?.copy(loading = true, error = null)
                            viewModel.retryAttachments()
                        }
                    ) {
                        attachmentDialog = dialog.copy(
                            loading = false,
                            error = state.message ?: "深圳 Web 会话已失效"
                        )
                    }
                }
                DataState.STATE.FETCH_FAILED -> {
                    attachmentDialog = dialog.copy(
                        loading = false,
                        error = state.message ?: "课程附件加载失败"
                    )
                }
                DataState.STATE.NOTHING -> Unit
                else -> {
                    attachmentDialog = dialog.copy(
                        loading = false,
                        error = state.message ?: "课程附件暂不可用"
                    )
                }
            }
        }
        viewModel.historicalFailureLiveData.observe(this) { state ->
            val dialog = historicalFailureDialog ?: return@observe
            when (state.state) {
                DataState.STATE.SUCCESS -> {
                    historicalFailureDialog = dialog.copy(
                        loading = false,
                        report = state.data,
                        error = null
                    )
                    resetSessionRetryState()
                }
                DataState.STATE.NOT_LOGGED_IN -> {
                    if (!handleSessionExpired {
                            historicalFailureDialog = historicalFailureDialog?.copy(
                                loading = true,
                                error = null
                            )
                            viewModel.retryHistoricalFailureRates()
                        }
                    ) {
                        historicalFailureDialog = dialog.copy(
                            loading = false,
                            error = state.message ?: "深圳 Web 会话已失效"
                        )
                    }
                }
                DataState.STATE.FETCH_FAILED -> {
                    historicalFailureDialog = dialog.copy(
                        loading = false,
                        error = state.message ?: "历史挂科率查询失败"
                    )
                }
                DataState.STATE.NOTHING -> Unit
                else -> {
                    historicalFailureDialog = dialog.copy(
                        loading = false,
                        error = state.message ?: "历史挂科率暂不可用"
                    )
                }
            }
        }
        viewModel.followActionLiveData.observe(this) { state ->
            when (state.state) {
                DataState.STATE.SUCCESS -> {
                    Toast.makeText(this, "关注课程已同步到时间表", Toast.LENGTH_SHORT).show()
                    WidgetUtils.sendRefreshToAll(this)
                }
                DataState.STATE.FETCH_FAILED -> Toast.makeText(
                    this,
                    state.message ?: "关注课程更新失败",
                    Toast.LENGTH_LONG
                ).show()
                else -> Unit
            }
        }
        viewModel.coursePlanActionLiveData.observe(this) { state ->
            when (state.state) {
                DataState.STATE.SUCCESS -> {
                    Toast.makeText(this, "选课预览已更新", Toast.LENGTH_SHORT).show()
                    WidgetUtils.sendRefreshToAll(this)
                }
                DataState.STATE.FETCH_FAILED -> Toast.makeText(
                    this,
                    state.message ?: "选课预览更新失败",
                    Toast.LENGTH_LONG
                ).show()
                else -> Unit
            }
        }
        viewModel.coursePlanProjectionActionLiveData.observe(this) { state ->
            when (state.state) {
                DataState.STATE.SUCCESS -> {
                    val timetableId = state.data?.timetableId
                    WidgetUtils.sendRefreshToAll(this)
                    viewModel.consumeCoursePlanProjectionAction()
                    if (timetableId != null) {
                        isShowingCoursePlanPreview = false
                        ActivityUtils.startTimetableDetailActivity(this, timetableId)
                    } else {
                        Toast.makeText(this, "选课预览已从时间表隐藏", Toast.LENGTH_SHORT).show()
                    }
                }
                DataState.STATE.FETCH_FAILED -> {
                    val message = state.message ?: "选课预览课表生成失败"
                    viewModel.consumeCoursePlanProjectionAction()
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                DataState.STATE.LOADING -> Toast.makeText(
                    this,
                    state.message ?: "正在准备选课预览，完成后会自动打开",
                    Toast.LENGTH_SHORT
                ).show()
                else -> Unit
            }
        }
        viewModel.selectionCommandEventLiveData.observe(this) { result ->
            result ?: return@observe
            val message = when (result) {
                is CourseSelectionCommandResult.Created ->
                    getString(R.string.course_selection_job_created)
                is CourseSelectionCommandResult.Rejected -> when (result.failure) {
                    CourseSelectionCommandFailure.NO_TERM ->
                        getString(R.string.course_selection_choose_term_first)
                    CourseSelectionCommandFailure.NO_POOL ->
                        getString(R.string.course_selection_choose_pool_first)
                    CourseSelectionCommandFailure.NO_COURSES ->
                        getString(R.string.course_selection_choose_courses_first)
                    CourseSelectionCommandFailure.TOO_MANY_COURSES ->
                        getString(
                            R.string.course_selection_too_many_courses,
                            CourseSelectionJobPolicy.MAX_COURSES
                        )
                    CourseSelectionCommandFailure.SCHEDULE_TOO_SOON,
                    CourseSelectionCommandFailure.SCHEDULE_TOO_FAR ->
                        getString(R.string.course_selection_schedule_range_error)
                    CourseSelectionCommandFailure.CREATION_FAILED ->
                        getString(R.string.course_selection_job_creation_failed)
                    CourseSelectionCommandFailure.CANNOT_CANCEL ->
                        getString(R.string.course_selection_job_cannot_cancel)
                    CourseSelectionCommandFailure.CANNOT_CONFIRM ->
                        getString(R.string.course_selection_reconfirmation_failed)
                }
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            viewModel.consumeSelectionCommandEvent()
        }
    }

    override fun refresh() {
        uiState = CatalogUiState.Loading
        viewModel.startRefresh()
    }

    private fun connectWebSession() {
        ActivityUtils.showEasVerifyWindow<Activity>(
            this,
            easRepository,
            preferredCampus = EASToken.Campus.SHENZHEN,
            onResponseListener = object : PopUpLoginEAS.OnResponseListener {
                override fun onSuccess(window: PopUpLoginEAS) {
                    window.dismiss()
                    if (easRepository.hasShenzhenWebSession()) {
                        refresh()
                    } else {
                        Toast.makeText(
                            this@ShenzhenCourseCatalogActivity,
                            "请点击“使用统一身份认证网页登录”完成 Web 会话连接",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onFailed(window: PopUpLoginEAS) = Unit
            }
        )
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermission() {
        if (hasNotificationPermission()) {
            notificationPermissionGranted = true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showTermPicker() {
        if (terms.isEmpty()) return
        PopUpCheckableList<TermItem>()
            .setTitle("选择学期")
            .setListData(terms.map(TermNameFormatter::fullTermName), terms)
            .setOnConfirmListener(object : PopUpCheckableList.OnConfirmListener<TermItem> {
                override fun OnConfirm(title: String?, key: TermItem) {
                    viewModel.selectTerm(key)
                }
            })
            .show(supportFragmentManager, "shenzhen_catalog_terms")
    }

    private fun showPoolPicker() {
        val labels = viewModel.pools.map { it.name }.toTypedArray()
        val current = viewModel.selectedPoolLiveData.value
        val checked = viewModel.pools.indexOfFirst { it.code == current?.code }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("选择课程类型")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.selectPool(viewModel.pools[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun showStudentTypePicker() {
        val labels = arrayOf("本科", "研究生")
        val checked = if (viewModel.studentTypeLiveData.value == "2") 1 else 0
        MaterialAlertDialogBuilder(this)
            .setTitle("培养层次")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.selectStudentType(if (which == 1) "2" else "1")
                dialog.dismiss()
            }
            .show()
    }

    private fun openCourseRecommendation() {
        val term = viewModel.selectedTermDisplay.current() ?: return
        startActivity(ShenzhenCourseRecommendationActivity.intent(this, term))
    }

    private fun showCoursePlan() {
        if (viewModel.selectedTermLiveData.value == null) {
            Toast.makeText(this, "请先选择课程所在学期", Toast.LENGTH_SHORT).show()
            return
        }
        isShowingCoursePlanPreview = true
    }

    private fun showCourseAttachments(course: ShenzhenCourseCatalogItem) {
        attachmentDialog = CourseAttachmentDialogState(course = course, loading = true)
        viewModel.loadAttachments(course)
    }

    private fun showCourseActions(course: ShenzhenCourseCatalogItem) {
        showCourseAttachments(course)
    }

    private fun showHistoricalFailureRates(course: ShenzhenCourseCatalogItem) {
        historicalFailureDialog = HistoricalFailureDialogState(course = course, loading = true)
        if (!viewModel.loadHistoricalFailureRates(course)) {
            historicalFailureDialog = historicalFailureDialog?.copy(
                loading = false,
                error = "请先选择课程所在学期"
            )
        }
    }

    private fun retryHistoricalFailureRates() {
        historicalFailureDialog = historicalFailureDialog?.copy(loading = true, error = null)
        viewModel.retryHistoricalFailureRates()
    }

    private fun retryCourseAttachments() {
        attachmentDialog = attachmentDialog?.copy(loading = true, error = null)
        viewModel.retryAttachments()
    }

    private fun downloadAttachment(attachment: ShenzhenCourseAttachment) {
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = attachment
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        enqueueAttachmentDownload(attachment)
    }

    private fun enqueueAttachmentDownload(attachment: ShenzhenCourseAttachment) {
        runCatching {
            easRepository.downloadShenzhenCourseAttachment(attachment)
        }.onSuccess {
            Toast.makeText(this, "开始下载：${attachment.name}", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            Toast.makeText(
                this,
                "下载失败：${error.message ?: "未知错误"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

private sealed interface CatalogUiState {
    data object Loading : CatalogUiState
    data class NeedsWebLogin(val message: String?) : CatalogUiState
    data class Error(val message: String) : CatalogUiState
    data class Ready(
        val refreshing: Boolean = false,
        val message: String? = null
    ) : CatalogUiState
}

private data class CourseAttachmentDialogState(
    val course: ShenzhenCourseCatalogItem,
    val loading: Boolean = false,
    val attachments: List<ShenzhenCourseAttachment> = emptyList(),
    val error: String? = null
)

private data class HistoricalFailureDialogState(
    val course: ShenzhenCourseCatalogItem,
    val loading: Boolean = false,
    val report: ShenzhenHistoricalFailureReport? = null,
    val error: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShenzhenCourseCatalogScreen(
    uiState: CatalogUiState,
    source: ShenzhenCourseCatalogSource,
    term: TermItem?,
    pool: ShenzhenSelectionPool?,
    studentType: String,
    query: ShenzhenCourseCatalogQuery?,
    page: ShenzhenCourseCatalogPage?,
    followedSectionIds: Set<String>,
    coursePlanDraft: CourseSelectionDraft?,
    selectedCourses: List<ShenzhenCourseCatalogItem>,
    selectedForSubmission: Set<String>,
    selectedSubmissionCourses: List<ShenzhenCourseCatalogItem>,
    selectionJobs: List<CourseSelectionJob>,
    notificationPermissionGranted: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onConnectWeb: () -> Unit,
    onSelectSource: (ShenzhenCourseCatalogSource) -> Unit,
    onSearch: (String) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSelectTerm: () -> Unit,
    onSelectPool: () -> Unit,
    onSelectStudentType: () -> Unit,
    onRecommend: () -> Unit,
    onShowCoursePlan: () -> Unit,
    onCoursePlanConflict: (ShenzhenCourseCatalogItem) -> String?,
    onToggleFollow: (ShenzhenCourseCatalogItem) -> Unit,
    onToggleCoursePlan: (ShenzhenCourseCatalogItem) -> Unit,
    onToggleSubmission: (ShenzhenCourseCatalogItem) -> Unit,
    onCreateImmediateSelection: () -> Unit,
    onCreateScheduledSelection: (Long) -> Unit,
    onCancelSelectionJob: (String) -> Unit,
    onConfirmSelectionJob: (String) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    canScheduleExactAlarms: () -> Boolean,
    onOpenExactAlarmSettings: () -> Unit,
    attachmentDialog: CourseAttachmentDialogState?,
    historicalFailureDialog: HistoricalFailureDialogState?,
    onCourseClick: (ShenzhenCourseCatalogItem) -> Unit,
    onDismissAttachments: () -> Unit,
    onRetryAttachments: () -> Unit,
    onDownloadAttachment: (ShenzhenCourseAttachment) -> Unit,
    onDismissHistoricalFailure: () -> Unit,
    onRetryHistoricalFailure: () -> Unit
) {
    val tokens = HitaTheme.tokens
    val selectedCourseIds = selectedCourses.mapTo(hashSetOf()) { it.taskId.ifBlank { it.id } }
    val effectivePreviewCount = (selectedCourses + coursePlanDraft?.courses.orEmpty())
        .distinctBy { it.taskId.ifBlank { it.id } }
        .size
    val activeSelectionJobs = selectionJobs
        .filter { it.status == CourseSelectionJobStatus.WAITING || it.status == CourseSelectionJobStatus.RUNNING }
        .sortedBy { it.scheduledAtMillis }
    val terminalSelectionJobs = selectionJobs
        .filterNot { it.status == CourseSelectionJobStatus.WAITING || it.status == CourseSelectionJobStatus.RUNNING }
        .sortedByDescending { it.createdAtMillis }
    val isLoading = uiState is CatalogUiState.Loading ||
        (uiState is CatalogUiState.Ready && uiState.refreshing)
    val errorMessage = when (uiState) {
        is CatalogUiState.Error -> uiState.message
        is CatalogUiState.Ready -> uiState.message
        is CatalogUiState.NeedsWebLogin -> uiState.message
        CatalogUiState.Loading -> null
    }
    var keyword by remember(query?.keyword) { mutableStateOf(query?.keyword.orEmpty()) }
    var showImmediateConfirmation by remember { mutableStateOf(false) }
    var showScheduleDatePicker by remember { mutableStateOf(false) }
    var scheduledDateMillis by remember { mutableStateOf<Long?>(null) }
    var showExactAlarmGuidance by remember { mutableStateOf(false) }
    var awaitingNotificationPermission by remember { mutableStateOf(false) }

    LaunchedEffect(notificationPermissionGranted, awaitingNotificationPermission) {
        if (notificationPermissionGranted && awaitingNotificationPermission) {
            awaitingNotificationPermission = false
            if (canScheduleExactAlarms()) {
                showScheduleDatePicker = true
            } else {
                showExactAlarmGuidance = true
            }
        }
    }

    attachmentDialog?.let { state ->
        CourseAttachmentDialog(
            state = state,
            onDismiss = onDismissAttachments,
            onRetry = onRetryAttachments,
            onDownload = onDownloadAttachment
        )
    }
    historicalFailureDialog?.let { state ->
        HistoricalFailureDialog(
            state = state,
            onDismiss = onDismissHistoricalFailure,
            onRetry = onRetryHistoricalFailure
        )
    }
    if (showImmediateConfirmation) {
        ImmediateSelectionConfirmationDialog(
            courses = selectedSubmissionCourses,
            onDismiss = { showImmediateConfirmation = false },
            onConfirm = {
                showImmediateConfirmation = false
                onCreateImmediateSelection()
            }
        )
    }
    if (showExactAlarmGuidance) {
        ExactAlarmGuidanceDialog(
            onDismiss = { showExactAlarmGuidance = false },
            onOpenSettings = {
                showExactAlarmGuidance = false
                onOpenExactAlarmSettings()
            }
        )
    }
    if (showScheduleDatePicker) {
        SelectionScheduleDateDialog(
            onDismiss = { showScheduleDatePicker = false },
            onDateSelected = { dateMillis ->
                showScheduleDatePicker = false
                scheduledDateMillis = dateMillis
            }
        )
    }
    scheduledDateMillis?.let { dateMillis ->
        SelectionScheduleTimeDialog(
            dateMillis = dateMillis,
            courseCount = selectedSubmissionCourses.size,
            onDismiss = { scheduledDateMillis = null },
            onConfirm = { scheduledAtMillis ->
                scheduledDateMillis = null
                onCreateScheduledSelection(scheduledAtMillis)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "深圳课程浏览",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                        contentDescription = "返回",
                        modifier = Modifier.rotate(180f)
                    )
                }
            },
            actions = {
                IconButton(onClick = onRefresh) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_settings_backup_restore_24),
                            contentDescription = "刷新"
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        if (
            uiState is CatalogUiState.NeedsWebLogin ||
            (uiState is CatalogUiState.Error && page == null)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(tokens.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
            ) {
                selectionJobItems(
                    activeJobs = activeSelectionJobs,
                    terminalJobs = terminalSelectionJobs,
                    onCancel = onCancelSelectionJob,
                    onReconfirm = onConfirmSelectionJob
                )
                item {
                    when (uiState) {
                        is CatalogUiState.NeedsWebLogin -> WebLoginRequiredCard(
                            message = uiState.message,
                            onConnectWeb = onConnectWeb
                        )
                        is CatalogUiState.Error -> CatalogErrorCard(
                            message = uiState.message,
                            onRetry = onRefresh
                        )
                        else -> Unit
                    }
                }
            }
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
        ) {
            FilterChip(
                selected = source == ShenzhenCourseCatalogSource.AVAILABLE,
                onClick = { onSelectSource(ShenzhenCourseCatalogSource.AVAILABLE) },
                label = { Text("教务选课池") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = source == ShenzhenCourseCatalogSource.SCHOOL,
                onClick = { onSelectSource(ShenzhenCourseCatalogSource.SCHOOL) },
                label = { Text("全校课表") },
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = if (source == ShenzhenCourseCatalogSource.AVAILABLE) {
                "学校选课任务池 · “必修”是课程性质，不代表你的个人必修"
            } else {
                "深圳 Web 教务 · 全校开课数据，只读浏览"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = tokens.spacing.xl)
        )

        CatalogFilters(
            termName = term?.let(TermNameFormatter::fullTermName) ?: "选择学期",
            secondaryName = if (source == ShenzhenCourseCatalogSource.AVAILABLE) {
                pool?.name ?: "选择课程类型"
            } else if (studentType == "2") {
                "研究生"
            } else {
                "本科"
            },
            keyword = keyword,
            onKeywordChange = { keyword = it },
            onSearch = { onSearch(keyword) },
            onSelectTerm = onSelectTerm,
            onSelectSecondary = if (source == ShenzhenCourseCatalogSource.AVAILABLE) {
                onSelectPool
            } else {
                onSelectStudentType
            },
            modifier = Modifier.padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm)
        )

        Button(
            onClick = onRecommend,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.xs)
        ) {
            Text("智能选课推荐")
        }
        OutlinedButton(
            onClick = onShowCoursePlan,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.xs)
        ) {
            Text("选课预览 · $effectivePreviewCount（已选 ${selectedCourses.size}）")
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = tokens.spacing.xl, vertical = tokens.spacing.xs)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = tokens.spacing.lg,
                end = tokens.spacing.lg,
                top = tokens.spacing.xs,
                bottom = tokens.spacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
        ) {
            selectionJobItems(
                activeJobs = activeSelectionJobs,
                terminalJobs = terminalSelectionJobs,
                onCancel = onCancelSelectionJob,
                onReconfirm = onConfirmSelectionJob
            )
            if (page != null) {
                item {
                    Text(
                        text = "共 ${page.total} 条 · 第 ${page.page} 页",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                items(page.items, key = { "${it.source}-${it.id}" }) { item ->
                    CourseCatalogCard(
                        item = item,
                        followed = item.taskId.ifBlank { item.id } in followedSectionIds,
                        selectedInEas = item.taskId.ifBlank { item.id } in selectedCourseIds,
                        inCoursePlan = item.taskId.ifBlank { item.id } in coursePlanDraft?.courseIds.orEmpty(),
                        conflictMessage = if (item.source == ShenzhenCourseCatalogSource.AVAILABLE) {
                            onCoursePlanConflict(item)
                        } else {
                            null
                        },
                        selectable = ShenzhenCourseSelectionUiPolicy.canSelect(item),
                        selectedForSubmission = item.selectionRequestId.isNotBlank() &&
                            item.selectionRequestId in selectedForSubmission,
                        onToggleFollow = { onToggleFollow(item) },
                        onToggleCoursePlan = { onToggleCoursePlan(item) },
                        onToggleSubmission = { onToggleSubmission(item) },
                        onClick = { onCourseClick(item) }
                    )
                }
                item {
                    PaginationRow(
                        page = page.page,
                        hasNext = page.hasNextPage,
                        onPrevious = onPreviousPage,
                        onNext = onNextPage
                    )
                }
            } else if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
        if (source == ShenzhenCourseCatalogSource.AVAILABLE) {
            SelectionActionBar(
                selectedCount = selectedSubmissionCourses.size,
                onSubmitNow = { showImmediateConfirmation = true },
                onSchedule = {
                    when {
                        !notificationPermissionGranted -> {
                            awaitingNotificationPermission = true
                            onRequestNotificationPermission()
                        }
                        !canScheduleExactAlarms() -> showExactAlarmGuidance = true
                        else -> showScheduleDatePicker = true
                    }
                }
            )
        }
    }
}

@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    onSubmitNow: () -> Unit,
    onSchedule: () -> Unit
) {
    val tokens = HitaTheme.tokens
    Surface(
        modifier = Modifier.navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.course_selection_selected_count, selectedCount),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
            ) {
                OutlinedButton(
                    onClick = onSubmitNow,
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.course_selection_submit_now))
                }
                Button(
                    onClick = onSchedule,
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.course_selection_schedule_submit))
                }
            }
        }
    }
}

@Composable
private fun ImmediateSelectionConfirmationDialog(
    courses: List<ShenzhenCourseCatalogItem>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.course_selection_immediate_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.course_selection_real_side_effect_warning),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.course_selection_selected_courses_title),
                    modifier = Modifier.padding(top = 12.dp),
                    fontWeight = FontWeight.Medium
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(courses, key = { "confirm-${it.selectionRequestId}" }) { course ->
                        Text(
                            text = stringResource(
                                R.string.course_selection_course_name_item,
                                course.courseName
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = courses.isNotEmpty()) {
                Text(stringResource(R.string.course_selection_confirm_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun ExactAlarmGuidanceDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.course_selection_exact_alarm_title)) },
        text = {
            Text(stringResource(R.string.course_selection_exact_alarm_permission_explanation))
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.course_selection_open_exact_alarm_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionScheduleDateDialog(
    onDismiss: () -> Unit,
    onDateSelected: (Long) -> Unit
) {
    val initialSelection = remember { defaultSelectionScheduleMillis() }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialSelection)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { datePickerState.selectedDateMillis?.let(onDateSelected) },
                enabled = datePickerState.selectedDateMillis != null
            ) {
                Text(stringResource(R.string.course_selection_next_to_time))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    ) {
        DatePicker(
            state = datePickerState,
            title = {
                Text(
                    text = stringResource(R.string.course_selection_choose_date),
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp)
                )
            },
            showModeToggle = false
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionScheduleTimeDialog(
    dateMillis: Long,
    courseCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val initialSelection = remember { Calendar.getInstance().apply { add(Calendar.MINUTE, 1) } }
    val timePickerState = rememberTimePickerState(
        initialHour = initialSelection.get(Calendar.HOUR_OF_DAY),
        initialMinute = initialSelection.get(Calendar.MINUTE),
        is24Hour = true
    )
    var secondsInput by remember {
        mutableStateOf(initialSelection.get(Calendar.SECOND).toString().padStart(2, '0'))
    }
    var validationError by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.course_selection_choose_time)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(
                        R.string.course_selection_schedule_date,
                        formatSelectionDate(dateMillis)
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                TimePicker(state = timePickerState)
                OutlinedTextField(
                    value = secondsInput,
                    onValueChange = { value ->
                        if (value.length <= 2 && value.all { it.isDigit() }) {
                            secondsInput = value
                            validationError = null
                        }
                    },
                    label = { Text(stringResource(R.string.course_selection_seconds)) },
                    singleLine = true,
                    isError = validationError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        R.string.course_selection_schedule_course_count,
                        courseCount
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                validationError?.let { messageRes ->
                    Text(
                        text = stringResource(messageRes),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val seconds = secondsInput.toIntOrNull()
                if (seconds == null || seconds !in 0..59) {
                    validationError = R.string.course_selection_invalid_seconds
                } else {
                    val scheduledAtMillis = combineSelectionDateTime(
                        dateMillis = dateMillis,
                        hour = timePickerState.hour,
                        minute = timePickerState.minute,
                        second = seconds
                    )
                    when (ShenzhenCourseSelectionUiPolicy.validateSchedule(
                        now = System.currentTimeMillis(),
                        scheduled = scheduledAtMillis
                    )) {
                        CourseSelectionScheduleValidation.VALID -> onConfirm(scheduledAtMillis)
                        CourseSelectionScheduleValidation.TOO_SOON,
                        CourseSelectionScheduleValidation.TOO_FAR ->
                            validationError = R.string.course_selection_schedule_range_error
                    }
                }
            }) {
                Text(stringResource(R.string.course_selection_create_schedule))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private fun LazyListScope.selectionJobItems(
    activeJobs: List<CourseSelectionJob>,
    terminalJobs: List<CourseSelectionJob>,
    onCancel: (String) -> Unit,
    onReconfirm: (String) -> Unit
) {
    if (activeJobs.isNotEmpty()) {
        item {
            SelectionJobSectionTitle(R.string.course_selection_active_jobs)
        }
        items(activeJobs, key = { "selection-job-${it.id}" }) { job ->
            SelectionJobCard(
                job = job,
                onCancel = { onCancel(job.id) },
                onReconfirm = { onReconfirm(job.id) }
            )
        }
    }
    if (terminalJobs.isNotEmpty()) {
        item {
            SelectionJobSectionTitle(R.string.course_selection_recent_jobs)
        }
        items(terminalJobs, key = { "selection-job-${it.id}" }) { job ->
            SelectionJobCard(
                job = job,
                onCancel = { onCancel(job.id) },
                onReconfirm = { onReconfirm(job.id) }
            )
        }
    }
}

@Composable
private fun SelectionJobSectionTitle(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun SelectionJobCard(
    job: CourseSelectionJob,
    onCancel: () -> Unit,
    onReconfirm: () -> Unit
) {
    val tokens = HitaTheme.tokens
    val shape = RoundedCornerShape(tokens.radius.lg)
    val canReconfirm = job.status !in setOf(
        CourseSelectionJobStatus.WAITING,
        CourseSelectionJobStatus.RUNNING,
        CourseSelectionJobStatus.CANCELLED
    ) && job.results.any {
        it.status == CourseSelectionCourseStatus.UNCONFIRMED ||
            it.status == CourseSelectionCourseStatus.UNKNOWN
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .hitaGlassCardModifier(shape),
        shape = shape,
        colors = hitaGlassCardColors(),
        border = hitaGlassCardBorder(),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = selectionJobStatusText(job.status),
                    color = selectionJobStatusColor(job.status),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(
                        R.string.course_selection_job_course_count,
                        job.courses.size
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Text(
                text = stringResource(
                    R.string.course_selection_job_schedule,
                    formatSelectionTime(job.scheduledAtMillis)
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (job.message.isNotBlank()) {
                Text(
                    text = job.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = tokens.spacing.sm),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
            job.courses.forEach { course ->
                val result = job.results.firstOrNull { it.courseId == course.courseId }
                Text(
                    text = stringResource(
                        R.string.course_selection_job_course_result,
                        course.courseName,
                        selectionCourseResultText(job.status, result?.status)
                    ),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (!result?.message.isNullOrBlank()) {
                    Text(
                        text = result?.message.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                    )
                }
            }
            if (job.status == CourseSelectionJobStatus.WAITING || canReconfirm) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = tokens.spacing.sm),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (job.status == CourseSelectionJobStatus.WAITING) {
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.course_selection_cancel_job))
                        }
                    }
                    if (canReconfirm) {
                        TextButton(onClick = onReconfirm) {
                            Text(stringResource(R.string.course_selection_reconfirm_results))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun selectionJobStatusText(status: CourseSelectionJobStatus): String = stringResource(
    when (status) {
        CourseSelectionJobStatus.WAITING -> R.string.course_selection_status_waiting
        CourseSelectionJobStatus.RUNNING -> R.string.course_selection_status_running
        CourseSelectionJobStatus.COMPLETED -> R.string.course_selection_status_completed
        CourseSelectionJobStatus.PARTIAL -> R.string.course_selection_status_partial
        CourseSelectionJobStatus.FAILED -> R.string.course_selection_status_failed
        CourseSelectionJobStatus.CANCELLED -> R.string.course_selection_status_cancelled
    }
)

@Composable
private fun selectionCourseResultText(
    jobStatus: CourseSelectionJobStatus,
    resultStatus: CourseSelectionCourseStatus?
): String = stringResource(
    when (resultStatus) {
        CourseSelectionCourseStatus.CONFIRMED -> R.string.course_selection_result_confirmed
        CourseSelectionCourseStatus.UNCONFIRMED -> R.string.course_selection_result_unconfirmed
        CourseSelectionCourseStatus.BUSINESS_FAILURE -> R.string.course_selection_result_business_failure
        CourseSelectionCourseStatus.AUTH_REQUIRED -> R.string.course_selection_result_auth_required
        CourseSelectionCourseStatus.UNKNOWN -> R.string.course_selection_result_unknown
        null -> when (jobStatus) {
            CourseSelectionJobStatus.WAITING -> R.string.course_selection_result_waiting
            CourseSelectionJobStatus.RUNNING -> R.string.course_selection_result_running
            else -> R.string.course_selection_result_missing
        }
    }
)

@Composable
private fun selectionJobStatusColor(status: CourseSelectionJobStatus) = when (status) {
    CourseSelectionJobStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    CourseSelectionJobStatus.PARTIAL,
    CourseSelectionJobStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun defaultSelectionScheduleMillis(): Long = Calendar.getInstance().apply {
    add(Calendar.MINUTE, 1)
}.timeInMillis

private fun combineSelectionDateTime(
    dateMillis: Long,
    hour: Int,
    minute: Int,
    second: Int
): Long {
    val selectedDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = dateMillis
    }
    return Calendar.getInstance().apply {
        set(Calendar.YEAR, selectedDate.get(Calendar.YEAR))
        set(Calendar.MONTH, selectedDate.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, selectedDate.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, second)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun formatSelectionDate(timeMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(timeMillis)

private fun formatSelectionTime(timeMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(timeMillis)

@Composable
private fun WebLoginRequiredCard(
    message: String?,
    onConnectWeb: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = HitaTheme.tokens
    val shape = RoundedCornerShape(tokens.radius.lg)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .hitaGlassCardModifier(shape),
        shape = shape,
        colors = hitaGlassCardColors(),
        border = hitaGlassCardBorder(),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Text("需要深圳 Web 会话", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = message ?: "教务选课池和全校课表属于 Web 教务独占接口，需要通过统一身份认证连接。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = tokens.spacing.sm)
            )
            Button(
                onClick = onConnectWeb,
                modifier = Modifier.padding(top = tokens.spacing.lg)
            ) {
                Text("连接深圳 Web 教务")
            }
        }
    }
}

@Composable
private fun CatalogErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = HitaTheme.tokens
    val shape = RoundedCornerShape(tokens.radius.lg)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .hitaGlassCardModifier(shape),
        shape = shape,
        colors = hitaGlassCardColors(),
        border = hitaGlassCardBorder(),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Text("课程数据暂不可用", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = tokens.spacing.sm)
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = tokens.spacing.lg)) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun CatalogFilters(
    termName: String,
    secondaryName: String,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectTerm: () -> Unit,
    onSelectSecondary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = HitaTheme.tokens
    val shape = RoundedCornerShape(tokens.radius.lg)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .hitaGlassCardModifier(shape),
        shape = shape,
        colors = hitaGlassCardColors(),
        border = hitaGlassCardBorder(),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)) {
                FilterValue(termName, onSelectTerm, Modifier.weight(1f))
                FilterValue(secondaryName, onSelectSecondary, Modifier.weight(1f))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
            ) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = onKeywordChange,
                    label = { Text("课程名 / 代码 / 教师") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSearch) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_search_24),
                        contentDescription = "搜索"
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterValue(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(
                painter = painterResource(R.drawable.ic_baseline_arrow_drop_down_24),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun CourseCatalogCard(
    item: ShenzhenCourseCatalogItem,
    followed: Boolean,
    selectedInEas: Boolean,
    inCoursePlan: Boolean,
    conflictMessage: String?,
    selectable: Boolean,
    selectedForSubmission: Boolean,
    onToggleFollow: () -> Unit,
    onToggleCoursePlan: () -> Unit,
    onToggleSubmission: () -> Unit,
    onClick: () -> Unit
) {
    val tokens = HitaTheme.tokens
    val shape = RoundedCornerShape(tokens.radius.lg)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .hitaGlassCardModifier(shape)
            .clickable(onClick = onClick),
        shape = shape,
        colors = hitaGlassCardColors(),
        border = hitaGlassCardBorder(),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.courseName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (item.courseCode.isNotBlank()) {
                        Text(
                            item.courseCode,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                if (item.credits.isNotBlank()) {
                    Text("${item.credits} 学分", color = MaterialTheme.colorScheme.primary)
                }
                if (item.source == ShenzhenCourseCatalogSource.SCHOOL) {
                    TextButton(
                        onClick = onToggleFollow,
                        enabled = followed || item.isFollowable
                    ) {
                        Text(
                            when {
                                followed -> "已关注"
                                item.isFollowable -> "关注"
                                else -> "时间不完整"
                            }
                        )
                    }
                } else {
                    TextButton(
                        onClick = onToggleCoursePlan,
                        enabled = !selectedInEas
                    ) {
                        Text(
                            when {
                                selectedInEas -> "教务已选"
                                inCoursePlan -> "已加入"
                                else -> "加入预览"
                            }
                        )
                    }
                }
            }
            if (selectable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleSubmission),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selectedForSubmission,
                        onCheckedChange = null
                    )
                    Text(
                        text = stringResource(
                            if (selectedForSubmission) {
                                R.string.course_selection_selected_for_submission
                            } else {
                                R.string.course_selection_select_for_submission
                            }
                        ),
                        color = if (selectedForSubmission) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            val metadata = listOf(
                item.teacher,
                item.offeringCollege,
                item.campus,
                item.courseNature,
                item.courseCategory,
                item.teachingLanguage
            ).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            if (metadata.isNotBlank()) {
                Text(
                    metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = tokens.spacing.sm)
                )
            }
            if (item.selectionRequirement.isNotBlank()) {
                Text(
                    text = "适用范围：${item.selectionRequirement}",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = tokens.spacing.sm)
                )
            }
            if (item.schedule.isNotBlank()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = tokens.spacing.sm),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
                Text(item.schedule, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (conflictMessage != null) {
                Text(
                    text = conflictMessage,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = tokens.spacing.sm)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.selectionPoolName.isNotBlank()) {
                    Text(
                        item.selectionPoolName,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp
                    )
                } else if (item.trainingLevel.isNotBlank()) {
                    Text(item.trainingLevel, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                val seats = when {
                    item.selectedCount != null && item.capacity != null ->
                        "已选 ${item.selectedCount} / ${item.capacity}"
                    item.capacity != null -> "容量 ${item.capacity}"
                    else -> ""
                }
                if (seats.isNotBlank()) {
                    val full = item.capacity != null && item.selectedCount != null &&
                        item.selectedCount >= item.capacity
                    Text(
                        seats,
                        color = if (full) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
            Text(
                text = if (item.source == ShenzhenCourseCatalogSource.SCHOOL) {
                    "关注后会显示在时间表、今日页和桌面小组件"
                } else {
                    "点击查看课程简介与教学大纲附件"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = tokens.spacing.sm)
            )
        }
    }
}

@Composable
private fun CourseAttachmentDialog(
    state: CourseAttachmentDialogState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onDownload: (ShenzhenCourseAttachment) -> Unit
) {
    val tokens = HitaTheme.tokens
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("教学附件")
                Text(
                    text = state.course.courseName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            when {
                state.loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                state.error != null -> Column {
                    Text(state.error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.padding(top = tokens.spacing.sm)
                    ) {
                        Text("重试")
                    }
                }
                state.attachments.isEmpty() -> Text(
                    "该课程暂未发布可下载的课程简介或教学大纲。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)) {
                    state.attachments.forEach { attachment ->
                        AttachmentRow(attachment = attachment, onClick = { onDownload(attachment) })
                    }
                    Text(
                        text = "点击附件后将保存到系统“下载”目录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun AttachmentRow(
    attachment: ShenzhenCourseAttachment,
    onClick: () -> Unit
) {
    val label = when (attachment.kind) {
        ShenzhenCourseAttachmentKind.COURSE_DESCRIPTION -> "课程简介"
        ShenzhenCourseAttachmentKind.CHINESE_SYLLABUS -> "中文教学大纲"
        ShenzhenCourseAttachmentKind.ENGLISH_SYLLABUS -> "英文教学大纲"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = attachment.name,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val size = attachment.sizeBytes?.takeIf { it >= 0 }?.let(::formatFileSize)
            Text(
                text = listOfNotNull(label, size).joinToString(" · "),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun HistoricalFailureDialog(
    state: HistoricalFailureDialogState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    val report = state.report
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("两年前各教师挂科率")
                Text(
                    text = state.course.courseName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            when {
                state.loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                state.error != null -> Column {
                    Text(state.error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                        Text("重试")
                    }
                }
                report == null -> Text("尚未生成查询结果")
                report.teacherRates.isEmpty() -> Column {
                    Text(
                        if (report.matchedClassCount == 0) {
                            "${report.targetTerm.name} 没有找到对应的同课程教学班。"
                        } else {
                            "找到 ${report.matchedClassCount} 个教学班，但教务没有返回可分析的分项成绩。"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> Column {
                    Text(
                        text = "${report.targetTerm.name} · ${report.analyzedClassCount} 个教学班",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(report.teacherRates, key = { it.teacher }) { rate ->
                            TeacherFailureRateRow(rate)
                        }
                    }
                    if (report.skippedClassCount > 0) {
                        Text(
                            text = "另有 ${report.skippedClassCount} 个教学班未返回可分析成绩",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    Text(
                        text = "按教务 seeFx 分项折算；含空白分项的学生按 0 分计入",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun TeacherFailureRateRow(rate: ShenzhenTeacherFailureRate) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rate.teacher, fontWeight = FontWeight.Medium)
                Text(
                    text = "${rate.failCount} / ${rate.studentCount} 人未及格 · ${rate.classCount} 个教学班",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = String.format(
                        Locale.ROOT,
                        "平均 %.2f · 前20%%平均 %.2f",
                        rate.averageScore,
                        rate.top20AverageScore
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (rate.excludedIncompleteStudentCount > 0) {
                    Text(
                        text = "${rate.excludedIncompleteStudentCount} 人含空白分项，按 0 分计入",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Text(
                text = String.format(Locale.ROOT, "%.2f%%", rate.failureRate),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun PaginationRow(
    page: Int,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = onPrevious, enabled = page > 1) { Text("上一页") }
        Spacer(modifier = Modifier.width(16.dp))
        Text("第 $page 页", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        OutlinedButton(onClick = onNext, enabled = hasNext) { Text("下一页") }
    }
}
