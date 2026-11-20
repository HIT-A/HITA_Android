package cn.limpu.hita.data.work

import cn.limpu.hita.data.source.preference.PreferenceStore

class MemoryPreferences : PreferenceStore {
    private val values = linkedMapOf<String, Any?>()

    override fun getString(key: String): String? = values[key] as String?

    override fun getInt(key: String, default: Int): Int = values[key] as Int? ?: default

    override fun putInt(key: String, value: Int) {
        values[key] = value
    }

    override fun edit(updates: Map<String, Any?>, removals: Set<String>): Boolean {
        removals.forEach(values::remove)
        updates.forEach { (key, value) ->
            if (value == null) values.remove(key) else values[key] = value
        }
        return true
    }
}
