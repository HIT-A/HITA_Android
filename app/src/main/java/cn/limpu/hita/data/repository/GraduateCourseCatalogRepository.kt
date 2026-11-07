package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.GraduateCourseRow
import cn.limpu.hita.data.model.eas.GraduateFilterOptions
import cn.limpu.hita.data.model.eas.GraduateSelectedCourses
import cn.limpu.hita.data.model.eas.GraduateSelectionCategory
import cn.limpu.hita.data.model.eas.GraduateSelectionPage
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogPage
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogSource
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.web.service.EASGraduateCatalogService
import com.limpu.component.data.DataState

/**
 * 威海研究生只读课程目录：真实可达的查询路由，不共享深圳选课池或远端提交。
 *
 * 数据映射到现有课程卡片模型，以复用目录界面、本地预览与跟课；但来源固定为
 * `SCHOOL`，绝不使研究生数据进入深圳自动提交任务。调用方负责会话代次与 LiveData 包装。
 */
internal class GraduateCourseCatalogRepository(
    private val captureSession: () -> EasQuerySession,
) {
    private fun requireSession(session: EasQuerySession): EASGraduateCatalogService {
        require(session.token.campus == EASToken.Campus.WEIHAI) { "研究生课程目录仅适用于威海校区" }
        require(session.token.stutype == EASToken.TYPE.GRAD) { "研究生课程目录仅适用于研究生账号" }
        return session.service as? EASGraduateCatalogService
            ?: throw IllegalStateException("当前账号不支持研究生课程目录")
    }

    suspend fun selectedCourses(
        session: EasQuerySession = captureSession(),
        term: TermItem,
    ): DataState<GraduateSelectedCourses> = session.whileCurrent {
        requireSession(session).getGraduateSelectedCoursesOnce(session.token, term.copyForQuery())
    }

    suspend fun candidates(
        term: TermItem,
        categoryId: String,
        keyword: String,
        campusId: String,
        collegeId: String,
        page: Int,
        pageSize: Int = 20,
        session: EasQuerySession = captureSession(),
    ): DataState<GraduateSelectionPage> = session.whileCurrent {
        requireSession(session).queryGraduateCandidatesOnce(
            session.token, term.copyForQuery(), categoryId, keyword, campusId, collegeId, page, pageSize,
        )
    }

    suspend fun schoolCourses(
        term: TermItem,
        keyword: String,
        campusId: String,
        page: Int,
        pageSize: Int = 20,
        session: EasQuerySession = captureSession(),
    ): DataState<GraduateSelectionPage> = session.whileCurrent {
        requireSession(session).queryGraduateSchoolCoursesOnce(
            session.token, term.copyForQuery(), keyword, campusId, page, pageSize,
        )
    }

    suspend fun filters(
        term: TermItem,
        session: EasQuerySession = captureSession(),
    ): DataState<GraduateFilterOptions> = session.whileCurrent {
        requireSession(session).getGraduateFiltersOnce(session.token, term.copyForQuery())
    }

    companion object {
        fun catalogPage(
            page: GraduateSelectionPage,
            categories: List<GraduateSelectionCategory> = emptyList(),
        ): ShenzhenCourseCatalogPage = ShenzhenCourseCatalogPage(
            items = page.rows.map { row -> card(row, categories.find { it.id == row.selectionMode }) },
            total = page.total,
            page = page.page,
            pageSize = page.pageSize,
        )

        /** `source=SCHOOL` 使 `ShenzhenCourseSelectionUiPolicy.canSelect()` 恒为 false。 */
        fun card(row: GraduateCourseRow, category: GraduateSelectionCategory?): ShenzhenCourseCatalogItem =
            ShenzhenCourseCatalogItem(
                id = row.taskNumber,
                taskId = row.taskNumber,
                courseId = row.taskNumber,
                courseCode = row.courseCode,
                courseName = row.courseName,
                teacher = row.teachers.joinToString("、"),
                credits = row.credits,
                totalHours = row.totalHours,
                courseNature = row.nature,
                courseCategory = row.category,
                offeringCollege = row.college,
                campus = row.campus,
                classNumber = row.classNumber,
                trainingLevel = row.trainingLevel,
                capacity = row.capacity,
                selectedCount = row.selectedCount,
                selectionPoolName = category?.name ?: row.selectionModeName,
                meetings = row.meetings,
                source = ShenzhenCourseCatalogSource.SCHOOL,
            )
    }
}
