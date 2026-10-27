package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetableSuspendRemoteContractTest : TimetableRemoteContractHarness() {
    @Test
    fun bothLegacyCampusesOnceUseTheRealCalendarMetadataScheduleAndTimetableChain() = runBlocking {
        for (campus in listOf(EASToken.Campus.BENBU, EASToken.Campus.WEIHAI)) {
            val source = legacySource(campus)
            val account = token(campus)
            requests.clear()
            assertEquals(listOf(2026, 9, 7), dateParts(success(source.getStartDateOnce(account, term))))
            assertEquals(DataState.STATE.FETCH_FAILED, source.getSubjectsOfTermOnce(account, term).state)
            val schedule = success(source.getScheduleStructureOnce(term, null, account))
            assertEquals(listOf(listOf(8, 0, 8, 50), listOf(8, 55, 9, 45)), schedule.map(::period))
            assertRegular(success(source.getTimetableOfTermOnce(term, account)).single())
            assertEquals(if (campus == EASToken.Campus.BENBU) 1 else 2, requests.count { it.path == "/kbcx/queryGrkb" })
            assertTrue(requests.filter { it.path == "/kbcx/queryGrkb" }.all { form(it)["xnxq"] == "2026-20271" })
        }
    }

    @Test
    fun benbuOncePreservesOptionalExperimentalAndDatedElectronicLessons() = runBlocking {
        response = { request -> when (request.requestUrl?.encodedPath) {
            "/kbcx/queryGrkb" -> html(regularHtml(adjacent = true))
            "/xskb/queryXszkb" -> html(experimentHtml())
            "/api/stu/viewCKKB" -> json(electronicJson())
            else -> defaultResponse(request)
        } }
        val courses = success(legacySource(EASToken.Campus.BENBU).getTimetableOfTermOnce(
            term, token().apply { electronicExpToken = "local-electronic-token" },
        ))
        assertEquals(listOf("普通课程", "物理实验", "电子实验", "电子实验"), courses.map { it.name })
        assertRegular(courses.first(), last = 4)
        assertEquals(listOf("13:00", "16:00"), courses.filter { it.scheduledDate != null }.map { it.startTime })
    }

    @Test
    fun graduateBridgeCapturesStudentAndTermThenSeparatesCurrentAndArchivedTimetables() = runBlocking {
        val source = BenbuEASWebSource(
            teachingBuildingsHostOverride = null, onCookiesUpdated = null,
            graduateTimetableHostOverride = server.url("/").toString(),
            graduateBridgeHostOverride = server.url("/").toString(),
        )
        val entered = gate()
        val release = gate()
        val firstBridge = AtomicBoolean(true)
        response = { request -> when (request.requestUrl?.encodedPath) {
            "/yjsgl/outInterface/grkb" -> {
                if (firstBridge.getAndSet(false)) { entered.countDown(); release.await(15, TimeUnit.SECONDS) }
                html("<html>local graduate session</html>")
            }
            "/kbgl/queryXsckcb" -> json("""[{"JCMC":"第1-2节","MON":"当前课程◇张老师[1-2周]A楼[必修]"}]""")
            "/kbgl/queryXsckcbyy" -> json("""[{"JCMC":"第5-6节","MON":"历史课程◇李老师[3周]B楼[选修]"}]""")
            else -> defaultResponse(request)
        } }
        val account = token().apply { stutype = EASToken.TYPE.GRAD }
        term.isCurrent = true
        val pending = async { source.getTimetableOfTermOnce(term, account) }
        try {
            awaitEntered(entered)
            account.stuId = "changed-student"
            account.stutype = EASToken.TYPE.UNDERGRAD
            term.isCurrent = false
            term.yearCode = "2099-2100"
        } finally { release.countDown() }
        val first = success(pending.await()).single()
        assertEquals("当前课程", first.name)
        assertEquals(listOf(1, 2), first.weeks)
        val request = requests.single { it.requestUrl?.encodedPath == "/kbgl/queryXsckcb" }
        assertEquals("student-a", request.requestUrl?.queryParameter("xh"))
        assertEquals("2026-20271", request.requestUrl?.queryParameter("xnxq"))
        assertEquals("1", request.requestUrl?.queryParameter("ys"))

        // A same-account schedule cache for the current endpoint cannot answer an archived request.
        account.stuId = "student-a"
        account.stutype = EASToken.TYPE.GRAD
        term.yearCode = "2026-2027"
        term.isCurrent = true
        assertEquals(2, success(source.getScheduleStructureOnce(term, null, account)).size)
        term.isCurrent = false
        val archived = success(source.getTimetableOfTermOnce(term, account)).single()
        assertEquals("历史课程", archived.name)
        assertEquals(5, archived.begin)
        assertEquals(listOf(3), archived.weeks)
        assertEquals(null, requests.last().requestUrl?.queryParameter("ys"))
    }

    @Test
    fun shenzhenOnceKeepsApiPriorityAndExplicitWebPlanning() = runBlocking {
        val source = shenzhenSource()
        response = { request -> when (request.requestUrl?.encodedPath) {
            "/app/Kbcx/query" -> json(asset("first-week.json"))
            "/app/Xsxk/queryYxkc" -> json(asset("selected.json"))
            "/app/kbrcbyapp/querykbrczong" -> json(asset("overview.json"))
            "/component/queryRlZcSj" -> json("""{"content":[{"RQ":"2026-09-07"}]}""")
            "/Xsxk/queryYxkc" -> json(asset("selected.json"))
            "/component/queryKbjg" -> json(asset("periods.json"))
            else -> defaultResponse(request)
        } }
        val account = token(EASToken.Campus.SHENZHEN).apply { webCookies["SESSION"] = "local-web" }
        assertEquals(listOf(2026, 9, 7), dateParts(success(source.getStartDateOnce(account, term))))
        assertEquals(listOf(8, 30, 9, 20), period(success(source.getScheduleStructureOnce(term, null, account)).first()))
        val subject = success(source.getSubjectsOfTermOnce(account, term)).single()
        assertEquals(listOf("CS1001", "数据结构", "测试教师甲"), listOf(subject.code, subject.name, subject.teacher))
        assertEquals(3f, subject.credit, 0f)
        val course = success(source.getTimetableOfTermOnce(term, account)).single()
        assertEquals("数据结构", course.name)
        assertTrue(course.weeks.isEmpty())
        assertTrue(requests.all { it.requestUrl?.encodedPath?.startsWith("/app/") == true })

        assertEquals(listOf(2026, 9, 7), dateParts(success(source.getShenzhenCoursePlanningStartDateOnce(account, term))))
        assertEquals(2, success(source.getShenzhenCoursePlanningScheduleStructureOnce(account, term)).size)
        assertEquals(listOf("/component/queryRlZcSj", "/Xsxk/queryYxkc", "/component/queryKbjg"), requests.takeLast(3).map { it.requestUrl?.encodedPath })
        assertEquals(server.url("/Xsxk/query/1").toString(), requests.last().getHeader("Referer"))
    }

    @Test
    fun webPlanningCapturesTermAndStudentTypeAcrossItsFallbackRequest() = runBlocking {
        val source = shenzhenSource()
        val entered = gate()
        val release = gate()
        response = { request -> when (request.requestUrl?.encodedPath) {
            "/Xsxk/queryYxkc" -> { entered.countDown(); release.await(15, TimeUnit.SECONDS); json(asset("selected.json")) }
            "/component/queryKbjg" -> json(asset("periods.json"))
            else -> defaultResponse(request)
        } }
        val account = webToken()
        val pending = async { source.getShenzhenCoursePlanningScheduleStructureOnce(account, term) }
        try {
            awaitEntered(entered)
            term.yearCode = "2099-2100"
            term.termCode = "2"
            account.stutype = EASToken.TYPE.GRAD
        } finally { release.countDown() }
        assertEquals(2, success(pending.await()).size)
        assertEquals(mapOf("xn" to "2026-2027", "xq" to "1", "pylx" to "1"), form(requests.last()))
        assertEquals("2026-20271", form(requests.first())["p_xnxq"])
    }

    @Test
    fun cancellingOverviewStopsBeforeSelectedCoursesAndWeekFanout() = runBlocking {
        val source = shenzhenSource()
        val entered = gate()
        val release = gate()
        response = { entered.countDown(); release.await(15, TimeUnit.SECONDS); json("""{"content":[]}""") }
        assertCancelledWithoutDelivery(entered, release) { source.getTimetableOfTermOnce(term, token(EASToken.Campus.SHENZHEN)) }
        assertEquals(listOf("/app/kbrcbyapp/querykbrczong"), requests.map { it.path })
    }

    @Test
    fun cancellingMetadataStopsRoleAndTermCandidateFallbacks() = runBlocking {
        val source = shenzhenSource()
        val entered = gate()
        val release = gate()
        response = { entered.countDown(); release.await(15, TimeUnit.SECONDS); json("""{"yxkcList":[]}""") }
        assertCancelledWithoutDelivery(entered, release) { source.getSubjectsOfTermOnce(token(EASToken.Campus.SHENZHEN), term) }
        assertEquals(1, requests.size)
        assertEquals("/app/Xsxk/queryYxkc", requests.single().requestUrl?.encodedPath)
    }

    @Test
    fun cancellingWebPlanningStopsThePeriodFallback() = runBlocking {
        val source = shenzhenSource()
        val entered = gate()
        val release = gate()
        response = { entered.countDown(); release.await(15, TimeUnit.SECONDS); json(asset("selected.json")) }
        assertCancelledWithoutDelivery(entered, release) { source.getShenzhenCoursePlanningScheduleStructureOnce(webToken(), term) }
        assertEquals(listOf("/Xsxk/queryYxkc"), requests.map { it.requestUrl?.encodedPath })
    }

    @Test
    fun cancellingFourActiveWeekRequestsPreventsQueuedWeeksAndDayEnrichment() = runBlocking {
        val source = shenzhenSource()
        val fourEntered = CountDownLatch(4)
        val release = gate()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        response = { request -> when (request.requestUrl?.encodedPath) {
            "/app/kbrcbyapp/querykbrczong" -> json("""{"content":[]}""")
            "/app/Xsxk/queryYxkc" -> json(asset("selected.json"))
            "/app/Kbcx/query" -> {
                val count = active.incrementAndGet()
                maximum.updateAndGet { previous -> maxOf(previous, count) }
                fourEntered.countDown()
                try { release.await(15, TimeUnit.SECONDS) } finally { active.decrementAndGet() }
                json("""{"code":200,"content":[{"kcxxList":[]}]}""")
            }
            else -> defaultResponse(request)
        } }
        assertCancelledWithoutDelivery(fourEntered, release) { source.getTimetableOfTermOnce(term, token(EASToken.Campus.SHENZHEN)) }
        assertEquals(4, maximum.get())
        assertEquals(4, requests.count { it.path == "/app/Kbcx/query" })
        assertEquals(6, requests.size)
    }

    @Test
    fun cancellingBenbuScheduleStopsExperimentsAndDoesNotPublishTheCourseCache() = runBlocking {
        val source = legacySource(EASToken.Campus.BENBU)
        val account = token()
        val entered = gate()
        val release = gate()
        response = { request ->
            if (request.path == "/kbcx/queryGrkb") { entered.countDown(); release.await(15, TimeUnit.SECONDS) }
            defaultResponse(request)
        }
        assertCancelledWithoutDelivery(entered, release) { source.getScheduleStructureOnce(term, null, account) }
        assertEquals(listOf("/kbcx/queryGrkb"), requests.map { it.path })
        response = { defaultResponse(it) }
        assertRegular(success(source.getTimetableOfTermOnce(term, account)).single())
        assertEquals(2, requests.count { it.path == "/kbcx/queryGrkb" })
    }

    @Test
    fun cancellingEachCampusStartDateNeverDeliversAFallbackCalendar() = runBlocking {
        for (campus in EASToken.Campus.entries) {
            val source = legacySource(campus)
            val entered = gate()
            val release = gate()
            requests.clear()
            response = { entered.countDown(); release.await(15, TimeUnit.SECONDS); html("unavailable", 503) }
            assertCancelledWithoutDelivery(entered, release) { source.getStartDateOnce(token(campus), term) }
            assertEquals(1, requests.size)
        }
    }

    private suspend fun awaitEntered(entered: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue("Fixture request must reach its gate", entered.await(10, TimeUnit.SECONDS))
    }

    private suspend fun assertCancelledWithoutDelivery(
        entered: CountDownLatch,
        release: CountDownLatch,
        query: suspend () -> Any,
    ) = coroutineScope {
        val delivered = AtomicBoolean()
        val job = launch { query(); delivered.set(true) }
        try {
            awaitEntered(entered)
            job.cancel()
        } finally { release.countDown() }
        withTimeout(10_000) { job.join() }
        assertTrue("The requesting job must remain cancelled", job.isCancelled)
        assertFalse("Cancellation must not become success or FETCH_FAILED", delivered.get())
    }
}
