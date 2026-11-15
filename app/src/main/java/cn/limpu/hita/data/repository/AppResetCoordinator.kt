package cn.limpu.hita.data.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * 一键重置：把本轮迁入的、用户可感知的偏好恢复默认。
 *
 * 范围刻意收窄到「本地设置」——不删课表、不删账号、不动 Token。
 * 每个分量独立执行并在清除后回读：任一分量失败只记入 [ResetReport.failed]，
 * 不影响其余分量，调用方据此如实告知用户哪些没清掉。
 */
class AppResetCoordinator(context: Context) {

    private val appContext = context.applicationContext

    /** 一个可重置分量：名字用于回执，`prefs` 是它写入的偏好文件。 */
    private data class Component(
        val label: String,
        val clear: () -> Unit,
        val cleared: () -> Boolean,
    )

    data class ResetReport(
        val cleared: List<String>,
        val failed: List<String>,
    ) {
        val isFullSuccess: Boolean get() = failed.isEmpty()
    }

    private fun preferences(name: String): SharedPreferences =
        appContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun component(label: String, name: String): Component = Component(
        label = label,
        clear = { preferences(name).edit().clear().commit() },
        cleared = { preferences(name).all.isEmpty() },
    )

    private fun components(): List<Component> = listOf(
        component("课程提醒", "course_reminder"),
        component("课表显示范围", "timetable_display_scope"),
        component("身份可见性", "identity_visibility"),
        component("资源搜索历史", "resource_search"),
        component("课表样式", "timetable_style"),
        component("公告已读", "app_notice"),
        component("课表结构迁移记录", "timetable_structure_migration"),
    )

    /**
     * 执行重置。
     *
     * 顺序执行而不是并发：这些文件都很小，`commit()` 的同步写足以在毫秒级完成，
     * 并发反而让「哪一步失败」难以归因。
     */
    fun reset(): ResetReport {
        val cleared = mutableListOf<String>()
        val failed = mutableListOf<String>()
        components().forEach { component ->
            val ok = runCatching {
                component.clear()
                component.cleared()
            }.getOrDefault(false)
            if (ok) cleared += component.label else failed += component.label
        }
        return ResetReport(cleared = cleared, failed = failed)
    }

    /** 影响清单文案，与 [components] 一一对应，供确认对话框展示。 */
    fun affectedLabels(): List<String> = components().map { it.label }
}
