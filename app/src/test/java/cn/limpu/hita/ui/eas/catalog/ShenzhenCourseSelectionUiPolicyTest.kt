package cn.limpu.hita.ui.eas.catalog

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.EasQueryScope
import cn.limpu.hita.data.model.eas.EasTermKey
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogSource
import cn.limpu.hita.data.model.eas.CourseSelectionJobStatus
import cn.limpu.hita.data.model.eas.TermItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShenzhenCourseSelectionUiPolicyTest {

    @Test
    fun `selection actions only appear for available source`() {
        assertTrue(ShenzhenCourseSelectionUiPolicy.canSelect(availableCourse()))
        assertFalse(ShenzhenCourseSelectionUiPolicy.canSelect(schoolCourse()))
    }

    @Test
    fun `schedule validation preserves five hundred millisecond and twenty four hour limits`() {
        assertEquals(
            CourseSelectionScheduleValidation.TOO_SOON,
            ShenzhenCourseSelectionUiPolicy.validateSchedule(now = 1_000L, scheduled = 1_499L)
        )
        assertEquals(
            CourseSelectionScheduleValidation.VALID,
            ShenzhenCourseSelectionUiPolicy.validateSchedule(now = 1_000L, scheduled = 1_500L)
        )
        assertEquals(
            CourseSelectionScheduleValidation.VALID,
            ShenzhenCourseSelectionUiPolicy.validateSchedule(now = 1_000L, scheduled = 86_401_000L)
        )
        assertEquals(
            CourseSelectionScheduleValidation.TOO_FAR,
            ShenzhenCourseSelectionUiPolicy.validateSchedule(now = 1_000L, scheduled = 86_401_001L)
        )
    }

    @Test
    fun `selection rejects a blank request id instead of falling back to task or catalog id`() {
        val draft = ShenzhenCourseSelectionDraftState()

        assertFalse(draft.toggle(availableCourse(requestId = "", taskId = "task-id", id = "catalog-id")))
        assertTrue(draft.requestIds().isEmpty())
    }

    @Test
    fun `selected courses follow card order instead of toggle order`() {
        val firstCard = availableCourse(requestId = "request-a", id = "catalog-a")
        val secondCard = availableCourse(requestId = "request-b", id = "catalog-b")
        val draft = ShenzhenCourseSelectionDraftState()

        assertTrue(draft.toggle(secondCard))
        assertTrue(draft.toggle(firstCard))

        assertEquals(
            listOf("request-a", "request-b"),
            draft.selectedCourses(listOf(firstCard, secondCard)).map { it.selectionRequestId }
        )
    }

    @Test
    fun `selection stops at twenty request ids`() {
        val draft = ShenzhenCourseSelectionDraftState()

        repeat(20) { index -> assertTrue(draft.toggle(availableCourse(requestId = "request-$index"))) }

        assertFalse(draft.toggle(availableCourse(requestId = "request-20")))
        assertEquals(20, draft.requestIds().size)
    }

    @Test
    fun `successful creation clears draft and failed creation keeps it`() {
        val card = availableCourse(requestId = "request-a")
        val draft = ShenzhenCourseSelectionDraftState()
        assertTrue(draft.toggle(card))

        val created = draft.create(listOf(card)) { courses ->
            assertEquals(listOf("request-a"), courses.map { it.selectionRequestId })
            "job-a"
        }

        assertEquals("job-a", created.getOrThrow())
        assertTrue(draft.requestIds().isEmpty())

        assertTrue(draft.toggle(card))
        val failed = draft.create(listOf(card)) { throw IllegalStateException("creation failed") }

        assertTrue(failed.isFailure)
        assertEquals(setOf("request-a"), draft.requestIds())
    }

    @Test
    fun `cancellation routes only a nonblank job id`() {
        val draft = ShenzhenCourseSelectionDraftState()
        var routedJobId: String? = null

        assertTrue(draft.routeCancellation("job-a") { jobId ->
            routedJobId = jobId
            true
        })
        assertEquals("job-a", routedJobId)

        assertFalse(draft.routeCancellation("") { error("blank job id must not route") })
    }

    @Test
    fun `schedule confirmation continues only on the identical target`() {
        val scope = EasQueryScope(
            campus = EASToken.Campus.SHENZHEN,
            sessionGeneration = 7L,
            ownerKey = "SHENZHEN:2021001",
            grade = "2021",
            studentType = EASToken.TYPE.UNDERGRAD,
            loggedIn = true,
            hasWebSession = true,
            hasBearer = true,
        )
        val target = CourseSelectionScheduleTarget(
            scope = scope,
            term = EasTermKey.of(TermItem("2025-2026", "2025-2026", "1", "秋季")),
            poolCode = "under",
            requestIds = setOf("request-a", "request-b"),
        )

        assertTrue(CourseSelectionScheduleContinuity.isSameTarget(target, target.copy()))
        assertTrue(
            CourseSelectionScheduleContinuity.isSameTarget(
                target,
                target.copy(requestIds = linkedSetOf("request-b", "request-a")),
            )
        )
        // 回到设置页期间换了学期或课程集合：不能沿用旧确认。
        assertFalse(
            CourseSelectionScheduleContinuity.isSameTarget(
                target,
                target.copy(requestIds = setOf("request-a")),
            )
        )
        assertFalse(
            CourseSelectionScheduleContinuity.isSameTarget(
                target,
                target.copy(term = EasTermKey("2025-2026", "2")),
            )
        )
        // 期间重新登录或换账号：即使身份键相同，也要重新确认。
        assertFalse(
            CourseSelectionScheduleContinuity.isSameTarget(
                target,
                target.copy(scope = scope.copy(sessionGeneration = scope.sessionGeneration + 1)),
            )
        )
        assertFalse(
            CourseSelectionScheduleContinuity.isSameTarget(
                target,
                target.copy(scope = scope.copy(ownerKey = "SHENZHEN:2021002")),
            )
        )
        assertFalse(CourseSelectionScheduleContinuity.isSameTarget(null, target))
        assertFalse(CourseSelectionScheduleContinuity.isSameTarget(target, null))
    }

    @Test
    fun `read only confirmation routes terminal jobs without a create action`() {
        val draft = ShenzhenCourseSelectionDraftState()
        var confirmedJobId: String? = null

        assertTrue(draft.routeReadOnlyConfirmation("job-a", CourseSelectionJobStatus.COMPLETED) { jobId ->
            confirmedJobId = jobId
        })
        assertEquals("job-a", confirmedJobId)

        assertFalse(draft.routeReadOnlyConfirmation("job-b", CourseSelectionJobStatus.WAITING) {
            error("waiting job must not route confirmation")
        })
    }

    private fun availableCourse(
        requestId: String = "request-id",
        taskId: String = "task-id",
        id: String = "catalog-id"
    ) = course(ShenzhenCourseCatalogSource.AVAILABLE, requestId, taskId, id)

    private fun schoolCourse() = course(ShenzhenCourseCatalogSource.SCHOOL)

    private fun course(
        source: ShenzhenCourseCatalogSource,
        requestId: String = "request-id",
        taskId: String = "task-id",
        id: String = "catalog-id"
    ) = ShenzhenCourseCatalogItem(
        id = id,
        taskId = taskId,
        selectionRequestId = requestId,
        courseCode = "CS101",
        courseName = "Course",
        source = source
    )
}
