package cn.limpu.hita.ui.main.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.ui.design.*

private val LocalTodayCourseColors = staticCompositionLocalOf<Map<String, Color>> { emptyMap() }

@Composable
internal fun TodayCourseColors(state: TodayUiState, content: @Composable () -> Unit) {
    val visible = state.agenda?.allToday.orEmpty().associate { it.subjectId.ifBlank { it.name } to it.color }
    val scope = state.courseColors.ifEmpty { visible }
    val themePalette = hitaCoursePalette()
    // Small theme palettes cannot distinguish a whole timetable. Reuse the existing
    // sixteen-color course palette while retaining the selected preset and light/dark mode.
    val palette = if (scope.size > themePalette.size) hitaCoursePaletteFor(
        HitaThemeStyle.Classic, HitaTheme.colorPreset, HitaTheme.isDark,
    ) else themePalette
    val colors = remember(scope, visible, palette) {
        val argb = IntArray(palette.size) { hitaColorToArgb(palette[it]) }
        val slots = TimetableColorAssigner.assign(scope.keys, scope, argb)
        buildMap {
            slots.forEach { (key, slot) -> put(key, palette[slot]) }
            // Events without a subject must not move the slots of established courses.
            visible.keys.forEach { key -> putIfAbsent(key, palette[Math.floorMod(key.hashCode(), palette.size)]) }
        }
    }
    CompositionLocalProvider(LocalTodayCourseColors provides colors, content = content)
}

@Composable
internal fun todayCourseColor(event: EventItem): Color {
    val key = event.subjectId.ifBlank { event.name }
    return LocalTodayCourseColors.current[key] ?: hitaCourseColor(key, event.color)
}
