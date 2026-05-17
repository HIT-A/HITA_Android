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

object HITCSWebSource {
    private const val REPO = "HITLittleZheng/HITCS"
    private const val API_BASE = "https://api.github.com/repos/$REPO"
    private const val RAW_PROXY = "https://ghproxy.net/"
    private const val TIMEOUT = 30000

    @Volatile
    private var courseCache: List<Triple<String, String, String>>? = null

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
        LogUtils.d("HITCS: searchCourses called with query='$query'")
        val result = MutableLiveData<DataState<List<ExternalCourseItem>>>()
        Thread {
            try {
                val courses = ensureCourseCache()
                val keyword = query.trim().lowercase()
                val matched = courses.filter { (_, courseName, _) ->
                    courseName.lowercase().contains(keyword)
                }.map { (category, courseName, path) ->
                    ExternalCourseItem(
                        courseName = courseName,
                        category = category,
                        source = ResourceSource.HITCS,
                        path = path,
                    )
                }
                result.postValue(DataState(matched, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                LogUtils.e("HITCS search failed: ${e.message}")
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
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val rawDownloadUrl = obj.optString("download_url", "")
                    // Wrap raw.githubusercontent.com URLs with proxy for CN mobile
                    val proxiedUrl = if (rawDownloadUrl.startsWith("https://raw.githubusercontent.com")) {
                        "$RAW_PROXY$rawDownloadUrl"
                    } else {
                        rawDownloadUrl
                    }
                    entries.add(
                        ExternalResourceEntry(
                            name = obj.optString("name", ""),
                            isDir = obj.optString("type") == "dir",
                            path = obj.optString("path", ""),
                            size = obj.optLong("size", 0),
                            downloadUrl = proxiedUrl,
                            source = ResourceSource.HITCS,
                        )
                    )
                }
                entries.sortWith(compareByDescending<ExternalResourceEntry> { it.isDir }.thenBy { it.name })
                result.postValue(DataState(entries, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                LogUtils.e("HITCS listDirectory failed: ${e.message}")
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return result
    }

    @Synchronized
    private fun ensureCourseCache(): List<Triple<String, String, String>> {
        val cached = courseCache
        if (cached != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return cached
        }
        LogUtils.d("HITCS: loading course cache from Tree API")
        val url = "$API_BASE/git/trees/main?recursive=1"
        val response = withHeaders(Jsoup.connect(url))
            .method(Connection.Method.GET)
            .execute()

        if (response.statusCode() >= 400) {
            throw Exception("GitHub Tree API returned HTTP ${response.statusCode()}")
        }

        val tree = JSONObject(response.body())
        val treeArr = tree.optJSONArray("tree") ?: JSONArray()

        val dirPaths = mutableSetOf<String>()
        for (i in 0 until treeArr.length()) {
            val item = treeArr.optJSONObject(i) ?: continue
            if (item.optString("type") != "tree") continue
            val itemPath = item.optString("path", "")
            val depth = itemPath.count { it == '/' }
            if (depth == 1) {
                dirPaths.add(itemPath)
            }
        }

        val courses = mutableListOf<Triple<String, String, String>>()
        for (dirPath in dirPaths) {
            val parts = dirPath.split("/", limit = 2)
            if (parts.size == 2) {
                val category = parts[0]
                val courseName = parts[1]
                if (!courseName.startsWith(".")) {
                    courses.add(Triple(category, courseName, dirPath))
                }
            }
        }
        LogUtils.d("HITCS: cached ${courses.size} courses")
        courseCache = courses
        cacheTimestamp = System.currentTimeMillis()
        return courses
    }

    private fun encodePath(path: String): String {
        return path.split("/").joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }
}
