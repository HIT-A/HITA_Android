package com.limpu.hitax.data.source.web

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.limpu.component.data.DataState
import com.limpu.hitax.BuildConfig
import com.limpu.hitax.data.model.resource.ExternalCourseItem
import com.limpu.hitax.data.model.resource.ExternalResourceEntry
import com.limpu.hitax.data.model.resource.ResourceSource
import com.limpu.hitax.utils.LogUtils
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup

object FireworksWebSource {
    private const val SITE_URL = "https://fireworks.jwyihao.top"
    private const val REPO = "HIT-Fireworks/fireworks-notes-society"
    private const val API_BASE = "https://api.github.com/repos/$REPO"
    private const val TIMEOUT = 30000

    @Volatile
    private var courseCache: List<Pair<String, String>>? = null // (courseName, path)

    @Volatile
    private var cacheTimestamp: Long = 0L

    private const val CACHE_TTL_MS = 3600_000L

    private fun withHeaders(req: Connection): Connection {
        req.ignoreContentType(true)
            .ignoreHttpErrors(true)
            .timeout(TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "HITA_L/${BuildConfig.VERSION_NAME}")
        return req
    }

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

    fun listDirectory(path: String): LiveData<DataState<List<ExternalResourceEntry>>> {
        val result = MutableLiveData<DataState<List<ExternalResourceEntry>>>()
        Thread {
            try {
                val url = "$API_BASE/contents/${encodePath(path)}"
                val response = withHeaders(Jsoup.connect(url))
                    .method(Connection.Method.GET)
                    .execute()

                if (response.statusCode() >= 400) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "HTTP ${response.statusCode()}"))
                    return@Thread
                }

                val arr = JSONArray(response.body())
                val entries = mutableListOf<ExternalResourceEntry>()

                // Parse index.md to find OList path for file server link
                var olistPath: String? = null

                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "")

                    if (name == "index.md") {
                        // Fetch index.md content to extract OList path
                        olistPath = fetchOListPath(obj.optString("download_url", ""))
                        continue
                    }

                    entries.add(
                        ExternalResourceEntry(
                            name = name,
                            isDir = obj.optString("type") == "dir",
                            path = obj.optString("path", ""),
                            size = obj.optLong("size", 0),
                            downloadUrl = obj.optString("download_url", ""),
                            source = ResourceSource.FIREWORKS,
                        )
                    )
                }

                // Add a link to the website course page where files are browsable
                val encodedPath = path.split("/").joinToString("/") { segment ->
                    java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                }
                val websiteUrl = "$SITE_URL/lessons/$encodedPath/"
                entries.add(0,
                    ExternalResourceEntry(
                        name = "在薪火笔记社查看资料",
                        isDir = false,
                        path = "",
                        size = 0,
                        downloadUrl = websiteUrl,
                        source = ResourceSource.FIREWORKS,
                    )
                )

                entries.sortWith(compareByDescending<ExternalResourceEntry> { it.isDir }.thenBy { it.name })
                result.postValue(DataState(entries, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                LogUtils.e("Fireworks listDirectory failed: ${e.message}")
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return result
    }

    private fun fetchOListPath(downloadUrl: String): String? {
        if (downloadUrl.isBlank()) return null
        return try {
            val response = Jsoup.connect(downloadUrl)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .timeout(TIMEOUT)
                .header("User-Agent", "HITA_L/${BuildConfig.VERSION_NAME}")
                .method(Connection.Method.GET)
                .execute()
            val content = response.body()
            val match = Regex("""OList\s+path="([^"]+)"""").find(content)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            LogUtils.w("Fireworks: failed to fetch OList path: ${e.message}")
            null
        }
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
            .method(Connection.Method.GET)
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
     * Format: "{\"category_course_index.md\":\"hash\",...}"
     * Course name = last segment of path (without _index.md)
     */
    private fun parseCoursesFromHashMap(html: String): List<Pair<String, String>> {
        val regex = """\\\"([^"\\]+_index\.md)\\\"""".toRegex()
        val skip = setOf("index.md", "lessons_index.md", "parts_wip.md", "team.md", "README.md")
        val courses = mutableListOf<Pair<String, String>>()

        val match = regex.findAll(html)
        for (m in match) {
            val key = m.groupValues[1]
            if (key in skip) continue
            val rawPath = key.replace("_index.md", "")
            val parts = rawPath.split("_")
            // Path format: category_subcategory_course -> last part is course name
            val path = parts.joinToString("/")
            val courseName = parts.lastOrNull() ?: continue
            if (courseName.isNotBlank()) {
                courses.add(Pair(courseName, path))
            }
        }
        return courses
    }

    private fun encodePath(path: String): String {
        return path.split("/").joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }
}
