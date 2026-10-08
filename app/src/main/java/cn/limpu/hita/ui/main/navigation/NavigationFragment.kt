package cn.limpu.hita.ui.main.navigation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cn.limpu.hita.R
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.data.source.preference.CourseReminderStore
import cn.limpu.hita.data.work.CourseReminderScheduler
import cn.limpu.hita.feature.livecourse.guard.LiveCourseGuardService
import cn.limpu.hita.feature.livecourse.scheduler.LiveCourseScheduler
import cn.limpu.hita.feature.livecourse.settings.LiveCourseSettings
import cn.limpu.hita.ui.credit.CreditStatsActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.HitaThemeStyle
import cn.limpu.hita.ui.design.hitaGlassCardBorder
import cn.limpu.hita.ui.design.hitaGlassCardColors
import cn.limpu.hita.ui.design.hitaGlassCardModifier
import cn.limpu.hita.ui.design.hitaUsesMainBackdrop
import cn.limpu.hita.ui.design.hitaIsAppleGlassSurface
import cn.limpu.hita.ui.design.hitaStyleCardShape
import cn.limpu.hita.ui.design.hitaSumiBrushUnderline
import cn.limpu.hita.ui.eas.classroom.EmptyClassroomActivity
import cn.limpu.hita.ui.eas.catalog.ShenzhenCourseCatalogActivity
import cn.limpu.hita.ui.eas.exam.ExamActivity
import cn.limpu.hita.ui.eas.grade.ShenzhenGradeAnalysisActivity
import cn.limpu.hita.ui.eas.imp.ImportTimetableActivity
import cn.limpu.hita.ui.notice.AppNoticesActivity
import cn.limpu.hita.ui.links.UsefulLinksActivity
import cn.limpu.hita.ui.eas.login.PopUpLoginEAS
import cn.limpu.hita.ui.eas.score.ScoreInquiryActivity
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.ActivityUtils.CourseResourceMode
import cn.limpu.hita.utils.IcsImportUtils
import com.limpu.hitauser.data.model.UserLocal
import com.limpu.hitauser.data.repository.LocalUserRepository
import com.limpu.hitauser.util.ImageUtils
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class NavigationFragment : androidx.fragment.app.Fragment() {

    @Inject lateinit var localUserRepository: LocalUserRepository
    @Inject lateinit var easRepository: EASRepository
    @Inject lateinit var timetableRepository: TimetableRepository

    private val viewModel: NavigationViewModel by viewModels()
    private var reminderEnabledState by mutableStateOf(false)
    private var liveCourseEnabledState by mutableStateOf(false)
    private var liveCourseStrongReminderState by mutableStateOf(false)
    private var usageAnalyticsEnabledState by mutableStateOf(true)
    private var userStateVersion by mutableStateOf(0)

    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveAvatarLocally(it) }
    }

    private val selectIcsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { importICS(it) }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            enableCourseReminder()
        } else {
            reminderEnabledState = false
            Toast.makeText(requireContext(), "需要通知权限才能发送课程提醒", Toast.LENGTH_LONG).show()
        }
    }

    private val liveCourseNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            enableLiveCourse()
        } else {
            liveCourseEnabledState = false
            Toast.makeText(requireContext(), R.string.live_course_notification_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        reminderEnabledState = CourseReminderStore(requireContext()).isEnabled()
        usageAnalyticsEnabledState = UsageAnalyticsClient.isEnabled(requireContext())
        LiveCourseSettings(requireContext()).also { settings ->
            liveCourseEnabledState = settings.isEnabled()
            liveCourseStrongReminderState = settings.isStrongReminderEnabled()
        }
        return ComposeView(requireContext()).apply {
            setContent {
                HitaComposeTheme() {
                    val easToken by easRepository.observeEasToken()
                        .observeAsState(easRepository.getEasToken())
                    NavigationScreen(
                        viewModel = viewModel,
                        localUser = remember(userStateVersion) { localUserRepository.getLoggedInUser() },
                        easToken = easToken,
                        reminderEnabled = reminderEnabledState,
                        onToggleReminder = { toggleCourseReminder(!reminderEnabledState) },
                        liveCourseEnabled = liveCourseEnabledState,
                        onToggleLiveCourse = { toggleLiveCourse(!liveCourseEnabledState) },
                        onOpenLiveCourseSystemSettings = { openLiveCourseSystemSettings() },
                        liveCourseStrongReminderEnabled = liveCourseStrongReminderState,
                        onToggleLiveCourseStrongReminder = {
                            toggleLiveCourseStrongReminder(!liveCourseStrongReminderState)
                        },
                        usageAnalyticsEnabled = usageAnalyticsEnabledState,
                        onToggleUsageAnalytics = { toggleUsageAnalytics() },
                        onOpenNotices = { openNotices() },
                        onAvatarClick = { showAvatarPicker() },
                        onUserClick = { openUserCard() },
                        onTimetableManager = { ActivityUtils.startTimetableManager(requireContext()) },
                        onRecentTimetable = {
                            viewModel.recentTimetableLiveData.value?.let {
                                ActivityUtils.startTimetableDetailActivity(requireContext(), it.id)
                            }
                        },
                        onImportTimetable = {
                            ActivityUtils.startActivity(requireContext(), ImportTimetableActivity::class.java)
                        },
                        onImportIcs = { selectIcsLauncher.launch(IcsImportUtils.pickerMimeTypes()) },
                        onExam = { openExam() },
                        onScores = { ActivityUtils.startActivity(requireContext(), ScoreInquiryActivity::class.java) },
                        onGradeAnalysis = {
                            ActivityUtils.startActivity(requireContext(), ShenzhenGradeAnalysisActivity::class.java)
                        },
                        onEmptyClassroom = { ActivityUtils.startActivity(requireContext(), EmptyClassroomActivity::class.java) },
                        onCreditStats = { ActivityUtils.startActivity(requireContext(), CreditStatsActivity::class.java) },
                        onCourseCatalog = {
                            ActivityUtils.startActivity(requireContext(), ShenzhenCourseCatalogActivity::class.java)
                        },
                        onCourseLookup = {
                            ActivityUtils.startCourseResourceSearchActivity(requireContext(), mode = CourseResourceMode.VIEW)
                        },
                        onCourseSubmit = {
                            ActivityUtils.startCourseResourceSearchActivity(requireContext(), mode = CourseResourceMode.SUBMIT)
                        },
                        onUsefulLinks = {
                            ActivityUtils.startActivity(requireContext(), UsefulLinksActivity::class.java)
                        },
                    )
                }
            }
        }
    }


    @SuppressLint("SetTextI18n")
    override fun onStart() {
        super.onStart()
        reminderEnabledState = CourseReminderStore(requireContext()).isEnabled()
        LiveCourseSettings(requireContext()).also { settings ->
            liveCourseEnabledState = settings.isEnabled()
            liveCourseStrongReminderState = settings.isStrongReminderEnabled()
        }
        // Returning from the Android "alarms and reminders" page can grant exact-alarm
        // access after an inexact fallback alarm was already registered. Reconcile here so
        // the next course transition is immediately replaced with an exact alarm.
        if (liveCourseEnabledState) {
            LiveCourseScheduler.autoSchedule(requireContext())
            startLiveCourseGuardIfEnabled()
        }
        usageAnalyticsEnabledState = UsageAnalyticsClient.isEnabled(requireContext())
        viewModel.startRefresh()
    }

    private fun showAvatarPicker() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("更换头像")
            .setItems(arrayOf("从相册选择", "取消")) { _, which ->
                if (which == 0) pickAvatarLauncher.launch("image/*")
            }
            .show()
    }

    private fun saveAvatarLocally(uri: Uri) {
        localUserRepository.saveLocalAvatar(uri, requireContext().applicationContext) { success ->
            if (success) {
                userStateVersion++
                Toast.makeText(requireContext(), "头像更换成功", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "图片处理失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openUserCard() {
        val localUser = localUserRepository.getLoggedInUser()
        val easToken = easRepository.getEasToken()
        if (localUser.isValid() || easToken.isLogin()) {
            val title = if (localUser.isValid()) localUser.nickname.orEmpty()
                else easToken.name ?: easToken.username.orEmpty()
            val items = if (localUser.isValid())
                arrayOf("查看资料", "退出登录")
            else
                arrayOf("退出登录")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setItems(items) { _: DialogInterface, which: Int ->
                    when {
                        localUser.isValid() && which == 0 -> {
                            val userId = localUser.id ?: return@setItems
                            ActivityUtils.startProfileActivity(requireContext(), userId, null)
                        }
                        else -> {
                            if (localUser.isValid())
                                localUserRepository.logout(requireContext())
                            easRepository.logout()
                            userStateVersion++
                        }
                    }
                }
                .show()
        } else {
            ActivityUtils.showEasVerifyWindow<Activity>(
                requireActivity(),
                easRepository,
                directTo = null,
                onResponseListener = object : PopUpLoginEAS.OnResponseListener {
                    override fun onSuccess(window: PopUpLoginEAS) {
                        userStateVersion++
                        window.dismiss()
                    }
                    // 登录取消/失败也必须关闭弹窗，否则 BottomSheet 会一直盖在“更多”页面上。
                    override fun onFailed(window: PopUpLoginEAS) {
                        window.dismiss()
                    }
                }
            )
        }
    }

    private fun openExam() {
        ActivityUtils.showEasVerifyWindow(
            requireActivity(),
            easRepository,
            directTo = ExamActivity::class.java,
            onResponseListener = object : PopUpLoginEAS.OnResponseListener {
                override fun onSuccess(window: PopUpLoginEAS) {
                    userStateVersion++
                    ActivityUtils.startActivity(requireActivity(), ExamActivity::class.java)
                    window.dismiss()
                }

                override fun onFailed(window: PopUpLoginEAS) {}
            }
        )
    }

    private fun toggleCourseReminder(enable: Boolean) {
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (requireContext().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    enableCourseReminder()
                } else {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                enableCourseReminder()
            }
        } else {
            reminderEnabledState = false
            CourseReminderStore(requireContext()).setEnabled(false)
            CourseReminderScheduler.autoSchedule(requireContext())
            Toast.makeText(requireContext(), "课程提醒已关闭", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importICS(uri: Uri) {
        val context = requireContext()
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Toast.makeText(context, "无法读取所选 ICS 文件", Toast.LENGTH_SHORT).show()
                return
            }
            timetableRepository.importFromICSAsNewTimetable(inputStream, IcsImportUtils.getDisplayName(context, uri))
                .observe(viewLifecycleOwner) { result ->
                    when (result.state) {
                        com.limpu.component.data.DataState.STATE.SUCCESS -> {
                            val importResult = result.data ?: return@observe
                            Toast.makeText(
                                context,
                                "已创建课表“${importResult.timetableName}”，导入 ${importResult.importedCount} 个课程",
                                Toast.LENGTH_SHORT
                            ).show()
                            ActivityUtils.startTimetableDetailActivity(context, importResult.timetableId)
                        }
                        com.limpu.component.data.DataState.STATE.FETCH_FAILED -> {
                            Toast.makeText(context, "导入失败: ${result.message}", Toast.LENGTH_SHORT).show()
                        }
                        else -> Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show()
                    }
                }
        } catch (e: Exception) {
            Toast.makeText(context, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enableCourseReminder() {
        reminderEnabledState = true
        val reminderStore = CourseReminderStore(requireContext())
        reminderStore.setEnabled(true)
        CourseReminderScheduler.autoSchedule(requireContext())
        Toast.makeText(requireContext(), "课程提醒已开启（上课前15分钟提醒）", Toast.LENGTH_SHORT).show()
    }

    private fun toggleLiveCourse(enable: Boolean) {
        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            requireContext().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            liveCourseNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (enable) {
            enableLiveCourse()
        } else {
            liveCourseEnabledState = false
            LiveCourseSettings(requireContext()).also { settings ->
                settings.setEnabled(false)
                settings.setStrongReminderEnabled(false)
            }
            liveCourseStrongReminderState = false
            LiveCourseGuardService.stop(requireContext())
            LiveCourseScheduler.autoSchedule(requireContext())
            Toast.makeText(requireContext(), R.string.live_course_disabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun enableLiveCourse() {
        liveCourseEnabledState = true
        val liveCourseSettings = LiveCourseSettings(requireContext())
        liveCourseSettings.setEnabled(true)
        LiveCourseScheduler.autoSchedule(requireContext())
        startLiveCourseGuardIfEnabled()
        Toast.makeText(requireContext(), R.string.live_course_enabled, Toast.LENGTH_SHORT).show()
        if (!liveCourseSettings.canScheduleExactAlarms()) {
            Toast.makeText(
                requireContext(),
                R.string.live_course_exact_alarm_permission_required,
                Toast.LENGTH_LONG,
            ).show()
            startActivity(liveCourseSettings.exactAlarmSettingsIntent())
        }
    }

    private fun openLiveCourseSystemSettings() {
        startActivity(LiveCourseSettings(requireContext()).promotedNotificationSettingsIntent())
    }

    private fun toggleLiveCourseStrongReminder(enable: Boolean) {
        if (!liveCourseEnabledState) return
        liveCourseStrongReminderState = enable
        LiveCourseSettings(requireContext()).setStrongReminderEnabled(enable)
        if (enable) {
            LiveCourseGuardService.start(requireContext())
            Toast.makeText(requireContext(), R.string.live_course_strong_reminder_enabled, Toast.LENGTH_SHORT).show()
        } else {
            LiveCourseGuardService.stop(requireContext())
            Toast.makeText(requireContext(), R.string.live_course_strong_reminder_disabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLiveCourseGuardIfEnabled() {
        if (LiveCourseSettings(requireContext()).isStrongReminderEnabled()) {
            LiveCourseGuardService.start(requireContext())
        }
    }

    private fun toggleUsageAnalytics() {
        val next = !usageAnalyticsEnabledState
        UsageAnalyticsClient.setEnabled(requireContext(), next)
        usageAnalyticsEnabledState = next
        if (next) {
            UsageAnalyticsClient.record(UsageAnalyticsEvent.APP_FOREGROUND)
        }
    }

    private fun openNotices() {
        ActivityUtils.startActivity(requireContext(), AppNoticesActivity::class.java)
    }
}

@Composable
private fun NavigationScreen(
    viewModel: NavigationViewModel,
    localUser: UserLocal,
    easToken: EASToken,
    reminderEnabled: Boolean,
    liveCourseEnabled: Boolean,
    liveCourseStrongReminderEnabled: Boolean,
    usageAnalyticsEnabled: Boolean,
    onAvatarClick: () -> Unit,
    onUserClick: () -> Unit,
    onRecentTimetable: () -> Unit,
    onTimetableManager: () -> Unit,
    onImportTimetable: () -> Unit,
    onImportIcs: () -> Unit,
    onExam: () -> Unit,
    onScores: () -> Unit,
    onGradeAnalysis: () -> Unit,
    onEmptyClassroom: () -> Unit,
    onCreditStats: () -> Unit,
    onCourseCatalog: () -> Unit,
    onCourseLookup: () -> Unit,
    onCourseSubmit: () -> Unit,
    onUsefulLinks: () -> Unit,
    onToggleReminder: () -> Unit,
    onToggleLiveCourse: () -> Unit,
    onOpenLiveCourseSystemSettings: () -> Unit,
    onToggleLiveCourseStrongReminder: () -> Unit,
    onToggleUsageAnalytics: () -> Unit,
    onOpenNotices: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    val recentTimetable by viewModel.recentTimetableLiveData.observeAsState()
    val timetableCount by viewModel.timetableCountLiveData.observeAsState(0)


    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(
                if (hitaUsesMainBackdrop()) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.background
                }
            )
            .padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
    ) {
        UserCard(
            localUser = localUser,
            easToken = easToken,
            onAvatarClick = onAvatarClick,
            onClick = onUserClick,
        )
        Row(
            modifier = Modifier.padding(horizontal = tokens.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
        ) {
            ShortcutCard(
                title = stringResource(R.string.recent_timetable),
                subtitle = recentTimetable?.name ?: stringResource(R.string.none),
                icon = R.drawable.ic_nav_timetable,
                onClick = onRecentTimetable,
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp)
            )
            ShortcutCard(
                title = stringResource(R.string.title_timetable_manager),
                subtitle = if (timetableCount == 0) {
                    stringResource(R.string.no_timetable)
                } else {
                    stringResource(R.string.timetable_count_format, timetableCount)
                },
                icon = R.drawable.ic_list,
                onClick = onTimetableManager,
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp)
            )
        }
        NavigationGroup(title = "课表工具") {
            NavigationRow(icon = R.drawable.ic_import, title = stringResource(R.string.import_timetable), onClick = onImportTimetable)
            NavigationRow(icon = R.drawable.ic_baseline_cloud_download_24, title = "导入 ICS 课表", subtitle = "从日历文件导入课程", onClick = onImportIcs)
        }
        NavigationGroup(title = stringResource(R.string.navi_jw_title)) {
            NavigationRow(icon = R.drawable.ic_baseline_today_24, title = stringResource(R.string.ade_exam), onClick = onExam)
            NavigationRow(icon = R.drawable.ic_baseline_format_list_bulleted_24, title = stringResource(R.string.jw_tabs_cj), onClick = onScores)
            if (easToken.campus == EASToken.Campus.SHENZHEN) {
                NavigationRow(
                    icon = R.drawable.ic_baseline_format_list_bulleted_24,
                    title = "个人成绩明细",
                    subtitle = "成绩状态 · 分项得分 · 权重折算",
                    onClick = onGradeAnalysis
                )
            }
            NavigationRow(icon = R.drawable.ic_baseline_location_city_24, title = stringResource(R.string.shortcut_empty_classroom_short), onClick = onEmptyClassroom)
            NavigationRow(
                icon = R.drawable.ic_baseline_format_list_bulleted_24,
                title = if (easToken.campus == EASToken.Campus.SHENZHEN) {
                    "培养方案"
                } else {
                    stringResource(R.string.navi_credit_stats)
                },
                subtitle = if (easToken.campus == EASToken.Campus.SHENZHEN) {
                    "完成度 · 学分类别 · 培养课组"
                } else "",
                onClick = onCreditStats
            )
            NavigationRow(
                icon = R.drawable.ic_baseline_search_24,
                title = "深圳课程浏览",
                subtitle = "教务选课池 · 全校课表（需 Web 登录）",
                onClick = onCourseCatalog
            )
        }
        NavigationGroup(title = stringResource(R.string.navi_course_resource_title)) {
            NavigationRow(icon = R.drawable.ic_baseline_search_24, title = stringResource(R.string.navi_course_lookup), subtitle = stringResource(R.string.navi_course_lookup_sub), onClick = onCourseLookup)
            NavigationRow(icon = R.drawable.ic_baseline_edit_24, title = stringResource(R.string.navi_course_submit_pr), subtitle = stringResource(R.string.navi_course_submit_pr_sub), onClick = onCourseSubmit)
            // 实用网址：三校区登录后可见，列表按当前校区切换
            if (easToken.isLogin()) {
                NavigationRow(icon = R.drawable.ic_baseline_link_24, title = stringResource(R.string.navi_useful_links), subtitle = stringResource(R.string.navi_useful_links_sub), onClick = onUsefulLinks)
            }
        }
        NavigationGroup(title = "设置") {
            NavigationRow(
                icon = R.drawable.ic_baseline_access_alarm_24,
                title = "课程提醒",
                subtitle = "上课前15分钟提醒",
                onClick = onToggleReminder,
                trailing = {
                    Switch(
                        checked = reminderEnabled,
                        onCheckedChange = { onToggleReminder() }
                    )
                }
            )
            LiveCourseControl(
                enabled = liveCourseEnabled,
                strongReminderEnabled = liveCourseStrongReminderEnabled,
                onToggle = onToggleLiveCourse,
                onOpenSystemSettings = onOpenLiveCourseSystemSettings,
                onToggleStrongReminder = onToggleLiveCourseStrongReminder,
            )
            NavigationRow(
                icon = R.drawable.ic_info,
                title = "使用统计",
                subtitle = "匿名统计功能使用情况，帮助改进应用",
                onClick = onToggleUsageAnalytics,
                trailing = {
                    Switch(
                        checked = usageAnalyticsEnabled,
                        onCheckedChange = { onToggleUsageAnalytics() }
                    )
                }
            )
            NavigationRow(
                icon = R.drawable.ic_menu_settings,
                title = "公告",
                subtitle = "版本更新 · 服务与故障通知",
                onClick = onOpenNotices,
                trailing = {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
        Spacer(modifier = Modifier.height(tokens.spacing.xl))
    }
}

@Composable
private fun UserCard(
    localUser: UserLocal,
    easToken: EASToken,
    onAvatarClick: () -> Unit,
    onClick: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    val context = LocalContext.current
    val localAvatar = localUser.avatar?.takeIf { it.startsWith("local://") }
    val isLocalValid = localUser.isValid()
    val title: String
    val subtitle: String
    val avatarSource: String?
    if (isLocalValid) {
        title = localUser.nickname.orEmpty()
        subtitle = localUser.username.orEmpty()
        avatarSource = localAvatar ?: localUser.avatar
    } else if (easToken.isLogin()) {
        title = easToken.name?.ifBlank { easToken.stuId?.ifBlank { easToken.username } }
            ?: easToken.stuId?.ifBlank { easToken.username }
            ?: easToken.username
            ?: stringResource(R.string.eas_account_not_logged_in_title)
        subtitle = buildString {
            val primary = easToken.stuId?.trim().orEmpty()
            val secondary = listOf(easToken.school, easToken.major, easToken.grade, easToken.className)
                .mapNotNull { info -> info?.trim()?.takeIf(String::isNotBlank) }
                .joinToString(" · ")
            append(primary)
            if (secondary.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(secondary)
            }
        }.ifBlank { easToken.username.orEmpty() }
        avatarSource = localAvatar
    } else {
        title = stringResource(R.string.eas_account_not_logged_in_title)
        subtitle = stringResource(R.string.eas_account_not_logged_in_subtitle)
        avatarSource = localAvatar
    }
    val cardShape = hitaStyleCardShape(tokens.radius.lg, 14.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm)
            .hitaGlassCardModifier(cardShape)
            .clickable(onClick = onClick),
        shape = cardShape,
        colors = hitaGlassCardColors(glassAlpha = 0.48f),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = hitaGlassCardBorder(alpha = 0.30f)
    ) {
        Row(
            modifier = Modifier.padding(tokens.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AndroidView(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAvatarClick),
                factory = {
                    ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                },
                update = { imageView ->
                    if (avatarSource.isNullOrBlank()) {
                        imageView.setImageResource(R.drawable.place_holder_avatar)
                    } else {
                        ImageUtils.loadAvatarInto(context, avatarSource, imageView)
                    }
                }
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = tokens.spacing.md)
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = tokens.spacing.xs)
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ShortcutCard(
    title: String,
    subtitle: String,
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HitaTheme.tokens
    val cardShape = hitaStyleCardShape(tokens.radius.lg, 14.dp)
    Card(
        modifier = modifier
            .hitaGlassCardModifier(cardShape, elevation = 10.dp)
            .clickable(onClick = onClick),
        shape = cardShape,
        colors = hitaGlassCardColors(glassAlpha = 0.44f),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = hitaGlassCardBorder(alpha = 0.28f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = tokens.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleIcon(icon = icon)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = tokens.spacing.md)
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = tokens.spacing.xs)
                )
            }
        }
    }
}

@Composable
private fun NavigationGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    val tokens = HitaTheme.tokens
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(
                start = tokens.spacing.lg,
                top = tokens.spacing.lg,
                end = tokens.spacing.lg,
                bottom = tokens.spacing.xs
            )
            .hitaSumiBrushUnderline()
    )
    val cardShape = hitaStyleCardShape(tokens.radius.lg, 14.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg)
            .hitaGlassCardModifier(cardShape, elevation = 12.dp),
        shape = cardShape,
        colors = hitaGlassCardColors(glassAlpha = 0.46f),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = hitaGlassCardBorder(alpha = 0.28f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = tokens.spacing.xs)
        ) {
            content()
        }
    }
}

@Composable
private fun NavigationRow(
    icon: Int,
    title: String,
    subtitle: String = "",
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    val tokens = HitaTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = tokens.spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleIcon(icon = icon)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = tokens.spacing.md)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LiveCourseControl(
    enabled: Boolean,
    strongReminderEnabled: Boolean,
    onToggle: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onToggleStrongReminder: () -> Unit,
) {
    NavigationRow(
        icon = R.drawable.ic_baseline_access_time_24,
        title = stringResource(R.string.live_course_title),
        subtitle = stringResource(
            if (enabled) R.string.live_course_active_badge else R.string.live_course_subtitle,
        ),
        onClick = onToggle,
        trailing = {
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() },
            )
        },
    )
    if (enabled) {
        NavigationRow(
            icon = R.drawable.ic_baseline_access_alarm_24,
            title = stringResource(R.string.live_course_strong_reminder),
            subtitle = stringResource(
                if (strongReminderEnabled) R.string.live_course_strong_reminder_active
                else R.string.live_course_strong_reminder_subtitle,
            ),
            onClick = onToggleStrongReminder,
            trailing = {
                Switch(
                    checked = strongReminderEnabled,
                    onCheckedChange = { onToggleStrongReminder() },
                )
            },
        )
        NavigationRow(
            icon = R.drawable.ic_menu_settings,
            title = stringResource(R.string.live_course_system_permission),
            subtitle = stringResource(R.string.live_course_system_permission_subtitle),
            onClick = onOpenSystemSettings,
        )
    }
}

@Composable
private fun CircleIcon(icon: Int) {
    val iconTint = if (HitaTheme.style == HitaThemeStyle.Persona5) {
        Color(0xFFFF6675)
    } else {
        MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
    }
}
