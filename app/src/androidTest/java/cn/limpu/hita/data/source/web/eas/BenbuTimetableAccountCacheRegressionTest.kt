package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BenbuTimetableAccountCacheRegressionTest : TimetableRemoteContractHarness() {
    @Test
    fun scheduleCacheFromOneAccountMustNotBecomeAnotherAccountsTimetable() {
        response = { request ->
            if (request.requestUrl?.encodedPath == "/kbcx/queryGrkb") {
                html(regularHtml(if (request.getHeader("Cookie").orEmpty().contains("student-b")) "乙课程" else "甲课程"))
            } else defaultResponse(request)
        }
        val source = legacySource(EASToken.Campus.BENBU)
        assertEquals(2, success(awaitLegacy { source.getScheduleStructure(term, true, token(owner = "student-a")) }).size)
        val courses = success(awaitLegacy { source.getTimetableOfTerm(term, token(owner = "student-b")) })

        assertEquals("Schedule cache must not cross accounts", "乙课程", courses.single().name)
        assertEquals(2, requests.count { it.path == "/kbcx/queryGrkb" })
    }
}
