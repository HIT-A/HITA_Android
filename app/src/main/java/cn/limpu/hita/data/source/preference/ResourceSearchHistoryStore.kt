package cn.limpu.hita.data.source.preference

import org.json.JSONArray

class ResourceSearchHistoryStore(private val preferences: PreferenceStore) {
    fun record(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        val updated = listOf(normalized) + list().filterNot { it == normalized }
        preferences.edit(mapOf(KEY_QUERIES to encode(updated.take(MAX_ENTRIES))))
    }

    fun list(): List<String> {
        val raw = preferences.getString(KEY_QUERIES) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).map { json.getString(it) }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .take(MAX_ENTRIES)
        }.getOrDefault(emptyList())
    }

    fun clear() {
        preferences.edit(emptyMap(), removals = setOf(KEY_QUERIES))
    }

    private fun encode(queries: List<String>): String = JSONArray().apply {
        queries.forEach(::put)
    }.toString()

    companion object {
        const val MAX_ENTRIES = 20
        private const val KEY_QUERIES = "queries_v1"
    }
}
