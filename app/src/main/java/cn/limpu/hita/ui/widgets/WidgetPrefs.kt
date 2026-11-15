package cn.limpu.hita.ui.widgets

import android.content.Context
import android.content.SharedPreferences

/**
 * 小组件实例级配置。
 *
 * 每个 `appWidgetId` 独立存一份，互不干扰；删除组件时必须清理自己的那份，
 * 否则 id 被系统复用后会读到上一个实例的设置。
 */
class WidgetPrefs(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** @return 该实例的配置；从未配置过时返回跟随主范围的默认值。 */
    fun read(appWidgetId: Int): WidgetConfigCodec.Config = WidgetConfigCodec.decode(
        scopeRaw = preferences.getString(WidgetConfigCodec.scopeKeyField(appWidgetId), null),
        backgroundRaw = preferences.getString(WidgetConfigCodec.backgroundKeyField(appWidgetId), null),
    )

    fun write(appWidgetId: Int, config: WidgetConfigCodec.Config) {
        val values = WidgetConfigCodec.encode(config)
        preferences.edit()
            .putString(
                WidgetConfigCodec.scopeKeyField(appWidgetId),
                values[WidgetConfigCodec.SCOPE_VALUE],
            )
            .putString(
                WidgetConfigCodec.backgroundKeyField(appWidgetId),
                values[WidgetConfigCodec.BACKGROUND_VALUE],
            )
            .apply()
    }

    fun delete(appWidgetId: Int) {
        preferences.edit()
            .remove(WidgetConfigCodec.scopeKeyField(appWidgetId))
            .remove(WidgetConfigCodec.backgroundKeyField(appWidgetId))
            .apply()
    }

    companion object {
        private const val NAME = "today_widget_config"
    }
}
