package cn.limpu.hita.data.source.web.eas

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.data.model.eas.*
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.source.web.service.EASService
import com.limpu.component.data.DataState
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.util.Calendar
import java.util.concurrent.Executors

/** Graduate authentication never delegates academic queries to undergraduate jwts. */
internal class WeihaiGraduateEASWebSource : EASService {
    companion object {
        private val executor = Executors.newSingleThreadExecutor()

        /** Blocking production probe, also used before the WebView delivers RESULT_OK. */
        fun validate(token: EASToken): DataState<EASToken> {
            if (token.cookies[WeihaiGraduateSessionPolicy.TICKET_COOKIE].isNullOrBlank()) {
                return DataState(DataState.STATE.NOT_LOGGED_IN, "登录已失效，请重新登录")
            }
            return try {
                val response = Jsoup.connect(WeihaiGraduateSessionPolicy.BASE_URL + "/user/me?vpn-12-o2-yjsgl.hitwh.edu.cn")
                    .cookies(token.cookies)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", WeihaiGraduateSessionPolicy.BASE_URL + "/authentication/main")
                    .header("Accept", "application/json")
                    .method(Connection.Method.POST).ignoreContentType(true).ignoreHttpErrors(true)
                    .followRedirects(false).timeout(15000).execute()
                when {
                    WeihaiGraduateSessionPolicy.isAuthenticationFailure(response.statusCode(), response.url().toString(), response.body()) ->
                        DataState(DataState.STATE.NOT_LOGGED_IN, "登录已失效，请重新登录")
                    response.statusCode() != 200 -> DataState(DataState.STATE.FETCH_FAILED, "登录验证暂不可用")
                    else -> {
                        val profile = WeihaiGraduateSessionPolicy.parseProfile(response.body())
                        if (profile == null) DataState(DataState.STATE.FETCH_FAILED, "研究生身份资料无效")
                        else if (!token.username.isNullOrBlank() && token.username != profile.account)
                            DataState(DataState.STATE.NOT_LOGGED_IN, "登录账号已变化，请重新登录")
                        else {
                            token.campus = EASToken.Campus.WEIHAI
                            token.stutype = EASToken.TYPE.GRAD
                            token.username = profile.account
                            token.stuId = profile.account
                            token.name = profile.name
                            token.cookies.putAll(response.cookies())
                            DataState(token, DataState.STATE.SUCCESS)
                        }
                    }
                }
            } catch (_: Exception) {
                DataState(DataState.STATE.FETCH_FAILED, "登录验证网络异常，请重试")
            }
        }
    }

    private fun <T> async(block: () -> DataState<T>): LiveData<DataState<T>> =
        MutableLiveData<DataState<T>>().also { result -> executor.execute { result.postValue(block()) } }
    private fun <T> unsupported(): DataState<T> = DataState(DataState.STATE.FETCH_FAILED, WeihaiGraduateSessionPolicy.UNSUPPORTED)
    private fun <T> unavailable(): LiveData<DataState<T>> = MutableLiveData(unsupported<T>())

    override fun login(username: String, password: String, code: String?): LiveData<DataState<EASToken>> = async {
        val cookies = runCatching {
            val json = JSONObject(username)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrNull()
        if (cookies == null || !WeihaiGraduateSessionPolicy.isAuthenticatedLanding(password, cookies))
            DataState(DataState.STATE.NOT_LOGGED_IN, "研究生登录会话无效")
        else validate(EASToken().apply { this.cookies.putAll(cookies) })
    }
    override fun getSafePersonalInfo(token: EASToken) = async { validate(token) }
    override fun loginCheck(token: EASToken): LiveData<DataState<Pair<Boolean, EASToken>>> = async {
        val result = validate(token)
        if (result.state == DataState.STATE.SUCCESS) DataState(Pair(true, token), DataState.STATE.SUCCESS)
        else DataState(result.state, result.message)
    }
    override fun getAllTerms(token: EASToken): LiveData<DataState<List<TermItem>>> = unavailable()
    override suspend fun getAllTermsOnce(token: EASToken): DataState<List<TermItem>> = unsupported()
    override fun getStartDate(token: EASToken, term: TermItem): LiveData<DataState<Calendar>> = unavailable()
    override suspend fun getStartDateOnce(token: EASToken, term: TermItem): DataState<Calendar> = unsupported()
    override fun getSubjectsOfTerm(token: EASToken, term: TermItem): LiveData<DataState<MutableList<TermSubject>>> = unavailable()
    override suspend fun getSubjectsOfTermOnce(token: EASToken, term: TermItem): DataState<MutableList<TermSubject>> = unsupported()
    override fun getTimetableOfTerm(term: TermItem, token: EASToken): LiveData<DataState<List<CourseItem>>> = unavailable()
    override suspend fun getTimetableOfTermOnce(term: TermItem, token: EASToken): DataState<List<CourseItem>> = unsupported()
    override fun getScheduleStructure(term: TermItem, isUndergraduate: Boolean?, token: EASToken): LiveData<DataState<MutableList<TimePeriodInDay>>> = unavailable()
    override suspend fun getScheduleStructureOnce(term: TermItem, isUndergraduate: Boolean?, token: EASToken): DataState<MutableList<TimePeriodInDay>> = unsupported()
    override fun getTeachingBuildings(token: EASToken): LiveData<DataState<List<BuildingItem>>> = unavailable()
    override suspend fun getTeachingBuildingsOnce(token: EASToken): DataState<List<BuildingItem>> = unsupported()
    override fun queryEmptyClassroom(token: EASToken, term: TermItem, building: BuildingItem, weeks: List<String>): LiveData<DataState<List<ClassroomItem>>> = unavailable()
    override suspend fun queryEmptyClassroomOnce(token: EASToken, term: TermItem, building: BuildingItem, weeks: List<String>): DataState<List<ClassroomItem>> = unsupported()
    override fun getPersonalScores(term: TermItem, token: EASToken, testType: EASService.TestType): LiveData<DataState<List<CourseScoreItem>>> = unavailable()
    override suspend fun getPersonalScoresOnce(term: TermItem, token: EASToken, testType: EASService.TestType): DataState<List<CourseScoreItem>> = unsupported()
    override fun getExamItems(token: EASToken, term: TermItem?): LiveData<DataState<List<ExamItem>>> = unavailable()
    override suspend fun getExamItemsOnce(token: EASToken, term: TermItem?): DataState<List<ExamItem>> = unsupported()
}
