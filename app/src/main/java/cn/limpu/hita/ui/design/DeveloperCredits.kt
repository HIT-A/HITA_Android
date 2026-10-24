package cn.limpu.hita.ui.design

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class DeveloperCredit(
    @param:StringRes val nameRes: Int,
    val onClick: () -> Unit
)

/** 按完整姓名折行，只在同一行相邻的姓名之间显示竖线。 */
@Composable
internal fun DeveloperCredits(
    credits: List<DeveloperCredit>,
    modifier: Modifier = Modifier
) {
    val names = credits.map { stringResource(it.nameRes) }
    val textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
    val textMeasurer = rememberTextMeasurer()
    val separator = " | "

    BoxWithConstraints(modifier = modifier) {
        fun textWidth(text: String) = textMeasurer.measure(
            text = AnnotatedString(text),
            style = textStyle,
            softWrap = false,
            maxLines = 1
        ).size.width

        val rows = partitionDeveloperNames(
            nameWidths = names.map(::textWidth),
            separatorWidth = textWidth(separator),
            maxWidth = constraints.maxWidth
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach { index ->
                        if (index != row.first) {
                            Text(
                                text = separator,
                                style = textStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Text(
                            text = names[index],
                            style = textStyle,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.clickable(onClick = credits[index].onClick)
                        )
                    }
                }
            }
        }
    }
}

internal fun partitionDeveloperNames(
    nameWidths: List<Int>,
    separatorWidth: Int,
    maxWidth: Int
): List<IntRange> {
    if (nameWidths.isEmpty()) return emptyList()

    val rows = mutableListOf<IntRange>()
    var rowStart = 0
    var rowWidth = 0L
    nameWidths.forEachIndexed { index, width ->
        val addedWidth = width.toLong() + if (index == rowStart) 0L else separatorWidth.toLong()
        if (index > rowStart && rowWidth + addedWidth > maxWidth) {
            rows.add(rowStart until index)
            rowStart = index
            rowWidth = width.toLong()
        } else {
            rowWidth += addedWidth
        }
    }
    rows.add(rowStart until nameWidths.size)
    return rows
}
