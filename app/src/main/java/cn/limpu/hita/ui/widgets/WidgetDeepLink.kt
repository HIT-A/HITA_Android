package cn.limpu.hita.ui.widgets

import android.content.Intent
import cn.limpu.hita.feature.timetable.TimetableEventTarget
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 小组件点击后的深链目标。
 *
 * 列表项和课程通知都携带事件 id 与本地日期；无效的显式目标由主界面说明，
 * 不猜测或打开另一项。
 */
data class WidgetDeepLink(
    val eventId: String,
    val date: String,
) {
    companion object {

        const val EXTRA_EVENT_ID = TimetableEventTarget.EXTRA_EVENT_ID
        const val EXTRA_DATE = TimetableEventTarget.EXTRA_DATE

        private val DATE_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}$""")

        fun dateFor(startMillis: Long): String = TimetableEventTarget.dateFor(startMillis)

        fun isTargetIntent(intent: Intent?): Boolean = when (intent?.action) {
            TimetableEventTarget.ACTION_WIDGET_ROW,
            TimetableEventTarget.ACTION_WIDGET_SLIM_ROW,
            TimetableEventTarget.ACTION_COURSE_REMINDER -> true
            else -> intent?.let { it.hasExtra(EXTRA_EVENT_ID) || it.hasExtra(EXTRA_DATE) } == true
        }

        /**
         * 解析逻辑与 Android [Intent] 解耦：入参是已经取出的两个字符串。
         * 这样校验规则可以在 JVM 单测里直接覆盖，不必构造 Intent。
         */
        fun parse(eventId: String?, date: String?): WidgetDeepLink? {
            val id = eventId?.trim().orEmpty()
            val day = date?.trim().orEmpty()
            if (id.isEmpty()) return null
            if (!DATE_PATTERN.matches(day)) return null
            try {
                LocalDate.parse(day)
            } catch (_: DateTimeParseException) {
                return null
            }
            return WidgetDeepLink(eventId = id, date = day)
        }

        /** @return 解析成功的目标；任一字段缺失或格式不对返回 null */
        fun fromIntent(intent: Intent?): WidgetDeepLink? =
            parse(intent?.getStringExtra(EXTRA_EVENT_ID), intent?.getStringExtra(EXTRA_DATE))

        /** 往列表项 intent 里写深链参数；模板 intent 由 `setPendingIntentTemplate` 补齐。 */
        fun fillIn(intent: Intent, eventId: String, date: String) {
            TimetableEventTarget.write(intent, eventId, date)
        }
    }
}
