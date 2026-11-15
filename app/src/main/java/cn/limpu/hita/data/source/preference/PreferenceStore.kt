package cn.limpu.hita.data.source.preference

/** Minimal durable key-value surface used by reminder and search preferences. */
interface PreferenceStore {
    fun getString(key: String): String?
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun edit(updates: Map<String, Any?>, removals: Set<String> = emptySet()): Boolean
}
