package cn.limpu.hita.ui.main.timetable.panel

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.HitaThemeStyle
import cn.limpu.hita.ui.design.hitaCoursePaletteFor
import cn.limpu.hita.ui.design.hitaIsPersona
import cn.limpu.hita.data.model.timetable.CourseBubbleStyle
import com.limpu.style.ThemeTools
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FragmentTimetablePanel : BottomSheetDialogFragment() {

    private val viewModel: TimetablePanelViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        retainInstance = true
        setStyle(STYLE_NORMAL, com.limpu.style.R.style.TransparentBottomSheetDialogTheme)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val wrapped = ContextThemeWrapper(requireContext(), com.limpu.style.R.style.AppTheme)
        return ComposeView(wrapped).apply {
            setContent {
                HitaComposeTheme() {
                    TimetablePanelScreen(
                        viewModel = viewModel,
                        onColorPresetSelected = { preset ->
                            ThemeTools.setColorPreset(requireContext(), preset)
                        },
                        onPickStartTime = { value ->
                            val minuteValue = value % 100
                            val hour = value / 100
                            TimePickerDialog(
                                requireContext(),
                                { pickerView, hourOfDay, minute ->
                                    pickerView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    viewModel.changeStartDate(hourOfDay, minute)
                                },
                                hour,
                                minuteValue,
                                true
                            ).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TimetablePanelScreen(
    viewModel: TimetablePanelViewModel,
    onColorPresetSelected: (ThemeTools.COLOR_PRESET) -> Unit,
    onPickStartTime: (Int) -> Unit,
) {
    val startTime by viewModel.startDateLiveData.observeAsState(viewModel.currentStartTime())
    val drawBgLines by viewModel.drawBGLinesLiveData.observeAsState(viewModel.isDrawBgLineEnabled())
    val colorEnable by viewModel.colorEnableLiveData.observeAsState(viewModel.isColorEnabled())
    val fadeEnable by viewModel.fadeEnableLiveData.observeAsState(viewModel.isFadeEnabled())
    val periodLabel by viewModel.periodLabelLiveData.observeAsState(viewModel.isPeriodLabelEnabled())
    val eveningHint by viewModel.eveningHintLiveData.observeAsState(viewModel.isEveningHintEnabled())
    val zoomCompressed by viewModel.zoomCompressedLiveData.observeAsState(viewModel.isZoomCompressed())
    val fontSizeLevel by viewModel.fontSizeLevelLiveData.observeAsState(viewModel.fontSizeLevel())
    val autoReimport by viewModel.autoReimportLiveData.observeAsState(viewModel.isAutoReimportEnabled())
    val scrimOpacity by viewModel.scrimOpacityLiveData.observeAsState(viewModel.scrimOpacity())
    val cardOpacity by viewModel.cardOpacityLiveData.observeAsState(viewModel.cardOpacity())
    val bubbleStyleValue by viewModel.courseBubbleStyleLiveData.observeAsState(
        viewModel.courseBubbleStyleValue()
    )
    val bubbleStyle = CourseBubbleStyle.fromStorage(bubbleStyleValue)
    val isPersona = hitaIsPersona()
    val isClassic = HitaTheme.preferenceStyle == ThemeTools.STYLE.CLASSIC
    val isSumi = HitaTheme.preferenceStyle == ThemeTools.STYLE.SUMI
    val currentColorPreset = HitaTheme.colorPreset
    var selectedColorPreset by remember { mutableStateOf(currentColorPreset) }
    LaunchedEffect(currentColorPreset) {
        selectedColorPreset = currentColorPreset
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .verticalScroll(rememberScrollState())
            .padding(bottom = HitaTheme.tokens.spacing.xl)
    ) {
        SectionTitle("显示设置")
        SettingRow(
            title = stringResource(R.string.timetable_start_time),
            trailing = {
                Text(
                    text = TimeInDay(startTime / 100, startTime % 100).toString(),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { onPickStartTime(startTime) }
                        .padding(horizontal = HitaTheme.tokens.spacing.md, vertical = HitaTheme.tokens.spacing.xs)
                )
            }
        )
        SwitchSettingRow(
            title = stringResource(R.string.draw_bg_dashed_lines),
            checked = drawBgLines,
            onCheckedChange = viewModel::setDrawBGLines
        )
        SwitchSettingRow(
            title = stringResource(R.string.timetable_evening_hint_title),
            checked = eveningHint,
            onCheckedChange = viewModel::setEveningHintEnabled
        )
        ZoomModeSelector(
            compressed = zoomCompressed,
            onSelect = viewModel::setZoomCompressed
        )
        SettingHint(stringResource(R.string.timetable_font_size) + "：作用于课程卡片与节次时间标签")
        FontSizeSelector(
            level = fontSizeLevel,
            onSelect = viewModel::setFontSizeLevel
        )

        if (isClassic) {
            SectionTitle("基础配色")
            SettingHint("基础配色会同时改变界面主色和课程气泡色板。")
            ColorPresetSelector(
                selected = selectedColorPreset,
                onSelected = { preset ->
                    selectedColorPreset = preset
                    onColorPresetSelected(preset)
                }
            )
        }

        SectionTitle("课程气泡")
        if (isPersona) {
            // P5 风格统一管理气泡质感，质感选项与对比度由风格接管
            SettingHint("「波普涂鸦」风格下，课程气泡统一为实色斜切面板 + 错位硬阴影，质感与对比度由风格管理。")
            SwitchSettingRow(
                title = stringResource(R.string.curriculum_manager_enable_color),
                checked = colorEnable,
                onCheckedChange = viewModel::setColorEnable
            )
        } else {
            SettingHint(
                if (isClassic) "选择气泡质感；颜色会跟随上方基础配色。"
                else "选择气泡质感；颜色由当前视觉风格统一管理。"
            )
            CourseBubbleStyleSelector(
                selected = bubbleStyle,
                onSelected = viewModel::setCourseBubbleStyle
            )
            SwitchSettingRow(
                title = stringResource(R.string.curriculum_manager_enable_color),
                checked = colorEnable,
                onCheckedChange = viewModel::setColorEnable
            )
            SwitchSettingRow(
                title = stringResource(R.string.fade_enabled),
                checked = fadeEnable,
                onCheckedChange = viewModel::setFadeEnable
            )
            if (!isSumi) {
                SliderSetting(
                    title = "课程气泡对比度",
                    value = cardOpacity,
                    valueRange = 20f..100f,
                    onValueChangeFinished = viewModel::setCardOpacity
                )
            }
        }

        SectionTitle("背景与标尺")
        SwitchSettingRow(
            title = stringResource(R.string.timetable_label_mode_period),
            checked = periodLabel,
            onCheckedChange = viewModel::setPeriodLabelEnabled
        )
        SliderSetting(
            title = stringResource(R.string.wallpaper_scrim_opacity),
            value = scrimOpacity,
            valueRange = 0f..80f,
            onValueChangeFinished = viewModel::setScrimOpacity
        )

        SectionTitle("数据管理")
        SwitchSettingRow(
            title = stringResource(R.string.timetable_auto_reimport),
            checked = autoReimport,
            onCheckedChange = {
                viewModel.setAutoReimportEnabled(it)
                if (it) viewModel.triggerAutoReimportNow()
            }
        )
    }
}

@Composable
private fun SettingHint(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(
            start = HitaTheme.tokens.spacing.xl,
            end = HitaTheme.tokens.spacing.xl,
            bottom = HitaTheme.tokens.spacing.sm
        )
    )
}

@Composable
private fun ColorPresetSelector(
    selected: ThemeTools.COLOR_PRESET,
    onSelected: (ThemeTools.COLOR_PRESET) -> Unit,
) {
    val isDark = HitaTheme.isDark
    val presets = listOf(
        ThemeTools.COLOR_PRESET.CLASSIC to "经典蓝",
        ThemeTools.COLOR_PRESET.FRESH to "清新绿",
        ThemeTools.COLOR_PRESET.FOCUS to "专注蓝",
        ThemeTools.COLOR_PRESET.HIGH_CONTRAST to "高对比",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = HitaTheme.tokens.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(HitaTheme.tokens.spacing.sm)
    ) {
        presets.forEach { (preset, label) ->
            val isSelected = selected == preset
            val palette = remember(preset, isDark) {
                hitaCoursePaletteFor(
                    style = HitaThemeStyle.Classic,
                    preset = preset,
                    isDark = isDark,
                )
            }
            Card(
                modifier = Modifier
                    .width(92.dp)
                    .clickable { onSelected(preset) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        palette.take(3).forEach { swatch ->
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .background(swatch, RoundedCornerShape(6.dp))
                            )
                        }
                    }
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun CourseBubbleStyleSelector(
    selected: CourseBubbleStyle,
    onSelected: (CourseBubbleStyle) -> Unit,
) {
    val options = listOf(
        CourseBubbleStyle.SOLID to "实色",
        CourseBubbleStyle.TONAL to "柔和",
        CourseBubbleStyle.OUTLINE to "描边",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = HitaTheme.tokens.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(HitaTheme.tokens.spacing.sm)
    ) {
        options.forEach { (style, label) ->
            val isSelected = selected == style
            val tint = MaterialTheme.colorScheme.primary
            val previewColor = when (style) {
                CourseBubbleStyle.SOLID -> tint
                CourseBubbleStyle.TONAL -> tint.copy(alpha = 0.22f)
                CourseBubbleStyle.OUTLINE -> Color.Transparent
            }
            Card(
                modifier = Modifier
                    .width(92.dp)
                    .clickable { onSelected(style) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) tint else MaterialTheme.colorScheme.outlineVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .background(previewColor, RoundedCornerShape(9.dp))
                            .then(
                                if (style == CourseBubbleStyle.OUTLINE) {
                                    Modifier
                                        .background(
                                            tint.copy(alpha = 0.08f),
                                            RoundedCornerShape(9.dp)
                                        )
                                        .border(1.5.dp, tint, RoundedCornerShape(9.dp))
                                } else Modifier
                            )
                    )
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomModeSelector(
    compressed: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val options = listOf(
        false to stringResource(R.string.timetable_zoom_expanded),
        true to stringResource(R.string.timetable_zoom_compressed),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = HitaTheme.tokens.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(HitaTheme.tokens.spacing.sm)
    ) {
        options.forEach { (value, label) ->
            val isSelected = compressed == value
            val tint = MaterialTheme.colorScheme.primary
            Card(
                modifier = Modifier
                    .width(92.dp)
                    .clickable { onSelect(value) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) tint else MaterialTheme.colorScheme.outlineVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(
                            if (value) R.string.timetable_zoom_compressed_desc
                            else R.string.timetable_zoom_expanded_desc
                        ),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FontSizeSelector(
    level: Int,
    onSelect: (Int) -> Unit,
) {
    val options = listOf(
        Triple(1, stringResource(R.string.timetable_font_size_small), stringResource(R.string.timetable_font_size_small_desc)),
        Triple(0, stringResource(R.string.timetable_font_size_default), stringResource(R.string.timetable_font_size_default_desc)),
        Triple(2, stringResource(R.string.timetable_font_size_large), stringResource(R.string.timetable_font_size_large_desc)),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = HitaTheme.tokens.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(HitaTheme.tokens.spacing.sm)
    ) {
        options.forEach { (value, label, desc) ->
            val isSelected = level == value
            val tint = MaterialTheme.colorScheme.primary
            Card(
                modifier = Modifier
                    .width(92.dp)
                    .clickable { onSelect(value) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) tint else MaterialTheme.colorScheme.outlineVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = desc,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = HitaTheme.tokens.spacing.xl,
                top = HitaTheme.tokens.spacing.xl,
                end = HitaTheme.tokens.spacing.xl,
                bottom = HitaTheme.tokens.spacing.sm
            )
    )
}

@Composable
private fun SettingRow(
    title: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = HitaTheme.tokens.spacing.xl,
                vertical = HitaTheme.tokens.spacing.sm
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(title = title) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: (Int) -> Unit,
) {
    var current by remember(value) {
        mutableFloatStateOf(value.toFloat())
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = HitaTheme.tokens.spacing.xl,
                vertical = HitaTheme.tokens.spacing.sm
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
            Text(
                text = "${current.toInt()}%",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = current,
            onValueChange = { current = it },
            valueRange = valueRange,
            onValueChangeFinished = { onValueChangeFinished(current.toInt()) }
        )
        Spacer(modifier = Modifier.height(HitaTheme.tokens.spacing.xs))
    }
}
