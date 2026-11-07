package cn.limpu.hita.data.source.preference

import android.content.Context
import android.content.SharedPreferences
import cn.limpu.hita.ui.main.OptionalTabFeatures

/** 待办、资讯、助手是否出现在底部导航。三者独立，默认都不显示。 */
class OptionalTabStore(context: Context) {
    private val preference: SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    fun features(): OptionalTabFeatures = OptionalTabFeatures(
        homework = preference.getBoolean(KEY_HOMEWORK, false),
        info = preference.getBoolean(KEY_INFO, false),
        agent = preference.getBoolean(KEY_AGENT, false),
    )

    fun setHomework(enabled: Boolean) {
        preference.edit().putBoolean(KEY_HOMEWORK, enabled).apply()
    }

    fun setInfo(enabled: Boolean) {
        preference.edit().putBoolean(KEY_INFO, enabled).apply()
    }

    fun setAgent(enabled: Boolean) {
        preference.edit().putBoolean(KEY_AGENT, enabled).apply()
    }

    private companion object {
        const val SP_NAME = "optional_tabs"
        const val KEY_HOMEWORK = "homework"
        const val KEY_INFO = "info"
        const val KEY_AGENT = "agent"
    }
}
