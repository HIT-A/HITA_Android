package cn.limpu.hita.data.source.web.eas

import android.annotation.SuppressLint
import android.util.Base64
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.utils.LogUtils
import com.limpu.component.data.DataState
import cn.limpu.hita.data.model.eas.CourseSelectionCourseResult
import cn.limpu.hita.data.model.eas.CourseSelectionCourseStatus
import cn.limpu.hita.data.model.eas.CourseSelectionJob
import cn.limpu.hita.data.model.eas.CourseSelectionJobCourse
import cn.limpu.hita.data.model.eas.CourseItem
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.eas.ScoreQueryResult
import cn.limpu.hita.data.model.eas.ScoreSummary
import cn.limpu.hita.data.model.eas.ScoreSummaryScope
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogPage
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogSource
import cn.limpu.hita.data.model.eas.ShenzhenCourseAttachment
import cn.limpu.hita.data.model.eas.ShenzhenGradeAnalysis
import cn.limpu.hita.data.model.eas.ShenzhenGradeAnalysisScope
import cn.limpu.hita.data.model.eas.ShenzhenGradeCourse
import cn.limpu.hita.data.model.eas.ShenzhenGradeStatus
import cn.limpu.hita.data.model.eas.ShenzhenHistoricalFailureReport
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.eas.ShenzhenCourseRecommendationResult
import cn.limpu.hita.data.model.eas.ShenzhenCreditProgress
import cn.limpu.hita.data.model.eas.ShenzhenCreditRequirement
import cn.limpu.hita.data.model.eas.ShenzhenRecommendationOptions
import cn.limpu.hita.data.model.eas.ShenzhenSelectionPool
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlan
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlanCourse
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlanDetail
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlanLevel
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.source.web.service.EASService
import cn.limpu.hita.data.repository.EasCredentialReloginPolicy
import cn.limpu.hita.data.source.preference.EasCredential
import cn.limpu.hita.ui.eas.classroom.BuildingItem
import cn.limpu.hita.ui.eas.classroom.ClassroomItem
import cn.limpu.hita.utils.JsonUtils
import cn.limpu.hita.utils.CourseCodeUtils
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup

/**
 * 深圳校区教务学术模块：选课、成绩、培养方案。
 *
 * 从 EASWebSource 抽取的内聚模块，通过 lambda 使用 EASWebSource 的 HTTP/鉴权基础设施。
 */
internal class ShenzhenAcademicWebSource(
    private val authedFormPost: (token: EASToken, path: String, data: Map<String, String>) -> Connection.Response,
    private val jwFormPost: (token: EASToken, path: String, data: Map<String, String>, refererPath: String) -> Connection.Response,
    private val jwJsonPost: (token: EASToken, path: String, body: String, refererPath: String) -> Connection.Response,
    private val jwSelectionFormPostOnce: (token: EASToken, path: String, data: Map<String, String>, refererPath: String) -> Connection.Response,
    private val isJwAuthenticationExpired: (resp: Connection.Response) -> Boolean,
    private val onTokenRefreshed: ((EASToken) -> Unit)?,
    private val buildTermDisplayName: (yearName: String?, termName: String?) -> String,
    private val shenzhenSelectedSubjectsForm: (token: EASToken, term: TermItem) -> Map<String, String>,
    private val requestShenzhenWebSelectedSubjects: (token: EASToken, term: TermItem) -> Connection.Response,
) {

    private fun mergeShenzhenCourseSelectionCookies(
        token: EASToken,
        response: Connection.Response
    ) {
        synchronized(token) {
            token.webCookies.putAll(response.cookies())
        }
        onTokenRefreshed?.invoke(token)
    }

    // ================================================================ 学年学期列表
    fun getAllTerms(token: EASToken): LiveData<DataState<List<TermItem>>> {
        if (token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()) {
            return getShenzhenWebTerms(token)
        }
        val res = MutableLiveData<DataState<List<TermItem>>>()
        Thread {
            val terms = arrayListOf<TermItem>()
            try {
                val resp = authedFormPost(token, "/app/commapp/queryxnxqlist", emptyMap())
                val jo = JsonUtils.getJsonObject(resp.body())
                val content = jo?.optJSONArray("content")
                if (content == null) {
                    res.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                    return@Thread
                }
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    val term = TermItem(
                        yearCode = item.optString("XN"),
                        yearName = item.optString("XN"),
                        termCode = item.optString("XQ"),
                        termName = item.optString("XNXQMC")
                    )
                    term.name = buildTermDisplayName(term.yearName, term.termName)
                    term.isCurrent = item.optString("SFDQXQ") == "1"
                    terms.add(term)
                }
                res.postValue(DataState(terms, DataState.STATE.SUCCESS))
} catch (e: Exception) {
                LogUtils.e("getAllTerms: failed, error=${e.message}", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }

    fun getShenzhenWebTerms(token: EASToken): LiveData<DataState<List<TermItem>>> {
        val result = MutableLiveData<DataState<List<TermItem>>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            try {
                // The course-selection module has its own complete term dropdown. Unlike the
                // generic historical-term endpoint, it includes a newly opened next term while
                // the academic calendar is still in the preceding summer term.
                var response = jwFormPost(token, "/component/queryXnxq", emptyMap(), "/Xsxk/query/1")
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                var terms = ShenzhenCourseCatalogParser.parseTerms(response.body())
                if (response.statusCode() != 200 || terms == null) {
                    // Keep compatibility with the previous portal while deployments are rolling
                    // between versions.
                    response = jwFormPost(token, "/component/queryxnxqdata", emptyMap(), "/authentication/main")
                    if (isJwAuthenticationExpired(response)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    terms = ShenzhenCourseCatalogParser.parseTerms(response.body())
                }
                if (response.statusCode() != 200 || terms == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "学期列表解析失败"))
                } else {
                    val selectionResponse = jwFormPost(token, "/Xsxk/queryXkdqXnxq", mapOf("p_pylx" to token.getStudentType()), "/Xsxk/query/1")
                    val selectionTermId = if (isJwAuthenticationExpired(selectionResponse)) {
                        null
                    } else {
                        ShenzhenCourseCatalogParser.parseSelectionTermId(selectionResponse.body())
                    }
                    if (selectionTermId != null && terms.any { it.id == selectionTermId }) {
                        terms.forEach { it.isCurrent = it.id == selectionTermId }
                    }
                    LogUtils.d(
                        "getShenzhenWebTerms: count=${terms.size}, selectionTerm=$selectionTermId, " +
                            "selectedPresent=${terms.any { it.id == selectionTermId }}"
                    )
                    result.postValue(DataState(terms, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun queryShenzhenAvailableCourses(
        token: EASToken,
        term: TermItem,
        pool: ShenzhenSelectionPool,
        keyword: String,
        page: Int,
        pageSize: Int
    ): LiveData<DataState<ShenzhenCourseCatalogPage>> {
        val form = linkedMapOf(
            "cxsfmt" to "0",
            "p_pylx" to token.getStudentType(),
            "mxpylx" to token.getStudentType(),
            "p_sfgldjr" to "0",
            "p_sfredis" to "0",
            "p_sfsyxkgwc" to "0",
            "p_xktjz" to "",
            "p_chaxunxh" to "",
            "p_gjz" to keyword,
            "p_skjs" to "",
            "p_xn" to term.yearCode,
            "p_xq" to term.termCode,
            "p_xnxq" to term.getCode(),
            "p_xkfsdm" to pool.code,
            "p_xiaoqu" to "",
            "p_kkyx" to "",
            "p_kclb" to "",
            "p_xkxs" to "",
            "p_dyc" to "",
            "p_kkxnxq" to "",
            "p_id" to "",
            "p_sfhlctkc" to "0",
            "p_sfhllrlkc" to "0",
            "p_kxsj_xqj" to "",
            "p_kxsj_ksjc" to "",
            "p_kxsj_jsjc" to "",
            "p_kcdm_js" to "",
            "p_kcdm_cxrw" to "",
            "p_kcdm_cxrw_zckc" to "",
            "p_kc_gjz" to keyword,
            "p_xzcxtjz_nj" to "",
            "p_xzcxtjz_yx" to "",
            "p_xzcxtjz_zy" to "",
            "p_xzcxtjz_zyfx" to "",
            "p_xzcxtjz_bj" to "",
            "p_sfxsgwckb" to "1",
            "p_skyy" to "",
            "p_sfmxzj" to "0",
            "p_chaxunxkfsdm" to "",
            "pageNum" to page.toString(),
            "pageSize" to pageSize.toString()
        )
        return queryShenzhenCourseCatalog(
            token = token,
            path = "/Xsxk/queryKxrw?sf_request_type=ajax",
            refererPath = "/Xsxk/query/1",
            form = form,
            source = ShenzhenCourseCatalogSource.AVAILABLE,
            selectionPoolName = pool.name
        )
    }

    @WorkerThread
    fun submitShenzhenCourseOnce(
        token: EASToken,
        job: CourseSelectionJob,
        course: CourseSelectionJobCourse
    ): CourseSelectionCourseResult {
        require(token.hasShenzhenWebSession()) { "A Shenzhen Web session is required" }
        val submittedAtMillis = System.currentTimeMillis()
        val response = try {
            jwSelectionFormPostOnce(token, "/Xsxk/addGouwuche", ShenzhenCourseSelectionForm.build(
                    studentType = token.getStudentType(),
                    termYearCode = job.termYearCode,
                    termCode = job.termCode,
                    poolCode = course.poolCode,
                    requestId = course.requestId
                ), "/Xsxk/query/1")
        } catch (_: IOException) {
            return CourseSelectionCourseResult(
                courseId = course.courseId,
                status = CourseSelectionCourseStatus.UNKNOWN,
                message = "",
                submittedAtMillis = submittedAtMillis
            )
        }
        mergeShenzhenCourseSelectionCookies(token, response)
        val parsed = ShenzhenCourseSelectionResponseParser.parse(
            statusCode = response.statusCode(),
            responseUrl = response.url().toString(),
            body = response.body()
        )
        return CourseSelectionCourseResult(
            courseId = course.courseId,
            status = parsed.status,
            message = parsed.message,
            submittedAtMillis = submittedAtMillis
        )
    }

    @WorkerThread
    fun getShenzhenSelectedRequestIdsOnce(
        token: EASToken,
        job: CourseSelectionJob
    ): Set<String> {
        require(token.hasShenzhenWebSession()) { "A Shenzhen Web session is required" }
        val response = try {
            jwSelectionFormPostOnce(token, "/Xsxk/queryYxkc?sf_request_type=ajax", shenzhenSelectedSubjectsForm(
                    token,
                    TermItem(job.termYearCode, job.termYearCode, job.termCode, "")
                ), "/Xsxk/query/1")
        } catch (_: IOException) {
            return emptySet()
        }
        mergeShenzhenCourseSelectionCookies(token, response)
        if (isJwAuthenticationExpired(response) || response.statusCode() !in 200..299) {
            return emptySet()
        }
        return ShenzhenSelectedCourseIdentityParser.parse(response.body())
    }

    fun queryShenzhenSchoolCourses(
        token: EASToken,
        term: TermItem,
        studentType: String,
        keyword: String,
        page: Int,
        pageSize: Int
    ): LiveData<DataState<ShenzhenCourseCatalogPage>> {
        val form = shenzhenSchoolCourseForm(term, studentType, keyword, page, pageSize)
        return queryShenzhenCourseCatalog(
            token = token,
            path = "/Xsxktz/queryRwxxcxList?sf_request_type=ajax",
            refererPath = "/Xsxktz/queryRwxxcx",
            form = form,
            source = ShenzhenCourseCatalogSource.SCHOOL,
            studentType = studentType
        )
    }

    private fun shenzhenSchoolCourseForm(
        term: TermItem,
        studentType: String,
        keyword: String,
        page: Int,
        pageSize: Int
    ) = linkedMapOf(
            "p_chapylx" to "",
            "ordertext_0" to "",
            "p_xn" to term.yearCode,
            "p_xq" to term.termCode,
            "p_xnxq" to term.getCode(),
            "p_gjz" to keyword,
            "p_xiaoqu" to "",
            "p_kkyx" to "",
            "p_rwlx" to "",
            "p_kclb" to "",
            "p_kcxz" to "",
            "p_chaxungjz" to keyword,
            "p_chaxunxiaoqu" to "",
            "p_chaxunkkyx" to "",
            "p_chaxunnj" to "",
            "p_chaxunglyx" to "",
            "p_chaxunzy" to "",
            "p_chaxunxdm" to "",
            "p_chaxunpylx" to studentType,
            "mxpylx" to studentType,
            "p_zc" to "",
            "p_xqj" to "",
            "p_ksjc" to "",
            "p_jsjc" to "",
            "p_skjs" to "",
            "p_ids" to "",
            "p_id" to "",
            "p_sfhltsxx" to "0",
            "file" to "",
            "pageNum" to page.toString(),
            "pageSize" to pageSize.toString()
        )

    private fun queryShenzhenCourseCatalog(
        token: EASToken,
        path: String,
        refererPath: String,
        form: Map<String, String>,
        source: ShenzhenCourseCatalogSource,
        studentType: String = token.getStudentType(),
        selectionPoolName: String = ""
    ): LiveData<DataState<ShenzhenCourseCatalogPage>> {
        val result = MutableLiveData<DataState<ShenzhenCourseCatalogPage>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            try {
                if (source == ShenzhenCourseCatalogSource.AVAILABLE) {
                    // The portal binds queryKxrw to state initialized by queryYxkc for the same
                    // term and selection mode. Calling queryKxrw directly can return jg=-1 or an
                    // empty list even while courses are available.
                    val initialization = jwFormPost(token, "/Xsxk/queryYxkc?sf_request_type=ajax", form, "/Xsxk/query/1")
                    if (isJwAuthenticationExpired(initialization)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    if (initialization.statusCode() != 200) {
                        result.postValue(DataState(DataState.STATE.FETCH_FAILED, "选课查询初始化失败"))
                        return@Thread
                    }
                }
                val response = jwFormPost(token, path, form, refererPath)
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val page = ShenzhenCourseCatalogParser.parsePage(
                    response.body(),
                    source,
                    studentType,
                    selectionPoolName
                )
                if (response.statusCode() != 200 || page == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "课程数据解析失败"))
                } else {
                    result.postValue(DataState(page, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun getShenzhenCourseRecommendations(
        token: EASToken,
        term: TermItem,
        pools: List<ShenzhenSelectionPool>,
        options: ShenzhenRecommendationOptions,
        requirements: List<ShenzhenCreditRequirement> = emptyList()
    ): LiveData<DataState<ShenzhenCourseRecommendationResult>> {
        val result = MutableLiveData(
            DataState<ShenzhenCourseRecommendationResult>(DataState.STATE.NOTHING)
        )
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            try {
                val selectedResponse = requestShenzhenWebSelectedSubjects(token, term)
                if (isJwAuthenticationExpired(selectedResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val selectedPage = selectedResponse.takeIf { it.statusCode() == 200 }?.let {
                    ShenzhenCourseCatalogParser.parsePage(
                        it.body(),
                        ShenzhenCourseCatalogSource.AVAILABLE,
                        token.getStudentType()
                    )
                }
                if (selectedPage == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "已选课程读取失败"))
                    return@Thread
                }
                val selected = selectedPage.items
                val effectivePools = ShenzhenCourseCatalogParser
                    .parseSelectionPools(selectedResponse.body())
                    .ifEmpty { pools }

                val candidates = mutableListOf<ShenzhenCourseCatalogItem>()
                effectivePools.forEach { pool ->
                    val initializationForm = recommendationAvailableCourseForm(
                        token = token,
                        term = term,
                        pool = pool,
                        page = 1,
                        pageSize = 200
                    )
                    val initialization = jwFormPost(token, "/Xsxk/queryYxkc?sf_request_type=ajax", initializationForm, "/Xsxk/query/1")
                    if (isJwAuthenticationExpired(initialization)) {
                        result.postValue(
                            DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效")
                        )
                        return@Thread
                    }
                    if (initialization.statusCode() != 200) {
                        result.postValue(
                            DataState(
                                DataState.STATE.FETCH_FAILED,
                                "选课池“${pool.name}”初始化失败"
                            )
                        )
                        return@Thread
                    }
                    var pageNumber = 1
                    var hasNext: Boolean
                    do {
                        val response = jwFormPost(token, "/Xsxk/queryKxrw?sf_request_type=ajax", recommendationAvailableCourseForm(
                                token = token,
                                term = term,
                                pool = pool,
                                page = pageNumber,
                                pageSize = 200
                            ), "/Xsxk/query/1")
                        if (isJwAuthenticationExpired(response)) {
                            result.postValue(
                                DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效")
                            )
                            return@Thread
                        }
                        val page = if (response.statusCode() == 200) {
                            ShenzhenCourseCatalogParser.parsePage(
                                response.body(),
                                ShenzhenCourseCatalogSource.AVAILABLE,
                                token.getStudentType(),
                                pool.name
                            )
                        } else null
                        if (page == null) {
                            result.postValue(
                                DataState(
                                    DataState.STATE.FETCH_FAILED,
                                    "选课池“${pool.name}”读取失败"
                                )
                            )
                            return@Thread
                        }
                        candidates += page.items
                        hasNext = page.hasNextPage
                        pageNumber++
                    } while (hasNext)
                }

                val recommendation = ShenzhenCourseRecommendationEngine.recommend(
                    selected = selected,
                    candidates = candidates,
                    options = options,
                    requirements = requirements
                )
                result.postValue(DataState(recommendation, DataState.STATE.SUCCESS))
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    private fun recommendationAvailableCourseForm(
        token: EASToken,
        term: TermItem,
        pool: ShenzhenSelectionPool,
        page: Int,
        pageSize: Int
    ) = linkedMapOf(
        "cxsfmt" to "0",
        "p_pylx" to token.getStudentType(),
        "mxpylx" to token.getStudentType(),
        "p_sfgldjr" to "0",
        "p_sfredis" to "0",
        "p_sfsyxkgwc" to "0",
        "p_xktjz" to "",
        "p_chaxunxh" to "",
        "p_gjz" to "",
        "p_skjs" to "",
        "p_xn" to term.yearCode,
        "p_xq" to term.termCode,
        "p_xnxq" to term.getCode(),
        "p_xkfsdm" to pool.code,
        "p_xiaoqu" to "",
        "p_kkyx" to "",
        "p_kclb" to "",
        "p_xkxs" to "",
        "p_dyc" to "",
        "p_kkxnxq" to "",
        "p_id" to "",
        "p_sfhlctkc" to "0",
        "p_sfhllrlkc" to "0",
        "p_kxsj_xqj" to "",
        "p_kxsj_ksjc" to "",
        "p_kxsj_jsjc" to "",
        "p_kcdm_js" to "",
        "p_kcdm_cxrw" to "",
        "p_kcdm_cxrw_zckc" to "",
        "p_kc_gjz" to "",
        "p_xzcxtjz_nj" to "",
        "p_xzcxtjz_yx" to "",
        "p_xzcxtjz_zy" to "",
        "p_xzcxtjz_zyfx" to "",
        "p_xzcxtjz_bj" to "",
        "p_sfxsgwckb" to "1",
        "p_skyy" to "",
        "p_sfmxzj" to "0",
        "p_chaxunxkfsdm" to "",
        "pageNum" to page.toString(),
        "pageSize" to pageSize.toString()
    )

    fun getShenzhenCourseAttachments(
        token: EASToken,
        courseId: String,
        taskNumber: String
    ): LiveData<DataState<List<ShenzhenCourseAttachment>>> {
        val result = MutableLiveData<DataState<List<ShenzhenCourseAttachment>>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            if (courseId.isBlank() || taskNumber.isBlank()) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, "课程缺少详情标识"))
                return@Thread
            }
            try {
                val response = jwFormPost(token, "/kck/kcxxwh/xsckViewByxk?sf_request_type=ajax", mapOf(
                        "kcid" to courseId,
                        "kcsqid" to "",
                        "rwh" to taskNumber
                    ), "/Xsxk/query/1")
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val attachments = ShenzhenCourseCatalogParser.parseAttachments(
                    response.body(),
                    courseId
                )
                if (response.statusCode() != 200 || attachments == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "课程附件解析失败"))
                } else {
                    result.postValue(DataState(attachments, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun getShenzhenGradeCourses(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<List<ShenzhenGradeCourse>>> {
        val result = MutableLiveData<DataState<List<ShenzhenGradeCourse>>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            try {
                val publishedResponse = jwJsonPost(token, "/cjgl/grcjcx/grcjcx", JSONObject()
                        .put("xn", term.yearCode)
                        .put("xq", term.termCode)
                        .put("kcmc", JSONObject.NULL)
                        .put("cxbj", "-1")
                        .put("pylx", token.getStudentType())
                        .put("current", 1)
                        .put("pageSize", 1000)
                        .put("xscjlb", JSONObject.NULL)
                        .put("sffx", JSONObject.NULL)
                        .toString(), "/cjgl/grcjcx")
                val selectedResponse = requestShenzhenWebSelectedSubjects(token, term)
                if (isJwAuthenticationExpired(publishedResponse) ||
                    isJwAuthenticationExpired(selectedResponse)
                ) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                if (publishedResponse.statusCode() != 200 || selectedResponse.statusCode() != 200) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "课程成绩列表请求失败"))
                    return@Thread
                }

                var earlyBody: String? = null
                val identityResponse = jwFormPost(token, "/UserManager/queryxsxx", emptyMap(), "/cjgl/cjzhtjcx/cjcx")
                if (isJwAuthenticationExpired(identityResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val identity = identityResponse.takeIf { it.statusCode() == 200 }
                    ?.let { ShenzhenCreditProgressParser.parseIdentity(it.body()) }
                val studentNumber = identity?.studentNumber.orEmpty()
                    .ifBlank { token.stuId.orEmpty() }
                    .ifBlank { token.username.orEmpty() }
                var studentRecordId = identity?.studentRecordId.orEmpty()
                if (studentNumber.isNotBlank()) {
                    if (studentRecordId.isBlank()) {
                        val studentResponse = jwJsonPost(token, "/cjgl/cjzhtjcx/cjcx/getXs", JSONObject().put("xjidorxh", studentNumber).toString(), "/cjgl/cjzhtjcx/cjcx")
                        if (isJwAuthenticationExpired(studentResponse)) {
                            result.postValue(
                                DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效")
                            )
                            return@Thread
                        }
                        studentRecordId = if (studentResponse.statusCode() == 200) {
                            ShenzhenGradeParser.parseStudentRecordId(studentResponse.body())
                                .orEmpty()
                        } else ""
                    }
                    studentRecordId = studentRecordId.ifBlank { token.id.orEmpty() }
                    if (studentRecordId.isNotBlank()) {
                        token.stuId = studentNumber
                        token.id = studentRecordId
                        identity?.studentType?.takeIf { it == "1" || it == "2" }?.let {
                            token.stutype = if (it == "1") {
                                EASToken.TYPE.UNDERGRAD
                            } else EASToken.TYPE.GRAD
                        }
                        onTokenRefreshed?.invoke(token)
                        val earlyResponse = jwJsonPost(token, "/cjgl/cjzhtjcx/cjcx/queryZxcjPage", JSONObject()
                                .put("current", 1)
                                .put("pageSize", 200)
                                .put("xh", studentNumber)
                                .put("xjid", studentRecordId)
                                .put("pylx", token.getStudentType())
                                .toString(), "/cjgl/cjzhtjcx/cjcx")
                        if (earlyResponse.statusCode() == 200 &&
                            !isJwAuthenticationExpired(earlyResponse)
                        ) {
                            earlyBody = earlyResponse.body()
                        }
                    }
                }
                LogUtils.w(
                    "getShenzhenGradeCourses: identity=${identity != null}, " +
                        "studentNumber=${studentNumber.isNotBlank()}, " +
                        "studentRecordId=${studentRecordId.isNotBlank()}, " +
                        "personalScores=${earlyBody != null}"
                )
                val earlyDiagnostics = ShenzhenGradeParser.earlyScoreDiagnostics(earlyBody)
                LogUtils.d(
                    "getShenzhenGradeCourses: personalScoreRows=${earlyDiagnostics.first}, " +
                        "numericPersonalScores=${earlyDiagnostics.second}"
                )

                val courses = ShenzhenGradeParser.parseCourses(
                    publishedBody = publishedResponse.body(),
                    selectedBody = selectedResponse.body(),
                    earlyBody = earlyBody,
                    term = term
                )
                LogUtils.d(
                    "getShenzhenGradeCourses: courses=${courses?.size ?: 0}, " +
                        "earlyScores=${courses?.count {
                            it.status == ShenzhenGradeStatus.EARLY && it.myScore != null
                        } ?: 0}"
                )
                if (courses == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "课程成绩列表解析失败"))
                } else {
                    result.postValue(DataState(courses, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun getShenzhenGradeAnalysis(
        token: EASToken,
        course: ShenzhenGradeCourse
    ): LiveData<DataState<ShenzhenGradeAnalysis>> {
        val result = MutableLiveData<DataState<ShenzhenGradeAnalysis>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            if (course.taskId.isBlank()) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, "该课程缺少教学任务标识"))
                return@Thread
            }
            if (course.recordId.isBlank()) {
                result.postValue(
                    DataState(
                        DataState.STATE.FETCH_FAILED,
                        "该课程尚未提供可查询的个人成绩分项"
                    )
                )
                return@Thread
            }
            try {
                val response = jwFormPost(token, "/cjgl/grcjcx/seeFx?sf_request_type=ajax", mapOf(
                        "rwid" to course.taskId,
                        "cjid" to course.recordId
                    ), "/cjgl/grcjcx/go/1")
                val diagnostics = ShenzhenGradeParser.analysisResponseDiagnostics(response.body())
                LogUtils.d(
                    "getShenzhenGradeAnalysis: HTTP ${response.statusCode()}, " +
                        "bodyLength=${response.body().length}, ${diagnostics.logSummary()}"
                )
                val permissionRestricted = isGradeAnalysisPermissionRestricted(
                    response.statusCode(),
                    diagnostics
                )
                if (!permissionRestricted && isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val analysis = if (response.statusCode() == 200) {
                    ShenzhenGradeParser.analyze(
                        course,
                        response.body(),
                        ShenzhenGradeAnalysisScope.PERSONAL
                    )
                } else null
                if (analysis == null) {
                    result.postValue(
                        DataState(
                            DataState.STATE.FETCH_FAILED,
                            gradeAnalysisFailureMessage(
                                response.statusCode(),
                                diagnostics,
                                permissionRestricted
                            )
                        )
                    )
                } else {
                    result.postValue(DataState(analysis, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun getShenzhenHistoricalTeacherFailureRates(
        token: EASToken,
        referenceTerm: TermItem,
        studentType: String,
        referenceCourse: ShenzhenCourseCatalogItem,
        yearsBack: Int = 2
    ): LiveData<DataState<ShenzhenHistoricalFailureReport>> {
        val result = MutableLiveData<DataState<ShenzhenHistoricalFailureReport>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            // 旧实现依赖 seeFx 在不传 cjid 时泄露整个教学班成绩。2026-07 后端已修复：
            // 接口必须携带当前学生自己的成绩记录 ID，且只返回该学生的分项。全校课表
            // 只提供教学任务 ID，不提供也不应尝试推测其他学生的 cjid，因此教师/班型统计
            // 已没有合法可靠的数据源。
            if (!supportsClassWideGradeAnalysis()) {
                result.postValue(
                    DataState(
                        DataState.STATE.FETCH_FAILED,
                        "新版教务已将成绩分项限制为本人记录，无法再生成其他教学班或教师的成绩统计"
                    )
                )
                return@Thread
            }

            val targetTerm = ShenzhenHistoricalGradeAnalyzer.termYearsBefore(referenceTerm, yearsBack)
            if (targetTerm == null) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, "无法识别当前课程学年"))
                return@Thread
            }
            try {
                val keyword = referenceCourse.courseName.ifBlank { referenceCourse.courseCode }
                val matchedCourses = mutableListOf<ShenzhenCourseCatalogItem>()
                var pageNumber = 1
                var hasNextPage: Boolean
                do {
                    val response = jwFormPost(token, "/Xsxktz/queryRwxxcxList?sf_request_type=ajax", shenzhenSchoolCourseForm(
                            term = targetTerm,
                            studentType = studentType,
                            keyword = keyword,
                            page = pageNumber,
                            pageSize = 200
                        ), "/Xsxktz/queryRwxxcx")
                    if (isJwAuthenticationExpired(response)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    val page = if (response.statusCode() == 200) {
                        ShenzhenCourseCatalogParser.parsePage(
                            response.body(),
                            ShenzhenCourseCatalogSource.SCHOOL,
                            studentType
                        )
                    } else null
                    if (page == null) {
                        result.postValue(DataState(DataState.STATE.FETCH_FAILED, "历史全校课表查询失败"))
                        return@Thread
                    }
                    matchedCourses += page.items.filter {
                        ShenzhenHistoricalGradeAnalyzer.matches(referenceCourse, it)
                    }
                    hasNextPage = page.hasNextPage
                    pageNumber++
                } while (hasNextPage)

                val distinctMatches = matchedCourses.distinctBy {
                    it.taskId.ifBlank { it.id }
                }
                val uniqueCourses = distinctMatches
                    .filter { it.taskId.isNotBlank() }
                val classStats = mutableListOf<ShenzhenHistoricalClassStats>()
                var skipped = distinctMatches.size - uniqueCourses.size
                uniqueCourses.forEach { course ->
                    val response = jwFormPost(token, "/cjgl/grcjcx/seeFx", mapOf("rwid" to course.taskId), "/cjgl/grcjcx")
                    val diagnostics = ShenzhenGradeParser.analysisResponseDiagnostics(response.body())
                    LogUtils.w(
                        "getShenzhenHistoricalTeacherFailureRates: HTTP ${response.statusCode()}, " +
                            "bodyLength=${response.body().length}, ${diagnostics.logSummary()}"
                    )
                    val permissionRestricted = isGradeAnalysisPermissionRestricted(
                        response.statusCode(),
                        diagnostics
                    )
                    if (permissionRestricted) {
                        result.postValue(
                            DataState(
                                DataState.STATE.FETCH_FAILED,
                                gradeAnalysisFailureMessage(
                                    response.statusCode(),
                                    diagnostics,
                                    permissionRestricted = true
                                )
                            )
                        )
                        return@Thread
                    }
                    if (isJwAuthenticationExpired(response)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    val analysis = if (response.statusCode() == 200) {
                        ShenzhenGradeParser.analyze(
                            ShenzhenGradeCourse(
                                taskId = course.taskId,
                                taskNumber = course.taskNumber,
                                courseCode = course.courseCode,
                                courseName = course.courseName,
                                termCode = targetTerm.getCode(),
                                teacher = course.teacher
                            ),
                            response.body()
                        )
                    } else null
                    if (analysis == null) {
                        skipped++
                    } else {
                        classStats += ShenzhenHistoricalClassStats(
                            teacher = course.teacher,
                            scores = analysis.students.map { it.total },
                            excludedIncompleteStudentCount =
                                analysis.excludedIncompleteStudentCount
                        )
                    }
                }

                if (uniqueCourses.isNotEmpty() && classStats.isEmpty()) {
                    result.postValue(
                        DataState(
                            DataState.STATE.FETCH_FAILED,
                            "教务未返回这些教学班的可用成绩汇总，可能尚未录入或接口已调整"
                        )
                    )
                    return@Thread
                }

                val report = ShenzhenHistoricalFailureReport(
                    courseName = referenceCourse.courseName,
                    courseCode = referenceCourse.courseCode,
                    targetTerm = targetTerm,
                    matchedClassCount = distinctMatches.size,
                    analyzedClassCount = classStats.size,
                    skippedClassCount = skipped,
                    teacherRates = ShenzhenHistoricalGradeAnalyzer.aggregate(classStats)
                )
                result.postValue(DataState(report, DataState.STATE.SUCCESS))
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    private fun supportsClassWideGradeAnalysis(): Boolean = false

    private fun isGradeAnalysisPermissionRestricted(
        statusCode: Int,
        diagnostics: ShenzhenGradeParser.AnalysisResponseDiagnostics
    ): Boolean = statusCode == 403 || diagnostics.serverCode.trim() == "403" ||
        diagnostics.serverMessage.contains("无权限") ||
        diagnostics.serverMessage.contains("权限不足") ||
        diagnostics.serverMessage.contains("forbidden", ignoreCase = true)

    private fun gradeAnalysisFailureMessage(
        statusCode: Int,
        diagnostics: ShenzhenGradeParser.AnalysisResponseDiagnostics,
        permissionRestricted: Boolean
    ): String = when {
        permissionRestricted -> "教务拒绝访问该课程的个人成绩分项"
        statusCode == 404 -> "教务成绩分析接口已调整，当前版本暂不兼容"
        statusCode !in 200..299 -> "个人成绩分项请求失败（HTTP $statusCode）"
        diagnostics.structure == "html" -> "教务返回了页面而不是成绩数据，接口可能已经调整"
        diagnostics.rowCount == 0 -> "教务未返回该课程的个人分项，可能尚未录入"
        diagnostics.serverMessage.isNotBlank() -> diagnostics.serverMessage
        else -> "个人成绩分项响应结构已变化，当前版本暂时无法解析"
    }

    fun getShenzhenTrainingPlans(
        token: EASToken
    ): LiveData<DataState<List<ShenzhenTrainingPlan>>> {
        val result = MutableLiveData<DataState<List<ShenzhenTrainingPlan>>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            try {
                val identityResponse = jwFormPost(token, "/UserManager/queryxsxx", emptyMap(), "/authentication/main")
                if (isJwAuthenticationExpired(identityResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val identity = if (identityResponse.statusCode() == 200) {
                    ShenzhenTrainingPlanParser.parseIdentity(identityResponse.body())
                } else null
                val major = identity?.major.orEmpty().ifBlank { token.major.orEmpty() }
                val grade = identity?.grade.orEmpty().ifBlank { token.grade.orEmpty() }
                    .let { Regex("20\\d{2}").find(it)?.value.orEmpty() }
                val studentType = identity?.studentType.orEmpty().ifBlank { token.getStudentType() }
                if (major.isBlank()) {
                    result.postValue(
                        DataState(
                            DataState.STATE.FETCH_FAILED,
                            "教务系统未返回你的专业信息，暂时无法匹配个人培养方案"
                        )
                    )
                    return@Thread
                }
                if (studentType == "1" && grade.isBlank()) {
                    result.postValue(
                        DataState(
                            DataState.STATE.FETCH_FAILED,
                            "教务系统未返回你的入学年级，暂时无法查询本科培养方案"
                        )
                    )
                    return@Thread
                }
                token.major = major
                token.grade = grade.ifBlank { token.grade }
                token.stutype = if (studentType == "1") EASToken.TYPE.UNDERGRAD else EASToken.TYPE.GRAD
                onTokenRefreshed?.invoke(token)

                val level = if (studentType == "1") {
                    ShenzhenTrainingPlanLevel.UNDERGRADUATE
                } else ShenzhenTrainingPlanLevel.POSTGRADUATE
                val baseForm = linkedMapOf(
                    "sf_request_type" to "ajax",
                    "key" to "",
                    "xkdm" to "",
                    "yxdm" to "",
                    "zydm" to "",
                    "zyfxdm" to "",
                    "bbh" to if (level == ShenzhenTrainingPlanLevel.POSTGRADUATE) "202603" else "",
                    "ywdm" to "",
                    "falx" to if (level == ShenzhenTrainingPlanLevel.UNDERGRADUATE) "3" else "2",
                    "njdm" to if (level == ShenzhenTrainingPlanLevel.UNDERGRADUATE) grade else "",
                    "cxby" to "",
                    "pylb" to if (level == ShenzhenTrainingPlanLevel.UNDERGRADUATE) "1" else "2",
                    "order1" to "",
                    "order2" to "",
                    "falxdm" to "",
                    "kzsjqx" to "0",
                    "py_xssfcxzj_zx" to "1",
                    "py_xssfcxzj_fx" to "1",
                    "sfdl" to "",
                    "pageNum" to "1",
                    "pageSize" to "500"
                )
                val plans = mutableListOf<ShenzhenTrainingPlan>()
                var page = 1
                var pages = 1
                do {
                    val response = jwFormPost(token, "/faxq/query?sf_request_type=ajax", baseForm + ("pageNum" to page.toString()), "/faxq/query")
                    if (isJwAuthenticationExpired(response)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    if (response.statusCode() != 200) {
                        result.postValue(DataState(DataState.STATE.FETCH_FAILED, "培养方案列表请求失败"))
                        return@Thread
                    }
                    plans += ShenzhenTrainingPlanParser.parsePlans(response.body(), level).orEmpty()
                    if (page == 1) pages = ShenzhenTrainingPlanParser.parsePageCount(response.body())
                    page++
                } while (page <= pages)

                val matched = ShenzhenTrainingPlanParser.matchPersonalPlans(plans, major)
                if (matched.isEmpty()) {
                    result.postValue(
                        DataState(
                            DataState.STATE.FETCH_FAILED,
                            "未找到与“$major”匹配的个人培养方案"
                        )
                    )
                } else {
                    result.postValue(DataState(matched, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun getShenzhenCreditProgress(
        token: EASToken,
        selectedTerm: TermItem? = null,
        includeDetails: Boolean = true,
        includeCourseRecords: Boolean = true,
        trackCoursesOnly: Boolean = false
    ): LiveData<DataState<ShenzhenCreditProgress>> {
        val result = MutableLiveData(
            DataState<ShenzhenCreditProgress>(DataState.STATE.NOTHING)
        )
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            try {
                val referer = "/cjgl/grcjcx/cjxqList"
                val identityResponse = jwFormPost(token, "/UserManager/queryxsxx", emptyMap(), "/authentication/main")
                if (isJwAuthenticationExpired(identityResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val parsedIdentity = identityResponse.takeIf { it.statusCode() == 200 }
                    ?.let { ShenzhenCreditProgressParser.parseIdentity(it.body()) }
                val studentNumber = parsedIdentity?.studentNumber.orEmpty()
                    .ifBlank { token.stuId.orEmpty() }
                    .ifBlank { token.username.orEmpty() }
                val studentType = parsedIdentity?.studentType.orEmpty()
                    .ifBlank { token.getStudentType() }
                val grade = parsedIdentity?.grade.orEmpty()
                    .ifBlank { Regex("(?:19|20)\\d{2}").find(token.grade.orEmpty())?.value.orEmpty() }
                var recordId = parsedIdentity?.studentRecordId.orEmpty()
                if (studentNumber.isBlank() || grade.isBlank()) {
                    result.postValue(
                        DataState(DataState.STATE.FETCH_FAILED, "教务系统未返回学号或入学年级")
                    )
                    return@Thread
                }
                if (recordId.isBlank()) {
                    val lookupResponse = jwJsonPost(token, "/cjgl/cjzhtjcx/cjcx/getXs", JSONObject().put("xjidorxh", studentNumber).toString(), referer)
                    if (isJwAuthenticationExpired(lookupResponse)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    recordId = lookupResponse.takeIf { it.statusCode() == 200 }
                        ?.let { ShenzhenGradeParser.parseStudentRecordId(it.body()) }.orEmpty()
                }
                if (recordId.isBlank()) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "教务系统未返回学籍标识"))
                    return@Thread
                }

                token.stuId = studentNumber
                token.id = recordId
                token.grade = grade
                token.stutype = if (studentType == "1") EASToken.TYPE.UNDERGRAD else EASToken.TYPE.GRAD
                onTokenRefreshed?.invoke(token)

                val termResponse = jwFormPost(token, "/cjgl/cjzhtjcx/cjcx/queryqxnxq?sf_request_type=ajax", emptyMap(), referer)
                if (isJwAuthenticationExpired(termResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val currentTerm = selectedTerm?.getCode()
                    ?: termResponse.takeIf { it.statusCode() == 200 }
                        ?.let { ShenzhenCreditProgressParser.parseCurrentTerm(it.body()) }
                if (currentTerm.isNullOrBlank()) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "当前成绩学期读取失败"))
                    return@Thread
                }
                val courseRecordTotal = ShenzhenCreditProgressParser.parseCourseRecordTotal(
                    termResponse.body()
                )

                val planResponse = jwFormPost(token, "/cjgl/cjzhtjcx/cjcx/queryfahHljdx?sf_request_type=ajax", mapOf(
                        "xh" to studentNumber,
                        "pylx" to studentType,
                        "falxdm" to "1"
                    ), referer)
                if (isJwAuthenticationExpired(planResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                var plan = planResponse.takeIf { it.statusCode() == 200 }
                    ?.let { ShenzhenCreditProgressParser.parsePlanContext(it.body()) }
                if (plan == null) {
                    val fallbackPlanResponse = jwFormPost(token, "/cjgl/cjzhtjcx/cjcx/queryfah?sf_request_type=ajax", mapOf(
                            "xh" to studentNumber,
                            "pylx" to studentType,
                            "falxdm" to "1"
                        ), referer)
                    if (isJwAuthenticationExpired(fallbackPlanResponse)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    plan = fallbackPlanResponse.takeIf { it.statusCode() == 200 }
                        ?.let { ShenzhenCreditProgressParser.parsePlanContext(it.body()) }
                }
                if (plan == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "个人培养方案读取失败"))
                    return@Thread
                }

                val commonBody = JSONObject()
                    .put("xjid", recordId)
                    .put("zyfxdm", JSONObject.NULL)
                    .put("pylx", studentType)
                    .put("fah", plan.planId)
                val summaryResponse = jwJsonPost(token, "/cjgl/cjzhtjcx/cjcx/queryBxkqk?sf_request_type=ajax", JSONObject(commonBody.toString())
                        .put("xh", studentNumber)
                        .put("nj", grade)
                        .put("jzxnxq", currentTerm)
                        .toString(), referer)
                if (isJwAuthenticationExpired(summaryResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val summaryUnavailable = summaryResponse.statusCode() == 404
                if (summaryResponse.statusCode() != 200 && !summaryUnavailable) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "培养方案完成度请求失败"))
                    return@Thread
                }

                val emptyListBody = """{"content":[]}"""
                val summaryBody = if (summaryUnavailable) "" else summaryResponse.body()
                val initialProgress = ShenzhenCreditProgressParser.parseProgress(
                    summaryBody = summaryBody,
                    categoriesBody = "",
                    groupsBody = emptyListBody,
                    courseRecordBodies = emptyList(),
                    currentTerm = currentTerm,
                    allowMissingSummary = summaryUnavailable
                )
                if (initialProgress == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "培养方案完成度解析失败"))
                    return@Thread
                }
                if (includeCourseRecords || !includeDetails) {
                    result.postValue(DataState(initialProgress, DataState.STATE.SUCCESS))
                }
                LogUtils.d(
                    "getShenzhenCreditProgress: summary categories=${initialProgress.categories.size}"
                )
                if (!includeDetails && initialProgress.categories.isNotEmpty()) return@Thread

                var categoryBody = ""
                if (initialProgress.categories.isEmpty()) {
                    val categoryResponse = runCatching {
                        jwJsonPost(token, "/cjgl/cjzhtjcx/cjcx/queryXflbyq1?sf_request_type=ajax", JSONObject(commonBody.toString())
                                .put("current", 1)
                                .put("pageSize", 200)
                                .toString(), referer)
                    }.onFailure {
                        LogUtils.w("credit progress: category details unavailable: ${it.message}")
                    }.getOrNull()
                    categoryBody = categoryResponse
                        ?.takeIf { it.statusCode() == 200 && !isJwAuthenticationExpired(it) }
                        ?.body()
                        .orEmpty()
                }
                if (!includeDetails) {
                    val progressWithCategories = ShenzhenCreditProgressParser.parseProgress(
                        summaryBody = summaryBody,
                        categoriesBody = categoryBody,
                        groupsBody = emptyListBody,
                        courseRecordBodies = emptyList(),
                        currentTerm = currentTerm
                    ) ?: initialProgress
                    result.postValue(DataState(progressWithCategories, DataState.STATE.SUCCESS))
                    return@Thread
                }

                val groupResponse = runCatching {
                    jwJsonPost(token, "/cjgl/cjzhtjcx/cjcx/queryMkyq?sf_request_type=ajax", commonBody.toString(), referer)
                }.onFailure {
                    LogUtils.w("credit progress: group details unavailable: ${it.message}")
                }.getOrNull()
                val groupBody = groupResponse
                    ?.takeIf { it.statusCode() == 200 && !isJwAuthenticationExpired(it) }
                    ?.body()
                    ?: emptyListBody

                val progressWithGroups = ShenzhenCreditProgressParser.parseProgress(
                    summaryBody = summaryBody,
                    categoriesBody = categoryBody,
                    groupsBody = groupBody,
                    courseRecordBodies = emptyList(),
                    currentTerm = currentTerm
                ) ?: initialProgress
                if (includeCourseRecords) {
                    result.postValue(DataState(progressWithGroups, DataState.STATE.SUCCESS))
                }

                val parsedGroups = progressWithGroups.groups
                val parentIds = parsedGroups.mapTo(hashSetOf()) { it.parentId }
                val courseGroups = parsedGroups.filter { group ->
                    if (trackCoursesOnly) {
                        group.name.contains("轨道")
                    } else {
                        group.depth > 0 && group.id !in parentIds
                    }
                }
                val groupCourseBodies = linkedMapOf<String, String>()
                courseGroups.forEach { group ->
                    val courseResponse = runCatching {
                        jwJsonPost(token, "/cjgl/cjzhtjcx/cjcx/queryFaKzkc?sf_request_type=ajax", JSONObject()
                                .put("current", 1)
                                .put("pageSize", 200)
                                .put("xn", JSONObject.NULL)
                                .put("xq", JSONObject.NULL)
                                .put("yxdm", JSONObject.NULL)
                                .put("kzid", group.id)
                                .put("kzlx", "0")
                                .put("kcxzdm", JSONObject.NULL)
                                .put("kclbdm", JSONObject.NULL)
                                .put("kzmc", group.name)
                                .put("kcmc", "")
                                .put("orderby", JSONObject.NULL)
                                .put("sxjx", JSONObject.NULL)
                                .put("xjid", recordId)
                                .put("nj", grade)
                                .put("pylx", studentType)
                                .put("fah", plan.planId)
                                .put("zyfxdm", JSONObject.NULL)
                                .toString(), referer)
                    }.onFailure {
                        LogUtils.w(
                            "credit progress: courses unavailable for group ${group.id}: ${it.message}"
                        )
                    }.getOrNull()
                    if (courseResponse?.statusCode() == 200 &&
                        !isJwAuthenticationExpired(courseResponse)
                    ) {
                        groupCourseBodies[group.id] = courseResponse.body()
                    }
                }

                if (!includeCourseRecords) {
                    val progressWithCourses = ShenzhenCreditProgressParser.parseProgress(
                        summaryBody = summaryBody,
                        categoriesBody = categoryBody,
                        groupsBody = groupBody,
                        groupCourseBodies = groupCourseBodies,
                        courseRecordBodies = emptyList(),
                        currentTerm = currentTerm
                    ) ?: progressWithGroups
                    result.postValue(DataState(progressWithCourses, DataState.STATE.SUCCESS))
                    LogUtils.d(
                        "getShenzhenCreditProgress: groups=${progressWithCourses.groups.size}, " +
                            "groupCourseResponses=${groupCourseBodies.size}, courseRecords=skipped"
                    )
                    return@Thread
                }

                val courseRecordBodies = mutableListOf<String>()
                var page = 1
                var pages = 1
                do {
                    val earnedResponse = runCatching {
                        jwFormPost(token, "/cjgl/grcjcx/dyxwList?sf_request_type=ajax", mapOf(
                                "pageNum" to page.toString(),
                                "pageSize" to "200",
                                "total" to courseRecordTotal.toString(),
                                "xjid" to recordId,
                                "sfgld" to "1",
                                "pxzd" to "",
                                "pxfx" to "",
                                "xn" to "",
                                "xq" to "",
                                "kcxz" to "",
                                "kclb" to "",
                                "key" to "",
                                "pylx" to studentType,
                                "sffx" to "",
                                "sfcxfxcj" to "0",
                                "sfsjqx" to "0"
                            ), referer)
                    }.onFailure {
                        LogUtils.w("credit progress: earned course page $page unavailable: ${it.message}")
                    }.getOrNull()
                    if (earnedResponse == null || earnedResponse.statusCode() != 200 ||
                        isJwAuthenticationExpired(earnedResponse)
                    ) {
                        break
                    }
                    courseRecordBodies += earnedResponse.body()
                    if (page == 1) {
                        pages = ShenzhenCreditProgressParser.parseCourseRecordPageCount(
                            earnedResponse.body()
                        )
                    }
                    page++
                } while (page <= pages)

                val progress = ShenzhenCreditProgressParser.parseProgress(
                    summaryBody = summaryBody,
                    categoriesBody = categoryBody,
                    groupsBody = groupBody,
                    groupCourseBodies = groupCourseBodies,
                    courseRecordBodies = courseRecordBodies,
                    currentTerm = currentTerm
                )
                val finalProgress = progress ?: progressWithGroups
                result.postValue(DataState(finalProgress, DataState.STATE.SUCCESS))
                LogUtils.d(
                    "getShenzhenCreditProgress: groups=${finalProgress.groups.size}, " +
                        "groupCourseResponses=${groupCourseBodies.size}, " +
                        "courseRecords=${finalProgress.courseRecords.size}"
                )
            } catch (error: Exception) {
                LogUtils.e("getShenzhenCreditProgress failed", error)
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun getShenzhenTrainingPlanCourses(
        token: EASToken,
        plan: ShenzhenTrainingPlan
    ): LiveData<DataState<ShenzhenTrainingPlanDetail>> {
        val result = MutableLiveData<DataState<ShenzhenTrainingPlanDetail>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            try {
                val groups = if (plan.level == ShenzhenTrainingPlanLevel.POSTGRADUATE) {
                    val response = jwFormPost(token, "/Zdxpyfakz/queryKzTree?sf_request_type=ajax", mapOf(
                            "fah" to plan.id,
                            "bgid" to plan.changeId,
                            "pylb" to "2",
                            "sfcx" to ""
                        ), "/Zdxpyfakz/query")
                    if (isJwAuthenticationExpired(response)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    if (response.statusCode() != 200) {
                        result.postValue(DataState(DataState.STATE.FETCH_FAILED, "培养方案课组请求失败"))
                        return@Thread
                    }
                    ShenzhenTrainingPlanParser.parseGroups(response.body()).orEmpty()
                } else emptyList()

                val path: String
                val referer: String
                val baseForm: Map<String, String>
                if (plan.level == ShenzhenTrainingPlanLevel.UNDERGRADUATE) {
                    path = "/Njpyfakc/queryList?sf_request_type=ajax"
                    referer = "/Njpyfakc/query"
                    baseForm = linkedMapOf(
                        "bglx" to "",
                        "multiple" to "false",
                        "sfcx" to "",
                        "pylx" to "1",
                        "pylb" to "1",
                        "fah" to plan.id,
                        "bgid" to plan.changeId,
                        "kcmc" to "",
                        "yxdm" to "",
                        "xqdm" to "",
                        "kclbdm" to "",
                        "kcxzdm" to "",
                        "order1" to "",
                        "order2" to "",
                        "pageNum" to "1",
                        "pageSize" to "500"
                    )
                } else {
                    path = "/Zdxpyfakz/queryFaKzkc?sf_request_type=ajax"
                    referer = "/Zdxpyfakz/query"
                    baseForm = linkedMapOf(
                        "sfcx" to "",
                        "pylx" to "2",
                        "pylb" to "2",
                        "fah" to plan.id,
                        "bgid" to plan.changeId,
                        "kzid" to "",
                        "kcmc" to "",
                        "zyfx" to plan.majorCode,
                        "yxdm" to "",
                        "xqdm" to "",
                        "order1" to "",
                        "order2" to "",
                        "pageNum" to "1",
                        "pageSize" to "500"
                    )
                }

                val courses = mutableListOf<ShenzhenTrainingPlanCourse>()
                var page = 1
                var pages = 1
                do {
                    val response = jwFormPost(token, path, baseForm + ("pageNum" to page.toString()), referer)
                    if (isJwAuthenticationExpired(response)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    if (response.statusCode() != 200) {
                        result.postValue(DataState(DataState.STATE.FETCH_FAILED, "培养方案课程请求失败"))
                        return@Thread
                    }
                    courses += ShenzhenTrainingPlanParser.parseCourses(response.body()).orEmpty()
                    if (page == 1) pages = ShenzhenTrainingPlanParser.parsePageCount(response.body())
                    page++
                } while (page <= pages)

                if (courses.isEmpty()) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "该培养方案暂未返回课程"))
                } else {
                    result.postValue(
                        DataState(
                            ShenzhenTrainingPlanParser.combine(
                                plan,
                                groups,
                                courses.distinctBy {
                                    listOf(it.groupId, it.courseCode, it.courseName, it.recommendedTerm)
                                }
                            ),
                            DataState.STATE.SUCCESS
                        )
                    )
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }
}