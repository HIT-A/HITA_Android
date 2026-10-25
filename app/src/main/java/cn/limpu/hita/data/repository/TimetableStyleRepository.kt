package cn.limpu.hita.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import javax.inject.Inject
import javax.inject.Singleton
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.limpu.component.data.booleanLiveData
import com.limpu.component.data.intLiveData
import com.limpu.component.data.stringLiveData
import cn.limpu.hita.data.model.timetable.TimetableStyleSheet
import cn.limpu.hita.data.model.timetable.CourseBubbleStyle

private const val SP_NAME = "timetable_style"
const val KEY_START_DATE = "start_date"
const val KEY_DRAW_BG_LINE = "draw_bg_line"
const val KEY_COLOR_ENABLE = "color_enable"
const val KEY_FADE_ENABLE = "fade_enable"
const val KEY_LABEL_PERIOD = "label_period"
const val KEY_WALLPAPER_PATH = "wallpaper_path"
const val KEY_WALLPAPER_SCRIM = "wallpaper_scrim"
const val KEY_CARD_OPACITY = "card_opacity"
const val KEY_COURSE_BUBBLE_STYLE = "course_bubble_style"
const val KEY_EVENING_HINT = "evening_hint"
const val KEY_ZOOM_COMPRESSED = "zoom_compressed"
const val KEY_FONT_SIZE_LEVEL = "font_size_level"

@Singleton
class TimetableStyleRepository @Inject constructor(application: Application) {
    private val timetableStyleSP: SharedPreferences = application.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    val startTimeLiveData = timetableStyleSP.intLiveData(KEY_START_DATE, 830)
    val drawBGLinesLiveData = timetableStyleSP.booleanLiveData(KEY_DRAW_BG_LINE, true)
    val colorEnableLiveData = timetableStyleSP.booleanLiveData(KEY_COLOR_ENABLE, true)
    val fadeEnableLiveData = timetableStyleSP.booleanLiveData(KEY_FADE_ENABLE, true)
    val periodLabelLiveData = timetableStyleSP.booleanLiveData(KEY_LABEL_PERIOD, false)
    val wallpaperPathLiveData = timetableStyleSP.stringLiveData(KEY_WALLPAPER_PATH, "")
    val wallpaperScrimLiveData = timetableStyleSP.intLiveData(KEY_WALLPAPER_SCRIM, 30)
    val cardOpacityLiveData = timetableStyleSP.intLiveData(KEY_CARD_OPACITY, 85)
    val courseBubbleStyleLiveData = timetableStyleSP.stringLiveData(
        KEY_COURSE_BUBBLE_STYLE,
        CourseBubbleStyle.SOLID.storageValue
    )
    /** “还有更多课程”悬浮提示开关，默认开启。 */
    val eveningHintLiveData = timetableStyleSP.booleanLiveData(KEY_EVENING_HINT, true)
    fun isEveningHintEnabled(): Boolean = timetableStyleSP.getBoolean(KEY_EVENING_HINT, true)
    fun currentStartTime(): Int = timetableStyleSP.getInt(KEY_START_DATE, 830)
    fun isDrawBgLineEnabled(): Boolean = timetableStyleSP.getBoolean(KEY_DRAW_BG_LINE, true)
    fun isColorEnabled(): Boolean = timetableStyleSP.getBoolean(KEY_COLOR_ENABLE, true)
    fun isFadeEnabled(): Boolean = timetableStyleSP.getBoolean(KEY_FADE_ENABLE, true)
    fun isPeriodLabelEnabled(): Boolean = timetableStyleSP.getBoolean(KEY_LABEL_PERIOD, false)
    fun isZoomCompressed(): Boolean = timetableStyleSP.getBoolean(KEY_ZOOM_COMPRESSED, false)
    fun wallpaperPath(): String = timetableStyleSP.getString(KEY_WALLPAPER_PATH, "").orEmpty()
    fun scrimOpacity(): Int = timetableStyleSP.getInt(KEY_WALLPAPER_SCRIM, 30)
    fun cardOpacity(): Int = timetableStyleSP.getInt(KEY_CARD_OPACITY, 85)
    fun courseBubbleStyleValue(): String =
        timetableStyleSP.getString(KEY_COURSE_BUBBLE_STYLE, CourseBubbleStyle.SOLID.storageValue)
            ?: CourseBubbleStyle.SOLID.storageValue
    /** 课表缩放：false=放大（默认，现状）/ true=缩小（整天压缩一屏）。 */
    val zoomCompressedLiveData = timetableStyleSP.booleanLiveData(KEY_ZOOM_COMPRESSED, false)
    /** 课程文字档位：0=默认 / 1=小字 / 2=大字。 */
    val fontSizeLevelLiveData = timetableStyleSP.intLiveData(KEY_FONT_SIZE_LEVEL, 0)
    fun fontSizeLevel(): Int = timetableStyleSP.getInt(KEY_FONT_SIZE_LEVEL, 0).coerceIn(0, 2)
    val wallpaperDateColorLiveData = MutableLiveData(Color.WHITE)
    val wallpaperLabelColorLiveData = MutableLiveData(Color.WHITE)


    fun putData(key: String, value: Int) {
        timetableStyleSP.edit().putInt(key, value).apply()
    }

    fun putData(key: String, value: Boolean) {
        timetableStyleSP.edit().putBoolean(key, value).apply()
    }

    fun putData(key: String, value: String) {
        timetableStyleSP.edit().putString(key, value).apply()
    }

    fun getStyleSheetLiveData(): MediatorLiveData<TimetableStyleSheet> {
        val sheet = MediatorLiveData<TimetableStyleSheet>()
        sheet.value = TimetableStyleSheet(
            isColorEnabled = isColorEnabled(),
            isFadeEnabled = isFadeEnabled(),
            drawBGLine = isDrawBgLineEnabled(),
            usePeriodLabel = isPeriodLabelEnabled(),
            startTime = currentStartTime(),
            courseBubbleStyle = CourseBubbleStyle.fromStorage(courseBubbleStyleValue()),
            fontSizeLevel = fontSizeLevel(),
        ).withCardOpacity(cardOpacity())
        sheet.addSource(startTimeLiveData) { start ->
            sheet.value = sheet.value?.copy(startTime = start)
        }
        sheet.addSource(drawBGLinesLiveData) { draw ->
            sheet.value = sheet.value?.copy(drawBGLine = draw)
        }
        sheet.addSource(colorEnableLiveData) { draw ->
            sheet.value = sheet.value?.copy(isColorEnabled = draw)
        }
        sheet.addSource(fadeEnableLiveData) { fade ->
            sheet.value = sheet.value?.copy(isFadeEnabled = fade)
        }
        sheet.addSource(periodLabelLiveData) { enabled ->
            sheet.value = sheet.value?.copy(usePeriodLabel = enabled)
        }
        sheet.addSource(cardOpacityLiveData) { opacity ->
            sheet.value = sheet.value?.withCardOpacity(opacity)
        }
        sheet.addSource(courseBubbleStyleLiveData) { bubbleStyle ->
            sheet.value = sheet.value?.copy(
                courseBubbleStyle = CourseBubbleStyle.fromStorage(bubbleStyle)
            )
        }
        sheet.addSource(fontSizeLevelLiveData) { level ->
            sheet.value = sheet.value?.copy(fontSizeLevel = level)
        }
        return sheet
    }
}
