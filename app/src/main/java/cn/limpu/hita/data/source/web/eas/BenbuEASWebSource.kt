package cn.limpu.hita.data.source.web.eas

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.limpu.component.data.DataState
import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.utils.AppConstants
import cn.limpu.hita.data.model.eas.*
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.source.web.service.EASService
import cn.limpu.hita.ui.eas.classroom.BuildingItem
import cn.limpu.hita.ui.eas.classroom.ClassroomItem
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class BenbuEASWebSource(
    onCookiesUpdated: ((EASToken) -> Unit)? = null
) : AbstractEASWebSource(onCookiesUpdated) {
    override val hostName = "http://jwts-hit-edu-cn.ivpn.hit.edu.cn:1080"
    override val campusDisplayName: String = "本部"

    override fun getGraduateTermsOrNull(token: EASToken): List<TermItem>? =
        if (token.stutype == EASToken.TYPE.GRAD) getGraduateTerms(token) else null

    override fun loadCoursesForSchedule(term: TermItem, token: EASToken): List<CourseItem> =
        getCachedOrFetchCourses(term, token)


    private val experimentHostName = "http://sjjx-hit-edu-cn.ivpn.hit.edu.cn:1080"
    private val electronicExpHostName = "http://eelabinfo-hit-edu-cn.ivpn.hit.edu.cn:1080"
    private val graduateHostName = "http://yjsgl-hit-edu-cn.ivpn.hit.edu.cn:1080"
    private val graduateCourseHostName = "http://gcourse-hit-edu-cn.ivpn.hit.edu.cn:1080"

    // Cache to avoid double-fetching in import flow (getScheduleStructure + getTimetableOfTerm)
    @Volatile private var cachedTermCode: String? = null
    @Volatile private var cachedCourses: List<CourseItem>? = null

    override fun login(
        username: String,
        password: String,
        code: String?
    ): LiveData<DataState<EASToken>> {
        val result = MutableLiveData<DataState<EASToken>>()

        executor.execute {
            try {
                val cookiesMap = parseCookiesFromJson(username)

                if (cookiesMap.isEmpty()) {
                    LogUtils.e("login: cookies EMPTY")
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "cookies 为空"))
                    return@execute
                }

                val isGraduate = password == "graduate"
                val requiredCookies = if (isGraduate) listOf("JSESSIONID", "sdp_user_token") else listOf("JSESSIONID", "HIT")
                val missingCookies = requiredCookies.filter { !cookiesMap.containsKey(it) }
                if (missingCookies.isNotEmpty()) {
                    LogUtils.e("login: missing required cookies: $missingCookies")
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "缺少必需的cookies: $missingCookies"))
                    return@execute
                }

                val url = if (isGraduate) "$graduateHostName/yjsgl/common/getXsJbxx?sf_request_type=ajax"
                    else "$hostName/kjscx/queryJxlListBySjid?sf_request_type=ajax"

                val response = Jsoup.connect(url)
                    .cookies(cookiesMap)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .apply { if (!isGraduate) data("id", "1") }
                    .timeout(timeout)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .method(Connection.Method.POST)
                    .execute()

                val graduateLoginValid = !isGraduate || runCatching {
                    JSONObject(response.body()).optBoolean("isSuccess", false)
                }.getOrDefault(false)
                if (response.statusCode() == 200 && graduateLoginValid) {
                    val token = EASToken().apply {
                        cookies.putAll(cookiesMap)
                        campus = EASToken.Campus.BENBU
                        stutype = if (isGraduate) EASToken.TYPE.GRAD else EASToken.TYPE.UNDERGRAD
                        if (isGraduate) {
                            val data = runCatching { JSONObject(response.body()).optJSONObject("module")?.optJSONObject("data") }.getOrNull()
                            val profileUsername = data?.optString("XH")?.takeIf { it.isNotBlank() }
                            this.username = profileUsername
                            name = data?.optString("XM")?.takeIf { !it.isNullOrBlank() }
                            stuId = profileUsername
                        }
                        this.username = this.username ?: extractLoginIdentity(cookiesMap)
                        this.password = password.ifBlank { username }
                    }
                    LogUtils.success("login: Benbu login ok, username=${token.username}")
                    result.postValue(DataState(token, DataState.STATE.SUCCESS))
                } else {
                    LogUtils.e("login: Benbu login failed, status=${response.statusCode()}")
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "登录验证失败 HTTP ${response.statusCode()}"))
                }
            } catch (e: Exception) {
                LogUtils.e("login: Benbu login exception", e)
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message ?: "登录失败"))
            }
        }

        return result
    }


    override fun loginCheck(token: EASToken): LiveData<DataState<Pair<Boolean, EASToken>>> {
        val result = MutableLiveData<DataState<Pair<Boolean, EASToken>>>()
        result.value = DataState(DataState.STATE.NOTHING)
        executor.execute {
            try {
                if (token.campus == EASToken.Campus.BENBU && token.stutype == EASToken.TYPE.GRAD) {
                    val response = Jsoup.connect("$graduateHostName/yjsgl/common/getXsJbxx?sf_request_type=ajax")
                        .cookies(token.cookies).header("Accept", "application/json, text/plain, */*")
                        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .header("X-Requested-With", "XMLHttpRequest").timeout(timeout)
                        .ignoreContentType(true).ignoreHttpErrors(true).method(Connection.Method.POST).execute()
                    val valid = response.statusCode() == 200 && runCatching {
                        JSONObject(response.body()).optBoolean("isSuccess", false)
                    }.getOrDefault(false)
                    if (valid) onCookiesUpdated?.invoke(token)
                    result.postValue(DataState(Pair(valid, token), DataState.STATE.SUCCESS))
                    return@execute
                }
                val response = Jsoup.connect("$hostName/xswhxx/queryXswhxx")
                    .cookies(token.cookies)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(timeout)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .method(Connection.Method.GET)
                    .execute()
                val valid = response.statusCode() == 200 &&
                    !isAuthExpiredResponse(response, response.body())
                if (valid) {
                    token.cookies.putAll(response.cookies())
                    onCookiesUpdated?.invoke(token)
                }
                LogUtils.d("loginCheck: valid=$valid status=${response.statusCode()}")
                result.postValue(DataState(Pair(valid, token), DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                LogUtils.w("loginCheck: exception, message=${e.message}")
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }
        return result
    }


    private fun getCachedOrFetchCourses(term: TermItem, token: EASToken): List<CourseItem> {
        val termCode = term.getCode()
        if (cachedTermCode == termCode && cachedCourses != null) {
            val cached = cachedCourses!!
            cachedTermCode = null
            cachedCourses = null
            return cached
        }
        val courses = getTimetableOfTermSync(term, token)
        cachedTermCode = termCode
        cachedCourses = courses
        return courses
    }

    override fun getTimetableOfTerm(
        term: TermItem,
        token: EASToken
    ): LiveData<DataState<List<CourseItem>>> {
        val result = MutableLiveData<DataState<List<CourseItem>>>()
        result.value = DataState(DataState.STATE.NOTHING)

        executor.execute {
            try {
                val courses = getCachedOrFetchCourses(term, token)
                cachedTermCode = null
                cachedCourses = null
                result.postValue(DataState(courses))
            } catch (e: Exception) {
                LogUtils.e("getTimetableOfTerm: failed", e)
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }

        return result
    }


    override fun getTeachingBuildings(token: EASToken): LiveData<DataState<List<BuildingItem>>> {
        val result = MutableLiveData<DataState<List<BuildingItem>>>()
        result.value = DataState(DataState.STATE.NOTHING)

        executor.execute {
            try {
                val buildings = mutableListOf<BuildingItem>()

                // 查询一校区教学楼
                buildings.addAll(queryBuildingsByCampusId(token, "1"))

                // 查询二校区教学楼
                buildings.addAll(queryBuildingsByCampusId(token, "2"))

                result.postValue(DataState(buildings))
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }

        return result
    }


    protected override fun parseTermsFromDoc(doc: Document, selectName: String): List<TermItem> {
        return BenbuTermParser.parseTerms(doc, selectName)
    }

    protected override fun mergeTerms(primary: List<TermItem>, secondary: List<TermItem>): MutableList<TermItem> {
        return BenbuTermParser.mergeTerms(primary, secondary)
    }


    private fun getTimetableOfTermSync(term: TermItem, token: EASToken): List<CourseItem> {
        if (token.stutype == EASToken.TYPE.GRAD) {
            return getGraduateTimetable(term, token)
        }
        // 查询普通课程
        val regularCourses = getRegularCourses(term, token)

        // 查询实验课程
        val experimentCourses = try {
            getExperimentCourses(term, token)
        } catch (e: Exception) {
            LogUtils.w("getTimetableOfTermSync: failed to fetch experiment courses, message=${e.message}")
            emptyList()
        }

        // 查询电子实验中心课程
        val electronicExperimentCourses = try {
            getElectronicExperimentCourses(term, token)
        } catch (e: Exception) {
            LogUtils.w("getTimetableOfTermSync: failed to fetch electronic experiment courses, message=${e.message}")
            emptyList()
        }

        // 合并所有课程
        val allCourses = regularCourses + experimentCourses + electronicExperimentCourses
        val mergedCourses = mergeAdjacentCourses(allCourses, scheduleFor(token))

        LogUtils.d(
            "getTimetableOfTermSync: term=${term.getCode()} " +
            "regular=${regularCourses.size} " +
            "experiment=${experimentCourses.size} " +
            "electronicExp=${electronicExperimentCourses.size} " +
            "merged=${mergedCourses.size}"
        )

        return mergedCourses
    }

    private fun getGraduateTerms(token: EASToken): List<TermItem> {
        val response = Jsoup.connect("$graduateHostName/yjsgl/common/getSemester")
            .cookies(token.cookies)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .timeout(timeout).ignoreContentType(true).ignoreHttpErrors(true)
            .method(Connection.Method.POST).execute()
        if (response.statusCode() !in 200..299) throw IllegalStateException("HTTP ${response.statusCode()}")
        val terms = BenbuGraduateScheduleParser.parseTerms(response.body())
        if (terms.isNotEmpty()) return terms

        val page = establishGraduateCourseSession(token)
        return parseTermsFromDoc(page, "xnxq")
    }

    private fun getGraduateTimetable(term: TermItem, token: EASToken): List<CourseItem> {
        val studentId = token.stuId ?: token.username ?: throw IllegalStateException("研究生学号为空")
        establishGraduateCourseSession(token)
        val endpoint = if (term.isCurrent) "queryXsckcb" else "queryXsckcbyy"
        val response = Jsoup.connect("$graduateCourseHostName/kbgl/$endpoint")
            .cookies(token.cookies)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "$graduateCourseHostName/kbgl/queryxskbxsy")
            .header("X-Requested-With", "XMLHttpRequest")
            .data("xh", studentId)
            .data("xnxq", term.getCode())
            .apply { if (term.isCurrent) data("ys", "1") }
            .timeout(timeout).ignoreContentType(true).ignoreHttpErrors(true)
            .method(Connection.Method.GET).execute()
        if (response.statusCode() !in 200..299) throw IllegalStateException("HTTP ${response.statusCode()}")
        val courses = BenbuGraduateScheduleParser.parseTimetable(response.body())
        if (courses.isEmpty() && response.body().contains("/common/login")) {
            throw IllegalStateException("研究生登录已过期")
        }
        return courses
    }

    private fun establishGraduateCourseSession(token: EASToken): Document {
        val response = Jsoup.connect("$graduateHostName/yjsgl/outInterface/grkb")
            .cookies(token.cookies)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
            .timeout(timeout).ignoreContentType(true).ignoreHttpErrors(true)
            .followRedirects(true)
            .method(Connection.Method.GET).execute()
        token.cookies.putAll(response.cookies())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("研究生课表桥接失败 HTTP ${response.statusCode()}")
        }
        val finalHost = response.url().host
        if (!finalHost.contains("gcourse", ignoreCase = true)) {
            throw IllegalStateException("研究生课表会话建立失败")
        }
        return response.parse()
    }

    private fun getRegularCourses(term: TermItem, token: EASToken): List<CourseItem> {
        val response = Jsoup.connect("$hostName/kbcx/queryGrkb")
            .cookies(token.cookies)
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
            .data("fhlj", "kbcx/queryGrkb")
            .data("xnxq", term.getCode())
            .timeout(timeout)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .method(Connection.Method.POST)
            .execute()
        if (response.statusCode() != 200) {
            throw IllegalStateException("HTTP ${response.statusCode()}")
        }
        val body = response.body()
        ensureTimetableResponse(term, body, response.statusCode())
        val parsedCourses = BenbuScheduleParser.parseScheduleHtml(body)
        logEmptyTimetableDetails(term, body, parsedCourses)
        return parsedCourses
    }

    private fun getExperimentCourses(term: TermItem, token: EASToken): List<CourseItem> {
        val experimentCookies = HashMap<String, String>(token.cookies)

        try {
            val loginResponse = Jsoup.connect("$hostName/loginsysj/loginSygl")
                .cookies(token.cookies)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.3.1 Safari/605.1.15")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                .header("Upgrade-Insecure-Requests", "1")
                .timeout(timeout)
                .ignoreContentType(true)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .method(Connection.Method.GET)
                .execute()

            loginResponse.cookies().forEach { (key, value) ->
                experimentCookies[key] = value
            }
        } catch (e: Exception) {
            LogUtils.e("getExperimentCourses: session auth failed, ${e.message}")
        }

        val getResponse = Jsoup.connect("$experimentHostName/xskb/queryXszkb")
            .cookies(experimentCookies)
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.3.1 Safari/605.1.15")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
            .header("Upgrade-Insecure-Requests", "1")
            .timeout(timeout)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .method(Connection.Method.GET)
            .execute()

        if (getResponse.statusCode() != 200) {
            LogUtils.e("getExperimentCourses: HTTP ${getResponse.statusCode()}")
            return emptyList()
        }

        val body = getResponse.body()

        if (body.contains("页面过期") || body.contains("请重新登录")) {
            LogUtils.w("getExperimentCourses: session expired")
            return emptyList()
        }

        val parsedCourses = BenbuScheduleParser.parseExperimentHtml(body)
        LogUtils.d("getExperimentCourses: term=${term.getCode()} count=${parsedCourses.size}")

        return parsedCourses
    }

    private fun getElectronicExperimentCourses(term: TermItem, token: EASToken): List<CourseItem> {
        val jwtToken = token.electronicExpToken
        if (jwtToken.isNullOrBlank()) {
            LogUtils.w("getElectronicExpCourses: JWT token is empty, skipping")
            return emptyList()
        }
        LogUtils.d("getElectronicExpCourses: JWT token ready, length=${jwtToken.length}")

        try {
            val url = "$electronicExpHostName/api/stu/viewCKKB?sf_request_type=ajax"

            val response = Jsoup.connect(url)
                .cookies(token.cookies)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.3.1 Safari/605.1.15")
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", electronicExpHostName)
                .header("Referer", "$electronicExpHostName/stu_ckkb.html")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                .header("VcTchType", "stu")
                .header("VcTchToken", jwtToken)
                .timeout(5000)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .method(Connection.Method.POST)
                .execute()

            LogUtils.d("getElectronicExpCourses: HTTP ${response.statusCode()}, body length=${response.body().length}")
            if (response.statusCode() != 200) {
                LogUtils.e("getElectronicExpCourses: request failed")
                return emptyList()
            }

            val parsedCourses = parseElectronicExperimentJson(response.body(), term)
            LogUtils.d("getElectronicExpCourses: term=${term.getCode()} count=${parsedCourses.size}")

            return parsedCourses

        } catch (e: Exception) {
            LogUtils.e("getElectronicExpCourses: query failed", e)
            return emptyList()
        }
    }

    /**
     * 解析电子实验中心返回的JSON数据
     *
     * JSON格式：
     * {
     *   "code": 0,
     *   "codeMessage": "成功",
     *   "data": [
     *     {
     *       "subjectName": "电路实验",
     *       "classDate": "2026-05-12",
     *       "startTime": "13:00",
     *       "endTime": "18:10",
     *       "teacher": "张三",
     *       "address": "一校区-电机楼-301",
     *       "labsName": "电路实验室"
     *     }
     *   ]
     * }
     */
    private fun parseElectronicExperimentJson(json: String, term: TermItem): List<CourseItem> {
        val courses = mutableListOf<CourseItem>()

        try {
            val jsonObj = org.json.JSONObject(json)
            val code = jsonObj.optInt("code", -1)
            val codeMessage = jsonObj.optString("codeMessage", "")

            if (code != 0) {
                LogUtils.e("parseElectronicExpJson: API error code=$code message=$codeMessage")
                return courses
            }

            val dataArray = jsonObj.optJSONArray("data")
            if (dataArray == null) {
                LogUtils.w("parseElectronicExpJson: data field is null")
                return courses
            }

            val termStartDate = inferTermStartDate(term)

            for (i in 0 until dataArray.length()) {
                try {
                    val item = dataArray.getJSONObject(i)

                    val subjectName = item.optString("subjectName", "").trim()
                    val classDate = item.optString("classDate", "").trim()
                    val startTimeRaw = item.optString("startTime", "").trim()
                    val endTimeRaw = item.optString("endTime", "").trim()
                    val teacher = item.optString("teacher", "").trim()
                    val address = item.optString("address", "").trim()
                    val labsName = item.optString("labsName", "").trim()

                    val startTime = parseTimeField(startTimeRaw)
                    val endTime = if (endTimeRaw.isBlank()) {
                        deriveEndTime(startTime, durationMinutes = 120)
                    } else {
                        parseTimeField(endTimeRaw)
                    }

                    if (subjectName.isEmpty()) continue

                    val (dow, weekNum) = parseDateToWeekAndDow(classDate, termStartDate)
                    if (dow == -1 || weekNum == -1) {
                        LogUtils.w("parseElectronicExpJson: date parse failed for '$classDate'")
                        continue
                    }

                    courses.add(CourseItem().apply {
                        name = subjectName
                        this.dow = dow
                        weeks = mutableListOf(weekNum)
                        this.teacher = teacher
                        classroom = if (labsName.isNotEmpty()) "$address($labsName)" else address
                        this.startTime = startTime
                        this.endTime = endTime
                        begin = -1
                        last = -1
                    })

                } catch (e: Exception) {
                    LogUtils.e("parseElectronicExpJson: record #$i failed", e)
                }
            }

        } catch (e: Exception) {
            LogUtils.e("parseElectronicExpJson: failed", e)
        }

        return courses
    }

    /** 将 "HH:MM" 或 "HH:MM:SS" 统一为 "HH:MM"，无法解析时返回 null */
    private fun parseTimeField(raw: String): String? {
        if (raw.isBlank()) return null
        val parts = raw.split(":")
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return String.format("%02d:%02d", hour, minute)
    }

    /** 从 startTime 推算 endTime，默认加 durationMinutes 分钟 */
    private fun deriveEndTime(startTime: String?, durationMinutes: Int): String? {
        if (startTime == null) return null
        val parts = startTime.split(":")
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val total = hour * 60 + minute + durationMinutes
        return String.format("%02d:%02d", total / 60, total % 60)
    }

    /**
     * 解析日期字符串为周数和星期
     *
     * @param dateStr 日期字符串，格式：2026-05-12
     * @param term 学期信息，用于推断开学日期
     * @return Pair(星期几, 周数)，星期几：1=周一, 7=周日
     */
    private fun parseDateToWeekAndDow(dateStr: String, termStartDate: Calendar): Pair<Int, Int> {
        try {
            val currentDate = SimpleDateFormat("yyyy-MM-dd").parse(dateStr)
                ?: throw IllegalArgumentException("Invalid date: $dateStr")
            val currentCalendar = Calendar.getInstance().apply {
                time = currentDate
            }

            val diffMillis = currentCalendar.timeInMillis - termStartDate.timeInMillis
            val diffDays = diffMillis / (1000 * 60 * 60 * 24)
            val weekNum = (diffDays / 7).toInt() + 1

            val dow = currentCalendar.get(Calendar.DAY_OF_WEEK)
            val adjustedDow = when (dow) {
                Calendar.MONDAY -> 1
                Calendar.TUESDAY -> 2
                Calendar.WEDNESDAY -> 3
                Calendar.THURSDAY -> 4
                Calendar.FRIDAY -> 5
                Calendar.SATURDAY -> 6
                Calendar.SUNDAY -> 7
                else -> -1
            }

            return Pair(adjustedDow, weekNum)

        } catch (e: Exception) {
            LogUtils.e("parseDateToWeekAndDow: failed for '$dateStr'", e)
            return Pair(-1, -1)
        }
    }


    private fun mergeAdjacentCourses(
        courses: List<CourseItem>,
        schedule: List<TimePeriodInDay>
    ): List<CourseItem> {
        if (courses.isEmpty()) return courses

        // Separate free time courses (experiment courses) from period-based courses
        val freeTimeCourses = courses.filter {
            it.startTime != null && it.endTime != null && it.begin == -1 && it.last == -1
        }
        val periodCourses = courses.filter {
            it.startTime == null || it.endTime == null || it.begin != -1 || it.last != -1
        }

        // Only merge period-based courses
        val sorted = periodCourses.sortedWith(
            compareBy<CourseItem> { it.dow }
                .thenBy { it.begin }
                .thenBy { normalized(it.name) }
                .thenBy { normalized(it.teacher) }
                .thenBy { it.weeks.sorted().joinToString(",") }
        )

        val merged = mutableListOf<CourseItem>()
        for (course in sorted) {
            val last = merged.lastOrNull()
            if (last != null && canMergeCourses(last, course, schedule)) {
                last.last += course.last
                if (last.classroom.isNullOrBlank()) {
                    last.classroom = course.classroom
                }
                if (last.code.isNullOrBlank()) {
                    last.code = course.code
                }
            } else {
                merged.add(copyCourse(course))
            }
        }

        // Deduplicate free time courses (same name, dow, weeks, teacher, classroom, time)
        val deduplicatedFreeTime = freeTimeCourses.distinctBy { course ->
            "${normalized(course.name)}_${course.dow}_${course.weeks.sorted()}_${normalized(course.teacher)}_${course.classroom}_${course.startTime}_${course.endTime}"
        }

        merged.addAll(deduplicatedFreeTime)

        return merged
    }


    private val defaultSchedule by lazy {
        mutableListOf(
            TimePeriodInDay(TimeInDay(8, 0), TimeInDay(8, 50)),
            TimePeriodInDay(TimeInDay(8, 55), TimeInDay(9, 45)),
            TimePeriodInDay(TimeInDay(10, 0), TimeInDay(10, 50)),
            TimePeriodInDay(TimeInDay(10, 55), TimeInDay(11, 45)),
            TimePeriodInDay(TimeInDay(14, 0), TimeInDay(14, 50)),
            TimePeriodInDay(TimeInDay(14, 55), TimeInDay(15, 45)),
            TimePeriodInDay(TimeInDay(16, 0), TimeInDay(16, 50)),
            TimePeriodInDay(TimeInDay(16, 55), TimeInDay(17, 45)),
            TimePeriodInDay(TimeInDay(18, 45), TimeInDay(19, 35)),
            TimePeriodInDay(TimeInDay(19, 40), TimeInDay(20, 30)),
            TimePeriodInDay(TimeInDay(20, 45), TimeInDay(21, 35)),
            TimePeriodInDay(TimeInDay(21, 40), TimeInDay(22, 30))
        )
    }

    private val undergraduateSchedule by lazy {
        mutableListOf(
            TimePeriodInDay(TimeInDay(8, 30), TimeInDay(9, 20)),
            TimePeriodInDay(TimeInDay(9, 25), TimeInDay(10, 15)),
            TimePeriodInDay(TimeInDay(10, 30), TimeInDay(11, 20)),
            TimePeriodInDay(TimeInDay(11, 25), TimeInDay(12, 15)),
            TimePeriodInDay(TimeInDay(14, 0), TimeInDay(14, 50)),
            TimePeriodInDay(TimeInDay(14, 55), TimeInDay(15, 45)),
            TimePeriodInDay(TimeInDay(16, 0), TimeInDay(16, 50)),
            TimePeriodInDay(TimeInDay(16, 55), TimeInDay(17, 45)),
            TimePeriodInDay(TimeInDay(18, 45), TimeInDay(19, 35)),
            TimePeriodInDay(TimeInDay(19, 40), TimeInDay(20, 30)),
            TimePeriodInDay(TimeInDay(20, 45), TimeInDay(21, 35)),
            TimePeriodInDay(TimeInDay(21, 40), TimeInDay(22, 30))
        )
    }

    private fun scheduleFor(token: EASToken): List<TimePeriodInDay> =
        if (token.stutype == EASToken.TYPE.UNDERGRAD) undergraduateSchedule else defaultSchedule

    protected override fun defaultScheduleStructure(isUndergraduate: Boolean): MutableList<TimePeriodInDay> {
        return mutableListOf(*(if (isUndergraduate) undergraduateSchedule else defaultSchedule).toTypedArray())
    }

    override fun queryEmptyClassroom(
        token: EASToken,
        term: TermItem,
        building: BuildingItem,
        weeks: List<String>
    ): LiveData<DataState<List<ClassroomItem>>> {
        val result = MutableLiveData<DataState<List<ClassroomItem>>>()
        result.value = DataState(DataState.STATE.NOTHING)

        executor.execute {
            try {
                val weekStart = weeks.firstOrNull() ?: "1"
                val weekEnd = weeks.lastOrNull() ?: weekStart

                val response = Jsoup.connect("$hostName/kjscx/queryKjs")
                    .cookies(token.cookies)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                    .data("pageXnxq", term.getCode())
                    .data("pageZc1", weekStart)
                    .data("pageZc2", weekEnd)
                    .data("pageXiaoqu", "")
                    .data("pageLhdm", building.id)
                    .data("pageCddm", "")
                    .timeout(timeout)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .method(Connection.Method.POST)
                    .execute()

                if (response.statusCode() == 200) {
                    val classrooms = BenbuClassroomParser.parseEmptyClassroomHtml(response.body())
                    result.postValue(DataState(classrooms))
                } else {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "HTTP ${response.statusCode()}"))
                }
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }

        return result
    }


    override fun requestScores(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): Connection.Response {
        val (path, params) = when (testType) {
            EASService.TestType.ALL -> {
                "/xfj/queryListXfj" to linkedMapOf(
                    "pageXnxqks" to term.getCode(),
                    "pageXnxqjs" to term.getCode(),
                    "pageKcmc" to ""
                )
            }
            EASService.TestType.NORMAL -> {
                "/cjcx/queryQmcj" to linkedMapOf(
                    "pageXnxq" to term.getCode(),
                    "pageBkcxbj" to "0",
                    "pageSfjg" to "",
                    "pageKcmc" to ""
                )
            }
            EASService.TestType.RESIT -> {
                "/cjcx/queryQzcj" to linkedMapOf(
                    "pageXnxq" to term.getCode(),
                    "pageKcmc" to ""
                )
            }
            EASService.TestType.RETAKE -> {
                "/cjcx/queryQmcj" to linkedMapOf(
                    "pageXnxq" to term.getCode(),
                    "pageBkcxbj" to "2",
                    "pageSfjg" to "",
                    "pageKcmc" to ""
                )
            }
        }
        LogUtils.d( "requestScores: term=${term.getCode()} name=${term.termName} testType=$testType path=$path params=$params")

        fun executeOnce(): Connection.Response {
            val resp = Jsoup.connect(hostName + path)
                .cookies(token.cookies)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                .timeout(timeout)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .data(params)
                .method(Connection.Method.POST)
                .execute()
            if (resp.cookies().isNotEmpty()) {
                token.cookies.putAll(resp.cookies())
                onCookiesUpdated?.invoke(token)
            }
            return resp
        }

        var response = executeOnce()
        if (isAuthExpiredResponse(response, response.body()) && tryRelogin(token)) {
            response = executeOnce()
        }
        return response
    }


    override fun isAuthExpiredResponse(response: Connection.Response, body: String): Boolean {
        if (response.statusCode() == 401 || response.statusCode() == 403) return true
        val doc = Jsoup.parse(body)
        val title = doc.title().lowercase()
        val text = doc.body()?.text()?.replace(Regex("\\s+"), " ")?.lowercase().orEmpty()
        val hasScoreTable = doc.selectFirst("table.bot_line") != null
        val hasLoginForm = doc.select("input[name=mm], input[name=password], form[action*=login], form[action*=authentication]").isNotEmpty()
        val isJsChallengePage = text.contains("your browser does not support javascript")
                || text.contains("javascript is disabled in your browser")
                || text.contains("please enable javascript")
                || text.contains("enable javascript to continue")
                || title.contains("just a moment")
                || title.contains("attention required")
        if (isJsChallengePage) return true
        if (hasLoginForm && !hasScoreTable) return true
        if (!hasScoreTable && (title.contains("登录") || title.contains("统一身份认证") || title.contains("ivpn") || text.contains("登录") || text.contains("未登录") || text.contains("认证"))) {
            return true
        }
        return false
    }

    override fun fetchScoreSummary(token: EASToken): ScoreSummary? {
        return try {
            val response = Jsoup.connect("$hostName/xfj/queryListXfj")
                .cookies(token.cookies)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                .timeout(timeout)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .method(Connection.Method.GET)
                .execute()
            if (response.statusCode() != 200) {
                return null
            }
            extractBenbuScoreSummary(Jsoup.parse(response.body()))
        } catch (_: Exception) {
            null
        }
    }

    private fun extractBenbuScoreSummary(doc: Document): ScoreSummary? {
        val values = mutableMapOf<String, String>()
        doc.select("span[id]").forEach { span ->
            val id = span.id().trim()
            val value = span.text().replace(Regex("\\s+"), " ").trim()
            if (id.isNotBlank() && value.isNotBlank()) {
                values[id] = value
            }
        }
        val weightedAverage = values["pjxfj"].orEmpty()
        val rankRaw = values["zrs"].orEmpty()
        val rankParts = rankRaw.split("/").map { it.trim() }.filter { it.isNotBlank() }
        val rank = rankParts.getOrNull(0).orEmpty()
        val total = rankParts.getOrNull(1).orEmpty()
        if (weightedAverage.isBlank() && rank.isBlank() && total.isBlank()) {
            return null
        }
        return ScoreSummary(
            weightedAverage = weightedAverage,
            rank = rank,
            total = total,
            scope = ScoreSummaryScope.CUMULATIVE
        )
    }


    /**
     * 获取考试信息
     *
     * 设计说明：
     * 1. API需要两个参数：xnxq（学年学期）和kssjd（考试时间段）
     * 2. 需要调用三次API分别获取期末、期中、补考的考试信息
     * 3. 解析HTML表格提取考试数据
     * 4. 转换为统一的ExamItem格式
     *
     * @param token 登录凭证
     * @param term 学期信息
     * @return 考试列表
     */
    override fun getExamItems(
        token: EASToken,
        term: TermItem?
    ): LiveData<DataState<List<ExamItem>>> {
        val result = MutableLiveData<DataState<List<ExamItem>>>()

        executor.execute {
            try {
                if (term == null) {
                    LogUtils.e("getExamItems: term is null")
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "学期参数为空"))
                    return@execute
                }

                val termCode = "${term.yearCode}${term.termCode}"

                val examTypes = mapOf(
                    "01" to "期末",
                    "02" to "期中",
                    "03" to "补考"
                )

                val allExamItems = mutableListOf<ExamItem>()

                for ((kssjd, typeName) in examTypes) {
                    val url = "$hostName/kscx/queryKcForXs1"
                    val response = Jsoup.connect(url)
                        .cookies(token.cookies)
                        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .data("xnxq", termCode)
                        .data("kssjd", kssjd)
                        .timeout(timeout)
                        .followRedirects(true)
                        .execute()

                    if (response.statusCode() == 200) {
                        val examItems = parseExamHtml(response.body(), typeName, term)
                        allExamItems.addAll(examItems)
                    } else {
                        LogUtils.w("getExamItems: $typeName exam query HTTP ${response.statusCode()}")
                    }
                }

                LogUtils.success("getExamItems: term=$termCode total=${allExamItems.size}")
                result.postValue(DataState(allExamItems, DataState.STATE.SUCCESS))

            } catch (e: Exception) {
                LogUtils.e("getExamItems: failed", e)
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message ?: "查询失败"))
            }
        }

        return result
    }

    /**
     * 解析考试查询的HTML响应
     *
     * HTML格式：
     * <table>
     *   <tr>
     *     <th>序号</th>
     *     <th>课程名称</th>
     *     <th>课程代码</th>
     *     <th>考试地点</th>
     *     <th>座位号</th>
     *     <th>考试具体时间</th>
     *     <th>考试时间段</th>
     *   </tr>
     *   <tr>
     *     <td>1</td>
     *     <td>数学分析（2）</td>
     *     <td>22MA15016</td>
     *     <td>一校区-正心楼-正心21</td>
     *     <td></td>
     *     <td>2026年05月09日(第9周     星期六)10:00-11:30</td>
     *     <td>期中</td>
     *   </tr>
     * </table>
     *
     * @param html HTML内容
     * @param examTypeName 考试类型名称（期末/期中/补考）
     * @param term 学期信息
     * @return 考试列表
     */
    private fun parseExamHtml(html: String, examTypeName: String, term: TermItem): List<ExamItem> {
        val examItems = mutableListOf<ExamItem>()

        try {
            val doc = Jsoup.parse(html)

            // 尝试多种选择器查找表格
            val table = doc.select("table.bot_line").first()
                ?: doc.select("table").firstOrNull { it.select("tr").size > 1 }

            if (table == null) {
                LogUtils.w("parseExamHtml: table not found for $examTypeName")
                return examItems
            }

            val rows = table.select("tr")

            for ((index, row) in rows.withIndex()) {
                if (index == 0) continue

                val cells = row.select("td")
                if (cells.size < 7) continue

                try {
                    val courseName = cells[1].text().trim()
                    val location = cells[3].text().trim()
                    val dateTimeStr = cells[5].text().trim()

                    val (examDate, examTime) = parseExamDateTime(dateTimeStr)

                    examItems.add(ExamItem().apply {
                        this.courseName = courseName
                        this.examLocation = location
                        this.examDate = examDate
                        this.examTime = examTime
                        this.examType = examTypeName
                        this.campusName = "本部"
                        this.termId = term.id
                    })

                } catch (e: Exception) {
                    LogUtils.e("parseExamHtml: row parse failed", e)
                }
            }

        } catch (e: Exception) {
            LogUtils.e("parseExamHtml: failed", e)
        }

        return examItems
    }

    /**
     * 解析考试日期时间字符串
     *
     * 输入格式：2026年05月09日(第9周     星期六)10:00-11:30
     * 输出：Pair(日期字符串, 时间字符串)
     *   - 日期：2026-05-09
     *   - 时间：10:00-11:30
     *
     * @param dateTimeStr 日期时间字符串
     * @return Pair(日期, 时间)
     */
    private fun parseExamDateTime(dateTimeStr: String): Pair<String, String> {
        try {
            // 提取日期部分：2026年05月09日
            val dateRegex = Regex("""(\d{4})年(\d{2})月(\d{2})日""")
            val dateMatch = dateRegex.find(dateTimeStr)

            // 提取时间部分：10:00-11:30
            val timeRegex = Regex("""(\d{2}:\d{2})-(\d{2}:\d{2})""")
            val timeMatch = timeRegex.find(dateTimeStr)

            val dateStr = if (dateMatch != null) {
                val year = dateMatch.groupValues[1]
                val month = dateMatch.groupValues[2]
                val day = dateMatch.groupValues[3]
                "$year-$month-$day"
            } else {
                ""
            }

            val timeStr = if (timeMatch != null) {
                "${timeMatch.groupValues[1]}-${timeMatch.groupValues[2]}"
            } else {
                ""
            }

            return Pair(dateStr, timeStr)

        } catch (e: Exception) {
            LogUtils.e("parseExamDateTime: failed for '$dateTimeStr'", e)
            return Pair("", "")
        }
    }

}
