package cn.limpu.hita.ui.widgets

import android.content.Intent

/**
 * 小组件点击后的深链目标。
 *
 * 小组件列表项通过 `setPendingIntentTemplate` 逐条填充 extra，
 * 这里只做解析与校验：缺少或非法的参数一律返回 null，交给主界面忽略，
 * 而不是猜一个默认课程去打开错误详情。
 */
data class WidgetDeepLink(
    val eventId: String,
    val date: String,
) {
    companion object {

        const val EXTRA_EVENT_ID = "hita.widget.EXTRA_EVENT_ID"
        const val EXTRA_DATE = "hita.widget.EXTRA_DATE"

        private val DATE_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}$""")

        /**
         * 解析逻辑与 Android [Intent] 解耦：入参是已经取出的两个字符串。
         * 这样校验规则可以在 JVM 单测里直接覆盖，不必构造 Intent。
         */
        fun parse(eventId: String?, date: String?): WidgetDeepLink? {
            val id = eventId?.trim().orEmpty()
            val day = date?.trim().orEmpty()
            if (id.isEmpty()) return null
            if (!DATE_PATTERN.matches(day)) return null
            return WidgetDeepLink(eventId = id, date = day)
        }

        /** @return 解析成功的目标；任一字段缺失或格式不对返回 null */
        fun fromIntent(intent: Intent?): WidgetDeepLink? =
            parse(intent?.getStringExtra(EXTRA_EVENT_ID), intent?.getStringExtra(EXTRA_DATE))

        /** 往列表项 intent 里写深链参数；模板 intent 由 `setPendingIntentTemplate` 补齐。 */
        fun fillIn(intent: Intent, eventId: String, date: String) {
            intent.putExtra(EXTRA_EVENT_ID, eventId)
            intent.putExtra(EXTRA_DATE, date)
        }
    }
}
