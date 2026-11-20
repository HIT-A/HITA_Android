package cn.limpu.hita.data.source.preference

import android.content.Context
import android.content.SharedPreferences
import cn.limpu.hita.data.work.CourseReminderRules
import cn.limpu.hita.data.work.ReminderRuleSet

/**
 * 课程与考试提醒设置存储。
 *
 * 旧的 enabled / minutes 仍然保留：首次读取 rules_v1 缺失时，用 minutes 生成单档并写回。
 */
class CourseReminderStore(private val context: Context) {
    private val preference: SharedPreferences
        get() = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    private val rules = CourseReminderRules(SharedPreferenceStore { preference })

    fun isEnabled(): Boolean = preference.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        preference.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 返回当前规则的最早一档，供旧调用方继续读取单一提前量。 */
    fun getReminderMinutes(): Int = rules().leads.first()

    /** 把多档规则收敛为这一档，同时回写旧 minutes。 */
    fun setReminderMinutes(minutes: Int) {
        saveRules(rules().copy(leads = listOf(minutes)))
    }

    fun rules(): ReminderRuleSet = rules.rules()

    fun saveRules(rules: ReminderRuleSet): Boolean = this.rules.saveRules(rules)

    companion object {
        const val SP_NAME = "course_reminder"
        private const val KEY_ENABLED = "enabled"
    }
}

internal class SharedPreferenceStore(
    private val preferences: () -> SharedPreferences,
) : PreferenceStore {
    override fun getString(key: String): String? = preferences().getString(key, null)

    override fun getInt(key: String, default: Int): Int = preferences().getInt(key, default)

    override fun putInt(key: String, value: Int) {
        preferences().edit().putInt(key, value).apply()
    }

    override fun edit(updates: Map<String, Any?>, removals: Set<String>): Boolean {
        val editor = preferences().edit()
        removals.forEach(editor::remove)
        updates.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                else -> error("Unsupported preference value for $key")
            }
        }
        return editor.commit()
    }
}
