package cn.limpu.hita.data.source.web.service

import androidx.lifecycle.LiveData
import com.limpu.component.data.DataState
import cn.limpu.hita.data.model.eas.*
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomItem
import java.util.*
import kotlinx.coroutines.flow.Flow

/**
 * 三校区教务系统的统一适配接口。
 *
 * 实现类负责消化各校区真实接口差异：
 * - 深圳：新版 JSON API；
 * - 本部：旧教务 / 实验系统 / Cookie；
 * - 威海：WebVPN 包裹下的旧教务。
 *
 * 调用方只能依赖这里定义的统一模型，不能依赖某个校区的原始字段。
 * 因此每个实现都必须尽量把原始响应规范化为 TermItem、CourseItem、ExamItem、
 * ClassroomItem 等本地模型。老教务解析分支很多是兼容旧系统返回格式的补丁，
 * 修改时应优先补 fixture/回归用例，避免为了“看起来更整洁”改变解析语义。
 */
interface EASService {
    /**
     * 登录
     * @param username 用户名
     * @param password 密码
     * @param code 验证码，可空
     * @return 登录结果
     */
    fun login(username: String, password: String, code: String?): LiveData<DataState<EASToken>>

    /**
     * 检查登录状态
     */
    fun loginCheck(token:EASToken):LiveData<DataState<Pair<Boolean,EASToken>>>

    /**
     * 获取可查询学期。
     *
     * 契约：返回的 TermItem.getCode() 必须能稳定标识该校区该学期，
     * Repository 会用它生成本地课表 code、缓存 key 和导入去重依据。
     */
    fun getAllTerms(token: EASToken):LiveData<DataState<List<TermItem>>>;

    /**
     * 单次远端学期查询；入口固定查询通道与培养类型，阻塞请求在 IO 执行。
     * 取消向上传播，在当前同步业务请求返回后停止后续步骤；不会立即中断 socket。
     * token 保留原引用以维持 Cookie 更新和既有会话代次守卫；缓存由调用方负责。
     */
    suspend fun getAllTermsOnce(token: EASToken): DataState<List<TermItem>>

    /**
     * 获取学期起始日期。
     *
     * 契约：返回值应尽量是教学第一周所在周的日期；Repository 会再归一化到周一零点。
     * 对本部/威海等旧系统，如果只能通过课表页面反推，应保留解析逻辑的兼容分支。
     */
    fun getStartDate(token: EASToken,term:TermItem):LiveData<DataState<Calendar>>;

    /**
     * 获取某学期的已选课程
     */
    fun getSubjectsOfTerm(token: EASToken, term: TermItem): LiveData<DataState<MutableList<TermSubject>>>
    /**
     * 获取个人总课表。
     *
     * 契约：无论原始来源是周课表、总课表、实验系统还是补充接口，
     * 最终都应转换为 CourseItem，并尽量补齐 name/code/teacher/classroom/weeks/dow/begin/last。
     * EASRepository 只做统一导入、去重和事件落库，不应再理解各校区原始 HTML/JSON 结构。
     */
    fun getTimetableOfTerm(term:TermItem, token: EASToken):LiveData<DataState<List<CourseItem>>>


    /**
     * 获取某学期的课表节次结构。
     *
     * 契约：返回列表下标与 CourseItem.begin/last 使用的节次编号保持一致。
     * 如果旧系统无法可靠返回完整结构，实现类应给出该校区当前可用的保守默认值。
     */
    fun getScheduleStructure(term: TermItem,isUndergraduate:Boolean?, token: EASToken):LiveData<DataState<MutableList<TimePeriodInDay>>>


    /** One terminal result; inputs are captured before IO and cancellation is never a fallback value. */
    suspend fun getStartDateOnce(token: EASToken, term: TermItem): DataState<Calendar>
    suspend fun getScheduleStructureOnce(term: TermItem, isUndergraduate: Boolean?, token: EASToken): DataState<MutableList<TimePeriodInDay>>
    suspend fun getSubjectsOfTermOnce(token: EASToken, term: TermItem): DataState<MutableList<TermSubject>>
    suspend fun getTimetableOfTermOnce(term: TermItem, token: EASToken): DataState<List<CourseItem>>

    /**
     * 获取教学楼列表
     */
    fun getTeachingBuildings(token: EASToken):LiveData<DataState<List<BuildingItem>>>

    /** 单次教学楼查询；阻塞网络在 IO 调度器执行，取消作为异常向上传播。 */
    suspend fun getTeachingBuildingsOnce(token: EASToken): DataState<List<BuildingItem>>


    /**
     * 查询空教室。
     *
     * 契约：各校区返回的 ClassroomItem.scheduleList 需要使用相同语义，
     * 这样本地缓存和 AI 工具才能在不关心校区的情况下读取。
     */
    fun queryEmptyClassroom(
        token: EASToken,
        term: TermItem,
        building: BuildingItem,
        weeks: List<String>
    ): LiveData<DataState<List<ClassroomItem>>>

    /**
     * 单次远端空教室查询；在入口复制学期、教学楼和周次，阻塞网络在 IO 执行。
     * 取消向上传播，并在当前同步请求返回后停止后续查询；不会立即中断 socket。
     * token 保留原引用以维持会话更新；缓存和观察者协调仍由调用方负责。
     */
    suspend fun queryEmptyClassroomOnce(
        token: EASToken,
        term: TermItem,
        building: BuildingItem,
        weeks: List<String>
    ): DataState<List<ClassroomItem>>

    /**
     * 获取最终成绩
     */
    fun getPersonalScores(
        term: TermItem,
        token: EASToken,
        testType: TestType
     ):LiveData<DataState<List<CourseScoreItem>>>

    /** Single score-list request. Optional summary enrichment is intentionally separate. */
    suspend fun getPersonalScoresOnce(
        term: TermItem,
        token: EASToken,
        testType: TestType,
    ): DataState<List<CourseScoreItem>>


    enum class TestType(val value:String){
        ALL("-1"), NORMAL("0"), RESIT("1"), RETAKE("2")
    }

    /**
     * 获取考试信息。
     *
     * 契约：可解析的考试必须提供 examDate 和 examTime，后续由 ExamEventMapper
     * 统一转换为本地 EventItem。暂不支持的校区应返回明确的失败状态或空结果，
     * 不要伪造考试时间。
     */
    fun getExamItems(
        token: EASToken,
        term: TermItem? = null
    ):LiveData<DataState<List<ExamItem>>>

    /** Single terminal exam result; blocking HTTP runs on IO and cancellation propagates. */
    suspend fun getExamItemsOnce(
        token: EASToken,
        term: TermItem? = null,
    ): DataState<List<ExamItem>>

    fun getSafePersonalInfo(token: EASToken): LiveData<DataState<EASToken>>

}

/**
 * Optional score capability implemented by sources that can fetch the academic summary
 * together with the score rows.  Callers can keep the ordinary [EASService] contract and
 * fall back to rows-only when a campus does not expose a summary endpoint.
 */
interface EASScoreSummaryService {
    fun getPersonalScoresWithSummary(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType,
    ): LiveData<DataState<ScoreQueryResult>>

    /** Cold, cancellable score-list then summary enrichment stream. */
    fun getPersonalScoresWithSummaryFlow(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType,
    ): Flow<DataState<ScoreQueryResult>>
}

/** Shenzhen-only capabilities kept behind a source-facing interface. */
interface EASShenzhenService : EASService {
    fun getShenzhenWebTerms(token: EASToken): LiveData<DataState<List<TermItem>>>
    suspend fun getShenzhenWebTermsOnce(token: EASToken): DataState<List<TermItem>>
    fun queryShenzhenAvailableCourses(
        token: EASToken,
        term: TermItem,
        pool: ShenzhenSelectionPool,
        keyword: String,
        page: Int,
        pageSize: Int,
    ): LiveData<DataState<ShenzhenCourseCatalogPage>>
    fun submitShenzhenCourseOnce(
        token: EASToken,
        job: CourseSelectionJob,
        course: CourseSelectionJobCourse,
    ): CourseSelectionCourseResult
    fun getShenzhenSelectedRequestIdsOnce(
        token: EASToken,
        job: CourseSelectionJob,
    ): Set<String>
    fun queryShenzhenSchoolCourses(
        token: EASToken,
        term: TermItem,
        studentType: String,
        keyword: String,
        page: Int,
        pageSize: Int,
    ): LiveData<DataState<ShenzhenCourseCatalogPage>>
    fun getShenzhenCourseRecommendations(
        token: EASToken,
        term: TermItem,
        pools: List<ShenzhenSelectionPool>,
        options: ShenzhenRecommendationOptions,
        requirements: List<ShenzhenCreditRequirement>,
    ): LiveData<DataState<ShenzhenCourseRecommendationResult>>

    fun getShenzhenCourseRecommendations(
        token: EASToken,
        term: TermItem,
        pools: List<ShenzhenSelectionPool>,
        options: ShenzhenRecommendationOptions,
    ): LiveData<DataState<ShenzhenCourseRecommendationResult>> =
        getShenzhenCourseRecommendations(token, term, pools, options, emptyList())
    fun getShenzhenCourseAttachments(
        token: EASToken,
        courseId: String,
        taskNumber: String,
    ): LiveData<DataState<List<ShenzhenCourseAttachment>>>
    fun getShenzhenGradeCourses(
        token: EASToken,
        term: TermItem,
    ): LiveData<DataState<List<ShenzhenGradeCourse>>>
    fun getShenzhenGradeAnalysis(
        token: EASToken,
        course: ShenzhenGradeCourse,
    ): LiveData<DataState<ShenzhenGradeAnalysis>>
    fun getShenzhenHistoricalTeacherFailureRates(
        token: EASToken,
        referenceTerm: TermItem,
        studentType: String,
        referenceCourse: ShenzhenCourseCatalogItem,
        yearsBack: Int,
    ): LiveData<DataState<ShenzhenHistoricalFailureReport>>

    fun getShenzhenHistoricalTeacherFailureRates(
        token: EASToken,
        referenceTerm: TermItem,
        studentType: String,
        referenceCourse: ShenzhenCourseCatalogItem,
    ): LiveData<DataState<ShenzhenHistoricalFailureReport>> =
        getShenzhenHistoricalTeacherFailureRates(
            token, referenceTerm, studentType, referenceCourse, 2,
        )
    fun getShenzhenTrainingPlans(token: EASToken): LiveData<DataState<List<ShenzhenTrainingPlan>>>
    fun getShenzhenCreditProgress(
        token: EASToken,
        selectedTerm: TermItem?,
        includeDetails: Boolean,
        includeCourseRecords: Boolean,
        trackCoursesOnly: Boolean,
    ): LiveData<DataState<ShenzhenCreditProgress>>

    fun getShenzhenCreditProgress(token: EASToken): LiveData<DataState<ShenzhenCreditProgress>> =
        getShenzhenCreditProgress(token, null, true, true, false)

    fun getShenzhenCreditProgress(
        token: EASToken,
        selectedTerm: TermItem?,
        includeDetails: Boolean,
    ): LiveData<DataState<ShenzhenCreditProgress>> =
        getShenzhenCreditProgress(token, selectedTerm, includeDetails, true, false)

    fun getShenzhenCreditProgress(
        token: EASToken,
        includeDetails: Boolean,
    ): LiveData<DataState<ShenzhenCreditProgress>> =
        getShenzhenCreditProgress(token, null, includeDetails, true, false)

    fun getShenzhenCreditProgress(
        token: EASToken,
        includeCourseRecords: Boolean,
        trackCoursesOnly: Boolean,
    ): LiveData<DataState<ShenzhenCreditProgress>> =
        getShenzhenCreditProgress(token, null, true, includeCourseRecords, trackCoursesOnly)
    fun getShenzhenTrainingPlanCourses(
        token: EASToken,
        plan: ShenzhenTrainingPlan,
    ): LiveData<DataState<ShenzhenTrainingPlanDetail>>
    fun getShenzhenCoursePlanningStartDate(
        token: EASToken,
        term: TermItem,
    ): LiveData<DataState<Calendar>>
    fun getShenzhenCoursePlanningScheduleStructure(
        token: EASToken,
        term: TermItem,
    ): LiveData<DataState<MutableList<TimePeriodInDay>>>
    fun getShenzhenSelectedCourses(
        token: EASToken,
        term: TermItem,
    ): LiveData<DataState<List<ShenzhenCourseCatalogItem>>>
    suspend fun getShenzhenCoursePlanningStartDateOnce(
        token: EASToken,
        term: TermItem,
    ): DataState<Calendar>
    suspend fun getShenzhenCoursePlanningScheduleStructureOnce(
        token: EASToken,
        term: TermItem,
    ): DataState<MutableList<TimePeriodInDay>>
}
