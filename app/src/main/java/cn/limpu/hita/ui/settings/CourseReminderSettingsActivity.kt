package cn.limpu.hita.ui.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.data.source.preference.CourseReminderStore
import cn.limpu.hita.data.work.ReminderRuleSet
import cn.limpu.hita.data.work.ReminderRules
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme

/**
 * 课程提醒规则设置。
 *
 * 与 iOS `CourseReminderSettingsView` 对齐到「可配置」这一点：
 * 上课前与下课前各有独立开关与提前量，课前支持多档、下课前同样支持多档。
 * 取色一律走 `MaterialTheme.colorScheme`，不区分深浅色硬编码。
 */
class CourseReminderSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = CourseReminderStore(this)
        setContent {
            HitaComposeTheme {
                ReminderSettingsScreen(
                    initial = store.rules(),
                    onSave = { rules ->
                        store.saveRules(rules)
                        finish()
                    },
                    onBack = { finish() },
                )
            }
        }
    }
}

/** 课前可选分钟数；与 iOS 的 [0, 5, 10, 15, 30] 一致。 */
private val START_PRESETS = listOf(0, 5, 10, 15, 30)

/** 下课前可选分钟数；与 iOS 的 [0, 5, 10] 一致。 */
private val END_PRESETS = listOf(0, 5, 10)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderSettingsScreen(
    initial: ReminderRuleSet,
    onSave: (ReminderRuleSet) -> Unit,
    onBack: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    var startEnabled by remember { mutableStateOf(initial.leads.isNotEmpty()) }
    var endEnabled by remember { mutableStateOf(initial.endLeads.isNotEmpty()) }
    // 只保留一个课前提前量：多档会导致同一节课连发多条通知，用户难以预期。
    var startLead by remember { mutableStateOf(initial.leads.firstOrNull() ?: ReminderRuleSet.DEFAULT_LEAD_MINUTES) }
    var endLead by remember { mutableStateOf(initial.endLeads.firstOrNull() ?: 5) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reminder_settings_title)) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                            contentDescription = stringResource(R.string.reminder_settings_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            // 复用列表里的右箭头，旋转成返回箭头，避免为一个图标新增资源。
                            modifier = Modifier.rotate(180f),
                        )
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            onSave(
                                ReminderRuleSet(
                                    leads = if (startEnabled) listOf(ReminderRules.clipLead(startLead)) else listOf(),
                                    periodOverrides = initial.periodOverrides,
                                    examLeads = initial.examLeads,
                                    endLeads = if (endEnabled) listOf(ReminderRules.clipLead(endLead)) else listOf(),
                                )
                            )
                        },
                    ) { Text(stringResource(R.string.reminder_settings_save)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = tokens.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.lg),
        ) {
            SettingSection(
                title = stringResource(R.string.reminder_settings_start_title),
                subtitle = stringResource(R.string.reminder_settings_start_subtitle),
                enabled = startEnabled,
                onEnabledChange = { startEnabled = it },
            ) {
                PresetChips(
                    presets = START_PRESETS,
                    selected = startLead,
                    enabled = startEnabled,
                    labelOf = { minutes ->
                        if (minutes == 0) stringResource(R.string.reminder_settings_at_start)
                        else stringResource(R.string.reminder_settings_minutes_before, minutes)
                    },
                    onSelect = { startLead = it },
                )
            }

            SettingSection(
                title = stringResource(R.string.reminder_settings_end_title),
                subtitle = stringResource(R.string.reminder_settings_end_subtitle),
                enabled = endEnabled,
                onEnabledChange = { endEnabled = it },
            ) {
                PresetChips(
                    presets = END_PRESETS,
                    selected = endLead,
                    enabled = endEnabled,
                    labelOf = { minutes ->
                        if (minutes == 0) stringResource(R.string.reminder_settings_at_end)
                        else stringResource(R.string.reminder_settings_minutes_before_end, minutes)
                    },
                    onSelect = { endLead = it },
                )
            }

            Text(
                text = stringResource(R.string.reminder_settings_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SettingSection(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val tokens = HitaTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PresetChips(
    presets: List<Int>,
    selected: Int,
    enabled: Boolean,
    labelOf: @Composable (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { minutes ->
            FilterChip(
                selected = minutes == selected,
                enabled = enabled,
                onClick = { onSelect(minutes) },
                label = { Text(labelOf(minutes)) },
            )
        }
    }
}
