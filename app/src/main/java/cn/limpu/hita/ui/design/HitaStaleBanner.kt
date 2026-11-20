package cn.limpu.hita.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import cn.limpu.hita.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val cachedAtFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)

/** 把缓存写入时间格式化成提示条里的短时间，例如 `11-20 10:32`。 */
fun formatCachedAt(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(cachedAtFormat)

/**
 * 「刷新失败，正在显示上次数据」的提示条。
 *
 * 页面在保留旧数据继续可看的前提下用它说明数据不是最新的；[onAction] 为空时不显示动作按钮
 * （例如会话已失效、只能先去登录）。[actionLabel] 为按钮文案。
 */
@Composable
fun HitaStaleBanner(
    message: String,
    onAction: (() -> Unit)?,
    actionLabel: String,
    modifier: Modifier = Modifier,
) {
    val tokens = HitaTheme.tokens
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.xs)
            .clip(hitaStyleCardShape(tokens.radius.md, tokens.radius.sm))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(
                start = tokens.spacing.md,
                end = if (onAction != null) tokens.spacing.xs else tokens.spacing.md,
                top = tokens.spacing.xs,
                bottom = tokens.spacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * 展示缓存内容的页面统一用这个：有缓存时间就写明是几点的数据，没有就只说展示的是上次数据。
 */
@Composable
fun StaleDataBanner(
    cachedAtMillis: Long?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val message = cachedAtMillis?.let {
        stringResource(R.string.stale_data_message, formatCachedAt(it))
    } ?: stringResource(R.string.stale_data_message_no_time)
    HitaStaleBanner(
        message = message,
        onAction = onRetry,
        actionLabel = stringResource(R.string.stale_data_retry),
        modifier = modifier,
    )
}
