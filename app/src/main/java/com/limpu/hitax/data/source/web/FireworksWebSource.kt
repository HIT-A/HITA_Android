package com.limpu.hitax.data.source.web

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.limpu.component.data.DataState
import com.limpu.hitax.BuildConfig
import com.limpu.hitax.data.model.resource.ExternalCourseItem
import com.limpu.hitax.data.model.resource.ExternalResourceEntry
import com.limpu.hitax.data.model.resource.ResourceSource
import com.limpu.hitax.utils.LogUtils
import org.jsoup.Jsoup

object FireworksWebSource {
    private const val SITE_URL = "https://fireworks.jwyihao.top"
    private const val TIMEOUT = 30000

    @Volatile
    private var courseCache: List<Pair<String, String>>? = null // (courseName, path)

    @Volatile
    private var cacheTimestamp: Long = 0L

    private const val CACHE_TTL_MS = 3600_000L

    fun searchCourses(query: String): LiveData<DataState<List<ExternalCourseItem>>> {
        LogUtils.d("Fireworks: searchCourses called with query='$query'")
        val result = MutableLiveData<DataState<List<ExternalCourseItem>>>()
        Thread {
            try {
                val courses = ensureCourseCache()
                val keyword = query.trim().lowercase()
                val matched = courses.filter { (courseName, _) ->
                    courseName.lowercase().contains(keyword)
                }.map { (courseName, path) ->
                    val parts = path.split("/")
                    val category = parts.firstOrNull() ?: ""
                    ExternalCourseItem(
                        courseName = courseName,
                        category = category,
                        source = ResourceSource.FIREWORKS,
                        path = path,
                    )
                }
                LogUtils.d("Fireworks: matched ${matched.size} courses for '$query'")
                result.postValue(DataState(matched, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                LogUtils.e("Fireworks search failed: ${e.message}")
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return result
    }

    /**
     * Fireworks resources are on AList server, not GitHub.
     * Return a website link so users can browse/download on the site.
     */
    fun listDirectory(path: String): LiveData<DataState<List<ExternalResourceEntry>>> {
        val result = MutableLiveData<DataState<List<ExternalResourceEntry>>>()
        Thread {
            val encodedPath = path.split("/").joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            val websiteUrl = "$SITE_URL/$encodedPath/"
            val entries = listOf(
                ExternalResourceEntry(
                    name = "在薪火笔记社查看和下载资料",
                    isDir = false,
                    path = "",
                    size = 0,
                    downloadUrl = websiteUrl,
                    source = ResourceSource.FIREWORKS,
                )
            )
            result.postValue(DataState(entries, DataState.STATE.SUCCESS))
        }.start()
        return result
    }

    @Synchronized
    private fun ensureCourseCache(): List<Pair<String, String>> {
        val cached = courseCache
        if (cached != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return cached
        }
        LogUtils.d("Fireworks: loading course cache from website")

        val response = Jsoup.connect(SITE_URL)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .timeout(TIMEOUT)
            .header("User-Agent", "HITA_L/${BuildConfig.VERSION_NAME}")
            .method(org.jsoup.Connection.Method.GET)
            .execute()

        if (response.statusCode() >= 400) {
            throw Exception("Website returned HTTP ${response.statusCode()}")
        }

        val html = response.body()
        val courses = parseCoursesFromHashMap(html)
        LogUtils.d("Fireworks: cached ${courses.size} courses from website")
        courseCache = courses
        cacheTimestamp = System.currentTimeMillis()
        return courses
    }

    /**
     * Parse VitePress __VP_HASH_MAP__ to extract course paths.
     */
    private fun parseCoursesFromHashMap(html: String): List<Pair<String, String>> {
        val regex = """\\\"([^"\\]+_index\.md)\\\"""".toRegex()
        val skip = setOf("index.md", "lessons_index.md", "parts_wip.md", "team.md", "README.md")
        val courses = mutableListOf<Pair<String, String>>()

        for (m in regex.findAll(html)) {
            val key = m.groupValues[1]
            if (key in skip) continue
            val rawPath = key.replace("_index.md", "")
            val parts = rawPath.split("_")
            val path = parts.joinToString("/")
            val courseName = parts.lastOrNull() ?: continue
            if (courseName.isNotBlank()) {
                courses.add(Pair(courseName, path))
            }
        }
        return courses
    }
}
