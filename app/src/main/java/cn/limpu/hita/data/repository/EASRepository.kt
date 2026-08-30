package cn.limpu.hita.data.repository

import android.app.Application
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import javax.inject.Inject
import javax.inject.Singleton
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.classroom.ClassroomCacheEntity
import cn.limpu.hita.data.model.eas.*
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import cn.limpu.hita.data.source.web.eas.BenbuEASWebSource
import cn.limpu.hita.data.source.web.eas.EASWebSource
import cn.limpu.hita.data.source.web.eas.WeihaiEASWebSource
import cn.limpu.hita.data.source.web.service.EASService
import cn.limpu.hita.ui.eas.classroom.BuildingItem
import cn.limpu.hita.ui.eas.classroom.ClassroomItem
import cn.limpu.hita.utils.LiveDataUtils
import cn.limpu.hita.utils.TimeTools.getDateAtWOT
import cn.limpu.hita.utils.TermNameFormatter
import cn.limpu.hita.utils.CourseCodeUtils
import cn.limpu.hita.utils.ColorTools
import cn.limpu.hita.utils.CourseNameUtils
import java.sql.Timestamp
import java.lang.reflect.Type
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.security.SecureRandom
import cn.limpu.hita.utils.LogUtils
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class CourseSelectionExecutionTokenStore(
    private val loadToken: () -> EASToken
) {
    private data class ActiveExecution(
        val owner: Any,
        val token: EASToken
    )

    private val lock = Any()
    private val activeExecutions = mutableMapOf<String, ActiveExecution>()

    fun begin(job: CourseSelectionJob, owner: Any): EASToken = synchronized(lock) {
        check(job.id !in activeExecutions) { "Course-selection job is already executing" }
        loadToken().also { token ->
            CourseSelectionCredentialScopePolicy.requireMatching(job, token)
            activeExecutions[job.id] = ActiveExecution(owner, token)
        }
    }

    fun requireToken(job: CourseSelectionJob): EASToken = synchronized(lock) {
        CourseSelectionCredentialScopePolicy.requireMatching(job, loadToken())
        activeExecutions[job.id]?.token ?: error("Course-selection job is not executing")
    }

    fun end(jobId: String, owner: Any) {
        synchronized(lock) {
            if (activeExecutions[jobId]?.owner === owner) {
                activeExecutions.remove(jobId)
            }
        }
    }
}

@Singleton
class EASRepository @Inject constructor(
    application: Application,
    private val easPreferenceSource: EasPreferenceSource,
    private val timetablePreferenceSource: TimetablePreferenceSource
) : ShenzhenCourseSelectionGateway {
    private val appContext = application.applicationContext
    private val tokenStateLock = Any()
    private val authEpoch = AtomicLong(easPreferenceSource.getEasToken().sessionGeneration)
    private val credentialScopeRandom = SecureRandom()
    private val autoImportInProgress = AtomicBoolean(false)
    @Volatile private var acceptServiceTokenRefresh = easPreferenceSource.getEasToken().isLogin()
    private val shenzhenService: EASWebSource = EASWebSource(
        onTokenRefreshed = { token -> saveRefreshedEasToken(token) }
    )
    private val benbuService: EASService = BenbuEASWebSource { token ->
        saveRefreshedEasToken(token)
    }
    private val weihaiService: EASService = WeihaiEASWebSource { token ->
        saveRefreshedEasToken(token)
    }
    private var eventItemDao = AppDatabase.getDatabase(application).eventItemDao()
    private var timetableDao = AppDatabase.getDatabase(application).timetableDao()
    private var subjectDao = AppDatabase.getDatabase(application).subjectDao()
    private var classroomCacheDao = AppDatabase.getDatabase(application).classroomCacheDao()
    private var scoreCacheDao = AppDatabase.getDatabase(application).scoreCacheDao()
    private val timetableSnapshotStore = TimetableSnapshotStore(appContext)
    private val easTokenLiveData = MutableLiveData(easPreferenceSource.getEasToken())
    private val gson = Gson()
    private val scoreListType = object : TypeToken<List<CourseScoreItem>>() {}.type
    private val shenzhenGradeCourseListType = object : TypeToken<List<ShenzhenGradeCourse>>() {}.type
    private val courseSelectionExecutionTokens =
        CourseSelectionExecutionTokenStore(easPreferenceSource::getEasToken)

    companion object {
        private const val LOGIN_ENRICH_MAX_RETRIES = 3
        private const val LOGIN_ENRICH_RETRY_DELAY_MS = 800L
        private const val JW_DIRECT_BASE_URL = "https://jw.hitsz.edu.cn"
        private const val JW_PROXY_BASE_URL = "https://jw-hitsz-edu-cn.hitsz.edu.cn"
        private const val SCORE_CACHE_RETENTION_MS = 365L * 24L * 60L * 60L * 1000L
    }

    /**
     * 三校区教务策略入口。
     *
     * UI 和导入流程只依赖 EASService 的统一模型；具体校区的登录方式、
     * HTML/JSON 字段、WebVPN 地址都应留在对应 WebSource 内部。
     */
    private fun getService(campus: EASToken.Campus): EASService {
        return when (campus) {
            EASToken.Campus.SHENZHEN -> shenzhenService
            EASToken.Campus.BENBU -> benbuService
            EASToken.Campus.WEIHAI -> weihaiService
        }
    }

    /**
     * 获取当前校区
     * 用于UI层根据校区特性做不同的显示处理
     *
     * 注意：不同校区的差异：
     * - 深圳校区：考试无期中期末分类，所有考试都显示为"期末"
     * - 本部：有明确的期中期末分类
     * - 威海：暂不支持考试查询
     */
    fun getCurrentCampus(): EASToken.Campus {
        return easPreferenceSource.getEasToken().campus
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

    /**
     * 进行登录
     */
    fun login(username: String, password: String): LiveData<DataState<Boolean>> {
        return login(username, password, EASToken.Campus.SHENZHEN)
    }

    fun login(
        username: String,
        password: String,
        campus: EASToken.Campus
    ): LiveData<DataState<Boolean>> {
        val expectedEpoch = authEpoch.get()
        val result = MediatorLiveData<DataState<Boolean>>()
        val loginSource = getService(campus).login(username, password, null)
        result.addSource(loginSource) { state ->
            if (state.state == DataState.STATE.NOTHING) {
                return@addSource
            }
            if (state.state != DataState.STATE.SUCCESS) {
                result.value = DataState(false, state.state).apply { message = state.message }
                return@addSource
            }
            val token = state.data
            if (token == null) {
                result.value = DataState(false, DataState.STATE.FETCH_FAILED).apply { message = state.message }
                return@addSource
            }
            token.campus = campus
            if ((campus == EASToken.Campus.BENBU || campus == EASToken.Campus.WEIHAI) &&
                password.isNotBlank()
            ) {
                token.electronicExpToken = password
            }
            enrichLoginToken(result, token, campus, expectedEpoch = expectedEpoch)
            result.removeSource(loginSource)
        }
        return result
    }

    private fun enrichLoginToken(
        result: MediatorLiveData<DataState<Boolean>>,
        token: EASToken,
        campus: EASToken.Campus,
        attempt: Int = 0,
        expectedEpoch: Long
    ) {
        if (authEpoch.get() != expectedEpoch) {
            result.value = DataState(false, DataState.STATE.NOT_LOGGED_IN)
            return
        }
        val enrichSource = getService(campus).getSafePersonalInfo(token)
        result.addSource(enrichSource) enrichObserver@{ enrichedState ->
            if (enrichedState.state == DataState.STATE.NOTHING) {
                return@enrichObserver
            }
            result.removeSource(enrichSource)

            val enrichedToken = enrichedState.data
            val hasDisplayInfo = enrichedState.state == DataState.STATE.SUCCESS &&
                enrichedToken != null &&
                (!enrichedToken.name.isNullOrBlank() || !enrichedToken.stuId.isNullOrBlank())

            if (!hasDisplayInfo && attempt < LOGIN_ENRICH_MAX_RETRIES) {
                LogUtils.w(
                    "login: personal info not ready, retry=${attempt + 1}, " +
                        "state=${enrichedState.state}, message=${enrichedState.message}"
                )
                Handler(Looper.getMainLooper()).postDelayed(
                    {
                        enrichLoginToken(
                            result,
                            token,
                            campus,
                            attempt + 1,
                            expectedEpoch
                        )
                    },
                    LOGIN_ENRICH_RETRY_DELAY_MS
                )
                return@enrichObserver
            }

            val finalToken = enrichedToken ?: token
            finalToken.campus = campus
            if (finalToken.electronicExpToken.isNullOrBlank()) {
                finalToken.electronicExpToken = token.electronicExpToken
            }
            LogUtils.d(
                "login: saving token campus=$campus name=${finalToken.name} " +
                    "stuId=${finalToken.stuId} electronic=${!finalToken.electronicExpToken.isNullOrBlank()}"
            )
            if (saveEasToken(finalToken, expectedEpoch)) {
                result.value = DataState(true, DataState.STATE.SUCCESS)
            } else {
                result.value = DataState(false, DataState.STATE.NOT_LOGGED_IN)
            }
        }
    }

    /**
     * 验证登录
     */
    fun loginCheck(): LiveData<DataState<Boolean>> {
        val expectedEpoch = authEpoch.get()
        val token = easPreferenceSource.getEasToken()
        LogUtils.d("loginCheck: isLogin=${token.isLogin()}, campus=${token.campus}")
        if (!token.isLogin()) {
            LogUtils.w("loginCheck: not logged in")
            return LiveDataUtils.getMutableLiveData(DataState(false))
        }

        val result = MediatorLiveData<DataState<Boolean>>()
        val checkSource = getService(token.campus).loginCheck(token)
        result.addSource(checkSource) { state ->
            if (state.state == DataState.STATE.NOTHING) {
                return@addSource
            }
            if (state.state != DataState.STATE.SUCCESS || state.data == null) {
                result.value = DataState(false, state.state).apply { message = state.message }
                return@addSource
            }

            val (isValid, checkedToken) = state.data!!
            if (!isValid) {
                LogUtils.w("loginCheck: token invalid, keeping cookies for retry")
                // Don't clear token on first failure - cookies might still be valid
                // clearEasToken()
                result.value = DataState(false, DataState.STATE.SUCCESS).apply {
                    message = "登录验证失败，请重试"
                }
                return@addSource
            }

            LogUtils.d("loginCheck: token valid, fetching user info")
            // 验证成功后，获取用户信息（包括姓名）
            val enrichSource = getService(token.campus).getSafePersonalInfo(checkedToken)
            result.addSource(enrichSource) { enrichedState ->
                if (enrichedState.state == DataState.STATE.NOTHING) {
                    return@addSource
                }
                val finalToken = if (enrichedState.state == DataState.STATE.SUCCESS) {
                    enrichedState.data ?: checkedToken
                } else {
                    checkedToken
                }
                LogUtils.d("loginCheck: saving enriched token name=${finalToken.name}")
                if (saveEasToken(finalToken, expectedEpoch)) {
                    result.value = DataState(true, DataState.STATE.SUCCESS)
                } else {
                    result.value = DataState(false, DataState.STATE.NOT_LOGGED_IN)
                }
                result.removeSource(enrichSource)
            }
            result.removeSource(checkSource)
        }
        return result
    }

    /**
     * 获取学期开始日期
     */
    fun getStartDateOfTerm(term: TermItem): LiveData<DataState<Calendar>> {
        val easToken = easPreferenceSource.getEasToken()
        LogUtils.d("getStartDateOfTerm: isLogin=${easToken.isLogin()}, term=${term.getCode()}")
        if (easToken.isLogin()) {
            return getService(easToken.campus).getStartDate(easToken, term)
        }
        LogUtils.w("getStartDateOfTerm: not logged in")
        return LiveDataUtils.getMutableLiveData<DataState<Calendar>>(DataState(DataState.STATE.NOT_LOGGED_IN))
    }


    /**
     * 进行获取学年学期
     */
    fun getAllTerms(useCache: Boolean = true): LiveData<DataState<List<TermItem>>> {
        val easToken = easPreferenceSource.getEasToken()
        LogUtils.d("getAllTerms: isLogin=${easToken.isLogin()}, campus=${easToken.campus}")
        if (!useCache) {
            return getOnlineTerms(easToken)
        }
        val ownerKey = scoreCacheOwnerKey(easToken)
        if (ownerKey == null) {
            return getOnlineTerms(easToken)
        }

        val result = MediatorLiveData<DataState<List<TermItem>>>()
        val hasCachedResult = AtomicBoolean(false)
        thread(name = "score-term-cache-load") {
            val cachedTerms = scoreCacheDao.getTermsSync(ownerKey).map { it.toTermItem() }
            hasCachedResult.set(cachedTerms.isNotEmpty())
            if (cachedTerms.isNotEmpty()) {
                result.postValue(DataState(cachedTerms, DataState.STATE.SUCCESS).setFromCache(true))
            }

            if (!easToken.isLogin()) {
                if (!hasCachedResult.get()) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                }
                return@thread
            }

            Handler(Looper.getMainLooper()).post {
                val remote = getService(easToken.campus).getAllTerms(easToken)
                result.addSource(remote) { state ->
                    if (state.state == DataState.STATE.SUCCESS && state.data != null) {
                        val terms = state.data.orEmpty()
                        result.value = state
                        thread(name = "score-term-cache-save") {
                            saveScoreTermsCache(ownerKey, terms)
                        }
                    } else if (!hasCachedResult.get()) {
                        result.value = state
                    }
                }
            }
        }
        return result
    }

    private fun getOnlineTerms(easToken: EASToken): LiveData<DataState<List<TermItem>>> {
        if (easToken.isLogin()) {
            return getService(easToken.campus).getAllTerms(easToken)
        }
        LogUtils.w("getAllTerms: not logged in")
        return LiveDataUtils.getMutableLiveData(DataState(DataState.STATE.NOT_LOGGED_IN))
    }

    /**
     * 获取课表结构
     */
    fun getScheduleStructure(
        term: TermItem,
        isUndergraduate: Boolean? = null
    ): LiveData<DataState<MutableList<TimePeriodInDay>>> {
        val easToken = easPreferenceSource.getEasToken()
        LogUtils.d("getScheduleStructure: isLogin=${easToken.isLogin()}, term=${term.getCode()}")
        if (easToken.isLogin()) {
            return getService(easToken.campus).getScheduleStructure(term, isUndergraduate, easToken)
        }
        LogUtils.w("getScheduleStructure: not logged in")
        return LiveDataUtils.getMutableLiveData<DataState<MutableList<TimePeriodInDay>>>(
            DataState(
                DataState.STATE.NOT_LOGGED_IN
            )
        )

    }

    /**
     * 获取教学楼列表
     */
    fun getTeachingBuildings(): LiveData<DataState<List<BuildingItem>>> {
        val easToken = easPreferenceSource.getEasToken()
        LogUtils.d("getTeachingBuildings: isLogin=${easToken.isLogin()}")
        if (easToken.isLogin()) {
            return getService(easToken.campus).getTeachingBuildings(easToken)
        }
        LogUtils.w("getTeachingBuildings: not logged in")
        return LiveDataUtils.getMutableLiveData(DataState(DataState.STATE.NOT_LOGGED_IN))

    }

    /**
     * 查询空教室
     */
    fun queryEmptyClassroom(
        term: TermItem,
        buildingItem: BuildingItem,
        week: Int
    ): LiveData<DataState<List<ClassroomItem>>> {
        val easToken = easPreferenceSource.getEasToken()
        LogUtils.d("queryEmptyClassroom: isLogin=${easToken.isLogin()}, term=${term.getCode()}, building=${buildingItem.name}")
        if (easToken.isLogin()) {
            val result = MediatorLiveData<DataState<List<ClassroomItem>>>()
            val hasCachedResult = AtomicBoolean(false)
            thread(name = "classroom-cache-load") {
                val cached = classroomCacheDao.getByQuerySync(
                    buildingItem.id,
                    term.yearCode,
                    term.termCode,
                    week
                )
                if (cached.isNotEmpty()) {
                    hasCachedResult.set(true)
                    result.postValue(
                        DataState(cached.map { it.toClassroomItem() }).setFromCache(true)
                    )
                }
            }
            val remote = getService(easToken.campus).queryEmptyClassroom(
                easToken,
                term,
                buildingItem,
                listOf(week.toString())
            )
            result.addSource(remote) { state ->
                if (hasCachedResult.get() && state.state != DataState.STATE.SUCCESS) {
                    return@addSource
                }
                result.value = state
                if (state.state == DataState.STATE.SUCCESS) {
                    val classrooms = state.data.orEmpty()
                    thread(name = "classroom-cache-save") {
                        saveClassroomCache(term, buildingItem, week, classrooms)
                    }
                }
            }
            return result
        }
        LogUtils.w("queryEmptyClassroom: not logged in")
        return LiveDataUtils.getMutableLiveData(DataState(DataState.STATE.NOT_LOGGED_IN))
    }

    private fun saveClassroomCache(
        term: TermItem,
        buildingItem: BuildingItem,
        week: Int,
        classrooms: List<ClassroomItem>
    ) {
        classroomCacheDao.deleteByQuerySync(buildingItem.id, term.yearCode, term.termCode, week)
        if (classrooms.isEmpty()) return
        val cachedAt = System.currentTimeMillis()
        val entities = classrooms.map { classroom ->
            ClassroomCacheEntity(
                buildingId = buildingItem.id,
                buildingName = buildingItem.name.orEmpty(),
                termYearCode = term.yearCode,
                termTermCode = term.termCode,
                week = week,
                name = classroom.name,
                capacity = classroom.capacity,
                specialClassroom = classroom.specialClassroom,
                scheduleJson = JSONArray(classroom.scheduleList).toString(),
                cachedAt = cachedAt
            )
        }
        classroomCacheDao.saveAllSync(entities)
        classroomCacheDao.deleteOldCachesSync(cachedAt - 180L * 24L * 60L * 60L * 1000L)
    }

    private fun ClassroomCacheEntity.toClassroomItem(): ClassroomItem {
        return ClassroomItem().also { classroom ->
            classroom.id = name
            classroom.name = name
            classroom.capacity = capacity
            classroom.specialClassroom = specialClassroom
            val arr = JSONArray(scheduleJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: JSONObject()
                classroom.scheduleList.add(obj)
            }
        }
    }

    /**
     * 获取最终成绩
     */
    fun getPersonalScores(
        term: TermItem,
        testType: EASService.TestType
    ): LiveData<DataState<List<CourseScoreItem>>> {
        return getPersonalScoresWithSummary(term, testType).map { state ->
            DataState(state.data?.items ?: emptyList(), state.state).apply {
                message = state.message
                fromCache = state.fromCache
            }
        }
    }

    fun getPersonalScoresWithSummary(
        term: TermItem,
        testType: EASService.TestType,
        useCache: Boolean = true,
    ): LiveData<DataState<ScoreQueryResult>> {
        val easToken = easPreferenceSource.getEasToken()
        LogUtils.d("getPersonalScoresWithSummary: isLogin=${easToken.isLogin()}, term=${term.getCode()}")
        if (!useCache) {
            return getOnlinePersonalScoresWithSummary(term, testType, easToken)
        }
        val ownerKey = scoreCacheOwnerKey(easToken)
        if (ownerKey == null) {
            return getOnlinePersonalScoresWithSummary(term, testType, easToken)
        }

        val result = MediatorLiveData<DataState<ScoreQueryResult>>()
        val hasCachedResult = AtomicBoolean(false)
        thread(name = "score-cache-load") {
            scoreCacheDao.getScoreSync(
                ownerKey = ownerKey,
                termYearCode = term.yearCode,
                termTermCode = term.termCode,
                testType = testType.name
            )?.toScoreQueryResultOrNull()?.let { cached ->
                hasCachedResult.set(true)
                result.postValue(DataState(cached, DataState.STATE.SUCCESS).setFromCache(true))
            }

            if (!easToken.isLogin()) {
                if (!hasCachedResult.get()) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                }
                return@thread
            }

            Handler(Looper.getMainLooper()).post {
                val remote = getOnlinePersonalScoresWithSummary(term, testType, easToken)
                result.addSource(remote) { state ->
                    if (state.state == DataState.STATE.SUCCESS && state.data != null) {
                        result.value = state
                        thread(name = "score-cache-save") {
                            saveScoreCache(ownerKey, term, testType, state.data!!)
                        }
                    } else if (!hasCachedResult.get()) {
                        result.value = state
                    }
                }
            }
        }
        return result
    }

    private fun getOnlinePersonalScoresWithSummary(
        term: TermItem,
        testType: EASService.TestType,
        easToken: EASToken,
    ): LiveData<DataState<ScoreQueryResult>> {
        if (!easToken.isLogin()) {
            return LiveDataUtils.getMutableLiveData(DataState(DataState.STATE.NOT_LOGGED_IN))
        }
        val service = getService(easToken.campus)
        return when (service) {
            is EASWebSource -> service.getPersonalScoresWithSummary(term, easToken, testType)
            is BenbuEASWebSource -> service.getPersonalScoresWithSummary(term, easToken, testType)
            is WeihaiEASWebSource -> service.getPersonalScoresWithSummary(term, easToken, testType)
            else -> service.getPersonalScores(term, easToken, testType).map { state ->
                if (state.state == DataState.STATE.SUCCESS) {
                    DataState(ScoreQueryResult(items = state.data ?: emptyList(), summary = null), state.state)
                } else {
                    DataState<ScoreQueryResult>(state.state, state.message)
                }
            }
        }
    }

    private fun scoreCacheOwnerKey(token: EASToken): String? {
        val identity = token.stuId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: token.id?.trim()?.takeIf { it.isNotEmpty() }
            ?: token.username?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return "${token.campus.name}:$identity"
    }

    private fun saveScoreTermsCache(ownerKey: String, terms: List<TermItem>) {
        val cachedAt = System.currentTimeMillis()
        scoreCacheDao.deleteTermsByOwnerSync(ownerKey)
        if (terms.isNotEmpty()) {
            scoreCacheDao.saveTermsSync(
                terms.map { term ->
                    ScoreTermCacheEntity(
                        ownerKey = ownerKey,
                        termYearCode = term.yearCode,
                        yearName = term.yearName,
                        termTermCode = term.termCode,
                        termName = term.termName,
                        isCurrent = term.isCurrent,
                        cachedAt = cachedAt,
                    )
                }
            )
        }
        scoreCacheDao.deleteOldTermsSync(cachedAt - SCORE_CACHE_RETENTION_MS)
    }

    private fun saveScoreCache(
        ownerKey: String,
        term: TermItem,
        testType: EASService.TestType,
        result: ScoreQueryResult,
    ) {
        val cachedAt = System.currentTimeMillis()
        scoreCacheDao.saveScoreSync(
            ScoreCacheEntity(
                ownerKey = ownerKey,
                termYearCode = term.yearCode,
                termTermCode = term.termCode,
                testType = testType.name,
                scoresJson = gson.toJson(result.items),
                summaryJson = result.summary?.let(gson::toJson),
                cachedAt = cachedAt,
            )
        )
        scoreCacheDao.deleteOldScoresSync(cachedAt - SCORE_CACHE_RETENTION_MS)
    }

    private fun ScoreCacheEntity.toScoreQueryResultOrNull(): ScoreQueryResult? {
        return runCatching {
            ScoreQueryResult(
                items = gson.fromJson<List<CourseScoreItem>>(scoresJson, scoreListType).orEmpty(),
                summary = summaryJson?.let { gson.fromJson(it, ScoreSummary::class.java) }
            )
        }.getOrNull()
    }

    private fun ScoreTermCacheEntity.toTermItem(): TermItem {
        return TermItem(
            yearCode = termYearCode,
            yearName = yearName,
            termCode = termTermCode,
            termName = termName,
        ).also { it.isCurrent = isCurrent }
    }

    /**
     * 获取考试信息
     */
    fun getExamInfo(term: TermItem? = null): LiveData<DataState<List<ExamItem>>> {
        val easToken = easPreferenceSource.getEasToken()
        LogUtils.d("getExamInfo: term=${term?.name}, isLogin=${easToken.isLogin()}, campus=${easToken.campus}")
        if (easToken.isLogin()) {
            return getService(easToken.campus).getExamItems(easToken, term)
        }
        LogUtils.w("getExamInfo: not logged in")
        return LiveDataUtils.getMutableLiveData(DataState(DataState.STATE.NOT_LOGGED_IN))
    }

    /**
     * 动作：导入课表。
     *
     * 这里是导入编排层，不解析各校区原始响应：
     * - WebSource 负责把不同校区接口规范化为 CourseItem；
     * - Repository 负责复用/创建本地 Timetable、生成 EventItem、保存 Subject；
     * - EasImportIdentity 负责结构化去重，避免同一课程因名称长短、校区 code 差异重复导入。
     *
     * 老教务解析逻辑不要挪到这里；如果某校区字段变化，应优先修改对应 WebSource 或 Parser。
     */
    private var timetableWebLiveData: LiveData<DataState<List<CourseItem>>>? = null
    fun startImportTimetableOfTerm(
        term: TermItem,
        startDate: Calendar,
        schedule: List<TimePeriodInDay>,//课表结构
        importTimetableLiveData: MediatorLiveData<DataState<Boolean>>
    ) {
        startDate.set(Calendar.HOUR_OF_DAY, 0)
        startDate.set(Calendar.MINUTE, 0)
        startDate.firstDayOfWeek = Calendar.MONDAY
        startDate.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        startDate.set(Calendar.SECOND, 0)
        startDate.set(Calendar.MILLISECOND, 0)
        val easToken = easPreferenceSource.getEasToken()
        val timetableCode = EASTimetableCode.of(easToken.campus, term)
        val legacyTimetableCode = term.getCode()
        LogUtils.d("startImport: term=${term.getCode()}, campus=${easToken.campus}, code=$timetableCode, isLogin=${easToken.isLogin()}")
        if (easToken.isLogin()) {
            val finished = AtomicBoolean(false)
            val timeoutHandler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (finished.compareAndSet(false, true)) {
                    importTimetableLiveData.value =
                        DataState(DataState.STATE.FETCH_FAILED, "导入超时，请重试")
                }
            }
            timeoutHandler.postDelayed(timeoutRunnable, 90_000L)
            timetableWebLiveData?.let { importTimetableLiveData.removeSource(it) }
            timetableWebLiveData =
                getService(easToken.campus).getTimetableOfTerm(term, easToken)
            importTimetableLiveData.addSource(timetableWebLiveData!!) {
                when (it.state) {
                    DataState.STATE.SUCCESS -> {
                        val courseItems = it.data
                        LogUtils.d( "import: timetable response state=${it.state} term=${term.getCode()} courseCount=${courseItems?.size ?: -1}")
                        if (courseItems.isNullOrEmpty()) {
                            if (finished.compareAndSet(false, true)) {
                                timeoutHandler.removeCallbacks(timeoutRunnable)
                                importTimetableLiveData.value =
                                    DataState(DataState.STATE.FETCH_FAILED, "empty timetable")
                            }
                            return@addSource
                        }
                        Thread {
                            try {
                                val meta = if (easToken.campus == EASToken.Campus.SHENZHEN) {
                                    fetchSelectedSubjectMeta(term, easToken)
                                } else {
                                    SelectedSubjectMeta(
                                        emptyMap(),
                                        emptyMap(),
                                        emptyMap(),
                                        emptyMap(),
                                        emptyMap(),
                                        emptyMap()
                                    )
                                }
                                val teacherMap = meta.teacherMap
                                val creditMap = meta.creditMap
                                val maxPeriod = courseItems.maxOfOrNull { item ->
                                    (item.begin + item.last - 1).coerceAtLeast(item.begin)
                                } ?: 0
                                val safeSchedule = buildSafeSchedule(schedule, maxPeriod)
                                LogUtils.d(
                                    "import: processing term=${term.getCode()} campus=${easToken.campus} code=$timetableCode courseCount=${courseItems.size} maxPeriod=$maxPeriod schedule=${describeSchedule(safeSchedule)}"
                                )
                                //更新timetable信息
                                var timetable = timetableDao.getTimetableByEASCodeCandidatesSync(
                                    EASTimetableCode.candidates(term, easToken.campus),
                                    timetableCode,
                                    legacyTimetableCode
                                )
                                if (timetable == null) {
                                    timetable = Timetable()
                                }
                                //记录最后的时间戳，作为学期结束的标志
                                var maxTs: Long = 0
                                //添加时间表
                                val events = mutableListOf<EventItem>()
                                val pendingSubjects = linkedMapOf<String, TermSubject>()
                                val subjectsByKey = mutableMapOf<String, TermSubject>()
                                subjectDao.getSubjectsSync(timetable.id).forEach { subject ->
                                    EasImportIdentity.subjectLookupKeys(subject.code, subject.name, subject.name).forEach { key ->
                                        subjectsByKey[key] = subject
                                    }
                                }
                                val generatedClassKeys = mutableSetOf<String>()

                                // Count free time courses before processing
                                val freeTimeCount = courseItems.count { item ->
                                    !item.startTime.isNullOrBlank() && !item.endTime.isNullOrBlank() && item.begin == -1 && item.last == -1
                                }
                                LogUtils.d("import: courses=${courseItems.size} freeTime=$freeTimeCount period=${courseItems.size - freeTimeCount}")

                                for (item in courseItems) {
                                    // Check if this is a free time course (has startTime/EndTime)
                                    val isFreeTimeCourse = !item.startTime.isNullOrBlank() && !item.endTime.isNullOrBlank() && item.begin == -1 && item.last == -1

                                    // Debug log for experiment courses
                                    // Skip period-based courses with invalid indices
                                    if (!isFreeTimeCourse) {
                                        val startIndex = item.begin - 1
                                        val endIndex = item.begin + item.last - 2
                                        if (startIndex !in safeSchedule.indices || endIndex !in safeSchedule.indices) {
                                            continue
                                        }
                                    }

                                    val rawName = item.name?.toString().orEmpty().trim()
                                    if (rawName.isBlank()) {
                                        continue
                                    }
                                    val normalizedName = CourseNameUtils.normalize(rawName) ?: rawName
                                    val code = (
                                        CourseCodeUtils.normalize(item.code)
                                            ?: item.code?.trim().orEmpty()
                                        ).ifBlank {
                                        meta.codeMap[rawName]
                                            ?: meta.codeMap[normalizedName]
                                            ?: ""
                                    }

                                    //添加科目
                                    val lookupKeys = EasImportIdentity.subjectLookupKeys(code, normalizedName, rawName)
                                    var subject = lookupKeys.firstNotNullOfOrNull { key -> subjectsByKey[key] }
                                    if (subject == null) {//不存在，新建
                                        subject = TermSubject()
                                        // 优先保存完整的原始名称
                                        subject.name = rawName
                                        subject.timetableId = timetable.id
                                        subject.id = UUID.randomUUID().toString()
                                        subject.color = ColorTools.colorForName(normalizedName)
                                    } else {
                                        // 科目已存在，总是尝试更新为更完整的名称
                                        // 优先选择包含更多信息（括号、方括号）的名称
                                        val oldHasBrackets = subject.name.contains("（") || subject.name.contains("(") ||
                                                                       subject.name.contains("[") || subject.name.contains("【")
                                        val newHasBrackets = rawName.contains("（") || rawName.contains("(") ||
                                                                       rawName.contains("[") || rawName.contains("【")

                                        // 如果新名称包含括号信息（通常更完整），或者新名称明显更长，则更新
                                        if (newHasBrackets && !oldHasBrackets) {
                                            subject.name = rawName
                                        } else if (rawName.length > subject.name.length + 2) {
                                            // 只有新名称明显更长时才更新（避免因细微差异反复更新）
                                            subject.name = rawName
                                        }
                                    }
                                    if (code.isNotBlank() && subject.code.isNullOrBlank()) {
                                        subject.code = code
                                    }
                                    if (subject.credit <= 0f) {
                                        val mappedCredit = creditMap[code]
                                            ?: creditMap[rawName]
                                            ?: creditMap[normalizedName]
                                        if (mappedCredit != null && mappedCredit > 0f) {
                                            subject.credit = mappedCredit
                                        }
                                    }
                                    if (subject.field.isNullOrBlank()) {
                                        val mappedField = meta.fieldMap[code]
                                            ?: meta.fieldMap[rawName]
                                            ?: meta.fieldMap[normalizedName]
                                        if (!mappedField.isNullOrBlank()) {
                                            subject.field = mappedField
                                        }
                                    }
                                    if (subject.selectCategory.isNullOrBlank()) {
                                        val mappedSelect = meta.selectCategoryMap[code]
                                            ?: meta.selectCategoryMap[rawName]
                                            ?: meta.selectCategoryMap[normalizedName]
                                        if (!mappedSelect.isNullOrBlank()) {
                                            subject.selectCategory = mappedSelect
                                        }
                                    }
                                    if (subject.nature.isNullOrBlank()) {
                                        val mappedNature = meta.natureMap[code]
                                            ?: meta.natureMap[rawName]
                                            ?: meta.natureMap[normalizedName]
                                        if (!mappedNature.isNullOrBlank()) {
                                            subject.nature = mappedNature
                                        }
                                    }
                                    EasImportIdentity.subjectLookupKeys(subject.code, normalizedName, subject.name).forEach { key ->
                                        subjectsByKey[key] = subject
                                    }
                                    var itemHasEvent = false

                                    for (week in item.weeks) {
                                        val from = getDateAtWOT(startDate, week, item.dow)
                                        val to = getDateAtWOT(startDate, week, item.dow)

                                        // Handle free time courses (experiment courses with custom times)
                                        if (isFreeTimeCourse) {
                                            val startTime = item.startTime
                                            val endTime = item.endTime
                                            if (startTime != null && endTime != null) {
                                                // Parse "HH:MM" format
                                                val startParts = startTime.split(":")
                                                val endParts = endTime.split(":")
                                                from.set(Calendar.HOUR_OF_DAY, startParts[0].toInt())
                                                from.set(Calendar.MINUTE, startParts[1].toInt())
                                                to.set(Calendar.HOUR_OF_DAY, endParts[0].toInt())
                                                to.set(Calendar.MINUTE, endParts[1].toInt())
                                            }
                                        } else {
                                            // Period-based courses
                                            val spStart = safeSchedule[item.begin - 1]
                                            val spEnd = safeSchedule[item.begin + item.last - 2]
                                            from.set(Calendar.HOUR_OF_DAY, spStart.from.hour)
                                            from.set(Calendar.MINUTE, spStart.from.minute)
                                            to.set(Calendar.HOUR_OF_DAY, spEnd.to.hour)
                                            to.set(Calendar.MINUTE, spEnd.to.minute)
                                        }

                                        val e = EventItem()
                                        e.source = EventItem.SOURCE_EAS_IMPORT
                                        // 使用原始完整名称而不是normalized
                                        e.name = rawName
                                        e.from.time = from.timeInMillis
                                        e.fromNumber = if (isFreeTimeCourse) 0 else item.begin
                                        e.subjectId = subject.id
                                        e.lastNumber = if (isFreeTimeCourse) 0 else item.last
                                        e.to.time = to.timeInMillis
                                        val itemTeacher = sanitizeImportedTeacher(rawName, item.teacher)
                                        val mappedTeacher = itemTeacher
                                            ?: code.takeIf { it.isNotBlank() }?.let { teacherMap[it] }
                                            ?: teacherMap[rawName]
                                            ?: teacherMap[normalizedName]
                                        val teacherSource = when {
                                            !itemTeacher.isNullOrBlank() -> "item"
                                            !code.isNullOrBlank() && !teacherMap[code].isNullOrBlank() -> "meta_by_code"
                                            !teacherMap[rawName].isNullOrBlank() -> "meta_by_name_raw"
                                            !teacherMap[normalizedName].isNullOrBlank() -> "meta_by_name_normalized"
                                            else -> "none"
                                        }
                                        e.teacher = mappedTeacher
                                        e.place = item.classroom
                                        e.timetableId = timetable.id
                                        if (!generatedClassKeys.add(EasImportIdentity.classEventIdentityKey(e))) {
                                            continue
                                        }
                                        if (e.to.time > maxTs) maxTs = e.to.time
                                        events.add(e)
                                        itemHasEvent = true
                                    }
                                    if (itemHasEvent) {
                                        pendingSubjects[subject.id] = subject
                                    }
                                }
                                if (events.isEmpty()) {
                                    LogUtils.w("import: empty events for term=${term.getCode()}")
                                    if (finished.compareAndSet(false, true)) {
                                        timeoutHandler.removeCallbacks(timeoutRunnable)
                                        importTimetableLiveData.postValue(
                                            DataState(DataState.STATE.FETCH_FAILED, "empty events")
                                        )
                                    }
                                    return@Thread
                                }
                                LogUtils.d( "import: saving ${events.size} events for term=${term.getCode()}")
                                val snapshotOwnerKey = FollowedTeachingSectionStore.ownerKey(easToken)
                                timetableSnapshotStore.capture(
                                    snapshotOwnerKey,
                                    term,
                                    timetable,
                                    TimetableSnapshotKind.BEFORE_REFRESH
                                )
                                eventItemDao.deleteCourseFromTimetable(timetable.id)
                                subjectDao.saveSubjectsSync(pendingSubjects.values.toList())
                                eventItemDao.saveEvents(events)

                                //更新timetable对象
                                timetable.name = buildTimetableName(term, easToken.campus)
                                timetable.startTime = Timestamp(startDate.timeInMillis)
                                timetable.endTime = Timestamp(maxTs)
                                timetable.code = timetableCode
                                timetable.scheduleStructure = safeSchedule
                                timetableDao.saveTimetableSync(timetable)
                                timetableSnapshotStore.capture(
                                    snapshotOwnerKey,
                                    term,
                                    timetable,
                                    TimetableSnapshotKind.IMPORTED
                                )
                                cleanupDefaultDuplicateTimetablesAfterImport(timetable.id)

                                if (finished.compareAndSet(false, true)) {
                                    timeoutHandler.removeCallbacks(timeoutRunnable)
                                    LogUtils.success("import: term=${term.getCode()} events=${events.size}")
                                    importTimetableLiveData.postValue(DataState(true, DataState.STATE.SUCCESS))
                                }
                            } catch (e: Exception) {
                                LogUtils.e( "import: failed for term=${term.getCode()}", e)
                                if (finished.compareAndSet(false, true)) {
                                    timeoutHandler.removeCallbacks(timeoutRunnable)
                                    importTimetableLiveData.postValue(
                                        DataState(DataState.STATE.FETCH_FAILED, e.message)
                                    )
                                }
                            }
                        }.start()
                    }
                    DataState.STATE.FETCH_FAILED, DataState.STATE.NOT_LOGGED_IN -> {
                        LogUtils.w( "import: timetable fetch failed for term=${term.getCode()} message=${it.message}")
                        if (finished.compareAndSet(false, true)) {
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            importTimetableLiveData.value =
                                DataState(DataState.STATE.FETCH_FAILED, it.message)
                        }
                    }
                    else -> Unit
                }
            }
        } else {
            LogUtils.e("startImport: not logged in, cannot import")
            importTimetableLiveData.value = DataState(DataState.STATE.NOT_LOGGED_IN)
        }
    }

    /**
     * 清理历史遗留的“默认课表”重复数据。
     *
     * 早期版本可能把 EAS 导入课程放入默认课表，升级后同一学期会同时存在：
     * - 带 EAS code 的正式学期课表；
     * - code 为空、名字像“默认课表”的历史表。
     *
     * 只清理纯 EAS 课程且内容完全被正式课表覆盖的默认表；
     * 如果里面有手动活动、考试、ICS 或 AI 创建内容，一律保留。
     */
    private fun cleanupDefaultDuplicateTimetablesAfterImport(importedTimetableId: String) {
        val defaultPrefix = appContext.getString(R.string.default_timetable_name)
        val defaults = timetableDao.getDefaultNamedCustomTimetablesSync("$defaultPrefix%")
        if (defaults.isEmpty()) return

        val importedKeys = eventItemDao.getImportedClassEventsOfTimetableSync(importedTimetableId)
            .mapTo(mutableSetOf()) { importedClassEventIdentityKey(it) }
        if (importedKeys.isEmpty()) return

        val deleteIds = defaults.mapNotNull { timetable ->
            val eventCount = eventItemDao.countEventsOfTimetableSync(timetable.id)
            if (eventCount == 0) return@mapNotNull timetable.id

            val nonImportedClassCount = eventItemDao.countNonImportedClassEventsOfTimetableSync(timetable.id)
            if (nonImportedClassCount > 0) return@mapNotNull null

            val defaultKeys = eventItemDao.getImportedClassEventsOfTimetableSync(timetable.id)
                .mapTo(mutableSetOf()) { importedClassEventIdentityKey(it) }
            if (defaultKeys.isNotEmpty() && importedKeys.containsAll(defaultKeys)) {
                timetable.id
            } else {
                null
            }
        }
        if (deleteIds.isEmpty()) return

        LogUtils.d("import: cleanup duplicate default timetables=$deleteIds")
        timetableDao.deleteTimetablesInIdsSync(deleteIds)
        eventItemDao.deleteEventsFromTimetablesSync(deleteIds)
        subjectDao.deleteSubjectsFromTimetablesSync(deleteIds)
    }

    private fun importedClassEventIdentityKey(event: EventItem): String {
        return listOf(
            event.name.trim(),
            event.place.orEmpty().trim(),
            event.teacher.orEmpty().trim(),
            event.from.time.toString(),
            event.to.time.toString(),
            event.fromNumber.toString(),
            event.lastNumber.toString(),
        ).joinToString("|")
    }

    private fun sanitizeImportedTeacher(courseName: String?, teacherRaw: String?): String? {
        val source = teacherRaw?.trim().orEmpty()
        if (source.isBlank()) return null

        val name = courseName?.trim().orEmpty()
        val normalized = source.replace(" ", "")
        val looksLikeCoursePayload = normalized.startsWith("【") ||
            (name.isNotBlank() && (source.startsWith(name) || normalized.contains(name.replace(" ", ""))))
        if (looksLikeCoursePayload) return null

        val cleaned = source
            .replace(Regex("^第[一二三四五六七八九十0-9]+批"), "")
            .trimStart('/', '／', ' ', '\t')
            .trim()
        return cleaned.ifBlank { null }
    }

    private fun buildSafeSchedule(
        schedule: List<TimePeriodInDay>,
        requiredMaxPeriod: Int
    ): List<TimePeriodInDay> {
        if (requiredMaxPeriod <= 0) return schedule
        if (schedule.size >= requiredMaxPeriod) return schedule
        val defaults = Timetable().getDefaultTimeStructure()
        val size = maxOf(requiredMaxPeriod, defaults.size, schedule.size)
        return List(size) { idx ->
            schedule.getOrNull(idx) ?: defaults.getOrNull(idx) ?: defaults.last()
        }
    }

    private fun describeSchedule(schedule: List<TimePeriodInDay>): String {
        if (schedule.isEmpty()) return "empty"
        return "size=${schedule.size}, first=${schedule.first()}, last=${schedule.last()}"
    }

    private fun buildTimetableName(term: TermItem, campus: EASToken.Campus): String {
        val campusName = when (campus) {
            EASToken.Campus.SHENZHEN -> appContext.getString(R.string.eas_campus_shenzhen)
            EASToken.Campus.BENBU -> appContext.getString(R.string.eas_campus_benbu)
            EASToken.Campus.WEIHAI -> appContext.getString(R.string.eas_campus_weihai)
        }
        return "$campusName ${TermNameFormatter.shortTermName(term.termName, term.name)}"
    }

    private data class SelectedSubjectMeta(
        val codeMap: Map<String, String>,
        val teacherMap: Map<String, String>,
        val creditMap: Map<String, Float>,
        val fieldMap: Map<String, String>,
        val selectCategoryMap: Map<String, String>,
        val natureMap: Map<String, String>
    )

    private fun fetchSelectedSubjectMeta(term: TermItem, token: EASToken): SelectedSubjectMeta {
        val codeMap = mutableMapOf<String, String>()
        val teacherMap = mutableMapOf<String, String>()
        val creditMap = mutableMapOf<String, Float>()
        val fieldMap = mutableMapOf<String, String>()
        val selectCategoryMap = mutableMapOf<String, String>()
        val natureMap = mutableMapOf<String, String>()
        val latch = CountDownLatch(1)
        val live = getService(token.campus).getSubjectsOfTerm(token, term)
        val observer = Observer<DataState<MutableList<TermSubject>>> { state ->
            if (state.state == DataState.STATE.SUCCESS || state.state == DataState.STATE.FETCH_FAILED) {
                state.data?.forEach { subject ->
                    val code = CourseCodeUtils.normalize(subject.code)
                        ?: subject.code?.trim().orEmpty()
                    val nameKeys = listOfNotNull(
                        subject.name.trim().takeIf { it.isNotEmpty() },
                        CourseNameUtils.normalize(subject.name)?.trim()?.takeIf { it.isNotEmpty() }
                    ).distinct()
                    if (code.isNotBlank()) {
                        nameKeys.forEach { name -> codeMap[name] = code }
                    }
                    val teacher = subject.teacher?.trim()
                    if (!teacher.isNullOrEmpty()) {
                        if (code.isNotBlank()) teacherMap[code] = teacher
                        nameKeys.forEach { name -> teacherMap[name] = teacher }
                    }
                    val credit = subject.credit
                    if (credit > 0f) {
                        if (code.isNotBlank()) creditMap[code] = credit
                        nameKeys.forEach { name -> creditMap[name] = credit }
                    }
                    subject.field?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                        if (code.isNotBlank()) fieldMap[code] = value
                        nameKeys.forEach { name -> fieldMap[name] = value }
                    }
                    subject.selectCategory?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                        if (code.isNotBlank()) selectCategoryMap[code] = value
                        nameKeys.forEach { name -> selectCategoryMap[name] = value }
                    }
                    subject.nature?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                        if (code.isNotBlank()) natureMap[code] = value
                        nameKeys.forEach { name -> natureMap[name] = value }
                    }
                }
                latch.countDown()
            }
        }
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post { live.observeForever(observer) }
        latch.await(8, TimeUnit.SECONDS)
        mainHandler.post { live.removeObserver(observer) }
        return SelectedSubjectMeta(
            codeMap,
            teacherMap,
            creditMap,
            fieldMap,
            selectCategoryMap,
            natureMap
        )
    }

    fun startAutoImportCurrentTimetable(
        isUndergraduate: Boolean,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val token = easPreferenceSource.getEasToken()
        if (!token.isLogin()) {
            onResult?.invoke(false)
            return
        }
        if (!autoImportInProgress.compareAndSet(false, true)) {
            LogUtils.d("autoImport: ignored duplicate request while another import is running")
            onResult?.invoke(false)
            return
        }
        val expectedEpoch = authEpoch.get()
        Thread {
            var importedSuccessfully = false
            try {
                if (!isCurrentAuthOperation(expectedEpoch)) return@Thread
                val service = getService(token.campus)
                val termsState = awaitLiveData(service.getAllTerms(token), 6)
                LogUtils.d("autoImport: terms state=${termsState.state} count=${termsState.data?.size ?: -1}")
                if (!isCurrentAuthOperation(expectedEpoch)) return@Thread
                val term = termsState.data?.firstOrNull { it.isCurrent }
                    ?: termsState.data?.firstOrNull()
                    ?: return@Thread

                val startState = awaitLiveData(service.getStartDate(token, term), 6)
                val startDate = startState.data
                LogUtils.d("autoImport: startDate state=${startState.state}")
                if (!isCurrentAuthOperation(expectedEpoch)) return@Thread
                val scheduleState = awaitLiveData(
                    service.getScheduleStructure(term, isUndergraduate, token),
                    6
                )
                val schedule = scheduleState.data ?: timetablePreferenceSource.getSchedule()
                LogUtils.d("autoImport: schedule state=${scheduleState.state} size=${schedule.size}")
                if (startDate == null || !isCurrentAuthOperation(expectedEpoch)) return@Thread

                val importLive = MediatorLiveData<DataState<Boolean>>()
                val latch = CountDownLatch(1)
                var timetableImported = false
                val observer = Observer<DataState<Boolean>> { state ->
                    if (state.state == DataState.STATE.SUCCESS ||
                        state.state == DataState.STATE.FETCH_FAILED
                    ) {
                        timetableImported = state.state == DataState.STATE.SUCCESS
                        latch.countDown()
                    }
                }
                val mainHandler = Handler(Looper.getMainLooper())
                mainHandler.post {
                    if (!isCurrentAuthOperation(expectedEpoch)) {
                        latch.countDown()
                        return@post
                    }
                    importLive.observeForever(observer)
                    startImportTimetableOfTerm(term, startDate, schedule, importLive)
                }
                latch.await(25, TimeUnit.SECONDS)
                mainHandler.post { importLive.removeObserver(observer) }
                if (!isCurrentAuthOperation(expectedEpoch)) return@Thread

                val examState = awaitLiveData(service.getExamItems(token, term), 8)
                if (!isCurrentAuthOperation(expectedEpoch)) return@Thread
                val timetableCode = EASTimetableCode.of(token.campus, term)
                val timetable = timetableDao.getTimetableByEASCodeCandidatesSync(
                    EASTimetableCode.candidates(term, token.campus),
                    timetableCode,
                    term.getCode()
                )
                val importedExamCount = if (timetable == null) {
                    LogUtils.w("autoImport: skip exams, timetable not found code=$timetableCode")
                    0
                } else {
                    importExamItemsSync(examState.data.orEmpty(), timetable)
                }
                LogUtils.d(
                    "autoImport: exam state=${examState.state} " +
                        "total=${examState.data?.size ?: -1} imported=$importedExamCount"
                )
                importedSuccessfully = timetableImported || importedExamCount > 0
            } catch (error: Exception) {
                LogUtils.e("autoImport: failed, error=${error.message}", error)
            } finally {
                autoImportInProgress.set(false)
                onResult?.invoke(importedSuccessfully)
            }
        }.start()
    }

    private fun isCurrentAuthOperation(expectedEpoch: Long): Boolean {
        return authEpoch.get() == expectedEpoch && easPreferenceSource.getEasToken().isLogin()
    }

    @WorkerThread
    private fun importExamItemsSync(exams: List<ExamItem>, timetable: Timetable): Int {
        if (exams.isEmpty()) return 0
        val existingKeys = eventItemDao.getExamEventsSync()
            .mapTo(mutableSetOf()) { ExamEventMapper.identityKey(it) }
        var importedCount = 0

        for (exam in exams) {
            val examEvent = ExamEventMapper.toEvent(exam, timetable.id, "EASRepository") ?: continue
            val key = ExamEventMapper.identityKey(examEvent)
            if (!existingKeys.add(key)) continue
            eventItemDao.insertEventSync(examEvent)
            importedCount++
        }
        return importedCount
    }

    private fun <T> awaitLiveData(
        live: LiveData<DataState<T>>,
        timeoutSeconds: Long
    ): DataState<T> {
        val latch = CountDownLatch(1)
        var result = DataState<T>(DataState.STATE.FETCH_FAILED)
        val observer = Observer<DataState<T>> { state ->
            result = state
            latch.countDown()
        }
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post { live.observeForever(observer) }
        latch.await(timeoutSeconds, TimeUnit.SECONDS)
        mainHandler.post { live.removeObserver(observer) }
        return result
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

    fun getShenzhenCreditProgress(): LiveData<DataState<ShenzhenCreditProgress>> {
        return shenzhenService.getShenzhenCreditProgress(easPreferenceSource.getEasToken())
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

    fun getHoaCampus(@Suppress("UNUSED_PARAMETER") campus: EASToken.Campus = easPreferenceSource.getEasToken().campus): String {
        return "shenzhen"
    }

    fun getEasToken(): EASToken {
        return easPreferenceSource.getEasToken()
    }

    fun getTimetableSnapshots(term: TermItem): LiveData<DataState<List<TimetableVersionSnapshot>>> {
        val result = MutableLiveData<DataState<List<TimetableVersionSnapshot>>>(
            DataState(DataState.STATE.NOTHING)
        )
        thread(name = "timetable-snapshot-list") {
            runCatching {
                val token = easPreferenceSource.getEasToken()
                val ownerKey = FollowedTeachingSectionStore.ownerKey(token)
                timetableSnapshotStore.snapshots(ownerKey, term.id)
            }.onSuccess {
                result.postValue(DataState(it, DataState.STATE.SUCCESS))
            }.onFailure { error ->
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }
        return result
    }

    fun restoreTimetableSnapshot(snapshotId: String): LiveData<DataState<TimetableVersionSnapshot>> {
        val result = MutableLiveData<DataState<TimetableVersionSnapshot>>(
            DataState(DataState.STATE.NOTHING)
        )
        thread(name = "timetable-snapshot-restore") {
            runCatching {
                val token = easPreferenceSource.getEasToken()
                val ownerKey = FollowedTeachingSectionStore.ownerKey(token)
                timetableSnapshotStore.restore(ownerKey, snapshotId)
            }.onSuccess {
                result.postValue(DataState(it, DataState.STATE.SUCCESS))
            }.onFailure { error ->
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }
        return result
    }

    fun observeEasToken(): LiveData<EASToken> {
        return easTokenLiveData
    }

    private fun saveEasToken(
        token: EASToken,
        expectedEpoch: Long = authEpoch.get()
    ): Boolean = synchronized(tokenStateLock) {
        if (authEpoch.get() != expectedEpoch) {
            LogUtils.d("saveEasToken: ignored result from an operation cancelled by logout")
            return@synchronized false
        }
        val storedToken = easPreferenceSource.getEasToken()
        token.sessionGeneration = EasSessionGenerationGuard.resolveCredentialScopeGeneration(
            storedToken = storedToken,
            incomingToken = token,
            currentGeneration = authEpoch.get(),
            nextGeneration = ::nextCredentialScopeGeneration
        )
        authEpoch.set(token.sessionGeneration)
        val mergedToken = mergeWithStoredEasToken(token)
        easPreferenceSource.saveEasToken(mergedToken)
        acceptServiceTokenRefresh = mergedToken.isLogin()
        publishEasToken(mergedToken)
        true
    }

    private fun saveRefreshedEasToken(token: EASToken) {
        synchronized(tokenStateLock) {
            if (!EasSessionGenerationGuard.acceptsServiceRefresh(
                    refreshEnabled = acceptServiceTokenRefresh,
                    tokenGeneration = token.sessionGeneration,
                    currentGeneration = authEpoch.get(),
                    storedSessionLoggedIn = easPreferenceSource.getEasToken().isLogin()
                )
            ) {
                LogUtils.d("saveRefreshedEasToken: ignored stale refresh after logout")
                return
            }
            val storedToken = easPreferenceSource.getEasToken()
            token.sessionGeneration = EasSessionGenerationGuard.resolveCredentialScopeGeneration(
                storedToken = storedToken,
                incomingToken = token,
                currentGeneration = authEpoch.get(),
                nextGeneration = ::nextCredentialScopeGeneration
            )
            authEpoch.set(token.sessionGeneration)
            val mergedToken = mergeWithStoredEasToken(token)
            easPreferenceSource.saveEasToken(mergedToken)
            publishEasToken(mergedToken)
        }
    }

    fun saveEasTokenSync(token: EASToken) {
        saveEasToken(token)
    }

    private fun publishEasToken(token: EASToken) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            easTokenLiveData.value = token
        } else {
            easTokenLiveData.postValue(token)
        }
    }

    private fun mergeWithStoredEasToken(token: EASToken): EASToken {
        val stored = easPreferenceSource.getEasToken()
        if (!stored.isLogin() || stored.campus != token.campus) {
            return token
        }

        token.name = token.name?.takeIf { it.isNotBlank() } ?: stored.name
        token.stuId = token.stuId?.takeIf { it.isNotBlank() } ?: stored.stuId
        token.school = token.school?.takeIf { it.isNotBlank() } ?: stored.school
        token.major = token.major?.takeIf { it.isNotBlank() } ?: stored.major
        token.grade = token.grade?.takeIf { it.isNotBlank() } ?: stored.grade
        token.className = token.className?.takeIf { it.isNotBlank() } ?: stored.className
        token.picture = token.picture?.takeIf { it.isNotBlank() } ?: stored.picture
        token.id = token.id?.takeIf { it.isNotBlank() } ?: stored.id
        token.email = token.email?.takeIf { it.isNotBlank() } ?: stored.email
        token.phone = token.phone?.takeIf { it.isNotBlank() } ?: stored.phone
        token.sfxsx = token.sfxsx?.takeIf { it.isNotBlank() } ?: stored.sfxsx
        token.accessToken = token.accessToken?.takeIf { it.isNotBlank() } ?: stored.accessToken
        token.refreshToken = token.refreshToken?.takeIf { it.isNotBlank() } ?: stored.refreshToken
        token.electronicExpToken = token.electronicExpToken?.takeIf { it.isNotBlank() } ?: stored.electronicExpToken
        token.webBaseUrl = token.webBaseUrl?.takeIf { it.isNotBlank() } ?: stored.webBaseUrl
        token.username = token.username?.takeIf { it.isNotBlank() }
            ?: stored.username?.takeIf {
                stored.campus == EASToken.Campus.SHENZHEN && it.isNotBlank() && it != "value"
            }
        token.password = token.password?.takeIf { it.isNotBlank() } ?: stored.password
        if (stored.cookies.isNotEmpty()) {
            val mergedCookies = HashMap(stored.cookies)
            mergedCookies.putAll(token.cookies)
            token.cookies = mergedCookies
        }
        if (stored.webCookies.isNotEmpty()) {
            val mergedWebCookies = HashMap(stored.webCookies)
            mergedWebCookies.putAll(token.webCookies)
            token.webCookies = mergedWebCookies
        }
        return token
    }

    fun logout() {
        val previousToken: EASToken
        synchronized(tokenStateLock) {
            previousToken = easPreferenceSource.getEasToken()
            authEpoch.set(nextCredentialScopeGeneration())
            acceptServiceTokenRefresh = false
            easPreferenceSource.clearEasToken()
            publishEasToken(easPreferenceSource.getEasToken())
        }
        scoreCacheOwnerKey(previousToken)?.let { ownerKey ->
            thread(name = "score-cache-clear") {
                scoreCacheDao.deleteScoresByOwnerSync(ownerKey)
                scoreCacheDao.deleteTermsByOwnerSync(ownerKey)
                scoreCacheDao.deleteDetailsByOwnerSync(ownerKey)
            }
        }
        clearShenzhenWebViewCookies(previousToken)
    }

    private fun nextCredentialScopeGeneration(): Long {
        var generated: Long
        do {
            generated = credentialScopeRandom.nextLong() and Long.MAX_VALUE
        } while (generated == 0L || generated == authEpoch.get())
        return generated
    }

    private fun clearShenzhenWebViewCookies(token: EASToken) {
        if (token.campus != EASToken.Campus.SHENZHEN) return
        Handler(Looper.getMainLooper()).post {
            val manager = CookieManager.getInstance()
            manager.removeAllCookies {
                manager.flush()
            }
        }
    }


}
