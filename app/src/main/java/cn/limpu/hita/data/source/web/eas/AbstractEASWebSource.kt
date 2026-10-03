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

/**
 * 三校区教务 Web 数据源的公共基类（本部 / 威海）。
 *
 * 深圳校区（[EASWebSource]）采用 bearer token + 独立选课通道，架构差异较大，暂不纳入。
 *
 * 本类收拢两类成员：
 * 1. **完全一致的方法**（24 个）：直接上移，子类删除重复实现；
 * 2. **模板方法**：骨架一致、仅个别步骤随校区变化，差异点收敛为抽象/开放钩子：
 *    - [isAuthExpiredResponse]：各校区登录失效页特征不同；
 *    - [requestScores] / [fetchScoreSummary]：成绩查询的请求路径与汇总接口不同；
 *    - [parseTermsFromDoc] / [mergeTerms]：学期下拉框解析与合并策略不同；
 *    - [defaultScheduleStructure]：本部/威海作息时间表不同；
 *    - [loadCoursesForSchedule]：课表数据源不同（本部带缓存，威海直查）；
 *    - [getGraduateTermsOrNull]：仅本部有研究生教务；
 *    - [postProcessTerms]：威海需过滤不可见学期；
 *    - [campusDisplayName] / [timetableDebugCellSelector]：文案与调试选择器差异。
 */
abstract class AbstractEASWebSource(

    protected val onCookiesUpdated: ((EASToken) -> Unit)? = null,
) : EASService {

    /** 校区教务主域名，如本部 WebVPN / 威海 WebVPN 入口。 */

    protected abstract val hostName: String

    /** 校区中文名，用于用户可见文案（如"本部"/"威海"）。 */

    protected abstract val campusDisplayName: String


    protected val timeout: Int = AppConstants.Network.READ_TIMEOUT.toInt()

    protected val executor = Executors.newCachedThreadPool()

    companion object {
        internal val SAFE_PERSONAL_INFO_LABELS = setOf("姓名", "学号", "院系", "学院", "系", "专业", "年级", "班级")
    }

    // ------------------------------------------------------------------
    // 校区差异钩子
    // ------------------------------------------------------------------

    /** 判断响应是否为"登录失效/需重新登录"页面，各校区特征页不同。 */

    protected abstract fun isAuthExpiredResponse(response: Connection.Response, body: String): Boolean

    /** 发起成绩查询请求，各校区路径与参数不同。 */

    protected abstract fun requestScores(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): Connection.Response

    /** 查询成绩汇总（加权均分/排名），各校区接口不同。 */

    protected abstract fun fetchScoreSummary(token: EASToken): ScoreSummary?

    /** 解析学期下拉框，各校区 option 结构不同。 */

    protected abstract fun parseTermsFromDoc(doc: Document, selectName: String): List<TermItem>

    /** 合并成绩页/课表页两份学期列表，合并策略各校区不同。 */

    protected abstract fun mergeTerms(primary: List<TermItem>, secondary: List<TermItem>): MutableList<TermItem>

    /** 默认作息时间表，本部/威海不同。 */

    protected abstract fun defaultScheduleStructure(isUndergraduate: Boolean): MutableList<TimePeriodInDay>

    /** 课表结构页的数据来源：本部走带缓存的 getCachedOrFetchCourses，威海直查。 */

    protected abstract fun loadCoursesForSchedule(term: TermItem, token: EASToken): List<CourseItem>

    /** 研究生学期列表，仅本部实现；默认返回 null 表示跳过该分支。 */

    protected open fun getGraduateTermsOrNull(token: EASToken): List<TermItem>? = null

    /** 学期列表后处理，威海过滤不可见学期；默认原样返回。 */

    protected open fun postProcessTerms(terms: MutableList<TermItem>): MutableList<TermItem> = terms

    /** 空课表调试日志的单元格选择器，威海表头用 th。 */

    protected open val timetableDebugCellSelector: String = "td"


    protected data class TermDocFetchResult(
        val doc: Document?,
        val authExpired: Boolean
    )

    /**
     * 按校区 id 查询教学楼列表（`kjscx/queryJxlListBySjid`）。
     * 本部依次查 "1"/"2"，威海先试 "01" 再回退，二者编排逻辑不同，见各自的 getTeachingBuildings。
     */

    protected fun queryBuildingsByCampusId(token: EASToken, campusId: String): List<BuildingItem> {
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
        if (response.statusCode() != 200) return emptyList()
        return parseBuildingJson(response.body())
    }


    protected fun fetchTermDoc(token: EASToken, url: String, selectName: String): TermDocFetchResult {
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
            var authExpired = isAuthExpiredResponse(response, response.body())

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


    override fun getAllTerms(token: EASToken): LiveData<DataState<List<TermItem>>> {
        val result = MutableLiveData<DataState<List<TermItem>>>()
        result.postValue(DataState(DataState.STATE.NOTHING))

        executor.execute {
            try {
                val graduateTerms = getGraduateTermsOrNull(token)
                if (graduateTerms != null) {
                    if (graduateTerms.isEmpty()) {
                        result.postValue(DataState(DataState.STATE.FETCH_FAILED, "未获取到研究生学期列表"))
                    } else {
                        result.postValue(DataState(graduateTerms, DataState.STATE.SUCCESS))
                    }
                    return@execute
                }
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

                LogUtils.d("getAllTerms: score=${scoreTerms.map { it.getCode() }} timetable=${timetableTerms.map { it.getCode() }}")
                LogUtils.d("getAllTerms: parsed=${terms.map { "${it.getCode()}:${it.name}:current=${it.isCurrent}" }}")
                if (terms.isEmpty()) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "未获取到学期列表"))
                } else {
                    // 服务端下拉框代表当前允许查询的学期；即使“当前学期”标记仍停留在春季，
                    // 也必须保留已经开放的夏季学期。
                    result.postValue(DataState(postProcessTerms(terms), DataState.STATE.SUCCESS))
                }
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message ?: "获取学期失败"))
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
                val courses = loadCoursesForSchedule(term, token)
                val maxPeriod = courses.maxOfOrNull { it.begin + it.last - 1 } ?: 0
                val schedule = defaultScheduleStructure(
                    isUndergraduate ?: (token.stutype == EASToken.TYPE.UNDERGRAD)
                )
                val resolved = if (maxPeriod in 1 until schedule.size) {
                    schedule.take(maxPeriod).toMutableList()
                } else {
                    schedule
                }
                result.postValue(DataState(resolved, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                LogUtils.w("getScheduleStructure: fallback to default: ${e.message}")
                result.postValue(DataState(defaultScheduleStructure(
                    isUndergraduate ?: (token.stutype == EASToken.TYPE.UNDERGRAD)
                ), DataState.STATE.SUCCESS))
            }
        }
        return result
    }


    override fun getSubjectsOfTerm(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<MutableList<TermSubject>>> {
        return MutableLiveData<DataState<MutableList<TermSubject>>>().apply {
            postValue(DataState(DataState.STATE.FETCH_FAILED, "${campusDisplayName}暂不支持课程列表"))
        }
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
                val response = requestScores(term, token, testType)
                val body = response.body()
                if (response.statusCode() == 200) {
                    if (isAuthExpiredResponse(response, body)) {
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
                    if (isAuthExpiredResponse(response, body)) {
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
                val scoreResponse = requestScores(term, token, testType)
                val body = scoreResponse.body()
                if (scoreResponse.statusCode() != 200) {
                    if (isAuthExpiredResponse(scoreResponse, body)) {
                        logScoreFailure("getPersonalScoresWithSummary", term, testType, scoreResponse.statusCode(), body)
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                    } else {
                        logScoreFailure("getPersonalScoresWithSummary", term, testType, scoreResponse.statusCode(), body)
                        result.postValue(DataState(DataState.STATE.FETCH_FAILED, "HTTP ${scoreResponse.statusCode()}"))
                    }
                    return@execute
                }
                if (isAuthExpiredResponse(scoreResponse, body)) {
                    logScoreFailure("getPersonalScoresWithSummary", term, testType, scoreResponse.statusCode(), body)
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                    return@execute
                }
                val parsed = BenbuScoreParser.parseGradesHtml(body)
                val filtered = parsed.filter { item -> matchesRequestedTerm(item.termName, term) }
                val scores = if (filtered.isNotEmpty() || parsed.isEmpty()) filtered else parsed
                logScoreDebug("getPersonalScoresWithSummary", term, testType, scoreResponse.statusCode(), body, parsed, filtered)
                val summary = fetchScoreSummary(token)
                result.postValue(DataState(ScoreQueryResult(scores, summary), DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }

        return result
    }


    protected fun logEmptyTimetableDetails(term: TermItem, body: String, courses: List<CourseItem>) {
        if (courses.isNotEmpty()) return
        val doc = Jsoup.parse(body)
        val table = doc.selectFirst("table.addlist_01")
        val rows = table?.select("tr").orEmpty()
        val rowSummaries = rows.take(6).mapIndexed { index, row ->
            val cells = row.select(timetableDebugCellSelector)
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


    protected fun canMergeCourses(
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

    protected fun copyCourse(source: CourseItem): CourseItem {
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

    protected fun currentTermValueFromDoc(doc: Document, selectName: String): String {
        return doc.select("select[name=$selectName] option[selected]").attr("value").trim()
            .ifEmpty {
                doc.select("select[name=$selectName] option").firstOrNull()?.attr("value")?.trim().orEmpty()
            }
    }

    protected fun ensureTimetableResponse(term: TermItem, body: String, statusCode: Int) {
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

    protected fun extractEndYear(yearCode: String): Int? {
        val match = Regex("""^\d{4}-(\d{4})$""").find(yearCode.trim())
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    protected fun extractLoginIdentity(cookies: Map<String, String>): String? {
        return null
    }

    protected fun extractStartYear(yearCode: String): Int {
        return Regex("""^\d{4}""").find(yearCode)?.value?.toIntOrNull()
            ?: yearCode.toIntOrNull()
            ?: Calendar.getInstance().get(Calendar.YEAR)
    }

    protected fun extractYearFromLabel(text: String?): Int? {
        val normalized = text?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return Regex("""(?:19|20)\d{2}""")
            .find(normalized)
            ?.value
            ?.toIntOrNull()
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

    protected fun inferTermStartDate(term: TermItem): Calendar {
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

    protected fun logScoreDebug(
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

    protected fun logScoreFailure(
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

    protected fun matchesRequestedTerm(rawTermName: String?, term: TermItem): Boolean {
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

    protected fun mergeSafePersonalInfo(token: EASToken, doc: Document): EASToken {
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

    protected fun normalizePersonalInfoLabel(raw: String): String {
        return raw.replace("：", "")
            .replace(":", "")
            .replace("*", "")
            .replace(Regex("\\s+"), "")
            .trim()
    }

    protected fun normalized(value: String?): String {
        return value?.trim().orEmpty()
    }

    protected fun parseBuildingJson(json: String): List<BuildingItem> {
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

    protected fun parseCookiesFromJson(json: String): HashMap<String, String> {
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

    protected fun secondMondayOfMonth(year: Int, month: Int): Calendar {
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

    protected fun termSortOrder(term: TermItem): Int {
        return when {
            term.termName.contains("秋") || term.termCode.startsWith("1") -> 0
            term.termName.contains("春") || term.termCode.startsWith("2") -> 1
            term.termName.contains("夏") || term.termCode.startsWith("3") -> 2
            term.termName.contains("寒") || term.termName.contains("冬") || term.termCode.startsWith("4") -> 3
            else -> 9
        }
    }

    protected fun termSortYear(term: TermItem): Int {
        extractYearFromLabel(term.termName)?.let { return it }
        extractYearFromLabel(term.name)?.let { return it }

        val startYear = extractStartYear(term.yearCode)
        val endYear = extractEndYear(term.yearCode) ?: startYear
        return when {
            term.termName.contains("秋") || term.termCode.startsWith("1") -> startYear
            else -> endYear
        }
    }

    protected fun termTimelineKey(term: TermItem): Pair<Int, Int> {
        val year = termSortYear(term)
        val order = termSortOrder(term)
        return year to order
    }

    protected fun tryRelogin(token: EASToken): Boolean {
        // WebView-based relogin is handled at Activity level via handleSessionExpired
        return false
    }
}
