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
    private val onCookiesUpdated: ((EASToken) -> Unit)? = null
) : EASService {
    private val hostName = "https://webvpn.hitwh.edu.cn/http/77726476706e69737468656265737421fae0558f693861446900c7a99c406d3667"
    private val timeout = AppConstants.Network.READ_TIMEOUT.toInt()
    private val executor = Executors.newCachedThreadPool()

    companion object {
        private const val WEBVPN_JWTS_QUERY_HINT = "vpn-12-o1-jwts.hitwh.edu.cn"
        private val SAFE_PERSONAL_INFO_LABELS = setOf("姓名", "学号", "院系", "学院", "系", "专业", "年级", "班级")
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

    private fun parseCookiesFromJson(json: String): HashMap<String, String> {
        val cookiesMap = HashMap<String, String>()
        try {
            val jsonObject = JSONObject(json)
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                cookiesMap[key] = jsonObject.getString(key)
            }
        } catch (e: Exception) {
            // ignore
        }
        return cookiesMap
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

    override fun getAllTerms(token: EASToken): LiveData<DataState<List<TermItem>>> {
        val result = MutableLiveData<DataState<List<TermItem>>>()
        result.postValue(DataState(DataState.STATE.NOTHING))

        executor.execute {
            try {
                val scoreFetch = fetchTermDoc(token, "$hostName/cjcx/queryQmcj", "pageXnxq")
                val timetableFetch = fetchTermDoc(token, "$hostName/kbcx/queryGrkb", "xnxq")

                if (scoreFetch.authExpired && timetableFetch.authExpired) {
                    LogUtils.w("getAllTerms: auth expired on both term pages")
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                    return@execute
                }

                val scoreDoc = scoreFetch.doc
                val timetableDoc = timetableFetch.doc

                val scoreTerms = scoreDoc?.let { parseTermsFromDoc(it, "pageXnxq") }.orEmpty()
                val timetableTerms = timetableDoc?.let { parseTermsFromDoc(it, "xnxq") }.orEmpty()
                val terms = mergeTerms(scoreTerms, timetableTerms)

                terms.sortWith(compareBy<TermItem> { termTimelineKey(it).first }.thenBy { termTimelineKey(it).second })
                if (terms.none { it.isCurrent }) {
                    val currentCode = scoreDoc?.let { currentTermValueFromDoc(it, "pageXnxq") }
                        ?: timetableDoc?.let { currentTermValueFromDoc(it, "xnxq") }
                    if (!currentCode.isNullOrBlank()) {
                        terms.firstOrNull { it.getCode() == currentCode }?.isCurrent = true
                    }
                }

                val visibleTerms = terms.filterVisibleTerms().toMutableList()

                LogUtils.d("getAllTerms: score=${scoreTerms.map { it.getCode() }} timetable=${timetableTerms.map { it.getCode() }}")
                LogUtils.d("getAllTerms: parsed=${visibleTerms.map { "${it.getCode()}:${it.name}:current=${it.isCurrent}" }}")
                if (visibleTerms.isEmpty()) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "未获取到学期列表"))
                } else {
                    result.postValue(DataState(visibleTerms, DataState.STATE.SUCCESS))
                }
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message ?: "获取学期失败"))
            }
        }
        return result
    }


    override fun getStartDate(token: EASToken, term: TermItem): LiveData<DataState<Calendar>> {
        val result = MutableLiveData<DataState<Calendar>>()
        result.postValue(DataState(DataState.STATE.NOTHING))

        executor.execute {
            try {
                val start = inferTermStartDate(term)
                LogUtils.d("getStartDate: term=${term.getCode()} inferredStart=${start.time} termName=${term.termName} label=${term.name}")
                result.postValue(DataState(start, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message ?: "获取开学日期失败"))
            }
        }
        return result
    }

    override fun getSubjectsOfTerm(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<MutableList<TermSubject>>> {
        return MutableLiveData<DataState<MutableList<TermSubject>>>().apply {
            postValue(DataState(DataState.STATE.FETCH_FAILED, "威海暂不支持课程列表"))
        }
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

                if (isWeihaiAuthExpiredResponse(response, body)) {
                    LogUtils.w("getTimetableOfTerm: auth expired, term=$termCode status=$statusCode")
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
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

    override fun getScheduleStructure(
        term: TermItem,
        isUndergraduate: Boolean?,
        token: EASToken
    ): LiveData<DataState<MutableList<TimePeriodInDay>>> {
        val result = MutableLiveData<DataState<MutableList<TimePeriodInDay>>>()
        result.postValue(DataState(DataState.STATE.NOTHING))

        executor.execute {
            try {
                val courses = getTimetableOfTermSync(term, token)
                val maxPeriod = courses.maxOfOrNull { it.begin + it.last - 1 } ?: 0
                // 威海老教务只有本科生作息：忽略 isUndergraduate / stutype。
                val schedule = weihaiUndergraduateSchedule().toMutableList()
                val resolved = if (maxPeriod in 1 until schedule.size) {
                    schedule.take(maxPeriod).toMutableList()
                } else {
                    schedule
                }
                LogUtils.d("getScheduleStructure: term=${term.getCode()} courseCount=${courses.size} maxPeriod=$maxPeriod resolvedSize=${resolved.size}")
                result.postValue(DataState(resolved, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                LogUtils.w("getScheduleStructure: fallback to default: ${e.message}")
                result.postValue(DataState(
                    weihaiUndergraduateSchedule().toMutableList(),
                    DataState.STATE.SUCCESS
                ))
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
                    val response = Jsoup.connect("$hostName/kjscx/queryJxlListBySjid?sf_request_type=ajax")
                        .cookies(token.cookies)
                        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                        .header("Accept", "application/json, text/javascript, */*; q=0.01")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .data("id", campusId)
                        .timeout(timeout)
                        .ignoreContentType(true)
                        .ignoreHttpErrors(true)
                        .method(Connection.Method.POST)
                        .execute()
                    if (response.statusCode() != 200) return
                    parseBuildingJson(response.body()).forEach { item ->
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

    private fun parseBuildingJson(json: String): List<BuildingItem> {
        val buildings = mutableListOf<BuildingItem>()
        try {
            val jsonArray = org.json.JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                buildings.add(BuildingItem().apply {
                    name = obj.optString("MC", "")
                    id = obj.optString("DM", "")
                })
            }
        } catch (e: Exception) {
            // Silent fail
        }
        return buildings
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

    private data class TermDocFetchResult(
        val doc: Document?,
        val authExpired: Boolean
    )

    private fun fetchTermDoc(token: EASToken, url: String, selectName: String): TermDocFetchResult {
        fun executeOnce(): Connection.Response {
            val response = Jsoup.connect(url)
                .cookies(token.cookies)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                .timeout(timeout)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .method(Connection.Method.GET)
                .execute()
            if (response.cookies().isNotEmpty()) {
                token.cookies.putAll(response.cookies())
                onCookiesUpdated?.invoke(token)
            }
            return response
        }

        fun parseValidTermDoc(response: Connection.Response): Document? {
            if (response.statusCode() != 200) return null
            val doc = Jsoup.parse(response.body())
            val hasSelect = doc.select("select[name=$selectName] option").isNotEmpty()
            if (hasSelect) return doc
            val title = doc.title().trim()
            val sample = doc.body()?.text()?.replace(Regex("\\s+"), " ")?.take(120).orEmpty()
            LogUtils.w("fetchTermDoc: missing selector=$selectName url=$url title=$title sample=$sample")
            return null
        }

        return try {
            var response = executeOnce()
            var doc = parseValidTermDoc(response)
            var authExpired = isWeihaiAuthExpiredResponse(response, response.body())

            if (doc == null) {
                LogUtils.d("fetchTermDoc: retry by missing selector select=$selectName url=$url status=${response.statusCode()}")
                authExpired = true
            }

            if (authExpired) {
                LogUtils.w("fetchTermDoc: auth expired url=$url select=$selectName status=${response.statusCode()}")
                TermDocFetchResult(null, true)
            } else if (doc == null) {
                TermDocFetchResult(null, false)
            } else {
                TermDocFetchResult(doc, false)
            }
        } catch (e: Exception) {
            LogUtils.w("fetchTermDoc: failed url=$url select=$selectName err=${e.message}")
            TermDocFetchResult(null, false)
        }
    }

    private fun parseTermsFromDoc(doc: Document, selectName: String): List<TermItem> {
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

    private fun mergeTerms(primary: List<TermItem>, secondary: List<TermItem>): MutableList<TermItem> {
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

    private fun currentTermValueFromDoc(doc: Document, selectName: String): String {
        return doc.select("select[name=$selectName] option[selected]").attr("value").trim()
            .ifEmpty {
                doc.select("select[name=$selectName] option").firstOrNull()?.attr("value")?.trim().orEmpty()
            }
    }


    private fun inferTermStartDate(term: TermItem): Calendar {
        val startYear = extractStartYear(term.yearCode)
        val month = when {
            term.termName.contains("春") -> Calendar.MARCH
            term.termName.contains("夏") -> Calendar.JULY
            term.termName.contains("秋") -> Calendar.SEPTEMBER
            term.termName.contains("冬") || term.termName.contains("寒") -> Calendar.JANUARY
            term.termCode.startsWith("2") -> Calendar.MARCH
            term.termCode.startsWith("3") -> Calendar.JULY
            term.termCode.startsWith("1") -> Calendar.SEPTEMBER
            else -> Calendar.MARCH
        }
        val year = when {
            month == Calendar.SEPTEMBER -> startYear
            term.yearCode.contains("-") -> startYear + 1
            else -> startYear
        }
        return secondMondayOfMonth(year, month)
    }

    private fun extractStartYear(yearCode: String): Int {
        return Regex("""^\d{4}""").find(yearCode)?.value?.toIntOrNull()
            ?: yearCode.toIntOrNull()
            ?: Calendar.getInstance().get(Calendar.YEAR)
    }

    private fun secondMondayOfMonth(year: Int, month: Int): Calendar {
        return Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
            add(Calendar.DAY_OF_MONTH, 7)
        }
    }

    private fun getTimetableOfTermSync(term: TermItem, token: EASToken): List<CourseItem> {
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
        val courses = mergeAdjacentCourses(parsedCourses, scheduleFor(token))
        logEmptyTimetableDetails(term, body, courses)
        LogUtils.d( "getTimetableOfTermSync: term=${term.getCode()} rawCourseCount=${parsedCourses.size} mergedCourseCount=${courses.size} cookieKeys=${token.cookies.keys.sorted()} ${cookieFingerprintSummary(token.cookies)}")
        return courses
    }

    private fun ensureTimetableResponse(term: TermItem, body: String, statusCode: Int) {
        val doc = Jsoup.parse(body)
        val hasTimetableTable = doc.selectFirst("table.addlist_01") != null
        val hasTermSelector = doc.selectFirst("select[name=xnxq]") != null
        if (hasTimetableTable) {
            return
        }
        val title = doc.title().trim()
        val sampleText = doc.body()?.text()?.replace(Regex("\\s+"), " ")?.take(160).orEmpty()
        LogUtils.w(
            "ensureTimetableResponse: unexpected response, term=${term.getCode()} status=$statusCode hasTermSelector=$hasTermSelector title=$title sample=$sampleText"
        )
        throw IllegalStateException(
            if (hasTermSelector) "课表页返回异常，未找到课表表格"
            else "会话可能已失效，返回的不是课表页"
        )
    }

    private fun logEmptyTimetableDetails(term: TermItem, body: String, courses: List<CourseItem>) {
        if (courses.isNotEmpty()) return
        val doc = Jsoup.parse(body)
        val table = doc.selectFirst("table.addlist_01")
        val rows = table?.select("tr").orEmpty()
        val rowSummaries = rows.take(6).mapIndexed { index, row ->
            val cells = row.select("td,th")
            val texts = cells.take(8).map { cell ->
                cell.text().replace(Regex("\\s+"), " ").trim().take(40)
            }
            "r$index cells=${cells.size} texts=$texts"
        }
        val headerRowCellCount = rows.firstOrNull()?.select("td,th")?.size ?: 0
        val formInputs = doc.select("form input[name], form select[name]")
            .take(20)
            .joinToString { element ->
                val value = element.`val`().replace(Regex("\\s+"), " ").take(30)
                "${element.tagName()}[name=${element.attr("name")},value=$value]"
            }
        val bodySample = doc.body()?.text()?.replace(Regex("\\s+"), " ")?.take(240).orEmpty()
        LogUtils.w(
            "logEmptyTimetableDetails: empty timetable, term=${term.getCode()} hasAddlist01=${table != null} rows=${rows.size} headerCells=$headerRowCellCount rowSummaries=$rowSummaries formFields=$formInputs sample=$bodySample"
        )
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

    private fun canMergeCourses(
        left: CourseItem,
        right: CourseItem,
        schedule: List<TimePeriodInDay>
    ): Boolean {
        if (left.dow != right.dow) return false
        if (normalized(left.name) != normalized(right.name)) return false
        if (normalized(left.teacher) != normalized(right.teacher)) return false
        if (left.weeks.sorted() != right.weeks.sorted()) return false

        val leftEndPeriod = left.begin + left.last - 1
        if (leftEndPeriod + 1 != right.begin) return false

        if (leftEndPeriod !in 1..schedule.size || right.begin !in 1..schedule.size) return false

        val leftEndTime = schedule[leftEndPeriod - 1].to
        val rightStartTime = schedule[right.begin - 1].from
        val gapMinutes = leftEndTime.getDistanceInMinutes(rightStartTime)
        if (gapMinutes < 0 || gapMinutes >= 30) return false

        val leftClassroom = normalized(left.classroom)
        val rightClassroom = normalized(right.classroom)
        if (leftClassroom.isNotEmpty() && rightClassroom.isNotEmpty() && leftClassroom != rightClassroom) {
            return false
        }

        val leftCode = normalized(left.code)
        val rightCode = normalized(right.code)
        if (leftCode.isNotEmpty() && rightCode.isNotEmpty() && leftCode != rightCode) {
            return false
        }

        return true
    }

    private fun normalized(value: String?): String {
        return value?.trim().orEmpty()
    }

    private fun copyCourse(source: CourseItem): CourseItem {
        return CourseItem().apply {
            code = source.code
            name = source.name
            weeks = source.weeks.toMutableList()
            teacher = source.teacher
            classroom = source.classroom
            dow = source.dow
            begin = source.begin
            last = source.last
        }
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
                            if (isWeihaiAuthExpiredResponse(response, body)) {
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


    override fun getPersonalScores(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): LiveData<DataState<List<CourseScoreItem>>> {
        val result = MutableLiveData<DataState<List<CourseScoreItem>>>()
        result.value = DataState(DataState.STATE.NOTHING)

        executor.execute {
            try {
                val response = requestWeihaiScores(term, token, testType)
                val body = response.body()
                if (response.statusCode() == 200) {
                    if (isWeihaiAuthExpiredResponse(response, body)) {
                        logScoreFailure("getPersonalScores", term, testType, response.statusCode(), body)
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                        return@execute
                    }
                    val parsed = BenbuScoreParser.parseGradesHtml(body)
                    val filtered = parsed.filter { item -> matchesRequestedTerm(item.termName, term) }
                    val scores = if (filtered.isNotEmpty() || parsed.isEmpty()) filtered else parsed
                    logScoreDebug("getPersonalScores", term, testType, response.statusCode(), body, parsed, filtered)
                    result.postValue(DataState(scores))
                } else {
                    if (isWeihaiAuthExpiredResponse(response, body)) {
                        logScoreFailure("getPersonalScores", term, testType, response.statusCode(), body)
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                    } else {
                        logScoreFailure("getPersonalScores", term, testType, response.statusCode(), body)
                        result.postValue(DataState(DataState.STATE.FETCH_FAILED, "HTTP ${response.statusCode()}"))
                    }
                }
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }

        return result
    }

    fun getPersonalScoresWithSummary(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): LiveData<DataState<ScoreQueryResult>> {
        val result = MutableLiveData<DataState<ScoreQueryResult>>()
        result.value = DataState(DataState.STATE.NOTHING)

        executor.execute {
            try {
                val scoreResponse = requestWeihaiScores(term, token, testType)
                val body = scoreResponse.body()
                if (scoreResponse.statusCode() != 200) {
                    if (isWeihaiAuthExpiredResponse(scoreResponse, body)) {
                        logScoreFailure("getPersonalScoresWithSummary", term, testType, scoreResponse.statusCode(), body)
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                    } else {
                        logScoreFailure("getPersonalScoresWithSummary", term, testType, scoreResponse.statusCode(), body)
                        result.postValue(DataState(DataState.STATE.FETCH_FAILED, "HTTP ${scoreResponse.statusCode()}"))
                    }
                    return@execute
                }
                if (isWeihaiAuthExpiredResponse(scoreResponse, body)) {
                    logScoreFailure("getPersonalScoresWithSummary", term, testType, scoreResponse.statusCode(), body)
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                    return@execute
                }
                val parsed = BenbuScoreParser.parseGradesHtml(body)
                val filtered = parsed.filter { item -> matchesRequestedTerm(item.termName, term) }
                val scores = if (filtered.isNotEmpty() || parsed.isEmpty()) filtered else parsed
                logScoreDebug("getPersonalScoresWithSummary", term, testType, scoreResponse.statusCode(), body, parsed, filtered)
                val summary = fetchWeihaiScoreSummary(token)
                result.postValue(DataState(ScoreQueryResult(scores, summary), DataState.STATE.SUCCESS))
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

    override fun getSafePersonalInfo(token: EASToken): LiveData<DataState<EASToken>> {
        val result = MutableLiveData<DataState<EASToken>>()
        result.value = DataState(DataState.STATE.NOTHING)
        executor.execute {
            try {
                val response = Jsoup.connect("$hostName/xswhxx/queryXswhxx")
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
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "HTTP ${response.statusCode()}"))
                    return@execute
                }
                val enriched = mergeSafePersonalInfo(token, Jsoup.parse(response.body()))
                result.postValue(DataState(enriched, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message ?: "获取个人信息失败"))
            }
        }
        return result
    }

    private fun mergeSafePersonalInfo(token: EASToken, doc: Document): EASToken {
        val valuesByLabel = mutableMapOf<String, String>()
        doc.select("table.addlist tr").forEach { row ->
            val cells = row.children()
            for (i in 0 until cells.size - 1) {
                val cell = cells[i]
                if (cell.tagName() != "th") continue
                val label = normalizePersonalInfoLabel(cell.text())
                if (label !in SAFE_PERSONAL_INFO_LABELS) continue
                val valueCell = cells.drop(i + 1).firstOrNull { it.tagName() == "td" } ?: continue
                val inputValue = valueCell.selectFirst("input[value]")?.attr("value")?.trim().orEmpty()
                val textValue = valueCell.text().replace(Regex("\\s+"), " ").trim()
                val value = inputValue.ifBlank { textValue }.replace("\u00A0", " ").trim()
                if (value.isNotBlank()) {
                    valuesByLabel[label] = value
                }
            }
        }
        return EASToken().also { enriched ->
            enriched.accessToken = token.accessToken
            enriched.refreshToken = token.refreshToken
            enriched.cookies = HashMap(token.cookies)
            enriched.campus = token.campus
            enriched.username = token.username?.takeIf { it.isNotBlank() }
                ?: valuesByLabel["学号"]
                ?: token.stuId
            enriched.password = token.password
            enriched.stutype = token.stutype
            enriched.picture = token.picture
            enriched.id = token.id
            enriched.sfxsx = token.sfxsx
            enriched.email = token.email
            enriched.phone = token.phone
            enriched.name = valuesByLabel["姓名"] ?: token.name
            enriched.stuId = valuesByLabel["学号"] ?: token.stuId
            enriched.school = valuesByLabel["学院"] ?: valuesByLabel["系"] ?: valuesByLabel["院系"] ?: token.school
            enriched.major = valuesByLabel["专业"] ?: token.major
            enriched.grade = valuesByLabel["年级"] ?: token.grade
            enriched.className = valuesByLabel["班级"] ?: token.className
            enriched.electronicExpToken = token.electronicExpToken
        }
    }

    private fun extractLoginIdentity(cookies: Map<String, String>): String? {
        return null
    }

    private fun requestWeihaiScores(
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
        LogUtils.d( "requestWeihaiScores: term=${term.getCode()} name=${term.termName} testType=$testType path=$path params=$params")

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
        if (isWeihaiAuthExpiredResponse(response, response.body()) && tryRelogin(token)) {
            response = executeOnce()
        }
        return response
    }

    private fun tryRelogin(token: EASToken): Boolean {
        // WebView-based relogin is handled at Activity level via handleSessionExpired
        return false
    }

    private fun isWeihaiAuthExpiredResponse(resp: Connection.Response, body: String): Boolean {
        if (resp.statusCode() == 401 || resp.statusCode() == 403) return true

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


    private fun fetchWeihaiScoreSummary(token: EASToken): ScoreSummary? {
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

    private fun logScoreDebug(
        stage: String,
        term: TermItem,
        testType: EASService.TestType,
        statusCode: Int,
        body: String,
        parsed: List<CourseScoreItem>,
        filtered: List<CourseScoreItem>
    ) {
        val doc = Jsoup.parse(body)
        val title = doc.title().trim()
        val forms = doc.select("form").size
        val tables = doc.select("table").size
        val termSnippets = parsed.take(8).map { it.termName.orEmpty() }
        val sampleCourses = parsed.take(8).map { "${it.courseName.orEmpty()}|${it.termName.orEmpty()}|${it.finalScores}" }
        LogUtils.d(
            "$stage: term=${term.getCode()} name=${term.termName} testType=$testType status=$statusCode parsed=${parsed.size} filtered=${filtered.size} title=$title forms=$forms tables=$tables termSnippets=$termSnippets sampleCourses=$sampleCourses"
        )
    }

    private fun logScoreFailure(
        stage: String,
        term: TermItem,
        testType: EASService.TestType,
        statusCode: Int,
        body: String
    ) {
        val doc = Jsoup.parse(body)
        val title = doc.title().trim()
        val sample = doc.body()?.text()?.replace(Regex("\\s+"), " ")?.take(200).orEmpty()
        LogUtils.w(
            "$stage: failed, term=${term.getCode()} name=${term.termName} testType=$testType status=$statusCode title=$title sample=$sample"
        )
    }

    private fun matchesRequestedTerm(rawTermName: String?, term: TermItem): Boolean {
        val compact = rawTermName?.replace(Regex("\\s+"), "")?.trim().orEmpty()
        if (compact.isBlank()) return true

        val yearCodeCompact = term.yearCode.replace(Regex("\\s+"), "")
        val yearDigits = yearCodeCompact.replace("-", "")
        val termCodeCompact = term.termCode.replace(Regex("\\s+"), "")
        val aliases = linkedSetOf(
            term.termName.replace(Regex("\\s+"), ""),
            term.name.replace(Regex("\\s+"), ""),
            yearCodeCompact,
            yearDigits,
            "$yearCodeCompact$termCodeCompact",
            "$yearDigits$termCodeCompact"
        ).filter { it.isNotBlank() }

        if (aliases.any { alias -> compact.contains(alias) || alias.contains(compact) }) {
            return true
        }

        val normalized = compact
            .replace("学年", "")
            .replace("学期", "")
            .replace("-", "")
        val seasonMatches = when {
            term.termName.contains("秋") -> listOf("秋", "秋季", "秋季学期", "第一学期", "第一", "上学期")
            term.termName.contains("春") -> listOf("春", "春季", "春季学期", "第二学期", "第二", "下学期")
            term.termName.contains("夏") -> listOf("夏", "夏季", "夏季学期", "第三学期", "第三")
            term.termName.contains("寒") || term.termName.contains("冬") ->
                listOf("寒", "寒假", "冬", "冬季", "冬季学期", "第四学期", "第四")
            else -> emptyList()
        }
        return seasonMatches.any { season -> normalized.contains(season) } &&
            (normalized.contains(yearCodeCompact.substringBefore('-')) ||
                normalized.contains(yearCodeCompact.substringAfter('-', "")) ||
                normalized.contains(yearDigits))
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

    private fun termTimelineKey(term: TermItem): Pair<Int, Int> {
        val year = termSortYear(term)
        val order = termSortOrder(term)
        return year to order
    }

    private fun termSortYear(term: TermItem): Int {
        extractYearFromLabel(term.termName)?.let { return it }
        extractYearFromLabel(term.name)?.let { return it }

        val startYear = extractStartYear(term.yearCode)
        val endYear = extractEndYear(term.yearCode) ?: startYear
        return when {
            term.termName.contains("秋") || term.termCode.startsWith("1") -> startYear
            else -> endYear
        }
    }

    private fun termSortOrder(term: TermItem): Int {
        return when {
            term.termName.contains("秋") || term.termCode.startsWith("1") -> 0
            term.termName.contains("春") || term.termCode.startsWith("2") -> 1
            term.termName.contains("夏") || term.termCode.startsWith("3") -> 2
            term.termName.contains("寒") || term.termName.contains("冬") || term.termCode.startsWith("4") -> 3
            else -> 9
        }
    }

    private fun extractYearFromLabel(text: String?): Int? {
        val normalized = text?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return Regex("""(?:19|20)\d{2}""")
            .find(normalized)
            ?.value
            ?.toIntOrNull()
    }

    private fun extractEndYear(yearCode: String): Int? {
        val match = Regex("""^\d{4}-(\d{4})$""").find(yearCode.trim())
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }


    private fun normalizePersonalInfoLabel(raw: String): String {
        return raw.replace("：", "")
            .replace(":", "")
            .replace("*", "")
            .replace(Regex("\\s+"), "")
            .trim()
    }

}
