package com.limpu.hitax.data.source.web

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.limpu.component.data.DataState
import com.limpu.hitax.BuildConfig
import com.limpu.hitax.data.model.resource.CourseReadmeData
import com.limpu.hitax.data.model.resource.CourseResourceItem
import com.limpu.hitax.data.model.resource.CourseSectionSummary
import com.limpu.hitax.data.model.resource.CourseStructureSummary
import com.limpu.hitax.data.model.resource.CourseSummary
import com.limpu.hitax.data.model.resource.ValidateReadmeResult
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.util.UUID
import com.limpu.hitax.utils.LogUtils

object HoaResourceSource {
    private val baseUrl = BuildConfig.HOA_BASE_URL.removeSuffix("/")
    private val apiKey = BuildConfig.HOA_API_KEY
    private const val timeout = 15000

    private fun withHeaders(req: Connection): Connection {
        req.ignoreContentType(true)
            .ignoreHttpErrors(true)
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("User-Agent", "HITA_L/${BuildConfig.VERSION_NAME}")
        if (apiKey.isNotBlank()) {
            req.header("X-Api-Key", apiKey)
        }
        return req
    }

    /**
     * Extract campus from repo_name prefix.
     * repo_name format: "campus/COURSE_CODE" or "campus/repo_name"
     * Campus values: shenzhen, harbin, weihai
     */
    private fun extractCampus(repoName: String): String {
        val parts = repoName.split("/")
        return if (parts.isNotEmpty()) {
            val campus = parts[0].lowercase()
            normalizeHoaCampus(campus) ?: "shenzhen"
        } else {
            "shenzhen"
        }
    }

    private fun normalizeHoaCampus(raw: String?): String? {
        return when (raw?.trim()?.lowercase()) {
            "shenzhen", "sz" -> "shenzhen"
            "harbin", "benbu", "hb" -> "harbin"
            "weihai", "wh" -> "weihai"
            else -> null
        }
    }

    /**
     * Extract course_code from repo_name.
     * repo_name format: "campus/COURSE_CODE" or "campus/repo_name"
     */
    private fun extractCourseCode(repoName: String): String {
        val parts = repoName.split("/")
        return if (parts.size >= 2) {
            parts[1].uppercase()
        } else {
            repoName.uppercase()
        }
    }

    fun searchCourses(query: String, campus: String? = null): LiveData<DataState<List<CourseResourceItem>>> {
        val result = MutableLiveData<DataState<List<CourseResourceItem>>>()
        Thread {
            try {
                val requestBody = JSONObject()
                requestBody.put("keyword", query.trim())
                requestBody.put("campus", normalizeHoaCampus(campus).orEmpty())

                val response = withHeaders(Jsoup.connect("$baseUrl/v1/courses:search"))
                    .header("Content-Type", "application/json")
                    .requestBody(requestBody.toString())
                    .method(Connection.Method.POST)
                    .execute()

                if (response.statusCode() >= 400) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, response.body()))
                    return@Thread
                }

                val resObj = JSONObject(response.body())
                if (!resObj.optBoolean("ok", false)) {
                    val error = resObj.optJSONObject("error")
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, error?.optString("message", "Unknown error")))
                    return@Thread
                }

                val data = resObj.optJSONObject("data")
                val resultsArr = data?.optJSONArray("results") ?: JSONArray()
                val items = mutableListOf<CourseResourceItem>()

                for (index in 0 until resultsArr.length()) {
                    val obj = resultsArr.optJSONObject(index) ?: continue
                    val org = obj.optString("org")
                    val repo = obj.optString("repo")
                    val repoName = if (org.isNotBlank() && repo.isNotBlank()) {
                        "$org/$repo"
                    } else {
                        repo
                    }

                    // DEBUG: Log the raw object
                    LogUtils.d("Search result item: ${obj.toString()}")

                    items.add(
                        CourseResourceItem(
                            repoName = repoName,
                            courseCode = obj.optString("code"),
                            courseName = obj.optString("name"),
                            repoType = obj.optString("repo_type", "normal"),
                            campus = obj.optString("campus"),
                            teachers = jsonArrayToList(obj.optJSONArray("teachers")),
                            aliases = jsonArrayToList(obj.optJSONArray("aliases")),
                        )
                    )
                }
                result.postValue(DataState(items, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return result
    }

    /**
     * 直接 callback 版本（无 LiveData，适合非 UI 线程调用如 Agent Tool）
     */
    fun searchCoursesDirect(
        query: String,
        campus: String? = null,
        onResult: (DataState<List<CourseResourceItem>>) -> Unit,
    ) {
        Thread {
            try {
                val requestBody = JSONObject()
                requestBody.put("keyword", query.trim())
                requestBody.put("campus", normalizeHoaCampus(campus).orEmpty())

                LogUtils.d("HoaResourceSource searchCoursesDirect start: query=$query")
                val response = withHeaders(Jsoup.connect("$baseUrl/v1/courses:search"))
                    .header("Content-Type", "application/json")
                    .requestBody(requestBody.toString())
                    .method(Connection.Method.POST)
                    .execute()
                LogUtils.d("HoaResourceSource searchCoursesDirect response: status=${response.statusCode()}")

                if (response.statusCode() >= 400) {
                    onResult(DataState(DataState.STATE.FETCH_FAILED, response.body()))
                    return@Thread
                }

                val resObj = JSONObject(response.body())
                if (!resObj.optBoolean("ok", false)) {
                    val error = resObj.optJSONObject("error")
                    onResult(DataState(DataState.STATE.FETCH_FAILED, error?.optString("message", "Unknown error")))
                    return@Thread
                }

                val data = resObj.optJSONObject("data")
                val resultsArr = data?.optJSONArray("results") ?: JSONArray()
                val items = mutableListOf<CourseResourceItem>()

                for (index in 0 until resultsArr.length()) {
                    val obj = resultsArr.optJSONObject(index) ?: continue
                    val org = obj.optString("org")
                    val repo = obj.optString("repo")
                    val repoName = if (org.isNotBlank() && repo.isNotBlank()) {
                        "$org/$repo"
                    } else {
                        repo
                    }

                    items.add(
                        CourseResourceItem(
                            repoName = repoName,
                            courseCode = obj.optString("code"),
                            courseName = obj.optString("name"),
                            repoType = obj.optString("repo_type", "normal"),
                            campus = obj.optString("campus"),
                            teachers = jsonArrayToList(obj.optJSONArray("teachers")),
                            aliases = jsonArrayToList(obj.optJSONArray("aliases")),
                        )
                    )
                }
                LogUtils.d("HoaResourceSource searchCoursesDirect success: count=${items.size}")
                onResult(DataState(items, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                LogUtils.e("HoaResourceSource searchCoursesDirect error", e)
                onResult(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
    }

    fun getCourseReadme(repoName: String, campus: String? = null): LiveData<DataState<CourseReadmeData>> {
        val result = MutableLiveData<DataState<CourseReadmeData>>()
        Thread {
            try {
                val resolvedCampus = normalizeHoaCampus(campus) ?: extractCampus(repoName)
                val courseCode = extractCourseCode(repoName)

                val requestBody = JSONObject()
                val target = JSONObject()
                target.put("campus", resolvedCampus)
                target.put("course_code", courseCode)
                requestBody.put("target", target)

                val response = withHeaders(Jsoup.connect("$baseUrl/v1/course:read"))
                    .header("Content-Type", "application/json")
                    .requestBody(requestBody.toString())
                    .method(Connection.Method.POST)
                    .execute()

                if (response.statusCode() >= 400) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, response.body()))
                    return@Thread
                }

                val resObj = JSONObject(response.body())
                if (!resObj.optBoolean("ok", false)) {
                    val error = resObj.optJSONObject("error")
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, error?.optString("message", "Unknown error")))
                    return@Thread
                }

                val data = resObj.optJSONObject("data")
                val resultData = data?.optJSONObject("result")

                result.postValue(
                    DataState(
                        CourseReadmeData(
                            source = resultData?.optString("readme_toml", "") ?: "",
                            markdown = resultData?.optString("readme_md", "") ?: "",
                        ),
                        DataState.STATE.SUCCESS,
                    )
                )
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return result
    }

    fun getCourseStructure(repoName: String, campus: String? = null): LiveData<DataState<CourseStructureSummary>> {
        val result = MutableLiveData<DataState<CourseStructureSummary>>()
        Thread {
            try {
                val resolvedCampus = normalizeHoaCampus(campus) ?: extractCampus(repoName)
                val courseCode = extractCourseCode(repoName)

                val requestBody = JSONObject()
                val target = JSONObject()
                target.put("campus", resolvedCampus)
                target.put("course_code", courseCode)
                requestBody.put("target", target)
                requestBody.put("include_toml", false)

                val response = withHeaders(Jsoup.connect("$baseUrl/v1/course:read"))
                    .header("Content-Type", "application/json")
                    .requestBody(requestBody.toString())
                    .method(Connection.Method.POST)
                    .execute()

                if (response.statusCode() >= 400) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, response.body()))
                    return@Thread
                }

                val resObj = JSONObject(response.body())
                if (!resObj.optBoolean("ok", false)) {
                    val error = resObj.optJSONObject("error")
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, error?.optString("message", "Unknown error")))
                    return@Thread
                }

                val data = resObj.optJSONObject("data")
                val resultData = data?.optJSONObject("result") ?: JSONObject()
                
                // DEBUG: Log the raw response structure
                LogUtils.d("Course read response: ${resObj.toString(2)}")
                LogUtils.d("Result data: ${resultData.toString(2)}")
                
                val readmeMd = resultData.optString("readme_md", "")
                val tomlMeta = parseTomlMeta(readmeMd)
                
                LogUtils.d("readme_md length: ${readmeMd.length}, contains TOML-COURSE: ${readmeMd.contains("TOML-COURSE:")}")
                LogUtils.d("readme_md first 500 chars: ${readmeMd.take(500)}")
                
                val summary = resultData.optJSONObject("summary") ?: JSONObject()
                val meta = summary.optJSONObject("meta") ?: JSONObject()
                val sectionsObj = summary.optJSONObject("sections") ?: JSONObject()
                val normalSections = mutableListOf<CourseSectionSummary>()
                val appendTargets = linkedSetOf<String>()
                val sectionKeys = sectionsObj.keys()
                while (sectionKeys.hasNext()) {
                    val key = sectionKeys.next()
                    val sectionNode = sectionsObj.optJSONObject(key) ?: continue
                    val items = sectionNode.optJSONArray("items")
                    if (key == "sections") {
                        for (index in 0 until (items?.length() ?: 0)) {
                            val item = items?.optJSONObject(index) ?: continue
                            val label = item.optString("label")
                            val preview = item.optString("preview")
                            normalSections.add(
                                CourseSectionSummary(
                                    label = label,
                                    preview = preview,
                                )
                            )
                            if (label.isNotBlank()) {
                                appendTargets.add(label)
                            }
                        }
                    } else if ((items?.length() ?: 0) > 0) {
                        appendTargets.add(key)
                    }
                }
                if (appendTargets.isEmpty()) {
                    // Keep a safe fallback list for append-only posts on sparse templates.
                    appendTargets.addAll(
                        listOf("exam", "lab", "advice", "schedule", "course", "related_links", "misc", "online_resources")
                    )
                }

                var courses = mutableListOf<CourseSummary>()
                val coursesArr = summary.optJSONArray("courses")
                if (coursesArr != null && coursesArr.length() > 0) {
                    for (index in 0 until coursesArr.length()) {
                        val item = coursesArr.optJSONObject(index) ?: continue
                        courses.add(
                            CourseSummary(
                                name = item.optString("name"),
                                code = item.optString("code"),
                                reviewTopics = jsonArrayToList(item.optJSONArray("review_topics")),
                                teachers = jsonArrayToTeacherList(item.optJSONArray("teachers")),
                                sections = jsonArrayToList(item.optJSONArray("sections")),
                            )
                        )
                    }
                }
                if (courses.isEmpty() && readmeMd.contains("TOML-COURSE:")) {
                    LogUtils.d("Parsing TOML courses from readme")
                    courses = parseTomlCourses(readmeMd)
                    LogUtils.d("Parsed ${courses.size} courses from TOML")
                }
                val teachers = jsonArrayToTeacherList(summary.optJSONArray("teachers"))
                val resolvedRepoType = tomlMeta["repo_type"] ?: meta.optString("repo_type").ifBlank {
                    if (courses.isNotEmpty()) "multi-project" else "normal"
                }
                
                // DEBUG: Log repo type detection
                LogUtils.d("Detected repo_type: $resolvedRepoType, courses count: ${courses.size}, meta.repo_type: ${meta.optString("repo_type")}, tomlMeta: $tomlMeta")
                LogUtils.d("Courses parsed: ${courses.map { it.name to it.teachers }}")
                
                result.postValue(
                    DataState(
                        CourseStructureSummary(
                            repoType = resolvedRepoType,
                            sections = normalSections,
                            courses = courses,
                            appendTargets = appendTargets.toList(),
                            teachers = teachers,
                        ),
                        DataState.STATE.SUCCESS,
                    )
                )
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return result
    }

    fun submitOps(
        repoName: String,
        courseCode: String,
        courseName: String,
        repoType: String,
        ops: JSONArray,
        campus: String? = null,
    ): LiveData<DataState<String>> {
        val result = MutableLiveData<DataState<String>>()
        Thread {
            try {
                val resolvedCampus = normalizeHoaCampus(campus) ?: extractCampus(repoName)
                val actualCourseCode = if (courseCode.isNotBlank()) courseCode else extractCourseCode(repoName)

                val requestBody = JSONObject()
                val target = JSONObject()
                target.put("campus", resolvedCampus)
                target.put("course_code", actualCourseCode)
                if (courseName.isNotBlank()) {
                    target.put("course_name", courseName)
                }
                requestBody.put("target", target)
                requestBody.put("ops", ops)
                requestBody.put("idempotency_key", UUID.randomUUID().toString())

                // PR submit may take longer due to multiple GitHub API calls
                val response = withHeaders(Jsoup.connect("$baseUrl/v1/course:submit"))
                    .header("Content-Type", "application/json")
                    .requestBody(requestBody.toString())
                    .method(Connection.Method.POST)
                    .timeout(60000) // 60 seconds for PR submit
                    .execute()

                val resObj = JSONObject(response.body())
                if (response.statusCode() >= 400) {
                    val error = resObj.optJSONObject("error")
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, error?.optString("message", response.body())))
                    return@Thread
                }

                if (!resObj.optBoolean("ok", false)) {
                    val error = resObj.optJSONObject("error")
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, error?.optString("message", "Unknown error")))
                    return@Thread
                }

                val data = resObj.optJSONObject("data")
                val pr = data?.optJSONObject("pr")
                val prUrl = pr?.optString("url", "")

                result.postValue(DataState(prUrl ?: "", DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return result
    }

    fun validateReadme(
        repoName: String,
        courseCode: String,
        courseName: String,
        repoType: String,
        readmeMd: String
    ): LiveData<DataState<ValidateReadmeResult>> {
        val result = MutableLiveData<DataState<ValidateReadmeResult>>()
        Thread {
            try {
                val body = JSONObject()
                body.put("repo_name", repoName)
                body.put("course_code", courseCode)
                body.put("course_name", courseName)
                body.put("repo_type", repoType)
                body.put("readme_md", readmeMd)
                val response = withHeaders(Jsoup.connect("$baseUrl/v1/courses/readme/validate"))
                    .header("Content-Type", "application/json")
                    .requestBody(body.toString())
                    .method(Connection.Method.POST)
                    .execute()
                val res = JSONObject(response.body())
                if (response.statusCode() >= 400) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, res.optString("detail", response.body())))
                    return@Thread
                }
                result.postValue(
                    DataState(
                        ValidateReadmeResult(
                            ok = res.optBoolean("ok", false),
                            toml = res.optString("toml"),
                            normalizedReadme = res.optString("normalized_readme_md")
                        ),
                        DataState.STATE.SUCCESS
                    )
                )
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return result
    }

    /**
     * Lookup PR state (new endpoint)
     * GET /v1/pr:lookup
     */
    fun lookupPr(org: String, repo: String, number: Int): LiveData<DataState<JSONObject>> {
        val result = MutableLiveData<DataState<JSONObject>>()
        Thread {
            try {
                val url = "$baseUrl/v1/pr:lookup?org=${java.net.URLEncoder.encode(org, "UTF-8")}&repo=${java.net.URLEncoder.encode(repo, "UTF-8")}&number=$number"
                val response = withHeaders(Jsoup.connect(url))
                    .method(Connection.Method.GET)
                    .execute()

                if (response.statusCode() >= 400) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, response.body()))
                    return@Thread
                }

                val resObj = JSONObject(response.body())
                if (!resObj.optBoolean("ok", false)) {
                    val error = resObj.optJSONObject("error")
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, error?.optString("message", "Unknown error")))
                    return@Thread
                }

                val data = resObj.optJSONObject("data")
                result.postValue(DataState(data, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return result
    }

    private fun parseTomlMeta(readmeMd: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = """<!--\s*TOML-META:\s*(.*?)\s*-->""".toRegex()
        val match = regex.find(readmeMd)
        if (match != null) {
            val pairs = match.groupValues[1].split(",").map { it.trim() }
            for (pair in pairs) {
                val keyValue = pair.split("=").map { it.trim().removeSurrounding("\"") }
                if (keyValue.size == 2) {
                    result[keyValue[0]] = keyValue[1]
                }
            }
        }
        return result
    }

    private fun parseTomlCourses(readmeMd: String): MutableList<CourseSummary> {
        val courses = mutableListOf<CourseSummary>()
        val regex = """<!--\s*TOML-COURSE:\s*([^>]+)\s*-->""".toRegex()
        LogUtils.d("TOML regex pattern: $regex")
        val matches = regex.findAll(readmeMd)
        val matchList = matches.toList()
        LogUtils.d("Found ${matchList.size} TOML-COURSE matches")
        for (match in matchList) {
            LogUtils.d("Match: ${match.value}, group1: ${match.groupValues[1]}")
            val content = match.groupValues[1]
            var code = ""
            var name = ""
            
            // Parse code="..."
            val codeRegex = """code\s*=\s*"([^"]*)"""".toRegex()
            val codeMatch = codeRegex.find(content)
            if (codeMatch != null) {
                code = codeMatch.groupValues[1]
            }
            
            // Parse name="..."
            val nameRegex = """name\s*=\s*"([^"]*)"""".toRegex()
            val nameMatch = nameRegex.find(content)
            if (nameMatch != null) {
                name = nameMatch.groupValues[1]
            }
            
            LogUtils.d("Parsed: code=$code, name=$name")
            if (name.isNotBlank()) {
                val teachers = extractTeachersForCourse(readmeMd, name)
                courses.add(CourseSummary(name = name, code = code, teachers = teachers))
            }
        }
        return courses
    }

    private fun extractTeachersForCourse(readmeMd: String, courseName: String): List<String> {
        val teachers = mutableListOf<String>()
        val courseHeaderRegex = """##\s*$courseName""".toRegex()
        val courseMatch = courseHeaderRegex.find(readmeMd) ?: return teachers
        val courseSectionStart = courseMatch.range.last + 1
        val nextCourseRegex = """##\s*[\u4e00-\u9fa5]""".toRegex()
        val nextCourseMatch = nextCourseRegex.find(readmeMd, courseSectionStart)
        val courseSectionEnd = nextCourseMatch?.range?.first ?: readmeMd.length
        val courseSection = readmeMd.substring(courseSectionStart, courseSectionEnd)
        
        val teacherHeaderRegex = """###\s*授课教师""".toRegex()
        if (teacherHeaderRegex.containsMatchIn(courseSection)) {
            val teacherListRegex = """(?<=- )\s*([^(\n]+)""".toRegex()
            teacherListRegex.findAll(courseSection).forEach { match ->
                val teacherName = match.groupValues[1].trim()
                if (teacherName.isNotBlank()) {
                    teachers.add(teacherName)
                }
            }
        }
        return teachers
    }

    private fun jsonArrayToList(array: JSONArray?): List<String> {
        val result = mutableListOf<String>()
        for (index in 0 until (array?.length() ?: 0)) {
            val value = array?.opt(index)?.toString()?.trim().orEmpty()
            if (value.isNotBlank()) {
                result.add(value)
            }
        }
        return result
    }

    private fun jsonArrayToTeacherList(array: JSONArray?): List<String> {
        val raw = jsonArrayToList(array)
        val result = linkedSetOf<String>()
        for (item in raw) {
            splitTeacherNames(item).forEach { name ->
                if (name.isNotBlank()) result.add(name)
            }
        }
        return result.toList()
    }

    private fun splitTeacherNames(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(Regex("[,，、]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
