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
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class WeihaiEASWebSource(
    onCookiesUpdated: ((EASToken) -> Unit)? = null
) : AbstractEASWebSource(onCookiesUpdated) {
    override val hostName = "https://webvpn.hitwh.edu.cn/http/77726476706e69737468656265737421fae0558f693861446900c7a99c406d3667"
    override val campusDisplayName: String = "威海"

    override fun postProcessTerms(terms: MutableList<TermItem>): MutableList<TermItem> =
        terms.filterVisibleTerms().toMutableList()

    override fun loadCoursesForSchedule(term: TermItem, token: EASToken): List<CourseItem> =
        getTimetableOfTermSync(term, token)

    override val timetableDebugCellSelector: String = "td,th"


    companion object {
        private const val WEBVPN_JWTS_QUERY_HINT = "vpn-12-o1-jwts.hitwh.edu.cn"
    }

    override fun login(
        username: String,
        password: String,
        code: String?
    ): LiveData<DataState<EASToken>> {
        val result = MutableLiveData<DataState<EASToken>>()

        executor.execute {
            try {
                LogUtils.d("login: Weihai login START")
                LogUtils.d("login: username length=${username.length}")

                val cookiesMap = parseCookiesFromJson(username)
                LogUtils.d("login: parsed cookies=${cookiesMap.keys}, count=${cookiesMap.size}")
                LogUtils.d("login: key cookies, HIT=${cookiesMap["HIT"]?.take(8)}, JSESSIONID=${cookiesMap["JSESSIONID"]?.take(8)}")

                if (cookiesMap.isEmpty()) {
                    LogUtils.e("login: cookies EMPTY")
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "cookies 为空"))
                    return@execute
                }

                val url = "$hostName/kjscx/queryJxlListBySjid?sf_request_type=ajax"
                LogUtils.d("login: validating cookies, url=$url")

                val response = Jsoup.connect(url)
                    .cookies(cookiesMap)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .data("id", "1")
                    .timeout(timeout)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .method(Connection.Method.POST)
                    .execute()

                LogUtils.d("login: validation response, status=${response.statusCode()}")

                if (response.statusCode() == 200) {
                    val token = EASToken().apply {
                        cookies.putAll(cookiesMap)
                        campus = EASToken.Campus.WEIHAI
                        this.username = extractLoginIdentity(cookiesMap)
                        this.password = password.ifBlank { username }
                    }
                    LogUtils.success("login: Weihai login ok, username=${token.username}")
                    result.postValue(DataState(token, DataState.STATE.SUCCESS))
                } else {
                    LogUtils.e("login: Weihai login failed, status=${response.statusCode()}")
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "登录验证失败 HTTP ${response.statusCode()}"))
                }
            } catch (e: Exception) {
                LogUtils.e("login: Weihai login exception", e)
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
                val response = Jsoup.connect("$hostName/reAuth")
                    .cookies(token.cookies)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .timeout(timeout)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .method(Connection.Method.GET)
                    .execute()
                val body = response.body()
                val valid = response.statusCode() == 200 && body.contains("reAuth_success")

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


    override fun getTimetableOfTerm(
        term: TermItem,
        token: EASToken
    ): LiveData<DataState<List<CourseItem>>> {
        val result = MutableLiveData<DataState<List<CourseItem>>>()
        result.value = DataState(DataState.STATE.NOTHING)

        executor.execute {
            try {
                val termCode = term.getCode()
                LogUtils.d("getTimetableOfTerm: request path=/kbcx/queryGrkb params={fhlj=kbcx/queryGrkb,xnxq=$termCode}")
                val response = Jsoup.connect("$hostName/kbcx/queryGrkb")
                    .cookies(token.cookies)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .data("fhlj", "kbcx/queryGrkb")
                    .data("xnxq", termCode)
                    .timeout(timeout)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .method(Connection.Method.POST)
                    .execute()

                val statusCode = response.statusCode()
                val body = response.body()
                val hasTimetableTable = Jsoup.parse(body).selectFirst("table.addlist_01") != null
                LogUtils.d(
                    "getTimetableOfTerm: response, term=$termCode status=$statusCode hasAddlist01=$hasTimetableTable cookieKeys=${token.cookies.keys.sorted()} ${cookieFingerprintSummary(token.cookies)}"
                )

                // 错误分类对齐 iOS：登录特征 / 401 / 403 → 会话失效（引导重登）；
                // 5xx（上游不可用）→ 提示稍后重试，不引导重登。
                if (statusCode == 401 || statusCode == 403 || isAuthExpiredResponse(response, body)) {
                    LogUtils.w("getTimetableOfTerm: auth expired, term=$termCode status=$statusCode")
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "登录已失效，请重新登录"))
                    return@execute
                }

                if (statusCode in 500..599) {
                    LogUtils.w("getTimetableOfTerm: upstream unavailable, term=$termCode status=$statusCode")
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "服务器错误，请稍后重试"))
                    return@execute
                }

                if (statusCode != 200) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "HTTP $statusCode"))
                    return@execute
                }

                ensureTimetableResponse(term, body, statusCode)
                val parsedCourses = BenbuScheduleParser.parseScheduleHtml(body)
                val courses = mergeAdjacentCourses(parsedCourses, scheduleFor(token))
                logEmptyTimetableDetails(term, body, courses)
                LogUtils.d("getTimetableOfTerm: parsed, term=$termCode rawCourseCount=${parsedCourses.size} mergedCourseCount=${courses.size}")
                result.postValue(DataState(courses))
            } catch (e: Exception) {
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
                val seen = hashSetOf<String>()

                fun appendFromCampusId(campusId: String) {
                    queryBuildingsByCampusId(token, campusId).forEach { item ->
                        if (item.id.isBlank() || !seen.add(item.id)) return@forEach
                        buildings.add(item)
                    }
                }

                appendFromCampusId("01")
                if (buildings.isEmpty()) {
                    appendFromCampusId("1")
                    appendFromCampusId("2")
                }

                result.postValue(DataState(buildings))
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }

        return result
    }


    private fun parseTermValue(value: String, label: String): TermItem? {
        val cleanedValue = value.trim()
        val match = Regex("""(\d{4}-\d{4})(\d+)""").matchEntire(cleanedValue)
        val yearCode = match?.groupValues?.get(1)
            ?: Regex("""\d{4}-\d{4}""").find(cleanedValue)?.value
            ?: Regex("""\d{4}""").find(cleanedValue)?.value
            ?: return null
        val termCode = match?.groupValues?.get(2)
            ?: Regex("""\d+$""").find(cleanedValue)?.value
            ?: return null

        val normalizedLabel = normalizeTermLabel(label)
        val derivedTermName = when {
            normalizedLabel.isNotBlank() -> normalizedLabel
            termCode.startsWith("1") -> "$yearCode 秋季学期"
            termCode.startsWith("2") -> "$yearCode 春季学期"
            termCode.startsWith("3") -> "$yearCode 夏季学期"
            termCode.startsWith("4") -> "$yearCode 冬季学期"
            else -> "$yearCode $termCode"
        }

        return TermItem(
            yearCode = yearCode,
            yearName = yearCode,
            termCode = termCode,
            termName = derivedTermName
        ).apply {
            name = derivedTermName
        }
    }

    private fun normalizeTermLabel(label: String): String {
        val compact = label.replace(Regex("""\s+"""), "").trim()
        if (compact.isBlank()) return ""

        val yearCode = Regex("""\d{4}-\d{4}""").find(compact)?.value
            ?: Regex("""(\d{4})学年""").find(compact)?.groupValues?.getOrNull(1)?.let { "$it-${it.toInt() + 1}" }
            ?: ""

        val season = when {
            compact.contains("秋") -> "秋季学期"
            compact.contains("春") -> "春季学期"
            compact.contains("夏") -> "夏季学期"
            compact.contains("寒") || compact.contains("冬") -> "冬季学期"
            compact.contains("第一") -> "秋季学期"
            compact.contains("第二") -> "春季学期"
            compact.contains("第三") -> "夏季学期"
            compact.contains("第四") -> "冬季学期"
            compact.contains("上学期") -> "秋季学期"
            compact.contains("下学期") -> "春季学期"
            else -> ""
        }

        return when {
            yearCode.isNotBlank() && season.isNotBlank() -> "$yearCode $season"
            compact.contains("学年") && season.isNotBlank() -> compact.replace("学年", "学年 ").replace("学期", "学期 ").trim()
            else -> compact
        }
    }


    protected override fun parseTermsFromDoc(doc: Document, selectName: String): List<TermItem> {
        val options = doc.select("select[name=$selectName] option")
        val currentValue = currentTermValueFromDoc(doc, selectName)
        val terms = mutableListOf<TermItem>()

        options.forEachIndexed { index, option ->
            val value = option.attr("value").trim()
            if (value.isBlank()) return@forEachIndexed
            val parsed = parseTermValue(value, option.text().trim()) ?: return@forEachIndexed
            parsed.isCurrent = value == currentValue || (currentValue.isEmpty() && index == 0)
            terms.add(parsed)
        }
        return terms
    }

    protected override fun mergeTerms(primary: List<TermItem>, secondary: List<TermItem>): MutableList<TermItem> {
        val merged = LinkedHashMap<String, TermItem>()

        secondary.forEach { term ->
            merged[term.getCode()] = term
        }

        primary.forEach { term ->
            val key = term.getCode()
            val existing = merged[key]
            if (existing == null) {
                merged[key] = term
            } else {
                if (term.termName.isNotBlank()) {
                    existing.termName = term.termName
                }
                if (term.name.isNotBlank()) {
                    existing.name = term.name
                }
                existing.isCurrent = existing.isCurrent || term.isCurrent
            }
        }

        return merged.values.toMutableList()
    }


    private fun getTimetableOfTermSync(term: TermItem, token: EASToken): List<CourseItem> {
        val response = Jsoup.connect("$hostName/kbcx/queryGrkb")
            .cookies(token.cookies)
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .data("fhlj", "kbcx/queryGrkb")
            .data("xnxq", term.getCode())
            .timeout(timeout)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .method(Connection.Method.POST)
            .execute()
        val statusCode = response.statusCode()
        val body = response.body()
        // 错误分类对齐 iOS：登录特征 / 401 / 403 → 会话失效；5xx → 上游不可用。
        if (statusCode == 401 || statusCode == 403 || isAuthExpiredResponse(response, body)) {
            throw EastSessionExpiredException("登录已失效，请重新登录")
        }
        if (statusCode in 500..599) throw IllegalStateException("服务器错误，请稍后重试")
        if (statusCode != 200) {
            throw IllegalStateException("HTTP $statusCode")
        }
        ensureTimetableResponse(term, body, statusCode)
        val parsedCourses = BenbuScheduleParser.parseScheduleHtml(body)
        val courses = mergeAdjacentCourses(parsedCourses, scheduleFor(token))
        logEmptyTimetableDetails(term, body, courses)
        LogUtils.d( "getTimetableOfTermSync: term=${term.getCode()} rawCourseCount=${parsedCourses.size} mergedCourseCount=${courses.size} cookieKeys=${token.cookies.keys.sorted()} ${cookieFingerprintSummary(token.cookies)}")
        return courses
    }


    private fun cookieFingerprintSummary(cookies: Map<String, String>): String {
        val keys = listOf("JSESSIONID", "HIT", "TWFID")
        return keys.joinToString(prefix = "[", postfix = "]") { key ->
            "$key=${cookies[key]?.take(8) ?: "-"}"
        }
    }

    private fun mergeAdjacentCourses(
        courses: List<CourseItem>,
        schedule: List<TimePeriodInDay>
    ): List<CourseItem> {
        if (courses.isEmpty()) return courses
        val sorted = courses.sortedWith(
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
        return merged
    }


    /**
     * 威海老教务（jwts）只有本科生，不存在研究生作息。
     * 此前该处硬编码的是已作废的 08:30 版作息，现统一收敛到 [CampusDefaultSchedule]。
     */
    private fun weihaiUndergraduateSchedule(): List<TimePeriodInDay> =
        CampusDefaultSchedule.undergraduate(EASToken.Campus.WEIHAI)

    /**
     * 威海无研究生作息，恒定使用本科生表。
     *
     * 历史上这里按 `token.stutype` 分支，而威海登录链从不设置 stutype（默认 UNDERGRAD），
     * 导致行为上恒为本科生 —— 现改为显式写明，避免以后被误当成可切换。
     */
    private fun scheduleFor(token: EASToken): List<TimePeriodInDay> =
        weihaiUndergraduateSchedule()

    /**
     * 威海老教务无研究生作息，恒定返回威海本科生表。
     */
    protected override fun defaultScheduleStructure(isUndergraduate: Boolean): MutableList<TimePeriodInDay> =
        weihaiUndergraduateSchedule().toMutableList()

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
                val xiaoquCandidates = linkedSetOf("01", "1", "2", "")
                val roomCandidates = linkedSetOf("，", "")

                fun normalizeCode(raw: String): String {
                    val trimmed = raw.trim()
                    return trimmed.trimStart('0').ifEmpty { "0" }
                }

                fun fetchBuildingsForCampus(xiaoqu: String): List<BuildingItem> {
                    if (xiaoqu.isBlank()) return emptyList()
                    return try {
                        val response = Jsoup.connect("$hostName/kjscx/queryJxlListBySjid?sf_request_type=ajax")
                            .cookies(token.cookies)
                            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                            .header("Accept", "application/json, text/javascript, */*; q=0.01")
                            .header("X-Requested-With", "XMLHttpRequest")
                            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                            .data("id", xiaoqu)
                            .timeout(timeout)
                            .ignoreContentType(true)
                            .ignoreHttpErrors(true)
                            .method(Connection.Method.POST)
                            .execute()
                        if (response.statusCode() == 200) parseBuildingJson(response.body()) else emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                var lastResponse: Connection.Response? = null
                var lastBody = ""
                var lastClassrooms: List<ClassroomItem> = emptyList()
                var lastXiaoqu = ""
                var lastLhdm = ""
                var lastCddm = ""

                val targetBuildingCode = normalizeCode(building.id)

                for (xiaoqu in xiaoquCandidates) {
                    val remoteBuildings = fetchBuildingsForCampus(xiaoqu)
                    val selectedBuildingName = building.name?.trim().orEmpty()
                    val matchedRemoteCodes = remoteBuildings
                        .filter { remote ->
                            normalizeCode(remote.id) == targetBuildingCode ||
                                (selectedBuildingName.isNotBlank() && remote.name?.trim() == selectedBuildingName)
                        }
                        .map { it.id.trim() }

                    val buildingCandidates = linkedSetOf<String>()
                    matchedRemoteCodes.forEach { buildingCandidates.add(it) }
                    buildingCandidates.add(building.id.trim())
                    buildingCandidates.add(building.id.trimStart('0'))
                    buildingCandidates.add(building.id.trimStart('0').padStart(2, '0'))
                    buildingCandidates.add("")

                    LogUtils.d(
                        "queryEmptyClassroom: xiaoqu=$xiaoqu remoteBuildings=${remoteBuildings.size} matchedRemote=$matchedRemoteCodes buildingCandidates=$buildingCandidates"
                    )

                    for (lhdm in buildingCandidates) {
                        for (cddm in roomCandidates) {
                            val params = linkedMapOf(
                                "pageXnxq" to term.getCode(),
                                "pageZc1" to weekStart,
                                "pageZc2" to weekEnd,
                                "pageXiaoqu" to xiaoqu,
                                "pageLhdm" to lhdm,
                                "pageCddm" to cddm
                            )
                            LogUtils.d(
                                "queryEmptyClassroom: term=${term.getCode()}, building=${building.id} weeks=$weekStart-$weekEnd path=/kjscx/queryKjs pageXiaoqu=${params["pageXiaoqu"]} pageLhdm=${params["pageLhdm"]} pageCddm=${params["pageCddm"]}"
                            )

                            val response = Jsoup.connect("$hostName/kjscx/queryKjs?$WEBVPN_JWTS_QUERY_HINT")
                                .cookies(token.cookies)
                                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                                .header("Origin", "https://webvpn.hitwh.edu.cn")
                                .header("Referer", "$hostName/kjscx/queryKjs")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                                .header("sec-fetch-site", "same-origin")
                                .header("sec-fetch-mode", "cors")
                                .header("sec-fetch-dest", "empty")
                                .data(params)
                                .timeout(timeout)
                                .ignoreContentType(true)
                                .ignoreHttpErrors(true)
                                .method(Connection.Method.POST)
                                .execute()

                            val body = response.body()
                            if (isAuthExpiredResponse(response, body)) {
                                LogUtils.w(
                                    "queryEmptyClassroom: auth expired, term=${term.getCode()} building=${building.id} weeks=$weekStart-$weekEnd status=${response.statusCode()}"
                                )
                                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                                return@execute
                            }
                            if (response.statusCode() != 200) {
                                continue
                            }

                            val classrooms = BenbuClassroomParser.parseEmptyClassroomHtml(body)
                            val doc = Jsoup.parse(body)
                            val title = doc.title().trim()
                            val tableCount = doc.select("table").size
                            val firstNames = classrooms.take(6).map { it.name }
                            LogUtils.d(
                                "queryEmptyClassroom: parsed=${classrooms.size} title=$title tables=$tableCount first=$firstNames pageXiaoqu=$xiaoqu pageLhdm=$lhdm pageCddm=$cddm"
                            )

                            lastResponse = response
                            lastBody = body
                            lastClassrooms = classrooms
                            lastXiaoqu = xiaoqu
                            lastLhdm = lhdm
                            lastCddm = cddm

                            if (classrooms.isNotEmpty()) {
                                result.postValue(DataState(classrooms))
                                return@execute
                            }
                        }
                    }
                }

                val response = lastResponse
                if (response == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "HTTP request failed"))
                    return@execute
                }

                val doc = Jsoup.parse(lastBody)
                if (lastClassrooms.isEmpty()) {
                    val rowSummaries = doc.select("table.dataTable tr")
                        .take(8)
                        .mapIndexed { idx, row ->
                            val tdCount = row.select("td").size
                            val thCount = row.select("th").size
                            val text = row.text().replace(Regex("\\s+"), " ").take(80)
                            "r$idx td=$tdCount th=$thCount text=$text"
                        }
                    val headerInputs = doc.select("form input[name], form select[name]")
                        .take(16)
                        .joinToString { element ->
                            "${element.tagName()}[name=${element.attr("name")},value=${element.`val`().take(20)}]"
                        }
                    LogUtils.w(
                        "queryEmptyClassroom: empty details pageXiaoqu=$lastXiaoqu pageLhdm=$lastLhdm pageCddm=$lastCddm rows=$rowSummaries formFields=$headerInputs"
                    )
                }
                result.postValue(DataState(lastClassrooms))
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }

        return result
    }


    override fun getExamItems(token: EASToken, term: TermItem?): LiveData<DataState<List<ExamItem>>> {
        val result = MutableLiveData<DataState<List<ExamItem>>>()
        result.postValue(DataState(DataState.STATE.LOADING))
        executor.execute {
            try {
                // 获取所有考试时间段：01=期末，02=期中，03=补考
                val examPeriods = listOf("01", "02", "03")
                val allExams = mutableListOf<ExamItem>()

                for (period in examPeriods) {
                    val termCode = if (term != null) term.getCode() else "2025-20262"

                    val response = Jsoup.connect("$hostName/kscx/queryKcForXs")
                        .cookies(token.cookies)
                        .data("xnxq", termCode)
                        .data("kssjd", period)
                        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Origin", "https://webvpn.hitwh.edu.cn")
                        .header("Referer", "$hostName/kscx/queryKcForXs")
                        .timeout(timeout)
                        .ignoreContentType(true)
                        .ignoreHttpErrors(true)
                        .method(Connection.Method.POST)
                        .execute()

                    if (response.statusCode() != 200) {
                        continue
                    }

                    val doc = Jsoup.parse(response.body())
                    val examRows = doc.select("table.bot_line tr")

                    for (row in examRows) {
                        val cells = row.select("td")
                        if (cells.size < 6) continue

                        // 第一列通常是序号
                        val firstCellText = cells[0].text().trim()
                        if (firstCellText.matches(Regex("\\d+"))) {
                            val exam = ExamItem()
                            exam.courseName = cells[1].text().trim()
                            // exam.code = cells[2].text().trim()  // ExamItem没有code字段
                            exam.examLocation = cells[3].text().trim()
                            // 座位号：ExamItem没有此字段
                            // val seatNumber = cells[4].text().trim()

                            // 解析时间：2026年05月14日(第10周     星期四)14:00-16:00
                            val timeText = cells[5].text().trim()
                            parseExamTime(timeText, exam)

                            // 设置考试类型
                            exam.examType = when (period) {
                                "01" -> "期末"
                                "02" -> "期中"
                                "03" -> "补考"
                                else -> "考试"
                            }

                            // 设置学期信息
                            exam.termName = term?.name
                            exam.termId = term?.id
                            exam.campusName = "威海校区"

                            allExams.add(exam)
                        }
                    }
                }

                if (allExams.isEmpty()) {
                    result.postValue(DataState(emptyList(), DataState.STATE.SUCCESS))
                } else {
                    result.postValue(DataState(allExams, DataState.STATE.SUCCESS))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message ?: "查询考试失败"))
            }
        }
        return result
    }

    /**
     * 解析威海考试时间格式
     * 格式示例：2026年05月14日(第10周     星期四)14:00-16:00
     */
    private fun parseExamTime(timeText: String, exam: ExamItem) {
        try {
            // 提取日期和时间部分
            val datePattern = Regex("(\\d{4})年(\\d{2})月(\\d{2})日")
            val dateMatch = datePattern.find(timeText)

            val timePattern = Regex("(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})")
            val timeMatch = timePattern.find(timeText)

            if (dateMatch != null) {
                val year = dateMatch.groupValues[1].toInt()
                val month = dateMatch.groupValues[2].toInt()
                val day = dateMatch.groupValues[3].toInt()
                exam.examDate = String.format("%04d-%02d-%02d", year, month, day)
            }

            if (timeMatch != null) {
                val startHour = timeMatch.groupValues[1]
                val startMin = timeMatch.groupValues[2]
                val endHour = timeMatch.groupValues[3]
                val endMin = timeMatch.groupValues[4]
                exam.examTime = "$startHour:$startMin-$endHour:$endMin"
            }

            // 如果解析失败，设置原始文本
            if (exam.examDate == null) {
                exam.examDate = ""
                exam.examTime = timeText
            }
        } catch (e: Exception) {
            exam.examDate = ""
            exam.examTime = timeText
        }
    }


    override fun requestScores(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): Connection.Response {
        val (path, params) = when (testType) {
            EASService.TestType.ALL -> {
                "/cjcx/queryQmcj" to linkedMapOf(
                    "pageXnxq" to term.getCode(),
                    "pageBkcxbj" to "",
                    "pageSfjg" to "",
                    "pageKcmc" to "",
                )
            }
            EASService.TestType.NORMAL -> {
                "/cjcx/queryQmcj" to linkedMapOf(
                    "pageXnxq" to term.getCode(),
                    "pageBkcxbj" to "",
                    "pageSfjg" to "",
                    "pageKcmc" to "",
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
                    "pageKcmc" to "",
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

        if (body.contains("\"code\":\"reAuth_success\"", ignoreCase = true)
            || body.contains("\"code\": " + "\"reAuth_success\"", ignoreCase = true)
        ) {
            return false
        }

        val doc = Jsoup.parse(body)
        val title = doc.title().lowercase()
        val text = doc.body()?.text()?.replace(Regex("\\s+"), " ")?.lowercase().orEmpty()

        val hasLoginForm = doc.select("input[name=mm], input[name=password], form[action*=login], form[action*=authentication]").isNotEmpty()
        val hasReAuthPageMarker = body.contains("reAuth", ignoreCase = true)
                && !body.contains("reAuth_success", ignoreCase = true)

        val hasScoreBusinessMarker = title.contains("成绩查询")
                || body.contains("pageBkcxbj", ignoreCase = true)
                || body.contains("queryQmcj", ignoreCase = true)
                || body.contains("queryQzcj", ignoreCase = true)
                || body.contains("cjcx/query", ignoreCase = true)

        val isJsChallengePage = text.contains("your browser does not support javascript")
                || text.contains("javascript is disabled in your browser")
                || text.contains("please enable javascript")
                || text.contains("enable javascript to continue")
                || title.contains("just a moment")
                || title.contains("attention required")

        if (isJsChallengePage) return true
        if (hasReAuthPageMarker) return true
        if (hasLoginForm) return true
        if (hasScoreBusinessMarker) return false
        if (title.contains("登录") || title.contains("统一身份认证") || text.contains("未登录")) {
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
            extractWeihaiScoreSummary(Jsoup.parse(response.body()))
        } catch (_: Exception) {
            null
        }
    }

    private fun extractWeihaiScoreSummary(doc: Document): ScoreSummary? {
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


    private fun List<TermItem>.filterVisibleTerms(): List<TermItem> {
        val current = firstOrNull { it.isCurrent }
        if (current == null) {
            return this
        }
        val currentKey = termTimelineKey(current)
        val filtered = filter { term ->
            val key = termTimelineKey(term)
            key.first < currentKey.first || (key.first == currentKey.first && key.second <= currentKey.second)
        }
        return if (filtered.isNotEmpty()) filtered else this
    }


}
