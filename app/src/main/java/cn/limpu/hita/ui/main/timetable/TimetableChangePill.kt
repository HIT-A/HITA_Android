package cn.limpu.hita.ui.main.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaIsAppleGlassSurface

/** 课表浮动 pill 的配色结果。 */
internal data class TimetablePillColors(
    val background: Color,
    val content: Color
)

/**
 * 课表浮动 pill 的统一配色：
 * - AppleGlass 等半透明 surface 主题下给背景一个透明度下限，保证压得住底层课程卡；
 * - 玻璃风格的 surface 是白色半透明：透明度下限把背景提到近白，暗色下的浅色文本会糊在背景里，
 *   统一改用黑色保证可读（alert 态用 errorContainer，不受影响）。
 */
@Composable
internal fun resolveTimetablePillColors(alert: Boolean): TimetablePillColors {
    val container =
        if (alert) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surface
    val background = if (container.alpha < 0.85f) container.copy(alpha = 0.85f) else container
    val content = when {
        !alert && hitaIsAppleGlassSurface() && HitaTheme.isDark -> Color.Black
        alert -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    return TimetablePillColors(background, content)
}

/**
 * 课表变更提示 pill。
 *
 * - info 态（[pending] = false）：本次刷新采纳了调整，提示"课表已更新 · N 处调整"；
 * - pending 态（[pending] = true）：有等待用户确认的事项（持续缺失的课 / 课表源整批异常），
 *   用 error 容器色提升存在感，决策前不消失。
 *
 * 配色全部走 colorScheme 令牌（与 [TimetableEveningHintPill] 同一套做法），
 * 七种风格 × 明暗 × 壁纸模式下均保持可读。
 */
@Composable
internal fun TimetableChangePill(
    pending: Boolean,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    val colors = resolveTimetablePillColors(alert = pending)
    val pillBackground = colors.background
    val content = colors.content
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(pillBackground)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(
                if (pending) R.drawable.ic_baseline_error_24
                else R.drawable.ic_baseline_settings_backup_restore_24
            ),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = if (summary != null) {
                stringResource(R.string.timetable_source_changes, summary)
            } else if (pending) {
                stringResource(R.string.timetable_change_pill_pending, count)
            } else {
                stringResource(R.string.timetable_change_pill_updated, count)
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = content,
            maxLines = 1,
        )
    }
}
