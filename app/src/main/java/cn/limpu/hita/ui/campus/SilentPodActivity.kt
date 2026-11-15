package cn.limpu.hita.ui.campus

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
import cn.limpu.hita.data.model.campus.SilentPodContent
import cn.limpu.hita.data.model.campus.SilentPodGroup
import cn.limpu.hita.data.model.campus.SilentPodReservation
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
                        onCreateReservation = { space, start, end, phone ->
                            viewModel.createReservation(
                                spaceId = space.id,
                                date = (state as? CampusServiceUiState.Ready)?.value?.date.orEmpty(),
                                startTime = start,
                                endTime = end,
                                notes = phone,
                            )
                        },
                        onCancelReservation = { reservation ->
                            viewModel.cancelReservation(reservation.id)
                        },
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
    onCreateReservation: (SilentPodSpace, String, String, String) -> Unit,
    onCancelReservation: (SilentPodReservation) -> Unit,
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
            Text(
                text = stringResource(R.string.silent_pod_scope_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onCreateReservation: (SilentPodSpace, String, String, String) -> Unit,
    onCancelReservation: (SilentPodReservation) -> Unit,
) {
    val tokens = HitaTheme.tokens
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
            item(key = "group-${group.category.name}") { SilentPodGroupHeader(group) }
            items(group.spaces, key = { "space-${it.id}" }) { space ->
                SilentPodSpaceCard(space = space, onReserve = onCreateReservation)
            }
        }
        item {
            Text(
                text = stringResource(R.string.silent_pod_my_reservations),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = tokens.spacing.md),
            )
        }
        if (content.reservations.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.silent_pod_empty_reservations),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(content.reservations, key = { "reservation-${it.id}" }) { reservation ->
            SilentPodReservationCard(reservation = reservation, onCancel = onCancelReservation)
        }
    }
}

@Composable
private fun SilentPodGroupHeader(group: SilentPodGroup) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(categoryTitleRes(group.category)),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.silent_pod_group_count, group.spaces.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SilentPodSpaceCard(
    space: SilentPodSpace,
    onReserve: (SilentPodSpace, String, String, String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    if (showDialog) {
        SilentPodReservationDialog(
            space = space,
            onDismiss = { showDialog = false },
            onConfirm = { start, end, phone ->
                showDialog = false
                onReserve(space, start, end, phone)
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
            Text(
                text = if (space.allowDurations.isEmpty()) {
                    stringResource(R.string.silent_pod_no_slot)
                } else {
                    stringResource(R.string.silent_pod_slots, space.allowDurations.joinToString("  "))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (space.allowDurations.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (space.allowDurations.isNotEmpty()) {
                TextButton(
                    onClick = { showDialog = true },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.silent_pod_reserve_action)) }
            }
        }
    }
}

/**
 * 预约对话框：从空间的可选时段里挑一个，再填手机号。
 *
 * 只为「单人到访」设计：多人静音仓需要同行成员列表，那要走联系人接口，
 * 本页不做半套实现——服务端会拒绝人数不足的请求并给出原因。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SilentPodReservationDialog(
    space: SilentPodSpace,
    onDismiss: () -> Unit,
    onConfirm: (start: String, end: String, phone: String) -> Unit,
) {
    val tokens = HitaTheme.tokens
    var selected by remember { mutableStateOf(space.allowDurations.first()) }
    var phone by remember { mutableStateOf("") }
    val parts = selected.split('-', '~', '–')
    val start = parts.getOrNull(0)?.trim().orEmpty()
    val end = parts.getOrNull(1)?.trim().orEmpty()
    val phoneValid = phone.trim().length == 11 && phone.trim().all { it.isDigit() }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.silent_pod_reserve_title, space.codeName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)) {
                Text(
                    text = stringResource(R.string.silent_pod_reserve_pick_slot),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    space.allowDurations.forEach { slot ->
                        androidx.compose.material3.FilterChip(
                            selected = slot == selected,
                            onClick = { selected = slot },
                            label = { Text(slot) },
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
                enabled = start.isNotEmpty() && end.isNotEmpty() && phoneValid,
                onClick = { onConfirm(start, end, phone.trim()) },
            ) { Text(stringResource(R.string.silent_pod_reserve_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
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
    onCancel: (SilentPodReservation) -> Unit,
) {
    var confirmCancel by remember { mutableStateOf(false) }
    if (confirmCancel) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.silent_pod_cancel_action)) },
            text = { Text(stringResource(R.string.silent_pod_cancel_confirm, reservation.spaceName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    onCancel(reservation)
                }) { Text(stringResource(R.string.silent_pod_cancel_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.cancel)) }
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
        }
    }
}

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
