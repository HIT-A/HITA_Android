package cn.limpu.hita.data.source.web.service

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.GraduateFilterOptions
import cn.limpu.hita.data.model.eas.GraduateSelectedCourses
import cn.limpu.hita.data.model.eas.GraduateSelectionPage
import cn.limpu.hita.data.model.eas.TermItem
import com.limpu.component.data.DataState

/**
 * 威海研究生只读课程规划能力。
 *
 * 与深圳的 [EASShenzhenService] 分开声明：两端的学期通道、动态选课类别、筛选数据不同，
 * 且研究生尚未接入远端提交。实现方只在 WEIHAI + GRAD 会话下生效。
 */
interface EASGraduateCatalogService {
    /** 读取指定学期已选课程、动态类别、学期上下文与小节作息。 */
    suspend fun getGraduateSelectedCoursesOnce(token: EASToken, term: TermItem): DataState<GraduateSelectedCourses>

    /** 按服务端动态类别查询可选任务，分页。 */
    suspend fun queryGraduateCandidatesOnce(
        token: EASToken,
        term: TermItem,
        categoryId: String,
        keyword: String,
        campusId: String,
        collegeId: String,
        page: Int,
        pageSize: Int = 20,
    ): DataState<GraduateSelectionPage>

    /** 全校研究生课表查询，只读。 */
    suspend fun queryGraduateSchoolCoursesOnce(
        token: EASToken,
        term: TermItem,
        keyword: String,
        campusId: String,
        page: Int,
        pageSize: Int = 20,
    ): DataState<GraduateSelectionPage>

    /** 全校课表筛选：节次、周次、校区、开课院系。 */
    suspend fun getGraduateFiltersOnce(token: EASToken, term: TermItem): DataState<GraduateFilterOptions>
}
