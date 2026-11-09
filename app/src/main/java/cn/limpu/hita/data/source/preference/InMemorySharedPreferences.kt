package cn.limpu.hita.data.source.preference

import android.content.SharedPreferences

/**
 * 加密偏好打不开时的进程内占位。
 *
 * 契约：不把会话、密码或 API Key 写到磁盘；进程结束即丢弃。加密存储不可用时，
 * 各数据源统一退回这里，而不是退回同名的明文 SharedPreferences。
 */
internal class InMemorySharedPreferences : SharedPreferences {
    private val data = HashMap<String, Any?>()

    @Synchronized
    override fun getAll(): MutableMap<String, *> = HashMap(data)

    @Synchronized
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue

    @Synchronized
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val value = data[key] ?: return defValues
        @Suppress("UNCHECKED_CAST")
        return (value as? Set<String>)?.toMutableSet() ?: defValues
    }

    @Synchronized
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue

    @Synchronized
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue

    @Synchronized
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue

    @Synchronized
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue

    @Synchronized
    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val updates = HashMap<String, Any?>()
        private var clearAll = false

        override fun putString(key: String?, value: String?) = put(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)

        override fun putInt(key: String?, value: Int) = put(key, value)

        override fun putLong(key: String?, value: Long) = put(key, value)

        override fun putFloat(key: String?, value: Float) = put(key, value)

        override fun putBoolean(key: String?, value: Boolean) = put(key, value)

        override fun remove(key: String?) = put(key, null)

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        @Synchronized
        override fun commit(): Boolean {
            if (clearAll) data.clear()
            updates.forEach { (key, value) ->
                if (value == null) data.remove(key) else data[key] = value
            }
            return true
        }

        override fun apply() {
            commit()
        }

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) updates[key] = value
            return this
        }
    }
}
