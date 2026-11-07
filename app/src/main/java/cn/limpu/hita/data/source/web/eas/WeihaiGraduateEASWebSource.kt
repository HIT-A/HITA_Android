package cn.limpu.hita.data.source.web.eas

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.CourseItem
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.eas.GraduateCourseRow
import cn.limpu.hita.data.model.eas.GraduateFilterOptions
import cn.limpu.hita.data.model.eas.GraduateSelectedCourses
import cn.limpu.hita.data.model.eas.GraduateSelectionPage
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.source.web.eas.graduate.GraduateAuthenticationException
import cn.limpu.hita.data.source.web.eas.graduate.GraduateHttpTransport
import cn.limpu.hita.data.source.web.eas.graduate.GraduateInvalidResponseException
import cn.limpu.hita.data.source.web.eas.graduate.GraduateJsoupTransport
import cn.limpu.hita.data.source.web.eas.graduate.GraduateRecordsProtocol
import cn.limpu.hita.data.source.web.eas.graduate.GraduateTimetableProtocol
import cn.limpu.hita.data.source.web.eas.graduate.GraduateUpstreamException
import cn.limpu.hita.data.source.web.service.EASGraduateCatalogService
import cn.limpu.hita.data.source.web.service.EASService
import com.limpu.component.data.DataState
import java.time.LocalDate
import java.util.Calendar
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 威海研究生教务数据源。
 *
 * 认证、个人课表、成绩、考试、教学楼与空教室全部使用研究生独立 WebVPN 会话；
 * 绝不回退到本科 jwts。课表与成绩链路按 iOS WeihaiGraduate* 连接器契约移植。
 */
internal class WeihaiGraduateEASWebSource internal constructor(
    private val transportFactory: (EASToken) -> GraduateHttpTransport = { token ->
        GraduateJsoupTransport(token)
    },
) : EASService, EASGraduateCatalogService {
    companion object {
        private val executor = Executors.newSingleThreadExecutor()

        /** 阻塞登录校验；WebView 返回 RESULT_OK 前也会调用。 */
        fun validate(token: EASToken): DataState<EASToken> {
            if (token.cookies[WeihaiGraduateSessionPolicy.TICKET_COOKIE].isNullOrBlank()) {
                return DataState(DataState.STATE.NOT_LOGGED_IN, "登录已失效，请重新登录")
            }
            return try {
                val transport = GraduateJsoupTransport(token)
                val body = transport.identity()
                val profile = WeihaiGraduateSessionPolicy.parseProfile(body)
                when {
                    profile == null -> DataState(DataState.STATE.FETCH_FAILED, "研究生身份资料无效")
                    !token.username.isNullOrBlank() && token.username != profile.account ->
                        DataState(DataState.STATE.NOT_LOGGED_IN, "登录账号已变化，请重新登录")
                    else -> {
                        token.campus = EASToken.Campus.WEIHAI
                        token.stutype = EASToken.TYPE.GRAD
                        token.username = profile.account
                        token.stuId = profile.account
                        token.name = profile.name
                        DataState(token, DataState.STATE.SUCCESS)
                    }
                }
            } catch (_: GraduateAuthenticationException) {
                DataState(DataState.STATE.NOT_LOGGED_IN, "登录已失效，请重新登录")
            } catch (_: GraduateUpstreamException) {
                DataState(DataState.STATE.FETCH_FAILED, "登录验证暂不可用，请重试")
            } catch (_: GraduateInvalidResponseException) {
                DataState(DataState.STATE.FETCH_FAILED, "研究生身份资料无效")
            } catch (_: Exception) {
                DataState(DataState.STATE.FETCH_FAILED, "登录验证网络异常，请重试")
            }
        }
    }

    private fun <T> async(block: () -> DataState<T>): LiveData<DataState<T>> =
        MutableLiveData<DataState<T>>().also { result ->
            executor.execute { result.postValue(block()) }
        }

    private fun <T> once(block: suspend () -> T): LiveData<DataState<T>> =
        MutableLiveData<DataState<T>>().also { result ->
            executor.execute {
                kotlinx.coroutines.runBlocking { result.postValue(blocked(block)) }
            }
        }

    private suspend fun <T> blocked(block: suspend () -> T): DataState<T> = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        context.ensureActive()
        try {
            val value = block()
            context.ensureActive()
            DataState(value, DataState.STATE.SUCCESS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expired: GraduateAuthenticationException) {
            DataState(DataState.STATE.NOT_LOGGED_IN, "登录已失效，请重新登录")
        } catch (upstream: GraduateUpstreamException) {
            DataState(DataState.STATE.FETCH_FAILED, upstream.message ?: "教务服务暂不可用")
        } catch (invalid: GraduateInvalidResponseException) {
            DataState(DataState.STATE.FETCH_FAILED, invalid.message ?: "教务返回数据无法识别")
        } catch (error: Exception) {
            DataState(DataState.STATE.FETCH_FAILED, error.message ?: "教务查询失败")
        }
    }

    private fun transport(token: EASToken) = transportFactory(token)

    // ---------------------------------------------------------------- auth

    override fun login(username: String, password: String, code: String?): LiveData<DataState<EASToken>> = async {
        val cookies = runCatching {
            val json = org.json.JSONObject(username)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrNull()
        if (cookies == null || !WeihaiGraduateSessionPolicy.isAuthenticatedLanding(password, cookies)) {
            DataState(DataState.STATE.NOT_LOGGED_IN, "研究生登录会话无效")
        } else {
            validate(EASToken().apply { this.cookies.putAll(cookies) })
        }
    }

    override fun getSafePersonalInfo(token: EASToken) = async { validate(token) }

    override fun loginCheck(token: EASToken): LiveData<DataState<Pair<Boolean, EASToken>>> = async {
        val result = validate(token)
        if (result.state == DataState.STATE.SUCCESS) DataState(Pair(true, token), DataState.STATE.SUCCESS)
        else DataState(result.state, result.message)
    }

    // ---------------------------------------------------------------- terms

    override fun getAllTerms(token: EASToken): LiveData<DataState<List<TermItem>>> = once {
        terms(token)
    }

    override suspend fun getAllTermsOnce(token: EASToken): DataState<List<TermItem>> =
        blocked { terms(token) }

    private fun terms(token: EASToken): List<TermItem> {
        val http = transport(token)
        val current = http.form("/component/querydangqianxnxq")
        val options = http.form("/component/queryxnxqdata")
        return GraduateTimetableProtocol.terms(current, options)
    }

    // ---------------------------------------------------------------- timetable

    override fun getStartDate(token: EASToken, term: TermItem): LiveData<DataState<Calendar>> = once {
        startDate(token, term)
    }

    override suspend fun getStartDateOnce(token: EASToken, term: TermItem): DataState<Calendar> =
        blocked { startDate(token, term) }

    private fun startDate(token: EASToken, term: TermItem): Calendar {
        val http = transport(token)
        val current = http.form("/component/querydangqianxnxq")
        val options = http.form("/component/queryxnxqdata")
        val known = GraduateTimetableProtocol.terms(current, options).firstOrNull { it.id == term.id }
            ?: invalid("所选学期不在研究生学期列表中")
        val body = http.form("/Xiaoli/queryMonthList", mapOf("xn" to known.yearCode, "xq" to known.termCode))
        val date = GraduateTimetableProtocol.termStartDate(body, known.yearCode, known.termCode)
        return calendarOf(date)
    }

    override fun getScheduleStructure(
        term: TermItem,
        isUndergraduate: Boolean?,
        token: EASToken,
    ): LiveData<DataState<MutableList<TimePeriodInDay>>> = once {
        scheduleStructure(token, term)
    }

    override suspend fun getScheduleStructureOnce(
        term: TermItem,
        isUndergraduate: Boolean?,
        token: EASToken,
    ): DataState<MutableList<TimePeriodInDay>> = blocked { scheduleStructure(token, term) }

    private fun scheduleStructure(token: EASToken, term: TermItem): MutableList<TimePeriodInDay> {
        val http = transport(token)
        val selected = loadSelected(http, term)
        return GraduateTimetableProtocol.schedule(selected, term.yearCode, term.termCode).toMutableList()
    }

    override fun getSubjectsOfTerm(
        token: EASToken,
        term: TermItem,
    ): LiveData<DataState<MutableList<TermSubject>>> = once {
        subjects(token, term)
    }

    override suspend fun getSubjectsOfTermOnce(
        token: EASToken,
        term: TermItem,
    ): DataState<MutableList<TermSubject>> = blocked { subjects(token, term) }

    private fun subjects(token: EASToken, term: TermItem): MutableList<TermSubject> {
        val http = transport(token)
        val selected = loadSelected(http, term)
        return GraduateTimetableProtocol.selectedCourses(selected, term.yearCode, term.termCode)
            .rows.map { row -> subject(row, term) }.toMutableList()
    }

    private fun subject(row: GraduateCourseRow, term: TermItem) = TermSubject().apply {
        name = row.courseName
        code = row.courseCode.takeIf { it.isNotBlank() }
        key = row.taskNumber
        teacher = row.teachers.firstOrNull()
        school = row.college.takeIf { it.isNotBlank() }
        nature = row.nature.takeIf { it.isNotBlank() }
        selectCategory = row.category.takeIf { it.isNotBlank() }
        credit = row.credits.toFloatOrNull() ?: 0f
    }

    override fun getTimetableOfTerm(
        term: TermItem,
        token: EASToken,
    ): LiveData<DataState<List<CourseItem>>> = once {
        timetable(token, term).courses
    }

    override suspend fun getTimetableOfTermOnce(
        term: TermItem,
        token: EASToken,
    ): DataState<List<CourseItem>> = blocked { timetable(token, term).courses }

    private fun timetable(token: EASToken, term: TermItem): GraduateTimetableProtocol.TimetableResult {
        val http = transport(token)
        val fields = mapOf("xn" to term.yearCode, "xq" to term.termCode)
        when (GraduateTimetableProtocol.publication(http.form("/xszykb/querykbsffb", fields))) {
            GraduateTimetableProtocol.TimetablePublication.AVAILABLE -> Unit
            GraduateTimetableProtocol.TimetablePublication.UNPUBLISHED -> invalid("该学期课表尚未发布")
            GraduateTimetableProtocol.TimetablePublication.EVALUATION_REQUIRED -> invalid("请先完成课程评价后查看课表")
        }
        val rows = http.form("/xszykb/queryxszykbzong", fields)
        val selected = loadSelected(http, term)
        val calendar = http.form("/Xiaoli/queryMonthList", fields)
        return GraduateTimetableProtocol.timetable(rows, selected, calendar, term.yearCode, term.termCode)
    }

    /**
     * `GET /Xsxk/query/2` → `queryXkdqXnxq` → `queryYxkc`。
     *
     * 历史学期查询时 `p_x*` 使用请求学期，`p_dq*` 保持模块当前学期。
     */
    private fun loadSelected(http: GraduateHttpTransport, term: TermItem): String {
        val page = http.get("/Xsxk/query/2", referer = "/Xsxk/query/2")
        val runtime = GraduateTimetableProtocol.runtimeFields(page)
        val moduleFields = runtime + ("mxpylx" to "2")
        val module = GraduateTimetableProtocol.moduleTerm(
            http.form("/Xsxk/queryXkdqXnxq", moduleFields, referer = "/Xsxk/query/2")
        )
        val fields = LinkedHashMap<String, String>()
        fields.putAll(runtime)
        fields["mxpylx"] = "2"
        fields["p_xn"] = term.yearCode
        fields["p_xq"] = term.termCode
        fields["p_xnxq"] = term.yearCode + term.termCode
        fields.putAll(module.fields)
        fields["p_xkfsdm"] = "yixuan"
        fields["p_chaxunxh"] = ""
        fields["p_sfhlctkc"] = "0"
        fields["p_sfhllrlkc"] = "0"
        fields["p_gjz"] = ""
        return http.form("/Xsxk/queryYxkc", fields, referer = "/Xsxk/query/2")
    }

    // ---------------------------------------------------------------- classrooms

    override fun getTeachingBuildings(token: EASToken): LiveData<DataState<List<BuildingItem>>> = once {
        buildings(token)
    }

    override suspend fun getTeachingBuildingsOnce(token: EASToken): DataState<List<BuildingItem>> =
        blocked { buildings(token) }

    private fun buildings(token: EASToken): List<BuildingItem> =
        GraduateRecordsProtocol.buildings(
            transport(token).form("/pksd/queryjxlList", referer = "/cdkb/querycdzy")
        )

    override fun queryEmptyClassroom(
        token: EASToken,
        term: TermItem,
        building: BuildingItem,
        weeks: List<String>,
    ): LiveData<DataState<List<ClassroomItem>>> = once {
        classrooms(token, term, building, weeks.firstOrNull())
    }

    override suspend fun queryEmptyClassroomOnce(
        token: EASToken,
        term: TermItem,
        building: BuildingItem,
        weeks: List<String>,
    ): DataState<List<ClassroomItem>> = blocked {
        classrooms(token, term, building, weeks.firstOrNull())
    }

    private fun classrooms(
        token: EASToken,
        term: TermItem,
        building: BuildingItem,
        week: String?,
    ): List<ClassroomItem> {
        val rawWeek = week?.toIntOrNull() ?: invalid("请选择查询周次")
        val http = transport(token)
        val fields = linkedMapOf(
            "pxn" to term.yearCode,
            "pxq" to term.termCode,
            "jxl" to building.id,
            "zc" to rawWeek.toString(),
            "wpksfxs" to "1",
            "kjs" to "0",
            "xsbkycd" to "0",
        )
        val collector = GraduateRecordsProtocol.PageCollector()
        for (page in 1..GraduateRecordsProtocol.PageCollector.MAX_PAGES) {
            val body = http.form(
                "/cdkb/querycdzyleftzhou",
                fields + mapOf("pageNum" to page.toString(), "pageSize" to "20"),
                referer = "/cdkb/querycdzy",
            )
            if (collector.consume(body, page, enveloped = false)) break
        }
        val occupancy = http.form("/cdkb/querycdzyrightzhou", fields, referer = "/cdkb/querycdzy")
        return GraduateRecordsProtocol.classrooms(collector.completedRows(), occupancy)
    }

    // ---------------------------------------------------------------- scores / exams

    override fun getPersonalScores(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType,
    ): LiveData<DataState<List<CourseScoreItem>>> = once { scores(token, term) }

    override suspend fun getPersonalScoresOnce(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType,
    ): DataState<List<CourseScoreItem>> = blocked { scores(token, term) }

    private fun scores(token: EASToken, term: TermItem): List<CourseScoreItem> {
        val http = transport(token)
        val collector = GraduateRecordsProtocol.PageCollector()
        for (page in 1..GraduateRecordsProtocol.PageCollector.MAX_PAGES) {
            val body = http.json(
                "/cjgl/grcjcx/grcjcx",
                graduateScoreBody(term, page),
                referer = "/cjgl/grcjcx/go/2",
            )
            if (collector.consume(body, page, enveloped = true)) break
        }
        return GraduateRecordsProtocol.grades(collector.completedRows(), term.id)
    }

    private fun graduateScoreBody(term: TermItem, page: Int): String =
        com.google.gson.JsonObject().apply {
            addProperty("xn", term.yearCode)
            addProperty("xq", term.termCode)
            add("kcmc", com.google.gson.JsonNull.INSTANCE)
            addProperty("cxbj", "-1")
            addProperty("pylx", "2")
            addProperty("current", page)
            addProperty("pageSize", 20)
            add("sffx", com.google.gson.JsonNull.INSTANCE)
        }.toString()

    override fun getExamItems(token: EASToken, term: TermItem?): LiveData<DataState<List<ExamItem>>> = once {
        exams(token, term)
    }

    override suspend fun getExamItemsOnce(token: EASToken, term: TermItem?): DataState<List<ExamItem>> =
        blocked { exams(token, term) }

    private fun exams(token: EASToken, term: TermItem?): List<ExamItem> {
        val requested = term ?: invalid("请先选择学期")
        val http = transport(token)
        val collector = GraduateRecordsProtocol.PageCollector()
        for (page in 1..GraduateRecordsProtocol.PageCollector.MAX_PAGES) {
            val body = http.form(
                "/kscxtj/queryXsksByxhList",
                mapOf(
                    "ppylx" to "2",
                    "pxn" to requested.yearCode,
                    "pxq" to requested.termCode,
                    "pkssjdm" to "",
                    "pageNum" to page.toString(),
                    "pageSize" to "20",
                ),
                referer = "/kscxtj/queryXskscxByXh",
            )
            if (collector.consume(body, page, enveloped = false)) break
        }
        return GraduateRecordsProtocol.exams(
            collector.completedRows(),
            requested.id,
            requested.name,
        )
    }

    // ---------------------------------------------------------------- catalog

    override suspend fun getGraduateSelectedCoursesOnce(
        token: EASToken,
        term: TermItem,
    ): DataState<GraduateSelectedCourses> = blocked {
        GraduateTimetableProtocol.selectedCourses(loadSelected(transport(token), term), term.yearCode, term.termCode)
    }

    override suspend fun queryGraduateCandidatesOnce(
        token: EASToken,
        term: TermItem,
        categoryId: String,
        keyword: String,
        campusId: String,
        collegeId: String,
        page: Int,
        pageSize: Int,
    ): DataState<GraduateSelectionPage> = blocked {
        require(page > 0) { "页码必须大于 0" }
        val http = transport(token)
        val pageHtml = http.get("/Xsxk/query/2", referer = "/Xsxk/query/2")
        val runtime = GraduateTimetableProtocol.runtimeFields(pageHtml)
        val module = GraduateTimetableProtocol.moduleTerm(
            http.form("/Xsxk/queryXkdqXnxq", runtime + ("mxpylx" to "2"), referer = "/Xsxk/query/2")
        )
        val fields = LinkedHashMap<String, String>().apply {
            putAll(runtime)
            put("mxpylx", "2")
            put("p_xn", term.yearCode)
            put("p_xq", term.termCode)
            put("p_xnxq", term.yearCode + term.termCode)
            putAll(module.fields)
            put("p_xkfsdm", categoryId)
            put("p_kclb", "")
            put("p_kkyx", collegeId)
            put("p_xiaoqu", campusId)
            put("p_gjz", keyword)
            put("pageSize", pageSize.toString())
            put("pageNum", page.toString())
        }
        val body = http.form("/Xsxk/queryKxrw", fields, referer = "/Xsxk/query/2")
        GraduateTimetableProtocol.candidates(body, categoryId, page, pageSize)
    }

    override suspend fun queryGraduateSchoolCoursesOnce(
        token: EASToken,
        term: TermItem,
        keyword: String,
        campusId: String,
        page: Int,
        pageSize: Int,
    ): DataState<GraduateSelectionPage> = blocked {
        require(page > 0) { "页码必须大于 0" }
        val http = transport(token)
        val fields = linkedMapOf(
            "mxpylx" to "2",
            "p_chaxunpylx" to "2",
            "p_xn" to term.yearCode,
            "p_xq" to term.termCode,
            "p_xnxq" to term.yearCode + term.termCode,
            "pageNum" to page.toString(),
            "pageSize" to pageSize.toString(),
        )
        if (campusId.isNotBlank()) {
            fields["p_chaxunxiaoqu"] = campusId
            fields["p_xiaoqu"] = campusId
        }
        if (keyword.isNotBlank()) {
            fields["p_chaxungjz"] = keyword
            fields["p_gjz"] = keyword
        }
        val body = http.form("/Xsxktz/queryRwxxcxList", fields, referer = "/Xsxktz/queryRwxxcxList/2")
        GraduateTimetableProtocol.schoolCourses(body, page, pageSize)
    }

    override suspend fun getGraduateFiltersOnce(
        token: EASToken,
        term: TermItem,
    ): DataState<GraduateFilterOptions> = blocked {
        val http = transport(token)
        val periods = http.form("/Xsxktz/queryXlcxList", linkedMapOf(
            "mxpylx" to "2",
            "p_chaxunpylx" to "2",
            "p_xn" to term.yearCode,
            "p_xq" to term.termCode,
            "p_xnxq" to term.yearCode + term.termCode,
        ), referer = "/Xsxktz/queryXlcxList/2")
        val campuses = GraduateTimetableProtocol.options(
            http.form("/pksd/queryxiaoquList", mapOf("pylx" to "2"), referer = "/Xsxktz/queryRwxxcxList/2")
        )
        val colleges = GraduateTimetableProtocol.options(
            http.form("/component/queryKkyx", mapOf("nodataqx" to "1"), referer = "/Xsxk/query/2")
        )
        GraduateTimetableProtocol.filters(periods, campuses, colleges)
    }

    private fun calendarOf(date: LocalDate): Calendar =
        Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply {
            clear()
            set(date.year, date.monthValue - 1, date.dayOfMonth, 0, 0, 0)
        }

    private fun <T> invalid(message: String): T = throw GraduateInvalidResponseException(message)
}
