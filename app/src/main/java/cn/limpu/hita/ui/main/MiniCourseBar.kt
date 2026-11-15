package cn.limpu.hita.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.ui.design.HitaTheme

/**
 * 首页 Dock 上方的迷你课程条。
 *
 * 只在「正在上课」或「15 分钟内开始」时出现：其余阶段返回而不占位，
 * 避免首页常驻一条没有信息量的横条。
 */
@Composable
internal fun MiniCourseBar(
    state: MiniCourseState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.phase != MiniCoursePhase.ONGOING && state.phase != MiniCoursePhase.UPCOMING) return

    val tokens = HitaTheme.tokens
    val isOngoing = state.phase == MiniCoursePhase.ONGOING
    // 取色全部走主题令牌：container 用 surfaceVariant，前景用 onSurfaceVariant，
    // 深浅色模式由 MaterialTheme 决定，不做硬编码分叉。
    val container = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val onContainer = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.md),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg)
            .background(container, RoundedCornerShape(tokens.radius.lg))
            .clickable(onClick = onClick)
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isOngoing) "正在上课" else "即将开始",
                color = primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = state.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = state.timeText,
                color = onContainer,
                fontSize = 12.sp,
                maxLines = 1,
            )
            if (state.place.isNotBlank()) {
                Text(
                    text = state.place,
                    color = onContainer,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
