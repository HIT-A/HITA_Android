package cn.limpu.hita.ui.widgets

import cn.limpu.hita.data.model.timetable.DisplayScope

/**
 * 小组件实例配置的键与值编解码。
 *
 * 与 Android `SharedPreferences` 解耦，便于在 JVM 上验证「按 appWidgetId 隔离」这一点；
 * [WidgetPrefs] 只负责真正的读写。
 */
object WidgetConfigCodec {

    enum class Background(val storageValue: String) {
        PLAIN("plain"),
        CARD("card"),
    }

    data class Config(
        /** 显示范围；null 表示跟随主界面的当前范围。 */
        val scopeKey: String?,
        val background: Background,
    )

    /** 每个字段都带实例 id，两个组件不会互相覆盖。 */
    fun scopeKeyField(appWidgetId: Int): String = "scope_$appWidgetId"

    fun backgroundKeyField(appWidgetId: Int): String = "background_$appWidgetId"

    fun decodeBackground(raw: String?): Background =
        Background.entries.firstOrNull { it.storageValue == raw } ?: Background.PLAIN

    fun decode(scopeRaw: String?, backgroundRaw: String?): Config =
        Config(
            // The old UI persisted "pinned" without recording a target. Do not pin it to
            // whichever range happens to be active during upgrade.
            scopeKey = scopeRaw?.takeUnless { it.isBlank() || it == "pinned" },
            background = decodeBackground(backgroundRaw),
        )

    fun encodeScope(scope: DisplayScope): String = "${DisplayScope.typeOf(scope)}:${scope.id}"

    fun decodeScope(scopeKey: String): DisplayScope? {
        val separator = scopeKey.indexOf(':')
        if (separator < 0) return null
        return DisplayScope.of(scopeKey.substring(0, separator), scopeKey.substring(separator + 1))
    }

    fun encode(config: Config): Map<String, String?> = mapOf(
        SCOPE_VALUE to config.scopeKey,
        BACKGROUND_VALUE to config.background.storageValue,
    )

    const val SCOPE_VALUE = "scope"
    const val BACKGROUND_VALUE = "background"
}
