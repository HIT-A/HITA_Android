package cn.limpu.hita.data.source.web.eas

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.ShenzhenCourseAttachmentKind
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogSource
import cn.limpu.hita.data.model.eas.ShenzhenGradeAnalysisScope
import cn.limpu.hita.data.model.eas.ShenzhenGradeCourse
import cn.limpu.hita.data.model.eas.ShenzhenRecommendationOptions
import cn.limpu.hita.data.model.eas.ShenzhenSelectionPool
import com.limpu.component.data.DataState
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 学期、选课目录、推荐和个人成绩分析的入口契约；响应均为合成数据。 */
@RunWith(AndroidJUnit4::class)
class ShenzhenAcademicCatalogContractTest : ShenzhenAcademicContractHarness() {
    @Test
    fun apiTermsKeepBearerRouteWhenBothSessionsExist() {
        apiResponse = { request ->
            assertEquals("/app/commapp/queryxnxqlist", request.path)
            json(
                """{"content":[
                    {"XN":"2026-2027","XQ":"1","XNXQMC":"秋季","SFDQXQ":"1"},
                    {"XN":"2025-2026","XQ":"3","XNXQMC":"2025-2026夏季","SFDQXQ":"0"}
                ]}""",
            )
        }
        val token = apiToken().apply { webCookies["SESSION"] = "local-unused-web-session" }

        val terms = success { source.getAllTerms(token) }

        assertEquals(listOf("2026-2027-1", "2025-2026-3"), terms.map { it.id })
        assertEquals(listOf("2026-2027 秋季", "2025-2026夏季"), terms.map { it.name })
        assertEquals(listOf(true, false), terms.map { it.isCurrent })
        val request = apiRequests.single()
        assertEquals("POST", request.method)
        assertEquals("bearer local-api-token", request.getHeader("authorization"))
        assertEquals("06", request.getHeader("rolecode"))
        assertTrue(form(request).isEmpty())
        assertTrue(webRequests.isEmpty())
    }

    @Test
    fun webOnlyTermsUseSelectionDropdownAndSelectionCurrentTerm() {
        webResponse = { request ->
            when (request.path) {
                "/component/queryXnxq" -> json(TERMS)
                "/Xsxk/queryXkdqXnxq" -> json("""{"p_xn":"2026-2027","p_xq":"1"}""")
                else -> error("未配置学期请求：${request.path}")
            }
        }

        val terms = success { source.getAllTerms(webToken()) }

        assertEquals(listOf("2026-2027-1", "2025-2026-3"), terms.map { it.id })
        assertEquals("2026-2027学年秋季", terms.first().name)
        assertEquals(listOf(true, false), terms.map { it.isCurrent })
        assertEquals(listOf("/component/queryXnxq", "/Xsxk/queryXkdqXnxq"), webRequests.map { it.path })
        assertWebPost(webRequests[0], "/component/queryXnxq", "/Xsxk/query/1")
        assertTrue(form(webRequests[0]).isEmpty())
        assertWebPost(webRequests[1], "/Xsxk/queryXkdqXnxq", "/Xsxk/query/1")
        assertEquals(mapOf("p_pylx" to "1"), form(webRequests[1]))
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun unavailableSelectionDropdownFallsBackToLegacyTermsEndpoint() {
        webResponse = { request ->
            when (request.path) {
                "/component/queryXnxq" -> json("""{"message":"temporary unavailable"}""", 503)
                "/component/queryxnxqdata" -> json(TERMS)
                "/Xsxk/queryXkdqXnxq" -> json("{}")
                else -> error("未配置学期请求：${request.path}")
            }
        }

        val terms = success { source.getShenzhenWebTerms(webToken()) }

        assertEquals(2, terms.size)
        assertEquals(listOf(false, true), terms.map { it.isCurrent })
        assertEquals(
            listOf("/component/queryXnxq", "/component/queryxnxqdata", "/Xsxk/queryXkdqXnxq"),
            webRequests.map { it.path },
        )
        assertWebPost(webRequests[1], "/component/queryxnxqdata", "/authentication/main")
        assertTrue(form(webRequests[1]).isEmpty())
        assertEquals(mapOf("p_pylx" to "1"), form(webRequests.last()))
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun expiredAuxiliarySelectionTermDoesNotDiscardValidTermList() {
        webResponse = { request ->
            when (request.path) {
                "/component/queryXnxq" -> json(TERMS)
                "/Xsxk/queryXkdqXnxq" -> json("""{"code":401,"message":"session expired"}""", 401)
                else -> error("未配置学期请求：${request.path}")
            }
        }

        val terms = success { source.getShenzhenWebTerms(webToken()) }

        assertEquals(listOf("2026-2027-1", "2025-2026-3"), terms.map { it.id })
        assertEquals(listOf(false, true), terms.map { it.isCurrent })
        assertEquals(listOf("/component/queryXnxq", "/Xsxk/queryXkdqXnxq"), webRequests.map { it.path })
        assertEquals(mapOf("p_pylx" to "1"), form(webRequests.last()))
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun availableCoursesInitializeThenQueryRequestedPoolAndPage() {
        webResponse = { request ->
            when (request.path) {
                SELECTED_PATH -> json("""{"yxkcList":[]}""")
                AVAILABLE_PATH -> json(
                    """{"kxrwList":{"pageNum":2,"pageSize":2,"total":5,"list":[{
                        "id":"SELECT-NEW","rwh":"TASK-NEW","kcid":"COURSE-NEW",
                        "kcdm":"CS3000","kcmc":"合成选修课","xf":"3","bksrl":"40","bksyxrs":"38",
                        "pkjgmx":"<p>1-8周,星期二第3-4节 测试楼101</p>"
                    }]}}""",
                )
                else -> error("未配置可选课请求：${request.path}")
            }
        }

        val page = success {
            source.queryShenzhenAvailableCourses(
                webToken(), term, ShenzhenSelectionPool("xx-b-b", "测试限选池"), "合成 课程", 2, 2,
            )
        }

        assertEquals(2, page.page)
        assertEquals(2, page.pageSize)
        assertEquals(5, page.total)
        assertTrue(page.hasNextPage)
        val item = page.items.single()
        assertEquals("CS3000", item.courseCode)
        assertEquals("TASK-NEW", item.taskId)
        assertEquals("SELECT-NEW", item.selectionRequestId)
        assertEquals("测试限选池", item.selectionPoolName)
        assertEquals(ShenzhenCourseCatalogSource.AVAILABLE, item.source)
        assertEquals(2, item.remainingSeats)
        assertEquals(2, item.meetings.single().weekday)
        assertEquals(listOf(SELECTED_PATH, AVAILABLE_PATH), webRequests.map { it.path })
        assertEquals(form(webRequests[0]), form(webRequests[1]))
        val fields = form(webRequests[1])
        assertEquals("2026-2027", fields["p_xn"])
        assertEquals("1", fields["p_xq"])
        assertEquals("2026-20271", fields["p_xnxq"])
        assertEquals("xx-b-b", fields["p_xkfsdm"])
        assertEquals("合成 课程", fields["p_gjz"])
        assertEquals("合成 课程", fields["p_kc_gjz"])
        assertEquals("1", fields["p_pylx"])
        assertEquals("2", fields["pageNum"])
        assertEquals("2", fields["pageSize"])
        assertWebPost(webRequests[1], AVAILABLE_PATH, "/Xsxk/query/1")
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun failedAvailableCourseInitializationStopsBeforeCatalogRequest() {
        webResponse = { request ->
            assertEquals(SELECTED_PATH, request.path)
            json("""{"message":"initialization unavailable"}""", 503)
        }

        val state = awaitState {
            source.queryShenzhenAvailableCourses(
                webToken(), term, ShenzhenSelectionPool("xx-b-b", "测试限选池"), "", 1, 20,
            )
        }

        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals("选课查询初始化失败", state.message)
        assertEquals(listOf(SELECTED_PATH), webRequests.map { it.path })
        assertEquals("xx-b-b", form(webRequests.single())["p_xkfsdm"])
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun schoolCatalogUsesRequestedStudentTypeWithoutSelectionInitialization() {
        webResponse = { request ->
            assertEquals(SCHOOL_PATH, request.path)
            json(
                """{"rwList":{"pageNum":3,"pageSize":7,"total":15,"list":[{
                    "id":"SCHOOL-ROW","rwh":"SCHOOL-TASK","kcdm":"MATH5000","kcmc":"合成研究生课",
                    "bksrl":"50","bksyxrs":"45","yjsrl":"12","yjsyxrs":"9"
                }]}}""",
            )
        }

        val page = success { source.queryShenzhenSchoolCourses(webToken(), term, "2", "数学", 3, 7) }

        assertEquals(3, page.page)
        assertFalse(page.hasNextPage)
        val item = page.items.single()
        assertEquals("MATH5000", item.courseCode)
        assertEquals(ShenzhenCourseCatalogSource.SCHOOL, item.source)
        assertEquals(12, item.capacity)
        assertEquals(9, item.selectedCount)
        val request = webRequests.single()
        assertWebPost(request, SCHOOL_PATH, "/Xsxktz/queryRwxxcx")
        val fields = form(request)
        assertEquals("2026-20271", fields["p_xnxq"])
        assertEquals("2", fields["p_chaxunpylx"])
        assertEquals("2", fields["mxpylx"])
        assertEquals("数学", fields["p_chaxungjz"])
        assertEquals("3", fields["pageNum"])
        assertEquals("7", fields["pageSize"])
        assertFalse(fields.containsKey("p_xkfsdm"))
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun recommendationsUseResponsePoolsReadEveryPageAndExcludeAlreadySelectedCourse() {
        webResponse = { request ->
            val fields = form(request)
            when (request.path) {
                SELECTED_PATH -> if (fields["p_xkfsdm"] == "yixuan") {
                    json(
                        """{"yxkcList":[{
                            "id":"SELECTED-ROW","rwh":"SELECTED-TASK","kcdm":"SELECTED",
                            "kcmc":"已选合成课","xf":"2"
                        }],"xkgzszList":[
                            {"xkfsdm":"pool-a","xkfsmc":"合成池甲"},
                            {"xkfsdm":"pool-b","xkfsmc":"合成池乙"}
                        ]}""",
                    )
                } else {
                    json("{}")
                }
                AVAILABLE_PATH -> when (fields["p_xkfsdm"] to fields["pageNum"]) {
                    "pool-a" to "1" -> json(
                        """{"kxrwList":{"pageNum":1,"pageSize":200,"total":201,"list":[{
                            "id":"SELECTED-OTHER-ROW","rwh":"SELECTED-OTHER-TASK","kcdm":"SELECTED",
                            "kcmc":"已选合成课另一班","xf":"2"
                        }]}}""",
                    )
                    "pool-a" to "2" -> json(
                        """{"kxrwList":{"pageNum":2,"pageSize":200,"total":201,"list":[{
                            "id":"NEW-A-ROW","rwh":"NEW-A-TASK","kcdm":"NEW-A",
                            "kcmc":"合成新课甲","xf":"1"
                        }]}}""",
                    )
                    "pool-b" to "1" -> json(
                        """{"kxrwList":{"pageNum":1,"pageSize":200,"total":1,"list":[{
                            "id":"NEW-B-ROW","rwh":"NEW-B-TASK","kcdm":"NEW-B",
                            "kcmc":"合成新课乙","xf":"2"
                        }]}}""",
                    )
                    else -> error("未配置推荐课程池或页码：$fields")
                }
                else -> error("未配置推荐请求：${request.path}")
            }
        }

        val result = success {
            source.getShenzhenCourseRecommendations(
                webToken(), term,
                listOf(ShenzhenSelectionPool("unused-fallback", "未采用的备用池")),
                ShenzhenRecommendationOptions(targetAdditionalCredits = 3.0),
            )
        }

        assertEquals(2.0, result.selectedCredits, 0.0)
        assertEquals(2, result.candidateCount)
        val plan = result.plans.first()
        assertEquals(setOf("NEW-A", "NEW-B"), plan.courses.map { it.courseCode }.toSet())
        assertEquals(setOf("合成池甲", "合成池乙"), plan.courses.map { it.selectionPoolName }.toSet())
        assertEquals(3.0, plan.additionalCredits, 0.0)
        assertEquals(5.0, plan.totalCredits, 0.0)
        assertTrue(result.plans.all { candidate -> candidate.courses.none { it.courseCode == "SELECTED" } })
        assertEquals(
            listOf(SELECTED_PATH, SELECTED_PATH, AVAILABLE_PATH, AVAILABLE_PATH, SELECTED_PATH, AVAILABLE_PATH),
            webRequests.map { it.path },
        )
        assertEquals(
            listOf("yixuan", "pool-a", "pool-a", "pool-a", "pool-b", "pool-b"),
            webRequests.map { form(it)["p_xkfsdm"] },
        )
        assertEquals(listOf("1", "2", "1"), webRequests.filter { it.path == AVAILABLE_PATH }.map { form(it)["pageNum"] })
        webRequests.forEach { request ->
            assertWebPost(request, requireNotNull(request.path), "/Xsxk/query/1")
            assertEquals("2026-20271", form(request)["p_xnxq"])
            assertEquals("200", form(request)["pageSize"])
        }
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun courseAttachmentsSendCourseAndTaskIdsAndKeepFileMetadata() {
        webResponse = { request ->
            assertEquals("/kck/kcxxwh/xsckViewByxk?sf_request_type=ajax", request.path)
            json(
                """{"content":{
                    "kcxxbgbEntity":{"kcjjfname":"合成简介.docx","kcjjsname":"/document/synthetic/intro.docx"},
                    "kcdgbentity":{"kczwdgwjm":"合成大纲.pdf","kczwdgurl":"/document/synthetic/syllabus.pdf","kczwdgsize":"4096"}
                }}""",
            )
        }

        val files = success { source.getShenzhenCourseAttachments(webToken(), "COURSE-SYNTHETIC", "TASK-SYNTHETIC") }

        assertEquals(listOf("合成简介.docx", "合成大纲.pdf"), files.map { it.name })
        assertEquals(listOf(ShenzhenCourseAttachmentKind.COURSE_DESCRIPTION, ShenzhenCourseAttachmentKind.CHINESE_SYLLABUS), files.map { it.kind })
        assertEquals("/document/synthetic/intro.docx", files[0].serverPath)
        assertEquals(4096L, files[1].sizeBytes)
        assertTrue(files.all { it.courseId == "COURSE-SYNTHETIC" })
        val request = webRequests.single()
        assertWebPost(request, "/kck/kcxxwh/xsckViewByxk?sf_request_type=ajax", "/Xsxk/query/1")
        assertEquals(mapOf("kcid" to "COURSE-SYNTHETIC", "kcsqid" to "", "rwh" to "TASK-SYNTHETIC"), form(request))
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun personalGradeAnalysisSendsBothIdsAndKeepsPersonalScope() {
        webResponse = { request ->
            assertEquals(ANALYSIS_PATH, request.path)
            json(
                """[
                    {"XSCJB_ID":"RECORD-SYNTHETIC","FXMC":"期末","DF":"80","MF":"100","LJFXBZ":"75"},
                    {"XSCJB_ID":"RECORD-SYNTHETIC","FXMC":"实验","DF":"100","MF":"100","LJFXBZ":"25"}
                ]""",
            )
        }

        val analysis = success { source.getShenzhenGradeAnalysis(webToken(), gradeCourse()) }

        assertEquals(ShenzhenGradeAnalysisScope.PERSONAL, analysis.scope)
        assertEquals(1, analysis.students.size)
        assertEquals(85.0, requireNotNull(analysis.myScore), 0.0)
        assertEquals(listOf("期末", "实验"), analysis.myComponents.map { it.name })
        val request = webRequests.single()
        assertWebPost(request, ANALYSIS_PATH, "/cjgl/grcjcx/go/1")
        assertEquals(mapOf("rwid" to "TASK-SYNTHETIC", "cjid" to "RECORD-SYNTHETIC"), form(request))
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun personalGradeAnalysisRejectsMissingTaskOrRecordWithoutNetwork() {
        val missingTask = awaitState {
            source.getShenzhenGradeAnalysis(webToken(), gradeCourse().copy(taskId = ""))
        }
        val missingRecord = awaitState {
            source.getShenzhenGradeAnalysis(webToken(), gradeCourse().copy(recordId = ""))
        }

        assertEquals(DataState.STATE.FETCH_FAILED, missingTask.state)
        assertEquals("该课程缺少教学任务标识", missingTask.message)
        assertEquals(DataState.STATE.FETCH_FAILED, missingRecord.state)
        assertEquals("该课程尚未提供可查询的个人成绩分项", missingRecord.message)
        assertTrue(webRequests.isEmpty())
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun gradeAnalysisPermissionErrorsStayFetchFailuresForHttpAndJson403() {
        for (status in listOf(403, 200)) {
            webResponse = { request ->
                assertEquals(ANALYSIS_PATH, request.path)
                json("""{"code":403,"message":"权限不足"}""", status)
            }

            val state = awaitState { source.getShenzhenGradeAnalysis(webToken(), gradeCourse()) }

            assertEquals(DataState.STATE.FETCH_FAILED, state.state)
            assertEquals("教务拒绝访问该课程的个人成绩分项", state.message)
        }
        assertEquals(2, webRequests.size)
        webRequests.forEach { request ->
            assertEquals(mapOf("rwid" to "TASK-SYNTHETIC", "cjid" to "RECORD-SYNTHETIC"), form(request))
        }
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun gradeAnalysisSessionExpiryIsNotReportedAsPermissionFailure() {
        webResponse = { request ->
            assertEquals(ANALYSIS_PATH, request.path)
            json("""{"code":401,"message":"session expired"}""", 401)
        }

        val state = awaitState { source.getShenzhenGradeAnalysis(webToken(), gradeCourse()) }

        assertEquals(DataState.STATE.NOT_LOGGED_IN, state.state)
        assertEquals("深圳 Web 会话已失效", state.message)
        assertEquals(listOf(ANALYSIS_PATH), webRequests.map { it.path })
        assertEquals(mapOf("rwid" to "TASK-SYNTHETIC", "cjid" to "RECORD-SYNTHETIC"), form(webRequests.single()))
        assertTrue(apiRequests.isEmpty())
    }

    @Test
    fun disabledHistoricalTeacherStatisticsReturnFailureWithoutAnyRequest() {
        val course = ShenzhenCourseCatalogItem(
            id = "SCHOOL-SYNTHETIC", taskId = "TASK-SYNTHETIC", courseCode = "CS-SYNTHETIC",
            courseName = "合成课程", source = ShenzhenCourseCatalogSource.SCHOOL,
        )

        val state = awaitState {
            source.getShenzhenHistoricalTeacherFailureRates(webToken(), term, "1", course)
        }

        assertEquals(DataState.STATE.FETCH_FAILED, state.state)
        assertEquals("新版教务已将成绩分项限制为本人记录，无法再生成其他教学班或教师的成绩统计", state.message)
        assertTrue(webRequests.isEmpty())
        assertTrue(apiRequests.isEmpty())
    }

    private fun gradeCourse() = ShenzhenGradeCourse(
        taskId = "TASK-SYNTHETIC", recordId = "RECORD-SYNTHETIC", courseName = "合成课程",
    )

    private fun assertWebPost(request: RecordedRequest, path: String, referer: String) {
        assertEquals(path, request.path)
        assertEquals("POST", request.method)
        assertEquals(web.url(referer).toString(), request.getHeader("Referer"))
        assertEquals("01", request.getHeader("RoleCode"))
        assertTrue(request.getHeader("Cookie").orEmpty().contains("SESSION=local-web-session"))
    }

    companion object {
        private const val SELECTED_PATH = "/Xsxk/queryYxkc?sf_request_type=ajax"
        private const val AVAILABLE_PATH = "/Xsxk/queryKxrw?sf_request_type=ajax"
        private const val SCHOOL_PATH = "/Xsxktz/queryRwxxcxList?sf_request_type=ajax"
        private const val ANALYSIS_PATH = "/cjgl/grcjcx/seeFx?sf_request_type=ajax"
        private val TERMS = """{"content":[
            {"xn":"2026-2027","xq":"1","xnmc":"2026-2027学年","xqmc":"秋季","sfdqxq":"0"},
            {"xn":"2025-2026","xq":"3","xnmc":"2025-2026学年","xqmc":"夏季","sfdqxq":"1"}
        ]}"""
    }
}
