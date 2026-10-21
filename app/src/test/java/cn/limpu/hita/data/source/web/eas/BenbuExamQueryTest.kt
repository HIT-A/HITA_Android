package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.ExamItem
import com.limpu.component.data.DataState
import org.junit.Assert.*
import org.junit.Test

class BenbuExamQueryTest {
    @Test fun `failed final and resit pages preserve midterms`() {
        val result = BenbuExamQuery.load { code, _ ->
            if (code != "02") error("unsupported page")
            listOf(ExamItem().apply { courseName = "机器人学" })
        }
        assertEquals(DataState.STATE.SUCCESS, result.state)
        assertEquals("机器人学", result.data!!.single().courseName)
        assertTrue(result.message!!.contains("期末、补考"))
    }
    @Test fun `empty success plus failed category is not reported as no exams`() {
        val result = BenbuExamQuery.load { code, _ ->
            if (code == "02") error("network error")
            emptyList()
        }
        assertEquals(DataState.STATE.FETCH_FAILED, result.state)
    }
    @Test fun `all valid empty categories remain successful`() {
        val codes = mutableListOf<String>()
        val result = BenbuExamQuery.load { code, _ -> codes += code; emptyList() }
        assertEquals(listOf("01", "02", "03"), codes)
        assertEquals(DataState.STATE.SUCCESS, result.state)
        assertNull(result.message)
    }
    @Test fun `expired session is never swallowed as partial success`() {
        var calls = 0
        val result = BenbuExamQuery.load { _, _ ->
            calls++
            if (calls == 2) throw ExamSessionExpiredException()
            listOf(ExamItem())
        }
        assertEquals(2, calls)
        assertEquals(DataState.STATE.NOT_LOGGED_IN, result.state)
    }
}
