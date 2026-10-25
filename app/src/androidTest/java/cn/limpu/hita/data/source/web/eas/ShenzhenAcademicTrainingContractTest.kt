package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlan
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlanLevel
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 固定培养方案和学分查询的旧请求编排；夹具只含合成身份与课程。 */
@RunWith(AndroidJUnit4::class)
class ShenzhenAcademicTrainingContractTest : ShenzhenAcademicContractHarness() {
    @Test
    fun undergraduatePlansRefreshIdentityAndMatchMajorAcrossPages() {
        webResponse = { request ->
            when (path(request)) {
                IDENTITY -> json(fixture("training/identity.json"))
                PLANS -> when (form(request)["pageNum"]) {
                    "1" -> json(plansPage(2, "OTHER", "自动化"))
                    "2" -> json(plansPage(2, "LOCAL-CS", "计算机科学与技术专业"))
                    else -> error("Unexpected plan page")
                }
                else -> error("Unexpected training request ${request.path}")
            }
        }
        val token = webToken().apply {
            major = "过期专业"
            grade = "2020级"
            stutype = EASToken.TYPE.GRAD
        }

        val plans = success { source.getShenzhenTrainingPlans(token) }
        awaitWorkerCompletion()

        assertEquals("LOCAL-CS", plans.single().id)
        assertEquals(ShenzhenTrainingPlanLevel.UNDERGRADUATE, plans.single().level)
        assertEquals("计算机科学与技术", token.major)
        assertEquals("2024", token.grade)
        assertEquals(EASToken.TYPE.UNDERGRAD, token.stutype)
        assertSame(token, refreshedToken.get())
        assertTrue(refreshCount.get() >= 1)
        assertEquals(listOf(IDENTITY, PLANS, PLANS), paths())
        val requests = webRequests.filter { path(it) == PLANS }
        assertEquals(listOf("1", "2"), requests.map { form(it)["pageNum"] })
        requests.forEach { request ->
            assertTrainingRequest(request, "/faxq/query")
            val body = form(request)
            assertEquals("3", body["falx"])
            assertEquals("1", body["pylb"])
            assertEquals("2024", body["njdm"])
            assertEquals("", body["bbh"])
            assertEquals("500", body["pageSize"])
        }
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun undergraduatePlansFallBackToTokenMajorAndGrade() {
        webResponse = { request ->
            when (path(request)) {
                IDENTITY -> json("{}")
                PLANS -> json(plansPage(1, "LOCAL-CS", "软件工程"))
                else -> error("Unexpected fallback request ${request.path}")
            }
        }
        val token = webToken().apply {
            major = "软件工程"
            grade = "2024级本科"
        }

        assertEquals("LOCAL-CS", success { source.getShenzhenTrainingPlans(token) }.single().id)
        awaitWorkerCompletion()

        assertEquals("2024", token.grade)
        assertEquals("2024", form(webRequests.last())["njdm"])
        assertEquals("软件工程", token.major)
        assertSame(token, refreshedToken.get())
    }

    @Test
    fun postgraduatePlansUseVersionAndDoNotRequireUndergraduateGrade() {
        webResponse = { request ->
            when (path(request)) {
                IDENTITY -> json("""{"ZYMC":"计算机科学与技术","PYLX":"2"}""")
                PLANS -> json(plansPage(1, "LOCAL-PG", "计算机科学与技术"))
                else -> error("Unexpected postgraduate request ${request.path}")
            }
        }
        val token = webToken()

        val plan = success { source.getShenzhenTrainingPlans(token) }.single()
        awaitWorkerCompletion()

        assertEquals(ShenzhenTrainingPlanLevel.POSTGRADUATE, plan.level)
        assertEquals(EASToken.TYPE.GRAD, token.stutype)
        val body = form(webRequests.last())
        assertEquals("2", body["falx"])
        assertEquals("2", body["pylb"])
        assertEquals("202603", body["bbh"])
        assertEquals("", body["njdm"])
    }

    @Test
    fun missingMajorOrUndergraduateGradeStopsBeforePlanQuery() {
        webResponse = { request ->
            assertEquals(IDENTITY, path(request))
            json("{}")
        }
        val missingMajor = webToken().apply { grade = "2024级" }
        val missingGrade = webToken().apply { major = "软件工程" }

        for (token in listOf(missingMajor, missingGrade)) {
            val state = awaitState { source.getShenzhenTrainingPlans(token) }
            awaitWorkerCompletion()
            assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        }

        assertEquals(listOf(IDENTITY, IDENTITY), paths())
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun undergraduatePlanCoursesPaginateAndDeduplicateWithoutFetchingGroups() {
        webResponse = { request ->
            assertEquals(UNDERGRAD_COURSES, path(request))
            when (form(request)["pageNum"]) {
                "1" -> json(coursePage(2, course("CS101", "程序设计")))
                "2" -> json(coursePage(2, course("CS101", "程序设计"), course("CS102", "数据结构")))
                else -> error("Unexpected course page")
            }
        }
        val plan = plan(ShenzhenTrainingPlanLevel.UNDERGRADUATE)

        val detail = success { source.getShenzhenTrainingPlanCourses(webToken(), plan) }
        awaitWorkerCompletion()

        assertEquals(listOf("CS101", "CS102"), detail.courses.map { it.courseCode })
        assertEquals(6.0, detail.totalCredits, 0.0)
        assertTrue(detail.groups.isEmpty())
        assertEquals(listOf(UNDERGRAD_COURSES, UNDERGRAD_COURSES), paths())
        assertEquals(listOf("1", "2"), webRequests.map { form(it)["pageNum"] })
        webRequests.forEach { request ->
            assertTrainingRequest(request, "/Njpyfakc/query")
            val body = form(request)
            assertEquals(plan.id, body["fah"])
            assertEquals(plan.changeId, body["bgid"])
            assertEquals("1", body["pylx"])
            assertEquals("1", body["pylb"])
            assertEquals("500", body["pageSize"])
        }
    }

    @Test
    fun postgraduatePlanCoursesFetchGroupsBeforeCoursesAndKeepTheirAssociation() {
        webResponse = { request ->
            when (path(request)) {
                POSTGRAD_GROUPS -> json("""[{"kzid":"CORE","kzmc":"专业核心课","yqxdxf":"3"}]""")
                POSTGRAD_COURSES -> json(coursePage(1, course("CS501", "高级算法", "CORE")))
                else -> error("Unexpected postgraduate detail request ${request.path}")
            }
        }
        val plan = plan(ShenzhenTrainingPlanLevel.POSTGRADUATE)

        val detail = success { source.getShenzhenTrainingPlanCourses(webToken(), plan) }
        awaitWorkerCompletion()

        assertEquals(listOf(POSTGRAD_GROUPS, POSTGRAD_COURSES), paths())
        assertEquals("CORE", detail.courses.single().groupId)
        assertEquals("专业核心课", detail.categories.single().name)
        assertEquals("CORE", detail.categories.single().group?.id)
        webRequests.forEach { request ->
            assertTrainingRequest(request, "/Zdxpyfakz/query")
            assertEquals(plan.id, form(request)["fah"])
            assertEquals(plan.changeId, form(request)["bgid"])
            assertEquals("2", form(request)["pylb"])
        }
        assertEquals("2", form(webRequests.last())["pylx"])
        assertEquals("LOCAL-MAJOR", form(webRequests.last())["zyfx"])
    }

    @Test
    fun trainingAndCreditEntriesRejectMissingWebSessionWithoutRequests() {
        val token = apiToken()

        assertEquals(DataState.STATE.NOT_LOGGED_IN, awaitState { source.getShenzhenTrainingPlans(token) }.state)
        assertEquals(
            DataState.STATE.NOT_LOGGED_IN,
            awaitState { source.getShenzhenTrainingPlanCourses(token, plan(ShenzhenTrainingPlanLevel.UNDERGRADUATE)) }.state,
        )
        assertEquals(DataState.STATE.NOT_LOGGED_IN, awaitState { source.getShenzhenCreditProgress(token) }.state)
        assertTrue(apiRequests.isEmpty())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun creditProgressLooksUpRecordIdUsesSelectedTermAndFallsBackToLegacyPlan() {
        webResponse = { request ->
            when (path(request)) {
                IDENTITY -> json("""{"XH":"2024000001","NJMC":"2024级","PYLX":"1"}""")
                STUDENT_RECORD -> json("""{"content":[{"id":"looked-up-record"}]}""")
                CREDIT_TERM -> json("""{"XN":"2025-2026","XQ":"3","R":23}""")
                CREDIT_PLAN -> json("{}")
                CREDIT_PLAN_FALLBACK -> json("""{"FAH":"legacy-local-plan"}""")
                CREDIT_SUMMARY -> json(fixture("training/progress-summary.json"))
                else -> error("Unexpected credit lookup request ${request.path}")
            }
        }
        val token = webToken()

        val progress = success { source.getShenzhenCreditProgress(token, selectedTerm = term, includeDetails = false) }
        awaitWorkerCompletion()

        assertEquals(term.getCode(), progress.currentTerm)
        assertEquals(20.0, progress.requiredCredits, 0.0)
        assertEquals("2024000001", token.stuId)
        assertEquals("looked-up-record", token.id)
        assertEquals("2024", token.grade)
        assertSame(token, refreshedToken.get())
        assertEquals(
            listOf(IDENTITY, STUDENT_RECORD, CREDIT_TERM, CREDIT_PLAN, CREDIT_PLAN_FALLBACK, CREDIT_SUMMARY),
            paths(),
        )
        assertEquals("2024000001", jsonBody(webRequests[1]).getString("xjidorxh"))
        for (request in webRequests.filter { path(it) in setOf(CREDIT_PLAN, CREDIT_PLAN_FALLBACK) }) {
            assertEquals(mapOf("xh" to "2024000001", "pylx" to "1", "falxdm" to "1"), form(request))
        }
        val summary = jsonBody(webRequests.last())
        assertEquals("looked-up-record", summary.getString("xjid"))
        assertEquals("legacy-local-plan", summary.getString("fah"))
        assertEquals("2024000001", summary.getString("xh"))
        assertEquals("2024", summary.getString("nj"))
        assertEquals(term.getCode(), summary.getString("jzxnxq"))
        assertTrainingRequest(webRequests.last(), CREDIT_REFERER, jsonRequest = true)
    }

    @Test
    fun summary404KeepsExistingSuccessfulEmptyProgressEvenWhenCategoriesAreFetched() {
        val categoryStarted = CountDownLatch(1)
        val releaseCategory = CountDownLatch(1)
        webResponse = { request ->
            when (path(request)) {
                CREDIT_SUMMARY -> json("{}", 404)
                CREDIT_CATEGORIES -> {
                    categoryStarted.countDown()
                    assertTrue(releaseCategory.await(10, TimeUnit.SECONDS))
                    json("""{"content":{"xflbyq":[{"kclbdm":"01","kclbmc":"专业必修","yqwcxf":"20","ywcxf":"6"}]}}""")
                }
                else -> creditBaseResponse(request)
            }
        }
        observe { source.getShenzhenCreditProgress(webToken(), includeDetails = false) }.use { observed ->
            try {
                assertTrue(categoryStarted.await(10, TimeUnit.SECONDS))
                val initial = observed.await { it.state == DataState.STATE.SUCCESS }
                assertEquals(0.0, requireNotNull(initial.data).requiredCredits, 0.0)
                assertTrue(requireNotNull(initial.data).categories.isEmpty())
                // The old implementation omits allowMissingSummary in subsequent merges.
                // Keep this extraction baseline: fetched categories do not replace empty progress.
                releaseCategory.countDown()
                val terminal = observed.await { it !== initial && it.state == DataState.STATE.SUCCESS }
                awaitWorkerCompletion()
                assertEquals(0.0, requireNotNull(terminal.data).completedCredits, 0.0)
                assertTrue(requireNotNull(terminal.data).categories.isEmpty())
            } finally {
                releaseCategory.countDown()
            }
        }
        assertEquals(listOf(IDENTITY, CREDIT_TERM, CREDIT_PLAN, CREDIT_SUMMARY, CREDIT_CATEGORIES), paths())
        assertFalse(paths().contains(CREDIT_GROUPS))
        assertFalse(paths().contains(CREDIT_RECORDS))
    }

    @Test
    fun creditDetailFlagsSelectLeafOrTrackCoursesAndSkipEarnedRecords() {
        for (trackOnly in listOf(false, true)) {
            webRequests.clear()
            webResponse = { request ->
                when (path(request)) {
                    CREDIT_GROUPS -> json(fixture("training/progress-groups.json"))
                    CREDIT_GROUP_COURSES -> {
                        val groupId = jsonBody(request).getString("kzid")
                        json(coursePage(1, course("COURSE-$groupId", "课程-$groupId")))
                    }
                    else -> creditBaseResponse(request)
                }
            }
            val token = webToken()
            val progress = success {
                source.getShenzhenCreditProgress(token, includeCourseRecords = false, trackCoursesOnly = trackOnly)
            }
            awaitWorkerCompletion()

            val queriedGroups = webRequests.filter { path(it) == CREDIT_GROUP_COURSES }
                .map { jsonBody(it).getString("kzid") }
            assertEquals(if (trackOnly) listOf("TRACK") else listOf("TRACK", "CORE"), queriedGroups)
            assertEquals(3, progress.groups.size)
            assertEquals("COURSE-TRACK", progress.groups.single { it.id == "TRACK" }.courses.single().courseCode)
            assertEquals(if (trackOnly) 0 else 1, progress.groups.single { it.id == "CORE" }.courses.size)
            assertTrue(progress.courseRecords.isEmpty())
            assertFalse(paths().contains(CREDIT_RECORDS))
            assertFalse(paths().contains(CREDIT_CATEGORIES))
            webRequests.filter { path(it) == CREDIT_GROUP_COURSES }.forEach { request ->
                assertTrainingRequest(request, CREDIT_REFERER, jsonRequest = true)
                val body = jsonBody(request)
                assertEquals("local-record", body.getString("xjid"))
                assertEquals("2024", body.getString("nj"))
                assertEquals("local-credit-plan", body.getString("fah"))
                assertEquals(200, body.getInt("pageSize"))
            }
        }
    }

    @Test
    fun optionalCourseAndLaterRecordFailurePreserveSummaryGroupsAndEarlierRecords() {
        webResponse = { request ->
            when (path(request)) {
                CREDIT_GROUPS -> json(fixture("training/progress-groups.json"))
                CREDIT_GROUP_COURSES -> json("{}", 500)
                CREDIT_RECORDS -> when (form(request)["pageNum"]) {
                    "1" -> json(recordPage(2, "LOCAL-R1", "CS101"))
                    "2" -> json("""{"content":"访问的服务需要身份认证，请引导用户到登录页"}""", 401)
                    else -> error("Unexpected record page")
                }
                else -> creditBaseResponse(request)
            }
        }
        observe { source.getShenzhenCreditProgress(webToken()) }.use { observed ->
            val state = observed.await { it.data?.courseRecords?.size == 1 }
            awaitWorkerCompletion()
            val progress = requireNotNull(state.data)
            assertEquals(DataState.STATE.SUCCESS, state.state)
            assertEquals(20.0, progress.requiredCredits, 0.0)
            assertEquals(6.0, progress.completedCredits, 0.0)
            assertEquals(3, progress.groups.size)
            assertTrue(progress.groups.all { it.courses.isEmpty() })
            assertEquals("CS101", progress.courseRecords.single().courseCode)
        }
        assertEquals(listOf("1", "2"), webRequests.filter { path(it) == CREDIT_RECORDS }.map { form(it)["pageNum"] })
    }

    @Test
    fun earnedRecordsPaginateAfterVisibleSummaryAndDeduplicateAcrossPages() {
        val recordsStarted = CountDownLatch(1)
        val releaseRecords = CountDownLatch(1)
        webResponse = { request ->
            when (path(request)) {
                CREDIT_GROUPS -> json("""{"content":[]}""")
                CREDIT_RECORDS -> {
                    if (form(request)["pageNum"] == "1") {
                        recordsStarted.countDown()
                        assertTrue(releaseRecords.await(10, TimeUnit.SECONDS))
                        json(recordPage(2, "LOCAL-R1", "CS101"))
                    } else {
                        json("""{"content":{"pages":2,"list":[
                            {"rwh":"LOCAL-R1","kcdm":"CS101","kcmc":"程序设计","xf":"3","xscj":"90","xnxq":"2026秋季"},
                            {"rwh":"LOCAL-R2","kcdm":"CS102","kcmc":"数据结构","xf":"3","xscj":"92","xnxq":"2026秋季"}
                        ]}}""")
                    }
                }
                else -> creditBaseResponse(request)
            }
        }
        observe { source.getShenzhenCreditProgress(webToken()) }.use { observed ->
            try {
                assertTrue(recordsStarted.await(10, TimeUnit.SECONDS))
                val early = requireNotNull(observed.await { it.state == DataState.STATE.SUCCESS }.data)
                assertEquals(6.0, early.completedCredits, 0.0)
                assertTrue(early.courseRecords.isEmpty())
                releaseRecords.countDown()
                val finalProgress = requireNotNull(observed.await { it.data?.courseRecords?.size == 2 }.data)
                awaitWorkerCompletion()
                assertEquals(setOf("CS101", "CS102"), finalProgress.courseRecords.map { it.courseCode }.toSet())
                assertEquals(20.0, finalProgress.requiredCredits, 0.0)
            } finally {
                releaseRecords.countDown()
            }
        }
        val requests = webRequests.filter { path(it) == CREDIT_RECORDS }
        assertEquals(listOf("1", "2"), requests.map { form(it)["pageNum"] })
        requests.forEach { request ->
            assertTrainingRequest(request, CREDIT_REFERER)
            val body = form(request)
            assertEquals("23", body["total"])
            assertEquals("local-record", body["xjid"])
            assertEquals("200", body["pageSize"])
            assertEquals("1", body["sfgld"])
            assertEquals("", body["xn"])
            assertEquals("", body["xq"])
        }
    }

    @Test
    fun creditCriticalAuthenticationAndNon404SummaryFailureStopBeforeDetails() {
        for ((failedPath, status) in listOf(IDENTITY to 401, CREDIT_PLAN to 401, CREDIT_SUMMARY to 401, CREDIT_SUMMARY to 503)) {
            webRequests.clear()
            webResponse = { request ->
                if (path(request) == failedPath) json("{}", status) else creditBaseResponse(request)
            }

            val state = awaitState { source.getShenzhenCreditProgress(webToken()) }
            awaitWorkerCompletion()

            assertEquals(if (status == 401) DataState.STATE.NOT_LOGGED_IN else DataState.STATE.FETCH_FAILED, state.state)
            assertEquals(failedPath, paths().last())
            assertFalse(paths().contains(CREDIT_CATEGORIES))
            assertFalse(paths().contains(CREDIT_GROUPS))
            assertFalse(paths().contains(CREDIT_RECORDS))
            assertFalse(paths().contains(CREDIT_PLAN_FALLBACK))
        }
    }

    @Test
    fun trainingAuthenticationStopsListOrCourseChainAtTheExpiredResponse() {
        for (failedPath in listOf(IDENTITY, PLANS, POSTGRAD_GROUPS, POSTGRAD_COURSES)) {
            webRequests.clear()
            webResponse = { request ->
                if (path(request) == failedPath) {
                    json("""{"content":"访问的服务需要身份认证，请引导用户到登录页"}""", 401)
                } else when (path(request)) {
                    IDENTITY -> json(fixture("training/identity.json"))
                    POSTGRAD_GROUPS -> json("[]")
                    else -> error("Unexpected request after authentication failure ${request.path}")
                }
            }
            val state = if (failedPath == IDENTITY || failedPath == PLANS) {
                awaitState { source.getShenzhenTrainingPlans(webToken()) }.state
            } else {
                awaitState {
                    source.getShenzhenTrainingPlanCourses(webToken(), plan(ShenzhenTrainingPlanLevel.POSTGRADUATE))
                }.state
            }
            awaitWorkerCompletion()

            assertEquals(DataState.STATE.NOT_LOGGED_IN, state)
            assertEquals(failedPath, paths().last())
            assertEquals(if (failedPath in setOf(PLANS, POSTGRAD_COURSES)) 2 else 1, webRequests.size)
        }
    }

    private fun creditBaseResponse(request: RecordedRequest): MockResponse = when (path(request)) {
        IDENTITY -> json(fixture("training/identity.json"))
        CREDIT_TERM -> json("""{"XN":"2026-2027","XQ":"1","R":23}""")
        CREDIT_PLAN -> json("""{"FAH":"local-credit-plan","BGID":"local-change"}""")
        CREDIT_SUMMARY -> json(fixture("training/progress-summary.json"))
        else -> error("Unexpected credit base request ${request.path}")
    }

    private fun assertTrainingRequest(request: RecordedRequest, referer: String, jsonRequest: Boolean = false) {
        assertEquals("POST", request.method)
        assertEquals("${path(request)}?sf_request_type=ajax", request.path)
        assertEquals(web.url(referer).toString(), request.getHeader("Referer"))
        assertEquals("01", request.getHeader("RoleCode"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("SESSION=local-web-session"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith(
            if (jsonRequest) "application/json" else "application/x-www-form-urlencoded",
        ))
    }

    private fun plan(level: ShenzhenTrainingPlanLevel) = ShenzhenTrainingPlan(
        id = "local-plan", changeId = "local-change", name = "本地培养方案",
        majorCode = "LOCAL-MAJOR", majorName = "计算机科学与技术", level = level,
    )

    private fun plansPage(pages: Int, id: String, major: String): String =
        JSONObject().put("content", JSONObject().put("pages", pages).put("list", org.json.JSONArray().put(
            JSONObject().put("fah", id).put("bgid", "local-change").put("famc", "$major 培养方案").put("zymc", major),
        ))).toString()

    private fun course(code: String, name: String, group: String = "") =
        JSONObject().put("kcdm", code).put("kcmc", name).put("xf", "3").put("kzid", group)
            .put("fah", "local-plan").put("kclbmc", "专业基础课").put("tjkkxnxq", "2026-2027-1")

    private fun coursePage(pages: Int, vararg courses: JSONObject): String =
        JSONObject().put("content", JSONObject().put("pages", pages).put("list", org.json.JSONArray(courses.toList()))).toString()

    private fun recordPage(pages: Int, id: String, code: String): String =
        """{"content":{"pages":$pages,"list":[{"rwh":"$id","kcdm":"$code","kcmc":"程序设计","xf":"3","xscj":"90","xnxq":"2026秋季"}]}}"""

    private fun path(request: RecordedRequest): String = request.path.orEmpty().substringBefore('?')

    private fun paths() = webRequests.map(::path)

    private companion object {
        const val IDENTITY = "/UserManager/queryxsxx"
        const val PLANS = "/faxq/query"
        const val UNDERGRAD_COURSES = "/Njpyfakc/queryList"
        const val POSTGRAD_GROUPS = "/Zdxpyfakz/queryKzTree"
        const val POSTGRAD_COURSES = "/Zdxpyfakz/queryFaKzkc"
        const val CREDIT_REFERER = "/cjgl/grcjcx/cjxqList"
        const val STUDENT_RECORD = "/cjgl/cjzhtjcx/cjcx/getXs"
        const val CREDIT_TERM = "/cjgl/cjzhtjcx/cjcx/queryqxnxq"
        const val CREDIT_PLAN = "/cjgl/cjzhtjcx/cjcx/queryfahHljdx"
        const val CREDIT_PLAN_FALLBACK = "/cjgl/cjzhtjcx/cjcx/queryfah"
        const val CREDIT_SUMMARY = "/cjgl/cjzhtjcx/cjcx/queryBxkqk"
        const val CREDIT_CATEGORIES = "/cjgl/cjzhtjcx/cjcx/queryXflbyq1"
        const val CREDIT_GROUPS = "/cjgl/cjzhtjcx/cjcx/queryMkyq"
        const val CREDIT_GROUP_COURSES = "/cjgl/cjzhtjcx/cjcx/queryFaKzkc"
        const val CREDIT_RECORDS = "/cjgl/grcjcx/dyxwList"
    }
}
