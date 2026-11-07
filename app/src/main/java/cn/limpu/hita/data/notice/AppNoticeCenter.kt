package cn.limpu.hita.data.notice

import android.content.Context
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.R
import cn.limpu.hita.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 公告中心（Android 端）。
 *
 * 协议见 docs/unified-protocol.md §4：
 * GET {AGENT_BACKEND_BASE_URL}/api/notices?app_id=...&app_version=...&build=...
 * kind: version(版本提醒) / service(服务公告) / incident(故障通知)
 * severity: critical(全屏阻断) / info(列表展示)
 */
data class AppNotice(
    val id: String,
    val kind: String,
    val severity: String,
    val title: String,
    val body: String,
    val minAppVersion: Long?,
    val affectedMinVersion: Long?,
    val startsAt: Long?,
    val endsAt: Long?,
) {
    val isCritical: Boolean get() = severity == "critical"
    val isVersionKind: Boolean get() = kind == "version"
}

object AppNoticeCenter {

    private const val TAG = "AppNoticeCenter"
    private const val CACHE_KEY = "cached_notices_v1"
    private const val PREFS_NAME = "app_notice"
    private const val SEEN_KEY = "seen_notice_ids_v1"

    /** 本地内置公告 id（用户群）。换新公告时改 id，打开应用会再弹一次。 */
    const val LOCAL_GROUP_NOTICE_ID = "local_qq_group_1093659013"

    /** 待办、资讯、助手默认收起的说明。换文案时改 id，已读用户才会再看到一次。 */
    const val LOCAL_OPTIONAL_TABS_NOTICE_ID = "local_optional_tabs_folded_v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 异步拉取公告并缓存；回调在主线程执行（UI 操作安全）。
     *
     * 回调收到的是「本地内置 + 远程缓存」合并后的生效公告（[mergedActiveNotices]），
     * 调用方不要再过一遍 [activeNotices]，否则本地公告会在契约变化时丢失。
     */
    fun fetch(context: Context, onResult: (List<AppNotice>) -> Unit = {}) {
        val appContext = context.applicationContext
        scope.launch {
            val fetched = try {
                requestNotices(appContext)
            } catch (e: Exception) {
                LogUtils.d("$TAG: fetch failed ${e.message}")
                null
            }
            if (fetched != null) {
                cacheNotices(appContext, fetched)
            }
            val merged = mergedActiveNotices(appContext)
            withContext(Dispatchers.Main) {
                onResult(merged)
            }
        }
    }

    fun cachedNotices(context: Context): List<AppNotice> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(CACHE_KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                parseNotice(array.getJSONObject(i))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 命中当前时间窗且版本范围的公告。 */
    fun activeNotices(notices: List<AppNotice>, now: Long = System.currentTimeMillis()): List<AppNotice> {
        val currentVersion = BuildConfig.VERSION_CODE.toLong()
        return notices.filter { notice ->
            val started = notice.startsAt?.let { now >= it } ?: true
            val ended = notice.endsAt?.let { now <= it } ?: true
            val affected = notice.affectedMinVersion?.let { currentVersion >= it } ?: true
            started && ended && affected
        }.sortedBy { if (it.isCritical) 0 else 1 }
    }

    /**
     * 本地内置公告（随包发布，不依赖后端）。
     * 未读时和其它普通公告一起，在打开应用时弹一次。
     */
    fun localNotices(context: Context): List<AppNotice> = listOf(
        AppNotice(
            id = LOCAL_OPTIONAL_TABS_NOTICE_ID,
            kind = "service",
            severity = "info",
            title = context.getString(R.string.notice_optional_tabs_title),
            body = context.getString(R.string.notice_optional_tabs_body),
            minAppVersion = null,
            affectedMinVersion = null,
            startsAt = null,
            endsAt = null
        ),
        AppNotice(
            id = LOCAL_GROUP_NOTICE_ID,
            kind = "group",
            severity = "info",
            title = context.getString(R.string.notice_local_group_title),
            body = context.getString(R.string.notice_local_group_body),
            minAppVersion = null,
            affectedMinVersion = null,
            startsAt = null,
            endsAt = null
        )
    )

    /** 本地公告 + 生效的远程公告（本地在前，按 id 去重）。 */
    fun mergedActiveNotices(context: Context, now: Long = System.currentTimeMillis()): List<AppNotice> {
        val remote = activeNotices(cachedNotices(context), now)
        return (localNotices(context) + remote).distinctBy { it.id }
    }

    /** 已读公告 id。弹窗关闭或打开公告列表后写入，同一 id 不再弹。 */
    fun seenNoticeIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(SEEN_KEY, emptySet()).orEmpty()

    /** 标记公告为已读。 */
    fun markNoticesSeen(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val merged = prefs.getStringSet(SEEN_KEY, emptySet()).orEmpty() + ids
        prefs.edit().putStringSet(SEEN_KEY, merged).apply()
    }

    /**
     * 打开应用时合并弹出的未读公告。
     * critical，以及确实要求升级的 version，各自有对话框，不进这个列表。
     */
    fun noticesToPopup(
        notices: List<AppNotice>,
        seenIds: Set<String>,
        currentVersionCode: Long,
    ): List<AppNotice> = notices.filter { notice ->
        notice.id !in seenIds &&
            !notice.isCritical &&
            !notice.requiresVersionDialog(currentVersionCode)
    }

    private fun AppNotice.requiresVersionDialog(currentVersionCode: Long): Boolean =
        isVersionKind && minAppVersion != null && minAppVersion > currentVersionCode

    private fun requestNotices(context: Context): List<AppNotice>? {
        val endpoint = BuildConfig.AGENT_BACKEND_BASE_URL.trimEnd('/') +
            "/api/notices?app_id=hita-android" +
            "&app_version=${BuildConfig.VERSION_NAME}" +
            "&build=${BuildConfig.VERSION_CODE}"
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .header("User-Agent", "HITA-Android/${BuildConfig.VERSION_NAME}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code !in 200..299) return null
            val body = response.body?.string() ?: return null
            val root = JSONObject(body)
            val array = root.optJSONArray("notices") ?: JSONArray()
            return (0 until array.length()).mapNotNull { i ->
                parseNotice(array.getJSONObject(i))
            }
        }
    }

    private fun parseNotice(obj: JSONObject): AppNotice? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        return AppNotice(
            id = id,
            kind = obj.optString("kind", "service"),
            severity = obj.optString("severity", "info"),
            title = obj.optString("title", "公告"),
            body = obj.optString("body", ""),
            minAppVersion = obj.optLong("min_app_version", -1L).takeIf { it >= 0 },
            affectedMinVersion = obj.optLong("affected_min_version", -1L).takeIf { it >= 0 },
            startsAt = obj.optLong("starts_at", -1L).takeIf { it >= 0 },
            endsAt = obj.optLong("ends_at", -1L).takeIf { it >= 0 },
        )
    }

    private fun cacheNotices(context: Context, notices: List<AppNotice>) {
        val array = JSONArray()
        notices.forEach { notice ->
            array.put(
                JSONObject()
                    .put("id", notice.id)
                    .put("kind", notice.kind)
                    .put("severity", notice.severity)
                    .put("title", notice.title)
                    .put("body", notice.body)
                    .put("min_app_version", notice.minAppVersion ?: JSONObject.NULL)
                    .put("affected_min_version", notice.affectedMinVersion ?: JSONObject.NULL)
                    .put("starts_at", notice.startsAt ?: JSONObject.NULL)
                    .put("ends_at", notice.endsAt ?: JSONObject.NULL)
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(CACHE_KEY, array.toString()).apply()
    }
}
