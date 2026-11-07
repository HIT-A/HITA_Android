package cn.limpu.hita.ui.main.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.HitaThemeStyle
import cn.limpu.hita.ui.design.hitaGlassCardBorder
import cn.limpu.hita.ui.design.hitaGlassCardColors
import cn.limpu.hita.ui.design.hitaGlassCardModifier
import cn.limpu.hita.ui.design.hitaStyleCardShape
import cn.limpu.hita.ui.design.hitaSumiBrushUnderline

/** 设置页共用的分组卡片与行组件，供导航页与独立的更多功能页复用。 */

@Composable
internal fun NavigationGroup(
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
internal fun NavigationRow(
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
internal fun CircleIcon(icon: Int) {
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
