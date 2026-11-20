package cn.limpu.hita.ui.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme

/**
 * 小组件配置页。
 *
 * 只暴露两项：背景样式与「是否跟随主界面的显示范围」。
 * 结果写回 [WidgetPrefs] 后 `setResult(RESULT_OK)`，宿主桌面向导据此完成添加；
 * 用户取消时 `RESULT_CANCELED`，小组件不会被创建。
 */
class WidgetConfigureActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val prefs = WidgetPrefs(this)
        val existing = prefs.read(appWidgetId)
        setContent {
            HitaComposeTheme {
                WidgetConfigureScreen(
                    initial = existing,
                    onCancel = { finish() },
                    onConfirm = { config ->
                        prefs.write(appWidgetId, config)
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                        )
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun WidgetConfigureScreen(
    initial: WidgetConfigCodec.Config,
    onCancel: () -> Unit,
    onConfirm: (WidgetConfigCodec.Config) -> Unit,
) {
    val tokens = HitaTheme.tokens
    val background = remember { mutableStateOf(initial.background) }
    val followMainScope = remember { mutableStateOf(initial.scopeKey == null) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(tokens.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.lg),
        ) {
            Text(
                text = "今日课程小组件",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
            )
            Text(
                text = "背景样式",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs)) {
                WidgetConfigOption(
                    label = "跟随主题",
                    selected = background.value == WidgetConfigCodec.Background.PLAIN,
                    onSelect = { background.value = WidgetConfigCodec.Background.PLAIN },
                )
                WidgetConfigOption(
                    label = "卡片背景",
                    selected = background.value == WidgetConfigCodec.Background.CARD,
                    onSelect = { background.value = WidgetConfigCodec.Background.CARD },
                )
            }
            Text(
                text = "显示范围",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs)) {
                WidgetConfigOption(
                    label = "跟随主界面当前范围",
                    selected = followMainScope.value,
                    onSelect = { followMainScope.value = true },
                )
                WidgetConfigOption(
                    label = "固定为添加时的范围",
                    selected = !followMainScope.value,
                    onSelect = { followMainScope.value = false },
                )
            }
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)) {
                Button(
                    onClick = {
                        onConfirm(
                            WidgetConfigCodec.Config(
                                // 「跟随主界面」用 null 表达，不写死某个范围名，
                                // 否则主界面切换后小组件会停在旧范围。
                                scopeKey = if (followMainScope.value) null else initial.scopeKey ?: "pinned",
                                background = background.value,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("完成")
                }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("取消")
                }
            }
        }
    }
}

@Composable
private fun WidgetConfigOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
