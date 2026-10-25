package cn.limpu.hita.data.model.timetable

import android.view.Gravity
import androidx.compose.runtime.Immutable

enum class CourseBubbleStyle(val storageValue: String) {
    SOLID("solid"),
    TONAL("tonal"),
    OUTLINE("outline");

    companion object {
        fun fromStorage(value: String?): CourseBubbleStyle =
            entries.firstOrNull { it.storageValue == value } ?: SOLID
    }
}

@Immutable
data class TimetableStyleSheet(
    val isColorEnabled: Boolean = true,
    val isFadeEnabled: Boolean = true,
    val cardTitleColor: String = "auto",
    val subTitleColor: String = "auto",
    val iconColor: String = "white",
    val isBoldText: Boolean = true,
    val drawBGLine: Boolean = true,
    val cardIconEnabled: Boolean = false,
    private val storedCardOpacity: Int = 95,
    val cardHeight: Int = 160,
    val usePeriodLabel: Boolean = false,
    val startTime: Int = 830,
    val endHour: Int = 23,
    val todayBGColor: Int = 0x10000000,
    val titleGravity: Int = Gravity.CENTER,
    val titleAlpha: Int = 100,
    val subtitleAlpha: Int = 60,
    val drawNowLine: Boolean = true,
    val courseBubbleStyle: CourseBubbleStyle = CourseBubbleStyle.SOLID,
    /** 课程文字档位：0=默认 / 1=小字 / 2=大字。 */
    val fontSizeLevel: Int = 0,
) {
    val cardOpacity: Int get() = storedCardOpacity.coerceIn(20, 100)
    val startHour: Int get() = startTime / 100
    val startMinute: Int get() = startTime % 100

    /** 字体系数：作用于课程卡标题/副标题、冲突合并卡、时间轴标签。 */
    val fontScale: Float
        get() = when (fontSizeLevel.coerceIn(0, 2)) {
            1 -> 0.85f
            2 -> 1.12f
            else -> 1f
        }

    fun withCardOpacity(value: Int): TimetableStyleSheet =
        copy(storedCardOpacity = value.coerceIn(20, 100))

    fun getStartTimeObject(): TimeInDay = TimeInDay(startHour, startMinute)
}
