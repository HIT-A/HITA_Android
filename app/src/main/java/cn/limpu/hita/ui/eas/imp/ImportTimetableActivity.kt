package cn.limpu.hita.ui.eas.imp

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.appcompat.app.AlertDialog
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsDimensions
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.repository.TimetableSnapshotKind
import cn.limpu.hita.data.repository.TimetableVersionSnapshot
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.eas.EASActivity
import cn.limpu.hita.ui.eas.login.PopUpLoginEAS
import cn.limpu.hita.ui.widgets.PopUpCalendarPicker
import cn.limpu.hita.ui.widgets.PopUpTimePeriodPicker
import cn.limpu.hita.ui.widgets.WidgetUtils
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.TermNameFormatter
import cn.limpu.hita.utils.TextTools
import com.limpu.style.widgets.PopUpCheckableList
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import java.util.Date

@AndroidEntryPoint
class ImportTimetableActivity :
    EASActivity<ImportTimetableViewModel, ComposeViewBinding>() {

    override val viewModel: ImportTimetableViewModel by viewModels()

    override fun shouldRefreshOnStart(): Boolean = false
    // 进页即校验教务会话：过期立即弹重登窗，而不是等导入失败才发现。
    override fun shouldCheckLoginOnStart(): Boolean = true

    private var autoImportPending: Boolean = false
    private var autoImportTriggered: Boolean = false
    private var importActionInFlight: Boolean = false
    private var termQueryInFlight: Boolean = false
    private var calibrationPromptTermCode: String? = null
    private var openTermPickerWhenLoaded: Boolean = false
    private var isRefreshing by mutableStateOf(false)
    private var importEnabled by mutableStateOf(false)
    private var importing by mutableStateOf(false)
    private var importSuccess: Boolean? by mutableStateOf(null)
    private var snapshots: List<TimetableVersionSnapshot> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun initViewBinding(): ComposeViewBinding {
        return ComposeViewBinding(ComposeView(this))
    }

    override fun initViews() {
        super.initViews()
        bindLiveData()
        autoImportPending = intent.getBooleanExtra("autoImport", false)
        refreshLocalUiOnly()
        (binding.root as ComposeView).setContent {
            HitaComposeTheme() {
                ImportTimetableScreen(
                    viewModel = viewModel,
                    isRefreshing = isRefreshing,
                    importEnabled = importEnabled,
                    importing = importing,
                    importSuccess = importSuccess,
                    getDisplayTermName = ::getDisplayTermName,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onRefresh = {
                        ensureLoggedInForImport {
                            refresh()
                        }
                    },
                    onShowSnapshots = { showSnapshotHistory() },
                    onPickTerm = { pickTerm() },
                    onImport = {
                        ensureLoggedInForImport {
                            if (
                                viewModel.isBenbuTerm() &&
                                viewModel.benbuCalibrationConfirmedLiveData.value != true
                            ) {
                                showBenbuCalibrationPrompt(force = true)
                                return@ensureLoggedInForImport
                            }
                            confirmForceReplaceImport()
                        }
                    },
                    onPickDate = { pickStartDate() },
                    onShiftWeek = { viewModel.shiftStartDateByWeek(it) },
                    onEditPeriod = { period, position -> editTimePeriod(period, position) }
                )
            }
        }
        if (easRepository.getEasToken().isLogin()) {
            refresh()
        }
        if (autoImportPending) {
            ensureLoggedInForImport {
                refresh()
            }
        }
    }

    override fun refresh() {
        importEnabled = false
        importing = false
        importSuccess = null
        isRefreshing = true
        termQueryInFlight = true
        viewModel.startRefreshTerms()
    }

    override fun onLoginCheckSuccess(retry: Boolean) {
        refresh()
    }

    private fun refreshLocalUiOnly() {
        importEnabled = false
        importing = false
        importSuccess = null
        isRefreshing = false
        updateBenbuCalibrationVisibility()
    }

    private fun ensureLoggedInForImport(onSuccess: () -> Unit) {
        if (easRepository.getEasToken().isLogin()) {
            onSuccess()
            return
        }
        ActivityUtils.showEasVerifyWindow<android.app.Activity>(
            from = this,
            easRepository = easRepository,
            directTo = null,
            onResponseListener = object : PopUpLoginEAS.OnResponseListener {
                override fun onSuccess(window: PopUpLoginEAS) {
                    window.dismiss()
                    onSuccess()
                }

                override fun onFailed(window: PopUpLoginEAS) = Unit
            }
        )
    }

    private fun bindLiveData() {
        viewModel.selectedTermLiveData.observe(this) {
            it?.let {
                updateBenbuCalibrationVisibility()
                maybeAutoImport()
            }
        }
        viewModel.termsLiveData.observe(this) { data ->
            isRefreshing = false
            when (data.state) {
                DataState.STATE.SUCCESS -> {
                    termQueryInFlight = false
                    resetSessionRetryState()
                    val terms = data.data.orEmpty()
                    if (terms.isNotEmpty()) {
                        if (openTermPickerWhenLoaded) {
                            openTermPickerWhenLoaded = false
                            showTermPicker(terms)
                        }
                    }
                }

                DataState.STATE.NOT_LOGGED_IN -> {
                    if (termQueryInFlight) {
                        if (!handleSessionExpired {
                                termQueryInFlight = true
                                isRefreshing = true
                                viewModel.startRefreshTerms()
                                true
                            }) {
                            termQueryInFlight = false
                        }
                    }
                }

                DataState.STATE.NOTHING -> Unit

                else -> {
                    termQueryInFlight = false
                    resetSessionRetryState()
                    openTermPickerWhenLoaded = false
                }
            }
            maybeAutoImport()
        }
        viewModel.startDateLiveData.observe(this) {
            updateBenbuCalibrationVisibility()
            maybeShowBenbuCalibrationPrompt()
            maybeAutoImport()
            recoverImportSessionIfNeeded(it)
        }
        viewModel.benbuCalibrationConfirmedLiveData.observe(this) {
            updateBenbuCalibrationVisibility()
            maybeShowBenbuCalibrationPrompt()
            maybeAutoImport()
        }
        viewModel.scheduleStructureLiveData.observe(this) {
            importEnabled = !it.data.isNullOrEmpty()
            maybeAutoImport()
            recoverImportSessionIfNeeded(it)
        }
        viewModel.importTimetableResultLiveData.observe(this) {
            if (it.state == DataState.STATE.LOADING) {
                importing = true
                importSuccess = false
                return@observe
            }
            if (it.state == DataState.STATE.NOTHING) {
                importing = false
                importActionInFlight = false
                return@observe
            }
            importing = false
            importSuccess = it.state == DataState.STATE.SUCCESS
            when {
                it.state == DataState.STATE.SUCCESS -> {
                    importActionInFlight = false
                    resetSessionRetryState()
                    Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show()
                }

                it.state == DataState.STATE.NOT_LOGGED_IN -> {
                    // 不再受 importActionInFlight 门控：会话失效时始终提供重登入口。
                    if (!handleSessionExpired { retryImportFlow() }) {
                        importActionInFlight = false
                        showImportFailureWithRelogin(it.message)
                    }
                }

                else -> {
                    // 失效页特征漏匹配时归类为普通失败：toast 升级为带重登入口的对话框。
                    importActionInFlight = false
                    resetSessionRetryState()
                    showImportFailureWithRelogin(it.message)
                }
            }
            WidgetUtils.sendRefreshToAll(this)
        }
        viewModel.snapshotsLiveData.observe(this) { state ->
            if (state.state == DataState.STATE.SUCCESS) {
                snapshots = state.data.orEmpty()
            }
        }
        viewModel.restoreSnapshotLiveData.observe(this) { state ->
            when (state.state) {
                DataState.STATE.SUCCESS -> {
                    Toast.makeText(this, "课表版本已恢复", Toast.LENGTH_SHORT).show()
                    WidgetUtils.sendRefreshToAll(this)
                }
                DataState.STATE.FETCH_FAILED -> Toast.makeText(
                    this,
                    state.message ?: "课表版本恢复失败",
                    Toast.LENGTH_LONG
                ).show()
                else -> Unit
            }
        }
    }

    /**
     * 导入失败兜底对话框：会话失效页特征漏匹配（归类为普通失败）时
     * 仍保留一键重登入口，不再只有 toast 后无路可走。
     */
    private fun showImportFailureWithRelogin(message: String?) {
        val msg = message?.trim().orEmpty().ifEmpty { getString(R.string.import_failed) }
        AlertDialog.Builder(this)
            .setTitle(R.string.import_failed)
            .setMessage(msg)
            .setPositiveButton(R.string.eas_relogin_now) { _, _ ->
                // 用户显式点重登是新一轮尝试：重置计数，否则 3 次上限后按钮失效。
                resetSessionRetryState()
                handleSessionExpired { retryImportFlow() }
            }
            .setNegativeButton(R.string.eas_relogin_later, null)
            .show()
    }

    private fun recoverImportSessionIfNeeded(state: DataState<*>) {
        if (state.state != DataState.STATE.NOT_LOGGED_IN &&
            state.state != DataState.STATE.TOKEN_INVALID
        ) {
            return
        }
        handleSessionExpired {
            refresh()
            true
        }
    }

    private fun pickTerm() {
        val terms = viewModel.startGetAllTerms()
        if (terms.isEmpty()) {
            openTermPickerWhenLoaded = true
            ensureLoggedInForImport {
                refresh()
            }
            return
        }
        showTermPicker(terms)
    }

    private fun pickStartDate() {
        viewModel.startDateLiveData.value?.data?.let {
            PopUpCalendarPicker().setInitValue(it.timeInMillis)
                .setOnConfirmListener(object : PopUpCalendarPicker.OnConfirmListener {
                    override fun onConfirm(c: java.util.Calendar) {
                        viewModel.changeStartDate(c)
                    }
                }).show(supportFragmentManager, "pick")
        }
    }

    private fun editTimePeriod(period: TimePeriodInDay, position: Int) {
        PopUpTimePeriodPicker().setInitialValue(period.from, period.to)
            .setDialogTitle(R.string.pick_time_period)
            .setOnDialogConformListener(object : PopUpTimePeriodPicker.OnDialogConformListener {
                override fun onClick(timePeriodInDay: TimePeriodInDay) {
                    viewModel.setStructureData(timePeriodInDay, position)
                }
            }).show(supportFragmentManager, "pick")
    }

    private fun maybeAutoImport() {
        if (!autoImportPending || autoImportTriggered) return
        if (viewModel.isBenbuTerm() && viewModel.benbuCalibrationConfirmedLiveData.value != true) return
        if (startImportFlow()) {
            autoImportTriggered = true
        }
    }

    private fun startImportFlow(): Boolean {
        if (viewModel.startImportTimetable()) {
            importActionInFlight = true
            importing = true
            importSuccess = null
            if (viewModel.isBenbuTerm()) {
                viewModel.saveBenbuCalibration()
            }
            return true
        }
        return false
    }

    private fun retryImportFlow(): Boolean {
        if (viewModel.retryImportTimetable()) {
            importActionInFlight = true
            importing = true
            importSuccess = null
            return true
        }
        return false
    }

    private fun updateBenbuCalibrationVisibility() = Unit

    private fun maybeShowBenbuCalibrationPrompt() {
        showBenbuCalibrationPrompt(force = false)
    }

    private fun showBenbuCalibrationPrompt(force: Boolean) {
        val term = viewModel.selectedTermLiveData.value ?: return
        val startDate = viewModel.startDateLiveData.value?.data ?: return
        if (!viewModel.isBenbuTerm(term)) return
        if (!force && viewModel.benbuCalibrationConfirmedLiveData.value == true) return
        if (!force && calibrationPromptTermCode == term.getCode()) return
        calibrationPromptTermCode = term.getCode()
        AlertDialog.Builder(this)
            .setTitle(R.string.benbu_start_date_confirm_title)
            .setMessage(
                getString(R.string.benbu_start_date_confirm_message) + "\n\n" +
                    TextTools.getNormalDateText(this, startDate)
            )
            .setPositiveButton(R.string.benbu_start_date_confirm_positive) { _, _ ->
                viewModel.saveBenbuCalibration()
                maybeAutoImport()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTermPicker(terms: List<TermItem>) {
        val filteredTerms = viewModel.filterTermOptions(terms)
        val names = filteredTerms.map { getDisplayTermName(it) }
        PopUpCheckableList<TermItem>()
            .setListData(names, filteredTerms)
            .setTitle(getString(R.string.pick_import_term))
            .setOnConfirmListener(object : PopUpCheckableList.OnConfirmListener<TermItem> {
                override fun OnConfirm(title: String?, key: TermItem) {
                    viewModel.changeSelectedTerm(key)
                }
            }).show(supportFragmentManager, "terms")
    }

    private fun getDisplayTermName(term: TermItem): String {
        return TermNameFormatter.fullTermName(term)
    }

    /**
     * 手动导入 = 课表源全量替换（[cn.limpu.hita.data.repository.TimetableImportMode.REPLACE]）。
     * 打开应用时自动刷新保留的课程也会被覆盖，替换前必须让用户确认；
     * 刷新前快照仍可在“课表版本记录”中恢复。
     */
    private fun confirmForceReplaceImport() {
        AlertDialog.Builder(this)
            .setTitle(R.string.timetable_import_force_replace_title)
            .setMessage(R.string.timetable_import_force_replace_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.button_confirm) { _, _ ->
                if (startImportFlow()) {
                    (binding.root as ComposeView).performHapticFeedback(
                        HapticFeedbackConstants.CONTEXT_CLICK
                    )
                }
            }
            .show()
    }

    private fun showSnapshotHistory() {
        if (snapshots.isEmpty()) {
            Toast.makeText(this, "当前学期还没有可恢复的课表版本", Toast.LENGTH_SHORT).show()
            return
        }
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = snapshots.map { snapshot ->
            val kind = when (snapshot.kind) {
                TimetableSnapshotKind.BEFORE_REFRESH -> "刷新前"
                TimetableSnapshotKind.IMPORTED -> "教务导入"
                TimetableSnapshotKind.BEFORE_RESTORE -> "恢复前"
                TimetableSnapshotKind.BEFORE_USER_ADOPT -> "用户采纳前"
            }
            "$kind · ${dateFormat.format(Date(snapshot.createdAtMillis))}\n" +
                "${snapshot.courseCount} 门课程 · ${snapshot.lessonCount} 个课次"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("课表版本记录")
            .setItems(labels) { _, index -> confirmRestoreSnapshot(snapshots[index]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRestoreSnapshot(snapshot: TimetableVersionSnapshot) {
        AlertDialog.Builder(this)
            .setTitle("恢复这个课表版本？")
            .setMessage("当前教务课程会先自动保存为“恢复前”版本，然后替换为所选版本；手动活动和考试不受影响。")
            .setPositiveButton("恢复") { _, _ -> viewModel.restoreSnapshot(snapshot.id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportTimetableScreen(
    viewModel: ImportTimetableViewModel,
    isRefreshing: Boolean,
    importEnabled: Boolean,
    importing: Boolean,
    importSuccess: Boolean?,
    getDisplayTermName: (TermItem) -> String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onShowSnapshots: () -> Unit,
    onPickTerm: () -> Unit,
    onImport: () -> Unit,
    onPickDate: () -> Unit,
    onShiftWeek: (Int) -> Unit,
    onEditPeriod: (TimePeriodInDay, Int) -> Unit
) {
    val tokens = HitaTheme.tokens
    val selectedTermDisplay by viewModel.selectedTermDisplay.observeAsState()
    val selectedTerm = selectedTermDisplay?.toTermItem()
    val startDateState by viewModel.startDateLiveData.observeAsState()
    val scheduleState by viewModel.scheduleStructureLiveData.observeAsState()
    val isBenbu = viewModel.isBenbuTerm(selectedTerm)
    val context = androidx.compose.ui.platform.LocalContext.current
    val dateText = startDateState?.data?.let { TextTools.getNormalDateText(context, it) }
        ?: stringResource(R.string.no_valid_date)
    val periods = scheduleState?.data.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.title_import_timetable),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.rotate(180f)
                    )
                }
            },
            actions = {
                FilledTonalButton(
                    onClick = onShowSnapshots,
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_settings_backup_restore_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "版本记录",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                IconButton(onClick = onRefresh) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_settings_backup_restore_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = tokens.spacing.xl)
        ) {
            item {
                ImportHeader(
                    termText = selectedTerm?.let(getDisplayTermName)
                        ?: stringResource(R.string.pick_import_term),
                    importEnabled = importEnabled,
                    importing = importing,
                    importSuccess = importSuccess,
                    onPickTerm = onPickTerm,
                    onImport = onImport
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
                ) {
                    InfoCard(
                        title = selectedTerm?.let(getDisplayTermName)
                            ?: stringResource(R.string.timetable_name),
                        subtitle = stringResource(R.string.timetable_name),
                        icon = R.drawable.ic_timetable,
                        modifier = Modifier
                            .weight(1f)
                            .height(144.dp)
                    )
                    InfoCard(
                        title = dateText,
                        subtitle = stringResource(R.string.start_date_of_curriculum),
                        icon = R.drawable.ic_baseline_timetable_24,
                        modifier = Modifier
                            .weight(1f)
                            .height(144.dp)
                            .clickable(onClick = onPickDate)
                    )
                }
            }
            if (isBenbu) {
                item {
                    BenbuCalibrationCard(
                        onPrev = { onShiftWeek(-1) },
                        onNext = { onShiftWeek(1) }
                    )
                }
            }
            item {
                ScheduleStructureCard(
                    periods = periods,
                    onEditPeriod = onEditPeriod
                )
            }
        }
    }
}

@Composable
private fun ImportHeader(
    termText: String,
    importEnabled: Boolean,
    importing: Boolean,
    importSuccess: Boolean?,
    onPickTerm: () -> Unit,
    onImport: () -> Unit
) {
    val tokens = HitaTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.xl, vertical = tokens.spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onPickTerm)
                .padding(vertical = tokens.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = termText,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 32.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                painter = painterResource(R.drawable.ic_expand),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(20.dp)
            )
        }
        Button(
            onClick = onImport,
            enabled = importEnabled && !importing,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (importEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
            ),
            contentPadding = PaddingValues(horizontal = tokens.spacing.lg)
        ) {
            if (importing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.size(tokens.spacing.sm))
            }
            Text(
                text = when (importSuccess) {
                    true -> stringResource(R.string.import_success)
                    false -> stringResource(R.string.import_failed)
                    null -> stringResource(R.string.button_import)
                },
                fontSize = 16.sp
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    subtitle: String,
    icon: Int,
    modifier: Modifier
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = modifier.padding(vertical = tokens.spacing.sm),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun BenbuCalibrationCard(
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.xs),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Text(
                text = stringResource(R.string.benbu_start_date_calibration_hint),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.md)
            ) {
                TextButton(onClick = onPrev, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.benbu_start_date_prev_week))
                }
                TextButton(onClick = onNext, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.benbu_start_date_next_week))
                }
            }
        }
    }
}

@Composable
private fun ScheduleStructureCard(
    periods: List<TimePeriodInDay>,
    onEditPeriod: (TimePeriodInDay, Int) -> Unit
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = tokens.spacing.lg, top = tokens.spacing.lg, end = tokens.spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.timetable_structure_label),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(tokens.spacing.sm))
            periods.forEachIndexed { index, period ->
                StructureRow(
                    index = index,
                    period = period,
                    showDivider = index != periods.lastIndex,
                    onClick = { onEditPeriod(period, index) }
                )
            }
            Spacer(modifier = Modifier.height(tokens.spacing.sm))
        }
    }
}

@Composable
private fun StructureRow(
    index: Int,
    period: TimePeriodInDay,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val tokens = HitaTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(
                start = tokens.spacing.lg,
                top = 10.dp,
                end = tokens.spacing.lg,
                bottom = 10.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.schedule_list_item_pattern, index + 1),
                modifier = Modifier
                    .weight(1f)
                    .alpha(0.3f),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = period.toString(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = tokens.spacing.lg),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}
