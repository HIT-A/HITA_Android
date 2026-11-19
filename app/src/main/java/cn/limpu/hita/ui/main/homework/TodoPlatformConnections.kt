package cn.limpu.hita.ui.main.homework

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.geniusta.GeniusTaError
import cn.limpu.hita.data.repository.geniusta.GeniusTaState
import cn.limpu.hita.data.repository.grader.GraderError
import cn.limpu.hita.data.repository.grader.GraderState

internal val TodoPlatform.titleRes: Int
    @StringRes get() = when (this) {
        TodoPlatform.GRADER -> R.string.grader_title
        TodoPlatform.GENIUSTA -> R.string.geniusta_title
    }

internal val GraderError.messageRes: Int
    @StringRes get() = when (this) {
        GraderError.AUTHENTICATION -> R.string.grader_authentication_error
        GraderError.NETWORK -> R.string.grader_network_error
        GraderError.STORAGE -> R.string.grader_storage_error
        GraderError.INVALID_RESPONSE -> R.string.grader_response_error
    }

internal val GeniusTaError.messageRes: Int
    @StringRes get() = when (this) {
        GeniusTaError.AUTHENTICATION -> R.string.geniusta_authentication_error
        GeniusTaError.NETWORK -> R.string.geniusta_network_error
        GeniusTaError.STORAGE -> R.string.geniusta_storage_error
        GeniusTaError.INVALID_RESPONSE -> R.string.geniusta_response_error
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodoPlatformConnections(
    grader: GraderState?,
    geniusTa: GeniusTaState,
    onGraderLogin: () -> Unit,
    onGraderRefresh: () -> Unit,
    onGraderDisconnect: () -> Unit,
    onGeniusTaLogin: () -> Unit,
    onGeniusTaRefresh: () -> Unit,
    onGeniusTaDisconnect: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (grader != null) {
            TodoConnection(
                TodoPlatform.GRADER, grader.accountId != null, grader.ready, grader.syncing, grader.needsLogin,
                grader.error != null, grader.error == GraderError.STORAGE, onGraderLogin, onGraderRefresh, onGraderDisconnect,
            )
        }
        TodoConnection(
            TodoPlatform.GENIUSTA, geniusTa.accountId != null, geniusTa.ready, geniusTa.syncing, geniusTa.needsLogin,
            geniusTa.error != null, geniusTa.error == GeniusTaError.STORAGE, onGeniusTaLogin, onGeniusTaRefresh, onGeniusTaDisconnect,
        )
    }
}

@Composable
private fun TodoConnection(
    platform: TodoPlatform,
    connected: Boolean,
    ready: Boolean,
    syncing: Boolean,
    needsLogin: Boolean,
    failed: Boolean,
    storageFailed: Boolean,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val title = stringResource(platform.titleRes)
    val enabled = ready && !syncing
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!connected || needsLogin || failed) {
                TextButton(onClick = onLogin, enabled = enabled) {
                    Text(stringResource(if (connected) R.string.homework_platform_relogin else R.string.homework_platform_login, title))
                }
            } else {
                Text(title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
            if ((connected && !needsLogin) || storageFailed) {
                IconButton(onClick = onRefresh, enabled = !syncing) {
                    Icon(painterResource(R.drawable.widget_ic_refresh), stringResource(R.string.homework_platform_refresh, title))
                }
            }
            if (connected) {
                IconButton(onClick = onDisconnect, enabled = !syncing) {
                    Icon(painterResource(R.drawable.ic_baseline_close_24), stringResource(R.string.homework_platform_disconnect, title))
                }
            }
            if (syncing) {
                CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
internal fun GeniusTaLoginDialog(
    state: GeniusTaState,
    onDismiss: () -> Unit,
    onLogin: (String, String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val dismiss = { if (!state.syncing) onDismiss() }
    AlertDialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.homework_platform_login, stringResource(R.string.geniusta_title))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { if (it.length <= 255) username = it },
                    label = { Text(stringResource(R.string.geniusta_username)) },
                    enabled = !state.syncing,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { if (it.length <= 1024) password = it },
                    label = { Text(stringResource(R.string.geniusta_password)) },
                    enabled = !state.syncing,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (attempted && !state.syncing) state.error?.let {
                    Text(stringResource(it.messageRes), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.syncing && username.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    attempted = true
                    val enteredPassword = password
                    password = ""
                    onLogin(username.trim(), enteredPassword)
                },
            ) { Text(stringResource(R.string.homework_platform_sign_in)) }
        },
        dismissButton = {
            TextButton(onClick = dismiss, enabled = !state.syncing) { Text(stringResource(R.string.homework_cancel)) }
        },
    )
}
