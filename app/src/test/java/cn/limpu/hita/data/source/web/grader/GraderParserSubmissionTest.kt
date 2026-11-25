package cn.limpu.hita.data.source.web.grader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GraderParserSubmissionTest {
    private val course = GraderCourse("3", "合成课程")

    @Test
    fun `assignment list keeps submission limits when the server sends them`() {
        val parsed = GraderParser.assignments(
            """[{"id":11,"title":"实验报告","course":3,"published":true,"due_time":null,
                "description":"写两份","max_file_size":10485760,
                "allowed_extension":"PDF, docx","submit_allowed":true}]""",
            course,
        ).single()
        assertEquals("写两份", parsed.description)
        assertEquals(10485760L, parsed.maxFileSize)
        // 归一化成小写、去点、去空白。
        assertEquals(listOf("pdf", "docx"), parsed.allowedExtensions)
        assertEquals(true, parsed.submitAllowed)
    }

    @Test
    fun `assignment list tolerates missing submission fields`() {
        val parsed = GraderParser.assignments(
            """[{"id":11,"title":"实验报告","course":3,"published":true,"due_time":null}]""",
            course,
        ).single()
        assertNull(parsed.description)
        assertNull(parsed.maxFileSize)
        assertEquals(emptyList<String>(), parsed.allowedExtensions)
        assertNull(parsed.submitAllowed)
    }

    @Test
    fun `assignment detail falls back to the list title`() {
        val detail = GraderParser.assignmentDetail(
            """{"id":11,"description":"要求","max_file_size":2048,
                "allowed_extension":["pdf"],"submit_allowed":false}""",
            "列表标题",
        )
        assertEquals("列表标题", detail.title)
        assertEquals("要求", detail.description)
        assertEquals(2048L, detail.maxFileSize)
        assertEquals(listOf("pdf"), detail.allowedExtensions)
        assertEquals(false, detail.submitAllowed)
    }

    @Test
    fun `upload target keeps the signed url, form fields and ident`() {
        val target = GraderParser.uploadTarget(
            """{"url":"https://grader.hitsz.edu.cn/api/files/upload/abc","ident":"ident-1",
                "params":{"key":"a/b","policy":"p"}}"""
        )
        assertEquals("https://grader.hitsz.edu.cn/api/files/upload/abc", target.url)
        assertEquals("ident-1", target.ident)
        assertEquals(mapOf("key" to "a/b", "policy" to "p"), target.params)
    }

    @Test
    fun `upload target without ident or with non-scalar params is rejected`() {
        assertThrows(GraderInvalidResponseException::class.java) {
            GraderParser.uploadTarget("""{"url":"https://grader.hitsz.edu.cn/x","params":{}}""")
        }
        assertThrows(GraderInvalidResponseException::class.java) {
            GraderParser.uploadTarget(
                """{"url":"https://grader.hitsz.edu.cn/x","ident":"i","params":{"bad":{"n":12}}}"""
            )
        }
        assertThrows(GraderInvalidResponseException::class.java) {
            GraderParser.uploadTarget("""{"ident":"i"}""")
        }
    }
}
