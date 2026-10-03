package cn.limpu.hita.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import cn.limpu.hita.data.model.eas.*
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.source.dao.ScoreCacheDao
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.web.eas.EASWebSource
import cn.limpu.hita.utils.LiveDataUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.limpu.component.data.DataState
import java.lang.reflect.Type
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class EASShenzhenRepository(
    private val shenzhenService: EASWebSource,
    private val easPreferenceSource: EasPreferenceSource,
    private val scoreCacheDao: ScoreCacheDao,
    private val gson: Gson,
    private val appContext: Context,
    private val courseSelectionExecutionTokens: CourseSelectionExecutionTokenStore,
    private val tokenStateLock: Any,
    private val authEpoch: AtomicLong,
    private val publishEasToken: (EASToken) -> Unit,
    private val nextCredentialScopeGeneration: () -> Long,
    private val scoreCacheOwnerKey: (EASToken) -> String?,
) : ShenzhenCourseSelectionGateway {
    private val shenzhenGradeCourseListType = object : TypeToken<List<ShenzhenGradeCourse>>() {}.type

    companion object {
        private const val JW_DIRECT_BASE_URL = "https://jw.hitsz.edu.cn"
        private const val JW_PROXY_BASE_URL = "https://jw-hitsz-edu-cn.hitsz.edu.cn"
        private const val SCORE_CACHE_RETENTION_MS = 365L * 24L * 60L * 60L * 1000L
    }
    override suspend fun beginExecution(
        job: CourseSelectionJob,
        owner: Any
    ) {
        withContext(Dispatchers.IO) {
            courseSelectionExecutionTokens.begin(job, owner)
        }
    }

    override suspend fun endExecution(
        job: CourseSelectionJob,
        owner: Any
    ) {
        withContext(Dispatchers.IO) {
            courseSelectionExecutionTokens.end(job.id, owner)
        }
    }

    override suspend fun submitOnce(
        job: CourseSelectionJob,
        course: CourseSelectionJobCourse
    ): CourseSelectionCourseResult = withContext(Dispatchers.IO) {
        shenzhenService.submitShenzhenCourseOnce(
            courseSelectionExecutionTokens.requireToken(job),
            job,
            course
        )
    }

    override suspend fun selectedRequestIds(job: CourseSelectionJob): Set<String> =
        withContext(Dispatchers.IO) {
            shenzhenService.getShenzhenSelectedRequestIdsOnce(
                courseSelectionExecutionTokens.requireToken(job),
                job
            )
        }

    internal fun currentCourseSelectionCredentialScopeGeneration(): Long =
        synchronized(tokenStateLock) {
            val token = easPreferenceSource.getEasToken()
            require(token.campus == EASToken.Campus.SHENZHEN) {
                "Course selection is available for Shenzhen only"
            }
            require(token.hasShenzhenWebSession()) {
                "A Shenzhen Web session is required"
            }
            if (token.sessionGeneration <= 0L) {
                token.sessionGeneration = nextCredentialScopeGeneration()
                authEpoch.set(token.sessionGeneration)
                easPreferenceSource.saveEasToken(token)
                publishEasToken(token)
            }
            token.sessionGeneration
        }
    fun isSubjectMetaSupported(campus: EASToken.Campus = easPreferenceSource.getEasToken().campus): Boolean {
        return campus == EASToken.Campus.SHENZHEN
    }

    fun hasShenzhenWebSession(): Boolean {
        return easPreferenceSource.getEasToken().hasShenzhenWebSession()
    }

    fun getShenzhenWebTerms(): LiveData<DataState<List<TermItem>>> {
        return shenzhenService.getShenzhenWebTerms(easPreferenceSource.getEasToken())
    }

    fun queryShenzhenAvailableCourses(
        term: TermItem,
        pool: ShenzhenSelectionPool,
        keyword: String,
        page: Int,
        pageSize: Int = 20
    ): LiveData<DataState<ShenzhenCourseCatalogPage>> {
        return shenzhenService.queryShenzhenAvailableCourses(
            easPreferenceSource.getEasToken(),
            term,
            pool,
            keyword,
            page,
            pageSize
        )
    }

    fun queryShenzhenSchoolCourses(
        term: TermItem,
        studentType: String,
        keyword: String,
        page: Int,
        pageSize: Int = 20
    ): LiveData<DataState<ShenzhenCourseCatalogPage>> {
        return shenzhenService.queryShenzhenSchoolCourses(
            easPreferenceSource.getEasToken(),
            term,
            studentType,
            keyword,
            page,
            pageSize
        )
    }

    fun getShenzhenCourseRecommendations(
        term: TermItem,
        pools: List<ShenzhenSelectionPool>,
        options: ShenzhenRecommendationOptions
    ): LiveData<DataState<ShenzhenCourseRecommendationResult>> {
        val token = easPreferenceSource.getEasToken()
        return shenzhenService.getShenzhenCreditProgress(
            token,
            includeDetails = false
        ).switchMap { progressState ->
            val progress = progressState.data
            val hasRecommendationRequirements = progress?.categories?.any { requirement ->
                requirement.name.contains("跨专业发展") ||
                    requirement.name.contains("文理通识")
            } == true
            if (progressState.state == DataState.STATE.SUCCESS &&
                progress != null && hasRecommendationRequirements
            ) {
                shenzhenService.getShenzhenCourseRecommendations(
                    token,
                    term,
                    pools,
                    options,
                    progress.categories
                )
            } else if (progressState.state == DataState.STATE.SUCCESS) {
                LiveDataUtils.getMutableLiveData(
                    DataState<ShenzhenCourseRecommendationResult>(
                        DataState.STATE.FETCH_FAILED,
                        "教务系统未返回跨专业或文理通识学分要求"
                    )
                )
            } else {
                LiveDataUtils.getMutableLiveData(
                    DataState<ShenzhenCourseRecommendationResult>(
                        progressState.state,
                        progressState.message
                    )
                )
            }
        }
    }

    fun getShenzhenSelectedCourses(
        term: TermItem
    ): LiveData<DataState<List<ShenzhenCourseCatalogItem>>> =
        shenzhenService.getShenzhenSelectedCourses(easPreferenceSource.getEasToken(), term)

    fun getShenzhenCoursePlanningStartDate(term: TermItem): LiveData<DataState<Calendar>> =
        shenzhenService.getShenzhenCoursePlanningStartDate(
            easPreferenceSource.getEasToken(),
            term
        )

    fun getShenzhenCoursePlanningScheduleStructure(
        term: TermItem
    ): LiveData<DataState<MutableList<TimePeriodInDay>>> =
        shenzhenService.getShenzhenCoursePlanningScheduleStructure(
            easPreferenceSource.getEasToken(),
            term
        )

    fun getShenzhenRecommendationTracks(): LiveData<DataState<List<ShenzhenRecommendationTrack>>> {
        return shenzhenService.getShenzhenCreditProgress(
            easPreferenceSource.getEasToken(),
            includeDetails = true,
            includeCourseRecords = false,
            trackCoursesOnly = true
        ).map { state ->
            val tracks = state.data?.groups.orEmpty()
                .filter { group -> group.name.contains("轨道") && group.courses.isNotEmpty() }
                .map { group ->
                    ShenzhenRecommendationTrack(
                        id = group.id,
                        name = group.name,
                        courseCodes = group.courses.asSequence()
                            .map { it.courseCode.trim().uppercase(Locale.ROOT) }
                            .filter { it.isNotBlank() }
                            .toSet(),
                        compulsoryCourseCodes = group.courses.asSequence()
                            .filter { it.courseNature.contains("必修") }
                            .map { it.courseCode.trim().uppercase(Locale.ROOT) }
                            .filter { it.isNotBlank() }
                            .toSet()
                    )
                }
            DataState(tracks, state.state).apply { message = state.message }
        }
    }

    fun getShenzhenCourseAttachments(
        course: ShenzhenCourseCatalogItem
    ): LiveData<DataState<List<ShenzhenCourseAttachment>>> {
        return shenzhenService.getShenzhenCourseAttachments(
            easPreferenceSource.getEasToken(),
            course.courseId,
            course.taskNumber
        )
    }

    fun getShenzhenGradeCourses(
        term: TermItem
    ): LiveData<DataState<List<ShenzhenGradeCourse>>> {
        val token = easPreferenceSource.getEasToken()
        val ownerKey = scoreCacheOwnerKey(token)
            ?: return shenzhenService.getShenzhenGradeCourses(token, term)
        return getCachedScoreDetail(
            ownerKey = ownerKey,
            cacheKey = "grade-courses:${term.id}",
            payloadType = shenzhenGradeCourseListType,
            canRefreshRemotely = token.isLogin(),
            remote = { shenzhenService.getShenzhenGradeCourses(token, term) }
        )
    }

    fun getShenzhenGradeAnalysis(
        course: ShenzhenGradeCourse
    ): LiveData<DataState<ShenzhenGradeAnalysis>> {
        val token = easPreferenceSource.getEasToken()
        val ownerKey = scoreCacheOwnerKey(token)
            ?: return shenzhenService.getShenzhenGradeAnalysis(token, course)
        return getCachedScoreDetail(
            ownerKey = ownerKey,
            cacheKey = gradeAnalysisCacheKey(course),
            payloadType = ShenzhenGradeAnalysis::class.java,
            canRefreshRemotely = token.isLogin(),
            remote = { shenzhenService.getShenzhenGradeAnalysis(token, course) }
        )
    }

    private fun gradeAnalysisCacheKey(course: ShenzhenGradeCourse): String {
        return listOf(
            "grade-analysis",
            course.termCode,
            course.taskId,
            course.courseCode,
        ).joinToString(":")
    }

    private fun <T : Any> getCachedScoreDetail(
        ownerKey: String,
        cacheKey: String,
        payloadType: Type,
        canRefreshRemotely: Boolean,
        remote: () -> LiveData<DataState<T>>,
    ): LiveData<DataState<T>> {
        val result = MediatorLiveData<DataState<T>>()
        val hasCachedResult = AtomicBoolean(false)
        thread(name = "score-detail-cache-load") {
            scoreCacheDao.getDetailSync(ownerKey, cacheKey)
                ?.payloadJson
                ?.let { payload -> runCatching { gson.fromJson<T>(payload, payloadType) }.getOrNull() }
                ?.let { cached ->
                    hasCachedResult.set(true)
                    result.postValue(DataState(cached, DataState.STATE.SUCCESS).setFromCache(true))
                }

            if (!canRefreshRemotely) {
                if (!hasCachedResult.get()) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                }
                return@thread
            }

            Handler(Looper.getMainLooper()).post {
                val remoteSource = remote()
                result.addSource(remoteSource) { state ->
                    if (state.state == DataState.STATE.SUCCESS && state.data != null) {
                        result.value = state
                        thread(name = "score-detail-cache-save") {
                            saveScoreDetailCache(ownerKey, cacheKey, state.data!!)
                        }
                    } else if (!hasCachedResult.get()) {
                        result.value = state
                    }
                }
            }
        }
        return result
    }

    private fun saveScoreDetailCache(ownerKey: String, cacheKey: String, payload: Any) {
        val cachedAt = System.currentTimeMillis()
        scoreCacheDao.saveDetailSync(
            ScoreDetailCacheEntity(
                ownerKey = ownerKey,
                cacheKey = cacheKey,
                payloadJson = gson.toJson(payload),
                cachedAt = cachedAt,
            )
        )
        scoreCacheDao.deleteOldDetailsSync(cachedAt - SCORE_CACHE_RETENTION_MS)
    }

    fun getShenzhenHistoricalTeacherFailureRates(
        referenceTerm: TermItem,
        studentType: String,
        referenceCourse: ShenzhenCourseCatalogItem,
        yearsBack: Int = 2
    ): LiveData<DataState<ShenzhenHistoricalFailureReport>> {
        return shenzhenService.getShenzhenHistoricalTeacherFailureRates(
            easPreferenceSource.getEasToken(),
            referenceTerm,
            studentType,
            referenceCourse,
            yearsBack
        )
    }

    fun getShenzhenTrainingPlans(): LiveData<DataState<List<ShenzhenTrainingPlan>>> {
        return shenzhenService.getShenzhenTrainingPlans(easPreferenceSource.getEasToken())
    }

    fun getShenzhenCreditProgress(term: TermItem? = null): LiveData<DataState<ShenzhenCreditProgress>> {
        return shenzhenService.getShenzhenCreditProgress(
            easPreferenceSource.getEasToken(),
            selectedTerm = term
        )
    }

    fun getShenzhenTrainingPlanCourses(
        plan: ShenzhenTrainingPlan
    ): LiveData<DataState<ShenzhenTrainingPlanDetail>> {
        return shenzhenService.getShenzhenTrainingPlanCourses(
            easPreferenceSource.getEasToken(),
            plan
        )
    }

    fun downloadShenzhenCourseAttachment(attachment: ShenzhenCourseAttachment): Long {
        val token = easPreferenceSource.getEasToken()
        check(token.hasShenzhenWebSession()) { "深圳 Web 会话不可用" }
        val baseUrl = if (token.webBaseUrl?.trimEnd('/') == JW_PROXY_BASE_URL) {
            JW_PROXY_BASE_URL
        } else {
            JW_DIRECT_BASE_URL
        }
        val uri = when (attachment.kind) {
            ShenzhenCourseAttachmentKind.COURSE_DESCRIPTION -> {
                check(attachment.serverPath.isNotBlank()) { "课程简介缺少文件路径" }
                Uri.parse("$baseUrl/kck/kcxxwh/downkcjjFj").buildUpon()
                    .appendQueryParameter("sname", attachment.serverPath)
                    .appendQueryParameter("fname", attachment.name)
                    .build()
            }
            ShenzhenCourseAttachmentKind.CHINESE_SYLLABUS,
            ShenzhenCourseAttachmentKind.ENGLISH_SYLLABUS -> {
                check(attachment.courseId.isNotBlank()) { "课程大纲缺少课程标识" }
                val flag = if (
                    attachment.kind == ShenzhenCourseAttachmentKind.CHINESE_SYLLABUS
                ) "zwfj" else "ywfj"
                Uri.parse("$baseUrl/kck/kcxxwh/downFj").buildUpon()
                    .appendQueryParameter("kcid", attachment.courseId)
                    .appendQueryParameter("fjflag", flag)
                    .appendQueryParameter("downFlag", "")
                    .build()
            }
        }
        val fileName = attachment.name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "课程附件" }
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        val cookies = token.webCookies.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .joinToString("; ") { "${it.key}=${it.value}" }
        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setDescription("正在下载 $fileName")
            .setMimeType(mimeType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .addRequestHeader("Referer", "$baseUrl/Xsxk/query/1")
        if (cookies.isNotBlank()) {
            request.addRequestHeader("Cookie", cookies)
        }
        val manager = appContext.getSystemService(DownloadManager::class.java)
            ?: error("系统下载服务不可用")
        return manager.enqueue(request)
    }
}
