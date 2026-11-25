package cn.limpu.hita.data.source.web.grader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class GraderSubmissionPolicyTest {
    private fun detail(
        submitAllowed: Boolean? = true,
        maxFileSize: Long? = null,
        allowedExtensions: List<String> = emptyList(),
    ) = GraderAssignmentDetail("1", "作业", null, maxFileSize, allowedExtensions, submitAllowed)

    @Test
    fun `extension is normalized to lowercase without the dot`() {
        assertEquals("pdf", GraderSubmissionPolicy.extensionOf("实验报告.PDF"))
        assertEquals("docx", GraderSubmissionPolicy.extensionOf("a.b.docx"))
        assertNull(GraderSubmissionPolicy.extensionOf("noextension"))
        assertNull(GraderSubmissionPolicy.extensionOf("trailing."))
    }

    @Test
    fun `closed assignment rejects before any other check`() {
        val result = GraderSubmissionPolicy.violation(
            detail(submitAllowed = false, maxFileSize = 1L, allowedExtensions = listOf("pdf")),
            "a.png",
            2L,
        )
        assertEquals(GraderSubmissionPolicy.Violation.CLOSED, result)
    }

    @Test
    fun `size limit is enforced with equality allowed`() {
        val limited = detail(maxFileSize = 100L)
        assertNull(GraderSubmissionPolicy.violation(limited, "a.pdf", 100L))
        assertEquals(
            GraderSubmissionPolicy.Violation.TOO_LARGE,
            GraderSubmissionPolicy.violation(limited, "a.pdf", 101L),
        )
        // 没有上限时不拦。
        assertNull(GraderSubmissionPolicy.violation(detail(), "a.pdf", Long.MAX_VALUE))
    }

    @Test
    fun `extension list is enforced only when the assignment declares one`() {
        val limited = detail(allowedExtensions = listOf("pdf"))
        assertNull(GraderSubmissionPolicy.violation(limited, "a.PDF", 1L))
        assertEquals(
            GraderSubmissionPolicy.Violation.EXTENSION,
            GraderSubmissionPolicy.violation(limited, "a.zip", 1L),
        )
        assertNull(GraderSubmissionPolicy.violation(detail(), "a.zip", 1L))
    }

    @Test
    fun `object storage uploads are presigned and never carry the session`() {
        val storage = "https://s3.cs-lab.top/grader-uploads"
        // 会话入口与上传目标无关：两条入口下都直传，且都不带 cookie。
        for (entry in listOf(GraderSessionPolicy.Entry.DIRECT, GraderSessionPolicy.Entry.GATEWAY)) {
            val plan = requireNotNull(GraderSubmissionPolicy.plan(entry, storage))
            val send = plan as GraderSubmissionPolicy.UploadPlan.Send
            assertEquals(storage, send.url)
            assertFalse(send.authenticated)
        }
        // 相对地址先按入口补全，补全后属于入口自身，因此带凭据。
        val relative = requireNotNull(
            GraderSubmissionPolicy.plan(GraderSessionPolicy.Entry.DIRECT, "/api/files/upload/x")
        ) as GraderSubmissionPolicy.UploadPlan.Send
        assertEquals("https://grader.hitsz.edu.cn/api/files/upload/x", relative.url)
        assertTrue(relative.authenticated)
    }

    @Test
    fun `entry host uploads stay on the entry and keep credentials`() {
        val send = requireNotNull(
            GraderSubmissionPolicy.plan(
                GraderSessionPolicy.Entry.DIRECT, "https://grader.hitsz.edu.cn/api/files/upload/x",
            )
        ) as GraderSubmissionPolicy.UploadPlan.Send
        assertEquals("https://grader.hitsz.edu.cn/api/files/upload/x", send.url)
        assertTrue(send.authenticated)
    }

    @Test
    fun `insecure or malformed upload targets are rejected`() {
        for (url in listOf(
            "http://s3.cs-lab.top/grader-uploads",
            "https://user@s3.cs-lab.top/grader-uploads",
            "https://s3.cs-lab.top:8443/grader-uploads",
            "https://s3.cs-lab.top/grader-uploads#fragment",
        )) {
            assertNull(url, GraderSubmissionPolicy.plan(GraderSessionPolicy.Entry.DIRECT, url))
            assertNull(url, GraderSubmissionPolicy.plan(GraderSessionPolicy.Entry.GATEWAY, url))
        }
    }
}
