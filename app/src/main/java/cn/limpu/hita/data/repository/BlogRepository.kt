package cn.limpu.hita.data.repository

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.blog.BlogArticle
import cn.limpu.hita.data.source.preference.BlogPreferenceSource
import cn.limpu.hita.data.source.web.blog.BlogFeedParser
import cn.limpu.hita.utils.LogUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Blog feed storage.
 *
 * Every operation runs on [scope]: a single-thread executor was replaced because retry backoff
 * used `Thread.sleep`, which parked the only worker for 4.5 s and queued user-initiated article
 * reads behind a feed download that may last 150 s.
 */
@Singleton
class BlogRepository @Inject constructor(application: Application) {
    private val appContext = application.applicationContext
    private val dao = AppDatabase.getDatabase(application).blogArticleDao()
    private val prefs = BlogPreferenceSource(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val _tabUnseenLiveData = MutableLiveData(false)
    val tabUnseenLiveData: LiveData<Boolean> = _tabUnseenLiveData

    private val _unreadGuidsLiveData = MutableLiveData<Set<String>>(emptySet())
    val unreadGuidsLiveData: LiveData<Set<String>> = _unreadGuidsLiveData

    private val _refreshingLiveData = MutableLiveData(false)
    val refreshingLiveData: LiveData<Boolean> = _refreshingLiveData

    private val _syncErrorLiveData = MutableLiveData<String?>(null)
    val syncErrorLiveData: LiveData<String?> = _syncErrorLiveData

    @Volatile
    private var articleCache: List<BlogArticle> = emptyList()

    /** Only one refresh may run: a newer request supersedes the older one, like `REPLACE` work. */
    private var refreshJob: Job? = null

    init {
        scope.launch {
            try {
                articleCache = dao.getAll()
                publishUnseenLocked()
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                LogUtils.e("blog cache load failed", e)
                _syncErrorLiveData.postValue(e.message ?: "sync failed")
            }
        }
    }

    fun observeArticles(): LiveData<List<BlogArticle>> = dao.observeAll()

    fun getArticle(guid: String): BlogArticle? {
        articleCache.firstOrNull { it.guid == guid }?.let { return it }
        return null
    }

    fun loadArticle(guid: String, callback: (BlogArticle?) -> Unit) {
        scope.launch {
            val article = articleCache.firstOrNull { it.guid == guid } ?: dao.getByGuid(guid)
            callback(article)
        }
    }

    fun findByLink(link: String): BlogArticle? {
        val trimmed = link.trim()
        if (trimmed.isBlank()) return null
        val normalized = normalizeBlogLink(trimmed)
        val path = BlogFeedParser.pathFromLink(normalized ?: trimmed)
        return articleCache.firstOrNull { article ->
            article.guid == trimmed ||
                article.link == trimmed ||
                (normalized != null && (article.guid == normalized || article.link == normalized)) ||
                (path.isNotBlank() && article.path == path)
        }
    }

    fun syncOnAppOpen() {
        refresh(force = false)
    }

    fun refresh(force: Boolean) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            _refreshingLiveData.postValue(true)
            try {
                val xml = downloadFeed()
                val feed = BlogFeedParser.parse(xml)
                if (feed.articles.isEmpty()) {
                    throw IllegalStateException("empty feed")
                }
                val unchanged = !force &&
                    prefs.hasBaseline &&
                    prefs.lastBuildDate.isNotBlank() &&
                    prefs.lastBuildDate == feed.lastBuildDate &&
                    dao.count() > 0
                if (!unchanged) {
                    dao.replaceAll(feed.articles)
                    prefs.lastBuildDate = feed.lastBuildDate
                    val guids = feed.articles.map { it.guid }.toSet()
                    if (!prefs.hasBaseline) {
                        prefs.replaceReadGuids(guids)
                        prefs.replaceTabClearedGuids(guids)
                        prefs.hasBaseline = true
                    }
                }
                articleCache = dao.getAll()
                _syncErrorLiveData.postValue(null)
                publishUnseenLocked()
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                LogUtils.e("blog sync failed: ${e.message}", e)
                _syncErrorLiveData.postValue(e.message ?: "sync failed")
                publishUnseenLocked()
            } finally {
                _refreshingLiveData.postValue(false)
            }
        }
    }

    fun markTabOpened() {
        scope.launch {
            prefs.replaceTabClearedGuids(dao.getAllGuids().toSet())
            publishUnseenLocked()
        }
    }

    fun markArticleRead(guid: String) {
        if (guid.isBlank()) return
        scope.launch {
            prefs.addReadGuid(guid)
            publishUnseenLocked()
        }
    }

    private suspend fun publishUnseenLocked() {
        val current = dao.getAllGuids().toSet()
        val read = prefs.readGuids()
        val tabCleared = prefs.tabClearedGuids()
        _unreadGuidsLiveData.postValue(current - read)
        _tabUnseenLiveData.postValue(current.any { it !in tabCleared })
    }

    /**
     * Three attempts with cancellable backoff. `delay` releases the IO dispatcher thread, so a
     * superseded or abandoned refresh never holds a worker, and refresh cancellation is prompt.
     */
    private suspend fun downloadFeed(): String = retryWithBackoff(
        attempts = 3,
        backoffMillis = RETRY_BACKOFF_MILLIS,
        onAttemptFailed = { attempt, error ->
            LogUtils.e("blog download attempt ${attempt + 1} failed: ${error.message}", error)
        },
    ) { fetchFeedOnce() }

    private suspend fun fetchFeedOnce(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BlogFeedParser.FEED_URL)
            .get()
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 HITA/${BuildConfig.VERSION_NAME}",
            )
            .header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code !in 200..299) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("empty body")
            if (!body.contains("<item")) {
                throw IllegalStateException("rss has no items")
            }
            return@use body
        }
    }

    companion object {
        /** Matches the previous blocking backoff (1.5 s then 3 s) without parking a thread. */
        private const val RETRY_BACKOFF_MILLIS = 1500L

        /**
         * Retries [block] with cancellable backoff. Suspending instead of `Thread.sleep` means an
         * abandoned refresh releases its dispatcher thread during the wait, and a cancelled
         * coroutine stops immediately without performing the next attempt.
         */
        internal suspend fun <T> retryWithBackoff(
            attempts: Int,
            backoffMillis: Long,
            onAttemptFailed: (Int, Exception) -> Unit = { _, _ -> },
            block: suspend () -> T,
        ): T {
            var lastError: Exception? = null
            repeat(attempts) { attempt ->
                try {
                    return block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    lastError = error
                    onAttemptFailed(attempt, error)
                    if (attempt < attempts - 1) delay(backoffMillis * (attempt + 1))
                }
            }
            throw lastError ?: IllegalStateException("rss download failed")
        }

        fun normalizeBlogLink(raw: String): String? {
            val trimmed = raw.trim().substringBefore('#')
            if (trimmed.isBlank()) return null
            val relativePath = when {
                trimmed.startsWith("/blog/") -> trimmed
                trimmed.startsWith("blog/") -> "/$trimmed"
                else -> null
            }
            if (relativePath != null) {
                return "${BlogFeedParser.SITE_ORIGIN}${relativePath.trimEnd('/')}"
            }
            val uri = try {
                java.net.URI(trimmed)
            } catch (_: Exception) {
                return null
            }
            val host = uri.host?.removePrefix("www.") ?: return null
            if (host != "hoa.moe") return null
            val path = uri.path.orEmpty().trimEnd('/')
            if (!path.startsWith("/blog/")) return null
            return "${BlogFeedParser.SITE_ORIGIN}$path"
        }
    }
}
