package cn.limpu.hita.data.source.preference

import android.content.Context
import android.content.SharedPreferences
import cn.limpu.hita.data.model.eas.ExamItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

private const val SP_NAME = "exam_cache"
private const val KEY_ENTRIES = "entries_v1"
private const val MAX_ENTRIES = 8

/**
 * 考试列表的本地快照（只存远端拉到的考试，不含本地备忘录）。
 *
 * 进页面先用快照渲染、再后台刷新；刷新失败时快照继续可看，页面只加一条「数据是旧的」提示。
 * 按账号与学期隔离，同一台设备换账号或换学期都不会读到别人的、别的学期的考试。
 */
class ExamCacheStore(context: Context, private val gson: Gson = Gson()) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    data class Snapshot(val exams: List<ExamItem>, val cachedAtMillis: Long)

    @Synchronized
    fun read(ownerKey: String, termId: String?): Snapshot? {
        val entry = entries()[entryKey(ownerKey, termId)] ?: return null
        return Snapshot(entry.exams.filterNot(ExamItem::isMemo), entry.cachedAtMillis)
    }

    @Synchronized
    fun save(ownerKey: String, termId: String?, exams: List<ExamItem>) {
        val key = entryKey(ownerKey, termId)
        val remote = exams.filterNot(ExamItem::isMemo)
        // 空列表是一次成功的「本学期没有考试」，必须覆盖旧快照，否则下次进页面会把旧考试又显示出来。
        val next = if (remote.isEmpty()) {
            entries() - key
        } else {
            entries() + (key to Entry(remote, System.currentTimeMillis()))
        }
        persist(if (next.size <= MAX_ENTRIES) next else next.entries
            .sortedByDescending { it.value.cachedAtMillis }
            .take(MAX_ENTRIES)
            .associate { it.key to it.value })
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun entryKey(ownerKey: String, termId: String?): String =
        "$ownerKey|${termId?.trim().orEmpty().ifEmpty { "current" }}"

    private fun entries(): Map<String, Entry> {
        val raw = preferences.getString(KEY_ENTRIES, null) ?: return emptyMap()
        return runCatching { gson.fromJson<Map<String, Entry>>(raw, entriesType).orEmpty() }
            .getOrDefault(emptyMap())
    }

    private fun persist(entries: Map<String, Entry>) {
        if (entries.isEmpty()) {
            preferences.edit().remove(KEY_ENTRIES).apply()
        } else {
            preferences.edit().putString(KEY_ENTRIES, gson.toJson(entries)).apply()
        }
    }

    data class Entry(val exams: List<ExamItem>, val cachedAtMillis: Long)

    private companion object {
        val entriesType = object : TypeToken<Map<String, Entry>>() {}.type
    }
}
