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
import cn.limpu.hita.data.source.preference.EasCredential
import cn.limpu.hita.data.source.preference.EasCredentialStore
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

@Singleton
class EASRepository @Inject constructor(
    application: Application,
    private val easPreferenceSource: EasPreferenceSource,
    private val easCredentialStore: EasCredentialStore,
    private val timetablePreferenceSource: TimetablePreferenceSource,
    private val timetableChangeStore: TimetableChangeStore,
    private val timetableMutationLock: TimetableMutationLock
) : ShenzhenCourseSelectionGateway {
    private val appContext = application.applicationContext
    private val tokenStateLock = Any()
    private val authEpoch = AtomicLong(easPreferenceSource.getEasToken().sessionGeneration)
    private val credentialScopeRandom = SecureRandom()
    private val autoImportInProgress = AtomicBoolean(false)
    @Volatile private var acceptServiceTokenRefresh = easPreferenceSource.getEasToken().isLogin()
    private val shenzhenService: EASWebSource = EASWebSource(
        onTokenRefreshed = { token -> saveRefreshedEasToken(token) },
        credentialProvider = { campus, username -> easCredentialStore.get(campus, username) }
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
    private val courseSelectionExecutionTokens =
        CourseSelectionExecutionTokenStore(easPreferenceSource::getEasToken)

    // ===== 子仓库（按校区/功能拆分，原 EASRepository 方法的实际实现） =====
    private val shenzhenRepository = EASShenzhenRepository(
        shenzhenService = shenzhenService,
        easPreferenceSource = easPreferenceSource,
        scoreCacheDao = scoreCacheDao,
        gson = gson,
        appContext = appContext,
        courseSelectionExecutionTokens = courseSelectionExecutionTokens,
        tokenStateLock = tokenStateLock,
        authEpoch = authEpoch,
        publishEasToken = ::publishEasToken,
        nextCredentialScopeGeneration = ::nextCredentialScopeGeneration,
        scoreCacheOwnerKey = ::scoreCacheOwnerKey,
    )
    private val timetableImportRepository = EASTimetableImportRepository(
        getService = ::getService,
        easPreferenceSource = easPreferenceSource,
        eventItemDao = eventItemDao,
        timetableDao = timetableDao,
        subjectDao = subjectDao,
        timetableSnapshotStore = timetableSnapshotStore,
        timetableMutationLock = timetableMutationLock,
        timetableChangeStore = timetableChangeStore,
        timetablePreferenceSource = timetablePreferenceSource,
        appContext = appContext,
        authEpoch = authEpoch,
        autoImportInProgress = autoImportInProgress,
    )

    companion object {
        private const val LOGIN_ENRICH_MAX_RETRIES = 3
        private const val LOGIN_ENRICH_RETRY_DELAY_MS = 800L
        private const val JW_DIRECT_BASE_URL = "https://jw.hitsz.edu.cn"
        private const val JW_PROXY_BASE_URL = "https://jw-hitsz-edu-cn.hitsz.edu.cn"
        private const val SCORE_CACHE_RETENTION_MS = 365L * 24L * 60L * 60L * 1000L
    }

    init {
        runCatching { easCredentialStore.migrateLegacyIfNeeded(easPreferenceSource) }
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

    // ===== 委托给 shenzhenRepository =====
    override suspend fun beginExecution(job: CourseSelectionJob,
        owner: Any) = shenzhenRepository.beginExecution(job, owner)
    override suspend fun endExecution(job: CourseSelectionJob,
        owner: Any) = shenzhenRepository.endExecution(job, owner)
    override suspend fun submitOnce(job: CourseSelectionJob,
        course: CourseSelectionJobCourse): CourseSelectionCourseResult = shenzhenRepository.submitOnce(job, course)
    override suspend fun selectedRequestIds(job: CourseSelectionJob) = shenzhenRepository.selectedRequestIds(job)
    internal fun currentCourseSelectionCredentialScopeGeneration() = shenzhenRepository.currentCourseSelectionCredentialScopeGeneration()

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
            val credentialToSave = if (
                campus == EASToken.Campus.SHENZHEN &&
                !username.trim().startsWith("{")
            ) {
                EasCredential(campus, username, password)
            } else {
                null
            }
            if ((campus == EASToken.Campus.BENBU || campus == EASToken.Campus.WEIHAI) &&
                password.isNotBlank()
            ) {
                token.electronicExpToken = password
            }
            enrichLoginToken(
                result,
                token,
                campus,
                expectedEpoch = expectedEpoch,
                credentialsToSave = credentialToSave
            )
            result.removeSource(loginSource)
        }
        return result
    }

    private fun enrichLoginToken(
        result: MediatorLiveData<DataState<Boolean>>,
        token: EASToken,
        campus: EASToken.Campus,
        attempt: Int = 0,
        expectedEpoch: Long,
        credentialsToSave: EasCredential? = null
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
                            expectedEpoch,
                            credentialsToSave
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
            if (saveEasToken(finalToken, expectedEpoch, credentialsToSave)) {
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
        week: Int,
        useCache: Boolean = true,
    ): LiveData<DataState<List<ClassroomItem>>> {
        val easToken = easPreferenceSource.getEasToken()
        LogUtils.d("queryEmptyClassroom: isLogin=${easToken.isLogin()}, term=${term.getCode()}, building=${buildingItem.name}")
        if (easToken.isLogin()) {
            val result = MediatorLiveData<DataState<List<ClassroomItem>>>()
            val hasCachedResult = AtomicBoolean(false)
            if (useCache) {
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
            }
            val remote = getService(easToken.campus).queryEmptyClassroom(
                easToken,
                term,
                buildingItem,
                listOf(week.toString())
            )
            result.addSource(remote) { state ->
                if (useCache && hasCachedResult.get() && state.state != DataState.STATE.SUCCESS) {
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
    // ===== 委托给 timetableImportRepository =====
    fun startImportTimetableOfTerm(term: TermItem,
        startDate: Calendar,
        schedule: List<TimePeriodInDay>,//课表结构
        importTimetableLiveData: MediatorLiveData<DataState<Boolean>>,
        importMode: TimetableImportMode = TimetableImportMode.REPLACE) = timetableImportRepository.startImportTimetableOfTerm(term, startDate, schedule, importTimetableLiveData, importMode)
    fun startAutoImportCurrentTimetable(isUndergraduate: Boolean,
        onResult: ((Boolean) -> Unit)? = null) = timetableImportRepository.startAutoImportCurrentTimetable(isUndergraduate, onResult)

    // ===== 委托给 shenzhenRepository =====
    fun isSubjectMetaSupported(campus: EASToken.Campus = easPreferenceSource.getEasToken().campus): Boolean = shenzhenRepository.isSubjectMetaSupported(campus)
    fun hasShenzhenWebSession(): Boolean = shenzhenRepository.hasShenzhenWebSession()
    fun getShenzhenWebTerms(): LiveData<DataState<List<TermItem>>> = shenzhenRepository.getShenzhenWebTerms()
    fun queryShenzhenAvailableCourses(term: TermItem,
        pool: ShenzhenSelectionPool,
        keyword: String,
        page: Int,
        pageSize: Int = 20): LiveData<DataState<ShenzhenCourseCatalogPage>> = shenzhenRepository.queryShenzhenAvailableCourses(term, pool, keyword, page, pageSize)
    fun queryShenzhenSchoolCourses(term: TermItem,
        studentType: String,
        keyword: String,
        page: Int,
        pageSize: Int = 20): LiveData<DataState<ShenzhenCourseCatalogPage>> = shenzhenRepository.queryShenzhenSchoolCourses(term, studentType, keyword, page, pageSize)
    fun getShenzhenCourseRecommendations(term: TermItem,
        pools: List<ShenzhenSelectionPool>,
        options: ShenzhenRecommendationOptions): LiveData<DataState<ShenzhenCourseRecommendationResult>> = shenzhenRepository.getShenzhenCourseRecommendations(term, pools, options)
    fun getShenzhenSelectedCourses(term: TermItem) = shenzhenRepository.getShenzhenSelectedCourses(term)
    fun getShenzhenCourseAttachments(course: ShenzhenCourseCatalogItem): LiveData<DataState<List<ShenzhenCourseAttachment>>> = shenzhenRepository.getShenzhenCourseAttachments(course)
    fun getShenzhenGradeCourses(term: TermItem): LiveData<DataState<List<ShenzhenGradeCourse>>> = shenzhenRepository.getShenzhenGradeCourses(term)
    fun getShenzhenGradeAnalysis(course: ShenzhenGradeCourse): LiveData<DataState<ShenzhenGradeAnalysis>> = shenzhenRepository.getShenzhenGradeAnalysis(course)
    fun getShenzhenHistoricalTeacherFailureRates(referenceTerm: TermItem,
        studentType: String,
        referenceCourse: ShenzhenCourseCatalogItem,
        yearsBack: Int = 2): LiveData<DataState<ShenzhenHistoricalFailureReport>> = shenzhenRepository.getShenzhenHistoricalTeacherFailureRates(referenceTerm, studentType, referenceCourse, yearsBack)
    fun getShenzhenTrainingPlans(): LiveData<DataState<List<ShenzhenTrainingPlan>>> = shenzhenRepository.getShenzhenTrainingPlans()
    fun getShenzhenCreditProgress(term: TermItem? = null): LiveData<DataState<ShenzhenCreditProgress>> = shenzhenRepository.getShenzhenCreditProgress(term)
    fun getShenzhenTrainingPlanCourses(plan: ShenzhenTrainingPlan): LiveData<DataState<ShenzhenTrainingPlanDetail>> = shenzhenRepository.getShenzhenTrainingPlanCourses(plan)
    fun downloadShenzhenCourseAttachment(attachment: ShenzhenCourseAttachment): Long = shenzhenRepository.downloadShenzhenCourseAttachment(attachment)
    fun getShenzhenCoursePlanningStartDate(term: TermItem): LiveData<DataState<Calendar>> = shenzhenRepository.getShenzhenCoursePlanningStartDate(term)
    fun getShenzhenCoursePlanningScheduleStructure(term: TermItem): LiveData<DataState<MutableList<TimePeriodInDay>>> = shenzhenRepository.getShenzhenCoursePlanningScheduleStructure(term)
    fun getShenzhenRecommendationTracks(): LiveData<DataState<List<ShenzhenRecommendationTrack>>> = shenzhenRepository.getShenzhenRecommendationTracks()

    fun getHoaCampus(@Suppress("UNUSED_PARAMETER") campus: EASToken.Campus = easPreferenceSource.getEasToken().campus): String {
        return "shenzhen"
    }

    fun getEasToken(): EASToken {
        return easPreferenceSource.getEasToken()
    }

    // ===== 委托给 timetableImportRepository =====
    fun getTimetableSnapshots(term: TermItem): LiveData<DataState<List<TimetableVersionSnapshot>>> = timetableImportRepository.getTimetableSnapshots(term)
    fun restoreTimetableSnapshot(snapshotId: String): LiveData<DataState<TimetableVersionSnapshot>> = timetableImportRepository.restoreTimetableSnapshot(snapshotId)

    fun observeEasToken(): LiveData<EASToken> {
        return easTokenLiveData
    }

    private fun saveEasToken(
        token: EASToken,
        expectedEpoch: Long = authEpoch.get(),
        credentialsToSave: EasCredential? = null
    ): Boolean = synchronized(tokenStateLock) {
        if (authEpoch.get() != expectedEpoch) {
            LogUtils.d("saveEasToken: ignored result from an operation cancelled by logout")
            return@synchronized false
        }
        credentialsToSave?.let { easCredentialStore.save(it.campus, it.username, it.password) }
        token.password = null
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
            token.password = null
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
        if (!stored.isLogin() ||
            stored.campus != token.campus ||
            EasSessionGenerationGuard.blocksStoredSessionInheritance(stored, token)
        ) {
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

    fun forgetCredentials(campus: EASToken.Campus, username: String) {
        easCredentialStore.remove(campus, username)
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
