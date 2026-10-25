package cn.limpu.hita.ui.main.timetable.panel

import android.app.Application
import androidx.lifecycle.ViewModel
import com.limpu.component.data.SharedPreferenceBooleanLiveData
import com.limpu.component.data.SharedPreferenceIntLiveData
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.EasSettingsRepository
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.data.repository.TimetableStyleRepository
import cn.limpu.hita.data.repository.KEY_COLOR_ENABLE
import cn.limpu.hita.data.repository.KEY_DRAW_BG_LINE
import cn.limpu.hita.data.repository.KEY_FADE_ENABLE
import cn.limpu.hita.data.repository.KEY_LABEL_PERIOD
import cn.limpu.hita.data.repository.KEY_START_DATE
import cn.limpu.hita.data.repository.KEY_CARD_OPACITY
import cn.limpu.hita.data.repository.KEY_WALLPAPER_SCRIM
import cn.limpu.hita.data.repository.KEY_COURSE_BUBBLE_STYLE
import cn.limpu.hita.data.repository.KEY_EVENING_HINT
import cn.limpu.hita.data.repository.KEY_ZOOM_COMPRESSED
import cn.limpu.hita.data.repository.KEY_FONT_SIZE_LEVEL
import cn.limpu.hita.data.model.timetable.CourseBubbleStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TimetablePanelViewModel @Inject constructor(
    private val timetableStyleRepository: TimetableStyleRepository,
    private val easSettingsRepository: EasSettingsRepository,
    private val timetableRepository: TimetableRepository,
    private val easRepository: EASRepository,
) : ViewModel() {

    val startDateLiveData: SharedPreferenceIntLiveData
        get() = timetableStyleRepository.startTimeLiveData
    val drawBGLinesLiveData: SharedPreferenceBooleanLiveData
        get() = timetableStyleRepository.drawBGLinesLiveData

    val colorEnableLiveData: SharedPreferenceBooleanLiveData
        get() = timetableStyleRepository.colorEnableLiveData

    val fadeEnableLiveData: SharedPreferenceBooleanLiveData
        get() = timetableStyleRepository.fadeEnableLiveData
    val periodLabelLiveData: SharedPreferenceBooleanLiveData
        get() = timetableStyleRepository.periodLabelLiveData
    val eveningHintLiveData: SharedPreferenceBooleanLiveData
        get() = timetableStyleRepository.eveningHintLiveData
    val zoomCompressedLiveData: SharedPreferenceBooleanLiveData
        get() = timetableStyleRepository.zoomCompressedLiveData
    val fontSizeLevelLiveData: SharedPreferenceIntLiveData
        get() = timetableStyleRepository.fontSizeLevelLiveData
    fun fontSizeLevel(): Int = timetableStyleRepository.fontSizeLevel()
    fun setFontSizeLevel(level: Int) {
        timetableStyleRepository.putData(KEY_FONT_SIZE_LEVEL, level.coerceIn(0, 2))
    }
    val autoReimportLiveData: SharedPreferenceBooleanLiveData
        get() = easSettingsRepository.autoReimportLiveData
    val scrimOpacityLiveData: SharedPreferenceIntLiveData
        get() = timetableStyleRepository.wallpaperScrimLiveData
    val cardOpacityLiveData: SharedPreferenceIntLiveData
        get() = timetableStyleRepository.cardOpacityLiveData
    val courseBubbleStyleLiveData
        get() = timetableStyleRepository.courseBubbleStyleLiveData


    fun changeStartDate(hour: Int, minute: Int) {
        val v = hour * 100 + minute
        timetableStyleRepository.putData(KEY_START_DATE,v)
    }
    fun setDrawBGLines(draw:Boolean) {
        timetableStyleRepository.putData(KEY_DRAW_BG_LINE,draw)
    }
    fun setColorEnable(draw:Boolean) {
        timetableStyleRepository.putData(KEY_COLOR_ENABLE,draw)
    }
    fun setFadeEnable(draw:Boolean) {
        timetableStyleRepository.putData(KEY_FADE_ENABLE,draw)
    }
    fun setPeriodLabelEnabled(enabled: Boolean) {
        timetableStyleRepository.putData(KEY_LABEL_PERIOD, enabled)
    }
    fun setEveningHintEnabled(enabled: Boolean) {
        timetableStyleRepository.putData(KEY_EVENING_HINT, enabled)
    }
    fun setZoomCompressed(compressed: Boolean) {
        timetableStyleRepository.putData(KEY_ZOOM_COMPRESSED, compressed)
    }
    fun setAutoReimportEnabled(enabled: Boolean) {
        easSettingsRepository.setAutoReimport(enabled)
    }

    /** 同步读取当前值，供 observeAsState 首帧使用，避免开关在 LiveData 发射前闪成「关」。 */
    fun isAutoReimportEnabled(): Boolean = easSettingsRepository.isAutoReimportEnabled()
    fun isEveningHintEnabled(): Boolean = timetableStyleRepository.isEveningHintEnabled()
    fun currentStartTime(): Int = timetableStyleRepository.currentStartTime()
    fun isDrawBgLineEnabled(): Boolean = timetableStyleRepository.isDrawBgLineEnabled()
    fun isColorEnabled(): Boolean = timetableStyleRepository.isColorEnabled()
    fun isFadeEnabled(): Boolean = timetableStyleRepository.isFadeEnabled()
    fun isPeriodLabelEnabled(): Boolean = timetableStyleRepository.isPeriodLabelEnabled()
    fun isZoomCompressed(): Boolean = timetableStyleRepository.isZoomCompressed()
    fun scrimOpacity(): Int = timetableStyleRepository.scrimOpacity()
    fun cardOpacity(): Int = timetableStyleRepository.cardOpacity()
    fun courseBubbleStyleValue(): String = timetableStyleRepository.courseBubbleStyleValue()

    fun setScrimOpacity(opacity: Int) {
        timetableStyleRepository.putData(KEY_WALLPAPER_SCRIM, opacity)
    }

    fun setCardOpacity(opacity: Int) {
        timetableStyleRepository.putData(KEY_CARD_OPACITY, opacity)
    }

    fun setCourseBubbleStyle(style: CourseBubbleStyle) {
        timetableStyleRepository.putData(KEY_COURSE_BUBBLE_STYLE, style.storageValue)
    }

    fun triggerAutoReimportNow() {
        val token = easRepository.getEasToken()
        if (!token.isLogin()) return
        val isUndergrad = token.stutype == cn.limpu.hita.data.model.eas.EASToken.TYPE.UNDERGRAD
        easRepository.startAutoImportCurrentTimetable(isUndergrad) { result ->
            if (result == cn.limpu.hita.data.repository.AutoImportResult.SUCCESS) {
                easSettingsRepository.setLastAutoReimportTs(System.currentTimeMillis())
            }
        }
    }
}
