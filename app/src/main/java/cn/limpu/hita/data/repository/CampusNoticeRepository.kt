package cn.limpu.hita.data.repository

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.notice.CampusNotice
import cn.limpu.hita.data.source.preference.CampusNoticePreferenceSource
import cn.limpu.hita.data.source.web.notice.BenbuNoticeParser
import cn.limpu.hita.data.source.web.notice.CampusNoticeParser
import cn.limpu.hita.data.source.web.notice.InfoPortalLoginPolicy
import cn.limpu.hita.data.source.web.notice.WeihaiNoticeParser
import cn.limpu.hita.utils.LogUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class CampusNoticeSyncError {
    NEED_LOGIN,
    NEED_CAMPUS_NET,
    FAILED,
}

@Singleton
class CampusNoticeRepository @Inject constructor(application: Application) {
    private val dao = AppDatabase.getDatabase(application).campusNoticeDao()
    private val prefsByCampus = mapOf(
        EASToken.Campus.SHENZHEN to CampusNoticePreferenceSource(application, CampusNoticeParser.SHENZHEN_CAMPUS),
        EASToken.Campus.BENBU to CampusNoticePreferenceSource(application, BenbuNoticeParser.CAMPUS),
        EASToken.Campus.WEIHAI to CampusNoticePreferenceSource(application, WeihaiNoticeParser.CAMPUS),
    )
    private val executor = Executors.newSingleThreadExecutor()
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val _refreshing = MutableLiveData(false)
    val refreshing: LiveData<Boolean> = _refreshing

    private val _syncError = MutableLiveData<CampusNoticeSyncError?>(null)
    val syncError: LiveData<CampusNoticeSyncError?> = _syncError

    private val noticesByCampus = ConcurrentHashMap<EASToken.Campus, LiveData<List<CampusNotice>>>()

    fun observeNotices(campus: EASToken.Campus): LiveData<List<CampusNotice>> =
        noticesByCampus.getOrPut(campus) { dao.observeLatest(campus.name) }

    fun syncOnPageOpen(campus: EASToken.Campus) {
        refresh(campus, force = false)
    }

    fun refresh(campus: EASToken.Campus, force: Boolean) {
        val source = sourceFor(campus)
        val prefs = prefsByCampus.getValue(campus)
        executor.execute {
            _refreshing.postValue(true)
            var cached = emptyList<CampusNotice>()
            try {
                cached = dao.getLatest(campus.name)
                val skipNetwork = !force &&
                    !cacheIsJunk(source, cached) &&
                    prefs.isFetchedToday() &&
                    prefs.parserVersion >= source.parserVersion
                if (skipNetwork) {
                    _syncError.postValue(null)
                    return@execute
                }
                val notices = fetchLatestNotices(source)
                if (notices.isNotEmpty()) {
                    dao.replaceAll(campus.name, notices)
                    prefs.markFetchedToday(source.parserVersion)
                    _syncError.postValue(null)
                    return@execute
                }
                if (cacheIsJunk(source, cached)) {
                    dao.replaceAll(campus.name, emptyList())
                }
                if (dao.getLatest(campus.name).isNotEmpty()) {
                    _syncError.postValue(null)
                    return@execute
                }
                _syncError.postValue(
                    if (campus == EASToken.Campus.SHENZHEN && !hasPortalCookie()) CampusNoticeSyncError.NEED_LOGIN
                    else CampusNoticeSyncError.FAILED
                )
            } catch (e: CampusNoticeLoginRequired) {
                if (cacheIsJunk(source, cached)) dao.replaceAll(campus.name, emptyList())
                _syncError.postValue(
                    if (campus == EASToken.Campus.SHENZHEN) CampusNoticeSyncError.NEED_LOGIN
                    else CampusNoticeSyncError.FAILED
                )
            } catch (e: Exception) {
                LogUtils.e("campus notice sync failed: ${e.message}", e)
                if (cacheIsJunk(source, cached)) dao.replaceAll(campus.name, emptyList())
                _syncError.postValue(classify(e))
            } finally {
                _refreshing.postValue(false)
            }
        }
    }

    private interface NoticeSource {
        val parserVersion: Int
        val pagesForCap: Int
        val maxNotices: Int
        fun listPageUrl(page: Int): String
        fun parse(html: String, baseUrl: String): List<CampusNotice>
        fun isPersistedNotice(notice: CampusNotice): Boolean
    }

    private fun sourceFor(campus: EASToken.Campus): NoticeSource = when (campus) {
        EASToken.Campus.SHENZHEN -> object : NoticeSource {
            override val parserVersion = CampusNoticeParser.PARSER_VERSION
            override val pagesForCap = CampusNoticeParser.PAGES_FOR_CAP
            override val maxNotices = CampusNoticeParser.MAX_NOTICES
            override fun listPageUrl(page: Int) = CampusNoticeParser.listPageUrl(page)
            override fun parse(html: String, baseUrl: String) = CampusNoticeParser.parse(html, baseUrl)
            override fun isPersistedNotice(notice: CampusNotice) = CampusNoticeParser.isPersistedNotice(notice)
        }
        EASToken.Campus.BENBU -> object : NoticeSource {
            override val parserVersion = BenbuNoticeParser.PARSER_VERSION
            override val pagesForCap = BenbuNoticeParser.PAGES_FOR_CAP
            override val maxNotices = BenbuNoticeParser.MAX_NOTICES
            override fun listPageUrl(page: Int) = BenbuNoticeParser.listPageUrl(page)
            override fun parse(html: String, baseUrl: String) = BenbuNoticeParser.parse(html, baseUrl)
            override fun isPersistedNotice(notice: CampusNotice) = BenbuNoticeParser.isPersistedNotice(notice)
        }
        EASToken.Campus.WEIHAI -> object : NoticeSource {
            override val parserVersion = WeihaiNoticeParser.PARSER_VERSION
            override val pagesForCap = WeihaiNoticeParser.PAGES_FOR_CAP
            override val maxNotices = WeihaiNoticeParser.MAX_NOTICES
            override fun listPageUrl(page: Int) = WeihaiNoticeParser.listPageUrl(page)
            override fun parse(html: String, baseUrl: String) = WeihaiNoticeParser.parse(html, baseUrl)
            override fun isPersistedNotice(notice: CampusNotice) = WeihaiNoticeParser.isPersistedNotice(notice)
        }
    }

    private fun cacheIsJunk(source: NoticeSource, cached: List<CampusNotice>): Boolean {
        if (cached.isEmpty()) return true
        return cached.none { source.isPersistedNotice(it) }
    }

    private fun fetchLatestNotices(source: NoticeSource): List<CampusNotice> {
        val byId = LinkedHashMap<String, CampusNotice>()
        for (page in 1..source.pagesForCap) {
            val url = source.listPageUrl(page)
            val pageNotices = source.parse(download(url), url)
            if (pageNotices.isEmpty()) break
            for (notice in pageNotices) {
                if (notice.id !in byId) byId[notice.id] = notice
            }
            if (byId.size >= source.maxNotices) break
        }
        return byId.values
            .sortedWith(compareByDescending<CampusNotice> { it.pubDateMillis }.thenBy { it.title })
            .take(source.maxNotices)
    }

    private fun classify(error: Exception): CampusNoticeSyncError {
        return when (error) {
            is UnknownHostException, is SocketTimeoutException -> CampusNoticeSyncError.NEED_CAMPUS_NET
            is IOException -> CampusNoticeSyncError.FAILED
            else -> CampusNoticeSyncError.FAILED
        }
    }

    private fun hasPortalCookie(): Boolean =
        InfoPortalLoginPolicy.hasPortalSession(portalCookies())

    /** 门户域上的 cookie 表；scheme 取实际使用的 PORTAL_URL（http）。 */
    private fun portalCookies(): Map<String, String> {
        val header = portalCookieHeader(CampusNoticeParser.PORTAL_URL)
        if (header.isBlank()) return emptyMap()
        return header.split(';').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null
            else part.substring(0, index).trim() to part.substring(index + 1).trim()
        }.toMap()
    }

    private fun portalCookieHeader(url: String): String {
        return runCatching {
            CookieManager.getInstance().getCookie(url).orEmpty()
        }.getOrDefault("")
    }

    private fun download(url: String): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 HITA/${BuildConfig.VERSION_NAME}",
            )
            .header("Accept", "text/html,application/xhtml+xml")
            .apply {
                val cookie = portalCookieHeader(url)
                if (cookie.isNotBlank() && InfoPortalLoginPolicy.isPortalPage(url)) {
                    header("Cookie", cookie)
                }
            }
            .build()
        httpClient.newCall(request).execute().use { response ->
            val finalUrl = response.request.url.toString()
            if (InfoPortalLoginPolicy.isLoginTransitPage(finalUrl)) {
                throw CampusNoticeLoginRequired()
            }
            if (response.code in 401..403) {
                throw CampusNoticeLoginRequired()
            }
            if (response.code !in 200..299) {
                throw IOException("HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IOException("empty body")
        }
    }
    private class CampusNoticeLoginRequired : Exception("login required")
}
