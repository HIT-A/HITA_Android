package cn.limpu.hita.data.source.preference

import android.content.Context
import android.content.SharedPreferences
import cn.limpu.hita.data.model.campus.LabCourseProgress
import cn.limpu.hita.data.model.eas.EASToken
import com.google.gson.Gson

private const val SP_NAME = "lab_center_cache"
private const val KEY_OWNER = "owner"
private const val KEY_PAYLOAD = "payload"

/**
 * 实验中心课表的本地快照。
 *
 * 整学期课表实测要 10 秒以上，进页面先用这份快照渲染，再后台刷新；刷新失败时快照继续可看，
 * 页面只加一条「数据是旧的」提示。只保留最近一次成功的结果，写入即覆盖。
 *
 * 快照按教务账号隔离（[ownerKey]）：同一台设备换账号后不能读到上一个人的实验课表。
 */
class LabCenterCacheStore(context: Context, private val gson: Gson = Gson()) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /** @return 该账号上一次成功拉取的结果；没有或属于别的账号时返回 null */
    @Synchronized
    fun read(ownerKey: String): LabCourseProgress? {
        if (preferences.getString(KEY_OWNER, null) != ownerKey) return null
        val raw = preferences.getString(KEY_PAYLOAD, null) ?: return null
        return runCatching { gson.fromJson(raw, LabCourseProgress::class.java) }.getOrNull()
    }

    @Synchronized
    fun save(ownerKey: String, progress: LabCourseProgress) {
        preferences.edit()
            .putString(KEY_OWNER, ownerKey)
            .putString(KEY_PAYLOAD, gson.toJson(progress))
            .apply()
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        /**
         * 缓存归属：优先学号，其次学生 id、登录名；都没有时退化成 `anonymous`。
         *
         * 未登录时 [EASToken.campus] 仍有默认值，所以 key 里必须先带校区，再带身份。
         */
        fun ownerKey(token: EASToken): String {
            val identity = token.stuId?.trim()?.takeIf { it.isNotEmpty() }
                ?: token.id?.trim()?.takeIf { it.isNotEmpty() }
                ?: token.username?.trim()?.takeIf { it.isNotEmpty() }
                ?: "anonymous"
            return "${token.campus.name}:$identity"
        }
    }
}
