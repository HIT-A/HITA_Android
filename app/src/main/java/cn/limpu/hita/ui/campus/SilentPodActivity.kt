package cn.limpu.hita.ui.campus

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.campus.SilentPodCategory
import cn.limpu.hita.data.model.campus.SilentPodContact
import cn.limpu.hita.data.model.campus.SilentPodContent
import cn.limpu.hita.data.model.campus.SilentPodReservation
import cn.limpu.hita.data.model.campus.SilentPodSlotPlanner
import cn.limpu.hita.data.model.campus.SilentPodSpace
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme

/**
 * 静音仓预约。
 *
 * 数据来自大学城图书馆空间预约接口（`space-reservation-fg/x-space-reservation-api`），
 * 会话是 `pima.space-reservation-fg.sid`。页面支持查看空间与我的预约，
 * 并可对空间发起预约、对已有预约发起取消。
 */
class SilentPodActivity : AppCompatActivity() {

    private val viewModel: SilentPodViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HitaComposeTheme {
                val state by viewModel.state.collectAsState()
                // 登录页是整页覆盖：页面内的 WebView 需要完整高度，塞进卡片里无法输入。
                var showLogin by remember { mutableStateOf(false) }
                var resetting by remember { mutableStateOf(false) }
                // 未登录时直接展示图书馆登录页：由站点把用户带到认证中心，
                // 应用不再自主发起统一身份认证深链（CAS 回调 state 校验会失败）。
                androidx.compose.runtime.LaunchedEffect(state) {
                    if (state is CampusServiceUiState.RequiresSignIn) showLogin = true
                }
                val scope = rememberCoroutineScope()
                val signOut: () -> Unit = {
                    if (!resetting) {
                        resetting = true
                        showLogin = false
                        viewModel.load(null)
                        scope.launch {
                            try { CampusServiceAccount.POD.signOut(this@SilentPodActivity) }
                            finally { resetting = false }
                        }
                    }
                }
                if (showLogin) {
                    SilentPodLoginHost(
                        onSignOut = signOut,
                        onCancel = { showLogin = false },
                        onLoggedIn = {
                            if (!resetting && !CampusServiceAccount.POD.isSignedOut(this@SilentPodActivity)) {
                                showLogin = false
                                viewModel.load(readSilentPodSession())
                            }
                        },
                    )
                } else {
                    val actionMessage by viewModel.actionMessage.collectAsState()
                    val contacts by viewModel.contacts.collectAsState()
                    val defaultMobile by viewModel.defaultMobile.collectAsState()
                    androidx.compose.runtime.LaunchedEffect(actionMessage) {
                        actionMessage?.let {
                            android.widget.Toast.makeText(
                                this@SilentPodActivity, it, android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            viewModel.consumeActionMessage()
                        }
                    }
                    SilentPodScreen(
                        state = state,
                        onSignIn = {
                            if (!resetting) {
                                resetting = true
                                scope.launch {
                                    try {
                                        // An expired/anonymous business cookie must not immediately close the login page.
                                        CampusServiceAccount.POD.signOut(this@SilentPodActivity)
                                        CampusServiceAccount.POD.beginSignIn(this@SilentPodActivity)
                                        showLogin = true
                                    } finally { resetting = false }
                                }
                            }
                        },
                        onSignOut = signOut,
                        resetting = resetting,
                        onRefresh = {
                            if (!resetting) viewModel.load(
                                if (CampusServiceAccount.POD.isSignedOut(this@SilentPodActivity)) null
                                else readSilentPodSession(),
                            )
                        },
                        onCreateReservation = { space, start, end, phone, contactIds ->
                            viewModel.createReservation(
                                spaceId = space.id,
                                date = (state as? CampusServiceUiState.Ready)?.value?.date.orEmpty(),
                                startTime = start,
                                endTime = end,
                                notes = phone,
                                contactIds = contactIds,
                            )
                        },
                        onCancelReservation = { reservation, reason ->
                            viewModel.cancelReservation(reservation.id, reason)
                        },
                        contacts = contacts,
                        defaultMobile = defaultMobile,
                        onAddContact = { name, cardNo, onAdded ->
                            viewModel.addContact(name, cardNo, onAdded)
                        },
                        onRemoveContact = { contact -> viewModel.removeContact(contact) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从别处登录回来（或首次进入）时按当前 Cookie 决定状态，不保留上次的假数据。
        if (viewModel.state.value == CampusServiceUiState.Loading) {
            viewModel.load(if (CampusServiceAccount.POD.isSignedOut(this)) null else readSilentPodSession())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SilentPodLoginHost(
    onSignOut: () -> Unit,
    onCancel: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    // 登录页是整页覆盖：页面内的 WebView 需要完整高度，塞进卡片里无法输入。
    Scaffold { padding ->
        Column(modifier = Modifier.padding(padding)) {
            SilentPodLoginScreen(onCancel = onCancel, onLoggedIn = onLoggedIn, onSignOut = onSignOut)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SilentPodScreen(
    state: CampusServiceUiState<SilentPodContent>,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    resetting: Boolean,
    onRefresh: () -> Unit,
    onCreateReservation: (SilentPodSpace, String, String, String, List<Int>) -> Unit,
    onCancelReservation: (SilentPodReservation, String) -> Unit,
    contacts: List<SilentPodContact>,
    defaultMobile: String,
    onAddContact: (name: String, cardNo: String, onAdded: (SilentPodContact) -> Unit) -> Unit,
    onRemoveContact: (SilentPodContact) -> Unit,
) {
    Scaffold { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SilentPodHeader(onRefresh = onRefresh, refreshEnabled = !resetting && state !is CampusServiceUiState.Loading, onSignOut = onSignOut, resetting = resetting)
            when (state) {
                is CampusServiceUiState.Loading -> SilentPodLoading()
                is CampusServiceUiState.RequiresSignIn -> SilentPodSignInRequired(onSignIn = onSignIn)
                is CampusServiceUiState.Failed -> SilentPodFailed(reason = state.reason, onRetry = onRefresh)
                is CampusServiceUiState.Ready -> SilentPodContentList(
                    content = state.value,
                    onCreateReservation = onCreateReservation,
                    onCancelReservation = onCancelReservation,
                    contacts = contacts,
                    defaultMobile = defaultMobile,
                    onAddContact = onAddContact,
                    onRemoveContact = onRemoveContact,
                )
            }
        }
    }
}

@Composable
private fun SilentPodHeader(onRefresh: () -> Unit, refreshEnabled: Boolean, onSignOut: () -> Unit, resetting: Boolean) {
    val tokens = HitaTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = tokens.spacing.lg, end = tokens.spacing.sm, top = tokens.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.extension_silent_pod_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        CampusSignOutButton(enabled = !resetting, onSignOut = onSignOut)
        TextButton(onClick = onRefresh, enabled = refreshEnabled) {
            Text(stringResource(R.string.silent_pod_refresh))
        }
    }
}

@Composable
private fun SilentPodLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.silent_pod_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = HitaTheme.tokens.spacing.md),
        )
    }
}

@Composable
private fun SilentPodSignInRequired(onSignIn: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(HitaTheme.tokens.spacing.lg),
        verticalArrangement = Arrangement.Center,
    ) {
        CampusServiceStateCard(
            title = stringResource(R.string.campus_service_session_title),
            message = stringResource(R.string.silent_pod_session_required),
        )
        Button(
            onClick = onSignIn,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HitaTheme.tokens.spacing.lg, vertical = HitaTheme.tokens.spacing.sm),
        ) {
            Text(stringResource(R.string.silent_pod_go_login))
        }
    }
}

@Composable
private fun SilentPodFailed(reason: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(HitaTheme.tokens.spacing.lg),
        verticalArrangement = Arrangement.Center,
    ) {
        CampusServiceStateCard(
            title = stringResource(R.string.silent_pod_error_title),
            message = reason,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HitaTheme.tokens.spacing.lg, vertical = HitaTheme.tokens.spacing.sm),
        ) {
            Text(stringResource(R.string.silent_pod_retry))
        }
    }
}

@Composable
private fun SilentPodContentList(
    content: SilentPodContent,
    onCreateReservation: (SilentPodSpace, String, String, String, List<Int>) -> Unit,
    onCancelReservation: (SilentPodReservation, String) -> Unit,
    contacts: List<SilentPodContact>,
    defaultMobile: String,
    onAddContact: (name: String, cardNo: String, onAdded: (SilentPodContact) -> Unit) -> Unit,
    onRemoveContact: (SilentPodContact) -> Unit,
) {
    val tokens = HitaTheme.tokens
    // 四组默认全部折叠：进页面先看到四个栏目，点哪个展开哪个。
    var expandedGroups by remember { mutableStateOf(emptySet<SilentPodCategory>()) }
    var reservationsExpanded by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = tokens.spacing.lg,
            end = tokens.spacing.lg,
            top = tokens.spacing.sm,
            bottom = tokens.spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
    ) {
        item {
            Text(
                text = stringResource(R.string.silent_pod_spaces_of_date, content.date),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (content.groups.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.silent_pod_empty_spaces),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        content.groups.forEach { group ->
            val expanded = group.category in expandedGroups
            item(key = "group-${group.category.name}") {
                SilentPodSectionHeader(
                    title = stringResource(categoryTitleRes(group.category)),
                    countText = stringResource(R.string.silent_pod_group_count, group.spaces.size),
                    expanded = expanded,
                    onToggle = {
                        expandedGroups = if (expanded) {
                            expandedGroups - group.category
                        } else {
                            expandedGroups + group.category
                        }
                    },
                )
            }
            if (expanded) {
                items(group.spaces, key = { "space-${it.id}" }) { space ->
                    SilentPodSpaceCard(
                        space = space,
                        contacts = contacts,
                        defaultMobile = defaultMobile,
                        onReserve = onCreateReservation,
                        onAddContact = onAddContact,
                        onRemoveContact = onRemoveContact,
                    )
                }
            }
        }
        item(key = "reservations-header") {
            SilentPodSectionHeader(
                title = stringResource(R.string.silent_pod_my_reservations),
                countText = stringResource(
                    R.string.silent_pod_reservation_count,
                    content.reservations.size,
                ),
                expanded = reservationsExpanded,
                onToggle = { reservationsExpanded = !reservationsExpanded },
            )
        }
        if (reservationsExpanded) {
            if (content.reservations.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.silent_pod_empty_reservations),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(content.reservations, key = { "reservation-${it.id}" }) { reservation ->
                    SilentPodReservationCard(
                        reservation = reservation,
                        onCancel = onCancelReservation,
                    )
                }
            }
        }
    }
}

/**
 * 可折叠的分组标题（4 人仓 / 单人仓 / 我的预约共用）。
 *
 * 箭头方向表示当前状态：`▸` 折叠、`▾` 展开；整行可点。
 */
@Composable
private fun SilentPodSectionHeader(
    title: String,
    countText: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = HitaTheme.tokens.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) "▾" else "▸",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = HitaTheme.tokens.spacing.xs),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = countText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SilentPodSpaceCard(
    space: SilentPodSpace,
    contacts: List<SilentPodContact>,
    defaultMobile: String,
    onReserve: (SilentPodSpace, String, String, String, List<Int>) -> Unit,
    onAddContact: (name: String, cardNo: String, onAdded: (SilentPodContact) -> Unit) -> Unit,
    onRemoveContact: (SilentPodContact) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    if (showDialog) {
        val context = androidx.compose.ui.platform.LocalContext.current
        SilentPodReservationDialog(
            space = space,
            contacts = contacts,
            defaultMobile = defaultMobile,
            onDismiss = { showDialog = false },
            onAddContact = onAddContact,
            onRemoveContact = onRemoveContact,
            onExpired = {
                showDialog = false
                android.widget.Toast.makeText(
                    context,
                    R.string.silent_pod_slot_expired,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
            onConfirm = { start, end, phone, contactIds ->
                showDialog = false
                onReserve(space, start, end, phone, contactIds)
            },
        )
    }
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.md),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = space.codeName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (space.areaFullName.isNotEmpty()) {
                    Text(
                        text = space.areaFullName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = tokens.spacing.sm).weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (space.isAccessControl) {
                    Text(
                        text = stringResource(R.string.silent_pod_access_control),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = durationSummary(space),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 服务端给的是「可约窗口」，窗口起点常常就是当前分钟；这里只显示
            // 与「现在」取交集后仍然可约的部分，避免点进去才发现已经约不了。
            val windows = remember(space) { SilentPodSlotPlanner.effectiveWindows(space, nowMinutesOfDay()) }
            Text(
                text = if (windows.isEmpty()) {
                    stringResource(R.string.silent_pod_no_slot)
                } else {
                    stringResource(R.string.silent_pod_slots, windows.joinToString("  ") { it.label })
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (windows.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (windows.isNotEmpty()) {
                TextButton(
                    onClick = { showDialog = true },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.silent_pod_reserve_action)) }
            }
        }
    }
}

/**
 * 预约对话框：选开始时间、结束时间（多人仓还要选同行成员），最后填手机号。
 *
 * 与 iOS 一致：服务端的「可约窗口」只用来限定范围，真正的预约时段由用户按
 * 10 分钟步长挑，且时长必须落在空间的最小 / 最大时长之间。
 * 多人仓（`reservationType=TEAM`）必须带同行成员 id，否则服务端返回
 * `TEAM_PEOPLE_NUM_NO_EQUAL`。
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
private fun SilentPodReservationDialog(
    space: SilentPodSpace,
    contacts: List<SilentPodContact>,
    defaultMobile: String,
    onDismiss: () -> Unit,
    onAddContact: (name: String, cardNo: String, onAdded: (SilentPodContact) -> Unit) -> Unit,
    onRemoveContact: (SilentPodContact) -> Unit,
    onExpired: () -> Unit,
    onConfirm: (start: String, end: String, phone: String, contactIds: List<Int>) -> Unit,
) {
    val tokens = HitaTheme.tokens
    val nowMinutes = remember(space) { nowMinutesOfDay() }
    val startOptions = remember(space) { SilentPodSlotPlanner.startOptions(space, nowMinutes) }
    var selectedStart by remember(space) { mutableStateOf(startOptions.firstOrNull()) }
    var selectedEnd by remember(space) { mutableStateOf<Int?>(null) }
    val endOptions = remember(space, selectedStart) {
        selectedStart?.let { SilentPodSlotPlanner.endOptions(space, it) } ?: emptyList()
    }
    // 用户没手动选结束时间（或换了开始时间导致旧的失效）时，用默认时长兜底。
    val end = selectedEnd?.takeIf { it in endOptions }
        ?: SilentPodSlotPlanner.defaultEnd(selectedStart, endOptions)
    val start = selectedStart
    val guestRange = space.requiredGuestRange
    var selectedContactIds by remember(space) { mutableStateOf(emptySet<Int>()) }
    var showContacts by remember { mutableStateOf(false) }
    var phone by remember(space) { mutableStateOf(defaultMobile) }
    val phoneValid = phone.trim().matches(MAINLAND_MOBILE)
    val guestsValid = guestRange?.let { selectedContactIds.size in it } ?: true

    if (showContacts) {
        SilentPodContactsDialog(
            contacts = contacts,
            onDismiss = { showContacts = false },
            onAdd = onAddContact,
            onRemove = onRemoveContact,
            onAdded = { added -> selectedContactIds = selectedContactIds + added.id },
        )
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.silent_pod_reserve_title, space.codeName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)) {
                if (startOptions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.silent_pod_no_slot),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.silent_pod_reserve_start),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(startOptions) { minute ->
                            androidx.compose.material3.FilterChip(
                                selected = minute == start,
                                onClick = { selectedStart = minute },
                                label = { Text(SilentPodSlotPlanner.formatMinutes(minute)) },
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.silent_pod_reserve_end),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(endOptions) { minute ->
                            androidx.compose.material3.FilterChip(
                                selected = minute == end,
                                onClick = { selectedEnd = minute },
                                label = { Text(SilentPodSlotPlanner.formatMinutes(minute)) },
                            )
                        }
                    }
                    if (start != null && end != null) {
                        Text(
                            text = stringResource(
                                R.string.silent_pod_reserve_summary,
                                SilentPodSlotPlanner.formatMinutes(start),
                                SilentPodSlotPlanner.formatMinutes(end),
                                end - start,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (guestRange != null) {
                    Text(
                        text = stringResource(
                            R.string.silent_pod_team_need,
                            guestRange.first,
                            guestRange.last,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (contacts.isEmpty()) {
                        Text(
                            text = stringResource(R.string.silent_pod_team_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            contacts.forEach { contact ->
                                val selected = contact.id in selectedContactIds
                                androidx.compose.material3.FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedContactIds = when {
                                            selected -> selectedContactIds - contact.id
                                            selectedContactIds.size < guestRange.last ->
                                                selectedContactIds + contact.id
                                            else -> selectedContactIds
                                        }
                                    },
                                    label = { Text(contact.name) },
                                )
                            }
                        }
                    }
                    TextButton(onClick = { showContacts = true }) {
                        Text(stringResource(R.string.silent_pod_team_manage))
                    }
                    if (!guestsValid) {
                        Text(
                            text = stringResource(
                                R.string.silent_pod_team_required,
                                guestRange.first,
                                guestRange.last,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = phone,
                    onValueChange = { input -> phone = input.filter { it.isDigit() }.take(11) },
                    label = { Text(stringResource(R.string.silent_pod_reserve_phone)) },
                    singleLine = true,
                    isError = phone.isNotEmpty() && !phoneValid,
                )
                Text(
                    text = stringResource(R.string.silent_pod_reserve_phone_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = start != null && end != null && phoneValid && guestsValid,
                onClick = {
                    // 对话框可能开着很久：以提交这一刻为准再校验一次，
                    // 已过期就不要发给服务端（只会换回「预约时间异常」）。
                    if (start != null && end != null &&
                        SilentPodSlotPlanner.isValidSelection(space, start, end, nowMinutesOfDay())
                    ) {
                        onConfirm(
                            SilentPodSlotPlanner.formatMinutes(start),
                            SilentPodSlotPlanner.formatMinutes(end),
                            phone.trim(),
                            selectedContactIds.sorted(),
                        )
                    } else {
                        onExpired()
                    }
                },
            ) { Text(stringResource(R.string.silent_pod_reserve_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * 同行成员管理：列出已保存成员，支持新增与删除。
 *
 * 新增时先把证号交给服务端核验姓名，再把「核验通过的那个名字」保存下来，
 * 避免存成「名字和证件对不上」的联系人。
 */
@Composable
private fun SilentPodContactsDialog(
    contacts: List<SilentPodContact>,
    onDismiss: () -> Unit,
    onAdd: (name: String, cardNo: String, onAdded: (SilentPodContact) -> Unit) -> Unit,
    onRemove: (SilentPodContact) -> Unit,
    onAdded: (SilentPodContact) -> Unit,
) {
    val tokens = HitaTheme.tokens
    var name by remember { mutableStateOf("") }
    var cardNo by remember { mutableStateOf("") }
    val canAdd = name.isNotBlank() && cardNo.isNotBlank()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.silent_pod_team_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)) {
                if (contacts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.silent_pod_team_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    contacts.forEach { contact ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = contact.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = contact.cardNo,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onRemove(contact) }) {
                                Text(stringResource(R.string.silent_pod_team_delete))
                            }
                        }
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.silent_pod_team_name)) },
                    singleLine = true,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = cardNo,
                    onValueChange = { input -> cardNo = input.filter { it.isLetterOrDigit() } },
                    label = { Text(stringResource(R.string.silent_pod_team_card)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canAdd,
                onClick = {
                    onAdd(name.trim(), cardNo.trim()) { added ->
                        name = ""
                        cardNo = ""
                        onAdded(added)
                    }
                },
            ) { Text(stringResource(R.string.silent_pod_team_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 11 位中国大陆手机号；与服务端 `^1[3-9][0-9]{9}$` 保持一致。 */
private val MAINLAND_MOBILE = Regex("""^1[3-9]\d{9}$""")

/** 当前北京时间已过的分钟数；与客户端 `today()` 使用同一时区。 */
private fun nowMinutesOfDay(): Int {
    val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Shanghai"))
    return now.hour * 60 + now.minute
}

@Composable
private fun durationSummary(space: SilentPodSpace): String {
    val capacity = stringResource(R.string.silent_pod_capacity, space.capacity)
    val min = space.minDurationMinutes
    val max = space.maxDurationMinutes
    return if (min != null && max != null) {
        capacity + " · " + stringResource(R.string.silent_pod_duration_range, min, max)
    } else {
        capacity
    }
}

@Composable
private fun SilentPodReservationCard(
    reservation: SilentPodReservation,
    onCancel: (SilentPodReservation, String) -> Unit,
) {
    var showCancelDialog by remember { mutableStateOf(false) }
    if (showCancelDialog) {
        SilentPodCancelDialog(
            reservation = reservation,
            onDismiss = { showCancelDialog = false },
            onConfirm = { reason ->
                showCancelDialog = false
                onCancel(reservation, reason)
            },
        )
    }
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.md),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = listOfNotNull(reservation.spaceName.takeIf { it.isNotEmpty() })
                        .joinToString(" ")
                        .ifEmpty { reservation.spaceTypeName.orEmpty() },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = reservation.statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(
                    R.string.silent_pod_reservation_time,
                    reservation.date,
                    reservation.startTime,
                    reservation.endTime,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val meta = listOfNotNull(
                reservation.areaName.takeIf { it.isNotEmpty() },
                reservation.typeText,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val notes = listOfNotNull(
                reservation.cancelReason?.takeIf { it.isNotEmpty() }
                    ?.let { stringResource(R.string.silent_pod_cancel_reason, it) },
                if (reservation.isNeedSign) stringResource(R.string.silent_pod_need_sign) else null,
            ).joinToString(" · ")
            if (notes.isNotEmpty()) {
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (reservation.canCancelNow()) {
                TextButton(
                    onClick = { showCancelDialog = true },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.silent_pod_cancel_action)) }
            }
        }
    }
}

/**
 * 取消预约：官方要求填理由（最多 200 字），所以不能只弹一个「确定吗」。
 *
 * 理由留空时按钮不可点，与服务端「reason 必填」的规则保持一致。
 */
@Composable
private fun SilentPodCancelDialog(
    reservation: SilentPodReservation,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    val trimmed = reason.trim()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.silent_pod_cancel_action)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(HitaTheme.tokens.spacing.sm)) {
                Text(stringResource(R.string.silent_pod_cancel_confirm, reservation.spaceName))
                androidx.compose.material3.OutlinedTextField(
                    value = reason,
                    onValueChange = { input -> reason = input.take(MAX_CANCEL_REASON_LENGTH) },
                    label = { Text(stringResource(R.string.silent_pod_cancel_reason_label)) },
                    placeholder = { Text(stringResource(R.string.silent_pod_cancel_reason_hint)) },
                    minLines = 2,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty(), onClick = { onConfirm(trimmed) }) {
                Text(stringResource(R.string.silent_pod_cancel_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 服务端要求取消理由必填，最多 200 字。 */
private const val MAX_CANCEL_REASON_LENGTH = 200

/** 只有本人、状态未结束且服务端允许时才能取消。 */
private fun SilentPodReservation.canCancelNow(): Boolean =
    isMain && isAllowCancel && status in setOf("PENDING", "UNDERWAY")

private fun categoryTitleRes(category: SilentPodCategory): Int = when (category) {
    SilentPodCategory.QUAD_LOCKED -> R.string.silent_pod_category_quad_locked
    SilentPodCategory.QUAD -> R.string.silent_pod_category_quad
    SilentPodCategory.SINGLE -> R.string.silent_pod_category_single
}

/**
 * 扩展页的统一骨架：标题 + 前提说明。
 *
 * 封装成 composable 而不是基类，避免这些页面为了共享一行文案而引入继承。
 */
@Composable
internal fun CampusExtensionScreen(title: String, message: String) {
    Scaffold { padding ->
        CampusServiceMessageScreen(
            title = title,
            message = message,
            modifier = Modifier.padding(padding),
        )
    }
}
