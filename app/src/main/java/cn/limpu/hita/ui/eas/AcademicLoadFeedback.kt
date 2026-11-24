package cn.limpu.hita.ui.eas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import cn.limpu.hita.R
import com.limpu.component.data.DataState

internal fun DataState<*>?.requiresAcademicLogin(): Boolean =
    this != null && (state.isAuthenticationFailure() ||
        (refreshFailed && stateRetried.isAuthenticationFailure()))

private fun DataState.STATE.isAuthenticationFailure(): Boolean =
    this == DataState.STATE.NOT_LOGGED_IN || this == DataState.STATE.TOKEN_INVALID

internal fun DataState<*>?.isAcademicLoading(): Boolean =
    this == null || state == DataState.STATE.NOTHING || state == DataState.STATE.LOADING

/** switchMap keeps its last value until the new source emits; publish a reset immediately. */
internal fun <T> LiveData<DataState<T>>.withAcademicLoading(): LiveData<DataState<T>> =
    MediatorLiveData<DataState<T>>(DataState(DataState.STATE.LOADING)).also { result ->
        result.addSource(this) { result.value = it }
    }

@Composable
internal fun AcademicLoadFeedback(
    message: String,
    actionLabel: Int? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(stringResource(actionLabel)) }
        }
    }
}
