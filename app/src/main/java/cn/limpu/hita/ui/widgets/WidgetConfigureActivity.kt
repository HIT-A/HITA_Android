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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.DisplayScopeResolver
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
        val options = DisplayScopeResolver(application).options
        setContent {
            HitaComposeTheme {
                val scopes by options.observeAsState(emptyList())
                WidgetConfigureScreen(
                    initial = existing,
                    scopes = scopes,
                    onCancel = { finish() },
                    onConfirm = { config ->
                        prefs.write(appWidgetId, config)
                        WidgetUtils.sendRefreshToAll(this)
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
    scopes: List<DisplayScopeResolver.ScopeOption>,
    onCancel: () -> Unit,
    onConfirm: (WidgetConfigCodec.Config) -> Unit,
) {
    val tokens = HitaTheme.tokens
    val background = rememberSaveable { mutableStateOf(initial.background) }
    val scopeKey = rememberSaveable { mutableStateOf(initial.scopeKey) }
    val canSave = scopeKey.value == null || scopes.any { WidgetConfigCodec.encodeScope(it.scope) == scopeKey.value }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(tokens.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.widget_today_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
            )
            Text(
                text = stringResource(R.string.widget_background_title),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs)) {
                WidgetConfigOption(
                    label = stringResource(R.string.widget_background_plain),
                    selected = background.value == WidgetConfigCodec.Background.PLAIN,
                    onSelect = { background.value = WidgetConfigCodec.Background.PLAIN },
                )
                WidgetConfigOption(
                    label = stringResource(R.string.widget_background_card),
                    selected = background.value == WidgetConfigCodec.Background.CARD,
                    onSelect = { background.value = WidgetConfigCodec.Background.CARD },
                )
            }
            Text(
                text = stringResource(R.string.widget_scope_title),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs)) {
                WidgetConfigOption(
                    label = stringResource(R.string.widget_scope_follow),
                    selected = scopeKey.value == null,
                    onSelect = { scopeKey.value = null },
                )
                scopes.forEach { option ->
                    val key = WidgetConfigCodec.encodeScope(option.scope)
                    WidgetConfigOption(option.name, scopeKey.value == key) { scopeKey.value = key }
                }
            }
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)) {
                Button(
                    enabled = canSave,
                    onClick = {
                        onConfirm(
                            WidgetConfigCodec.Config(
                                scopeKey = scopeKey.value,
                                background = background.value,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.widget_config_done))
                }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel))
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
