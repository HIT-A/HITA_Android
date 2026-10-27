package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.component.data.DataState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Same assertions run against frozen pre-Q3 production files and the migrated compatibility entry. */
@RunWith(AndroidJUnit4::class)
class TimetableLegacyRemoteContractTest : TimetableRemoteContractHarness() {
    @Test
    fun benbuCombinesRegularExperimentalAndDatedElectronicLessonsWithoutLosingSessions() {
        response = { request -> when (request.requestUrl?.encodedPath) {
            "/kbcx/queryGrkb" -> html(regularHtml(adjacent = true))
            "/xskb/queryXszkb" -> html(experimentHtml())
            "/api/stu/viewCKKB" -> json(electronicJson())
            else -> defaultResponse(request)
        } }
        val account = token().apply { electronicExpToken = "local-electronic-token" }
        val courses = success(awaitLegacy { legacySource(EASToken.Campus.BENBU).getTimetableOfTerm(term, account) })
        assertEquals(4, courses.size)
        assertRegular(courses.single { it.name == "普通课程" }, last = 4)
        val experiment = courses.single { it.name == "物理实验" }
        assertEquals(listOf(2), experiment.weeks)
        assertEquals(listOf("13:00", "15:00", "教师乙", "L101"), listOf(experiment.startTime, experiment.endTime, experiment.teacher, experiment.classroom))
        val electronic = courses.filter { it.name == "电子实验" }.sortedBy { it.startTime }
        assertEquals(listOf("2026-09-08", "2026-09-08"), electronic.map { it.scheduledDate })
        assertEquals(listOf("13:00", "16:00"), electronic.map { it.startTime })
        assertEquals(listOf("15:00", "18:00"), electronic.map { it.endTime })
        assertTrue(electronic.all { it.classroom == "电机楼301(电路室)" && it.teacher == "教师丙" })
        assertEquals(listOf("/kbcx/queryGrkb", "/loginsysj/loginSygl", "/xskb/queryXszkb", "/api/stu/viewCKKB"), requests.map { it.requestUrl?.encodedPath })
        assertEquals(mapOf("fhlj" to "kbcx/queryGrkb", "xnxq" to term.getCode()), form(requests.first()))
        assertEquals("local-electronic-token", requests.last().getHeader("VcTchToken"))
    }

    @Test
    fun optionalExperimentFailuresKeepTheRegularTimetable() {
        response = { request -> when (request.requestUrl?.encodedPath) {
            "/loginsysj/loginSygl", "/xskb/queryXszkb" -> html("unavailable", 503)
            "/api/stu/viewCKKB" -> json("malformed")
            else -> defaultResponse(request)
        } }
        val account = token().apply { electronicExpToken = "local-electronic-token" }
        assertRegular(success(awaitLegacy { legacySource(EASToken.Campus.BENBU).getTimetableOfTerm(term, account) }).single())
        assertEquals(4, requests.size)
    }

    @Test
    fun bothLegacyCampusesKeepAuthenticationFailureAndScheduleFallbackDistinct() {
        for (campus in listOf(EASToken.Campus.BENBU, EASToken.Campus.WEIHAI)) {
            for (status in listOf(401, 503)) {
                response = { html("upstream failure", status) }
                val source = legacySource(campus)
                val account = token(campus)
                val state = awaitLegacy { source.getTimetableOfTerm(term, account) }
                assertEquals(if (status == 401) DataState.STATE.NOT_LOGGED_IN else DataState.STATE.FETCH_FAILED, state.state)
                assertEquals(12, success(awaitLegacy { source.getScheduleStructure(term, true, account) }).size)
            }
        }
    }

    @Test
    fun bothLegacyCampusesRetainCalendarParsingAndInferenceFallback() {
        for (campus in listOf(EASToken.Campus.BENBU, EASToken.Campus.WEIHAI)) {
            val source = legacySource(campus)
            response = { html(calendarHtml()) }
            assertEquals(listOf(2026, 9, 7), dateParts(success(awaitLegacy { source.getStartDate(token(campus), term) })))
            response = { html("unavailable", 503) }
            assertEquals(listOf(2026, 9, 14), dateParts(success(awaitLegacy { source.getStartDate(token(campus), term) })))
        }
        assertTrue(requests.all { it.path == "/xlcx/queryXlcx" && form(it)["xnxq"] == term.getCode() })
    }

    @Test
    fun bothLegacyCampusesKeepUnsupportedMetadataAndEmptyTimetableSuccess() {
        for (campus in listOf(EASToken.Campus.BENBU, EASToken.Campus.WEIHAI)) {
            val source = legacySource(campus)
            val before = requests.size
            val metadata = awaitLegacy { source.getSubjectsOfTerm(token(campus), term) }
            assertEquals(DataState.STATE.FETCH_FAILED, metadata.state)
            assertEquals(before, requests.size)
            response = { request -> if (request.requestUrl?.encodedPath == "/kbcx/queryGrkb") html("<table class=addlist_01></table>") else defaultResponse(request) }
            assertTrue(success(awaitLegacy { source.getTimetableOfTerm(term, token(campus)) }).isEmpty())
        }
    }
}
