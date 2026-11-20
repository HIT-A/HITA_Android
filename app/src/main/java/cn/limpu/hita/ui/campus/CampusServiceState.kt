package cn.limpu.hita.ui.campus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.limpu.hita.ui.design.HitaTheme

/**
 * 校园扩展页共用的状态展示。
 *
 * 这些页面依赖各自门户的独立会话，无法把「未登录」伪装成空数据；
 * 因此状态只有四种，且失败必须带原因。
 */
sealed interface CampusServiceUiState<out T> {
    data object Loading : CampusServiceUiState<Nothing>
    data class Ready<T>(val value: T) : CampusServiceUiState<T>
    data object RequiresSignIn : CampusServiceUiState<Nothing>
    data class Failed(val reason: String) : CampusServiceUiState<Nothing>
}

@Composable
fun CampusServiceStateCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.md),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun CampusServiceMessageScreen(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(HitaTheme.tokens.spacing.lg),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = HitaTheme.tokens.spacing.sm),
            textAlign = TextAlign.Center,
        )
    }
}
