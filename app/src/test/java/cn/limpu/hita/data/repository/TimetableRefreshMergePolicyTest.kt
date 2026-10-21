package cn.limpu.hita.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TimetableRefreshMergePolicyTest {

    @Test
    fun plan_adoptsTimeAndPlaceChangesWhenLessonCountUnchanged() {
        val local = listOf(
            course("高等数学（A）", lessons = 2, from = mondayAt(8, 0), place = "正心楼 101")
        )
        val incoming = listOf(
            course("高等数学（A）", lessons = 2, from = mondayAt(10, 5), place = "诚意楼 202")
        )

        val plan = TimetableRefreshMergePolicy.plan(local, incoming, emptyMap(), NOW)

        assertFalse(plan.holdBatch)
        assertTrue(plan.adopt.isEmpty())
        assertEquals(1, plan.pendingUpdates.size)
        assertEquals(1, plan.updated.size)
        assertTrue(plan.updated.single().timeAdjusted)
        assertTrue(plan.updated.single().placeAdjusted)
        assertTrue(plan.kept.isEmpty())
        assertTrue(plan.decisions.isEmpty())
        assertTrue(plan.vetoes.isEmpty())
    }

    @Test
    fun plan_reportsNothingWhenSourceMatchesLocal() {
        val local = listOf(
            course("高等数学（A）", lessons = 2, from = mondayAt(8, 0)),
            course("计算机导论", lessons = 2, from = mondayAt(14, 0))
        )

        val plan = TimetableRefreshMergePolicy.plan(local, local, emptyMap(), NOW)

        // 无变化的匹配课不重写（避免课次 id 抖动与无效快照）
        assertTrue(plan.adopt.isEmpty())
        assertTrue(plan.replacedLocal.isEmpty())
        assertTrue(plan.updated.isEmpty())
        assertTrue(plan.added.isEmpty())
        assertTrue(plan.kept.isEmpty())
        assertTrue(plan.decisions.isEmpty())
        assertFalse(plan.hasChanges)
    }

    @Test
    fun plan_describesSlotLevelFieldChanges() {
        val local = listOf(
            MergeCourse(
                subjectId = "s1",
                name = "高等数学（A）",
                code = null,
                lessons = listOf(
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 8 * 60, week = 1, place = "正心楼 101"),
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 8 * 60, week = 2, place = "正心楼 101"),
                    lesson(dow = 3, fromNumber = 6, lastNumber = 7, clock = 14 * 60, week = 1)
                )
            )
        )
        val incoming = listOf(
            MergeCourse(
                subjectId = "s1",
                name = "高等数学（A）",
                code = null,
                lessons = listOf(
                    // 同一槽位：上课时间与地点都变了
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 10 * 60 + 5, week = 1, place = "诚意楼 202", teacher = "李四"),
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 10 * 60 + 5, week = 2, place = "诚意楼 202", teacher = "李四"),
                    // 原周三的课调到周五：减少一个槽位、新增一个槽位
                    lesson(dow = 5, fromNumber = 6, lastNumber = 7, clock = 14 * 60, week = 1)
                )
            )
        )

        val plan = TimetableRefreshMergePolicy.plan(local, incoming, emptyMap(), NOW)

        val change = plan.updated.single()
        val monday = change.slotChanges.single { it.dow == 1 && it.kind == SlotChangeKind.ADJUSTED }
        assertEquals("10:05-10:50", monday.clock)
        assertEquals("08:00-08:45", monday.clockBefore)
        assertEquals("诚意楼 202", monday.place)
        assertEquals("正心楼 101", monday.placeBefore)
        assertEquals("李四", monday.teacher)
        assertEquals("张三", monday.teacherBefore)
        assertTrue(change.timeAdjusted)
        assertTrue(change.placeAdjusted)
        assertTrue(change.teacherAdjusted)

        val removed = change.slotChanges.single { it.kind == SlotChangeKind.REMOVED }
        assertEquals(3, removed.dow)
        assertEquals(6, removed.fromNumber)
        val added = change.slotChanges.single { it.kind == SlotChangeKind.ADDED }
        assertEquals(5, added.dow)
        assertEquals("14:00-14:45", added.clock)
    }

    @Test
    fun plan_reportsRenameAsChange() {
        val local = listOf(course("高等数学", code = "MATH1001", lessons = 2, from = mondayAt(8, 0)))
        val incoming = listOf(course("高等数学（A）", code = "MATH1001", lessons = 2, from = mondayAt(8, 0)))

        val plan = TimetableRefreshMergePolicy.plan(local, incoming, emptyMap(), NOW)

        val change = plan.updated.single()
        assertEquals("高等数学（A）", change.name)
        assertEquals("高等数学", change.previousName)
        assertTrue(change.slotChanges.isEmpty())
    }

    @Test
    fun plan_keptCoursesCarryReasonAndCounts() {
        val local = listOf(
            course("计算机导论", lessons = 2, from = mondayAt(8, 0)),
            course("信号与系统", lessons = 4, from = mondayAt(14, 0))
        )
        val incoming = listOf(
            course("信号与系统", lessons = 2, from = mondayAt(14, 0))
        )

        val plan = TimetableRefreshMergePolicy.plan(local, incoming, emptyMap(), NOW)

        val missing = plan.kept.single { it.name == "计算机导论" }
        assertEquals(CourseVetoReason.MISSING_FROM_SOURCE, missing.reason)
        assertEquals(2, missing.localLessonCount)
        assertEquals(0, missing.incomingLessonCount)
        val reduced = plan.kept.single { it.name == "信号与系统" }
        assertEquals(CourseVetoReason.LESSON_COUNT_REDUCED, reduced.reason)
        assertEquals(4, reduced.localLessonCount)
        assertEquals(2, reduced.incomingLessonCount)
    }

    @Test
    fun plan_adoptsAddedLessonsAndNewCourses() {
        val local = listOf(course("线性代数", lessons = 2, from = 1000))
        val incoming = listOf(
            course("线性代数", lessons = 3, from = 1000),
            course("大学物理", lessons = 2, from = 2000)
        )

        val plan = TimetableRefreshMergePolicy.plan(local, incoming, emptyMap(), NOW)

        assertTrue(plan.adopt.isEmpty())
        assertEquals(listOf("大学物理"), plan.added.map { it.name })
        assertEquals(1, plan.updated.single().lessonCountDelta)
        assertEquals(2, plan.pendingUpdates.size)
        assertTrue(plan.pendingUpdates.any { it.isAdded && it.name == "大学物理" })
        assertTrue(plan.vetoes.isEmpty())
    }

    @Test
    fun plan_keepsCourseMissingFromSource() {
        val local = listOf(
            course("高等数学（A）", lessons = 2, from = 1000),
            course("计算机导论", lessons = 2, from = 2000)
        )
        val incoming = listOf(course("高等数学（A）", lessons = 2, from = 1000))

        val plan = TimetableRefreshMergePolicy.plan(local, incoming, emptyMap(), NOW)

        // 高数与源端完全一致：不重写（无变化不采纳）
        assertTrue(plan.adopt.isEmpty())
        assertEquals(listOf("计算机导论"), plan.kept.map { it.name })
        // 首次观察到缺失 → 计入变更（点亮提示）；后续持续缺失不重复提醒
        assertTrue(plan.kept.single().newlyObserved)
        assertTrue(plan.hasChanges)
        assertTrue(plan.decisions.isEmpty())
        assertEquals(1, plan.vetoes.size)
        assertEquals(1, plan.vetoes.values.single().observationCount)
    }
    @Test
    fun plan_partiallyMergesWhenLessonCountReduced() {
        val local = listOf(
            MergeCourse(
                subjectId = "s1",
                name = "信号与系统",
                code = null,
                lessons = listOf(
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 8 * 60, week = 1),
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 8 * 60, week = 2),
                    lesson(dow = 3, fromNumber = 6, lastNumber = 7, clock = 14 * 60, week = 1),
                    lesson(dow = 3, fromNumber = 6, lastNumber = 7, clock = 14 * 60, week = 2)
                )
            )
        )
        val incoming = listOf(
            MergeCourse(
                subjectId = "s1",
                name = "信号与系统",
                code = null,
                lessons = listOf(
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 8 * 60, week = 1, place = "诚意楼 202"),
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 8 * 60, week = 2, place = "诚意楼 202")
                )
            )
        )

        val plan = TimetableRefreshMergePolicy.plan(local, incoming, emptyMap(), NOW)

        assertTrue(plan.adopt.isEmpty())
        assertTrue(plan.replacedLocal.isEmpty())
        assertTrue(plan.partialMerges.isEmpty())
        val kept = plan.kept.single()
        assertEquals(CourseVetoReason.LESSON_COUNT_REDUCED, kept.reason)
        assertEquals(4, kept.localLessonCount)
        assertEquals(2, kept.incomingLessonCount)
        assertEquals(1, plan.vetoes.size)
    }

    @Test
    fun plan_reducedCourseWithoutFieldChangesIsNotRewritten() {
        // 课次减少但槽位字段无变化：保留本地即可，不重复删插（第二次及以后的持续削减）
        val local = listOf(course("信号与系统", lessons = 4, from = 1000))
        val incoming = listOf(course("信号与系统", lessons = 2, from = 1000))

        val plan = TimetableRefreshMergePolicy.plan(local, incoming, emptyMap(), NOW)

        assertTrue(plan.adopt.isEmpty())
        assertTrue(plan.partialMerges.isEmpty())
        assertEquals(listOf("信号与系统"), plan.kept.map { it.name })
        assertEquals(1, plan.vetoes.size)
    }

    @Test
    fun plan_reportsPlaceFilledFromEmpty() {
        // 本地地点为空、源端补上：也要产生可见的字段变化（空侧非 null，由界面渲染占位符）
        val local = listOf(
            MergeCourse(
                subjectId = "s1",
                name = "高等数学（A）",
                code = null,
                lessons = listOf(
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 8 * 60, week = 1, place = "")
                )
            )
        )
        val incoming = listOf(
            MergeCourse(
                subjectId = "s1",
                name = "高等数学（A）",
                code = null,
                lessons = listOf(
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 8 * 60, week = 1)
                )
            )
        )

        val plan = TimetableRefreshMergePolicy.plan(local, incoming, emptyMap(), NOW)

        val adjusted = plan.updated.single().slotChanges.single { it.kind == SlotChangeKind.ADJUSTED }
        assertEquals("", adjusted.placeBefore)
        assertEquals("正心楼 101", adjusted.place)
        assertTrue(plan.updated.single().placeAdjusted)
    }

    @Test
    fun plan_reportsLessonCountChangeWhenWeeksUnknown() {
        // 无开学日期（weekOfTerm=0）时，同槽位加周无法表达为周次变化，用课次数兜底
        val local = listOf(
            MergeCourse(
                subjectId = "s1",
                name = "高等数学（A）",
                code = null,
                lessons = listOf(
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 8 * 60, week = 1)
                        .copy(weekOfTerm = 0)
                )
            )
        )
        val incoming = listOf(
            MergeCourse(
                subjectId = "s1",
                name = "高等数学（A）",
                code = null,
                lessons = listOf(
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 8 * 60, week = 1)
                        .copy(weekOfTerm = 0),
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 8 * 60, week = 2)
                        .copy(weekOfTerm = 0)
                )
            )
        )

        val plan = TimetableRefreshMergePolicy.plan(local, incoming, emptyMap(), NOW)

        val adjusted = plan.updated.single().slotChanges.single { it.kind == SlotChangeKind.ADJUSTED }
        assertEquals(1, adjusted.lessonCountBefore)
        assertEquals(2, adjusted.lessonCount)
        assertNull(adjusted.weeksBefore)
        assertTrue(plan.updated.single().timeAdjusted)
    }

    @Test
    fun plan_matchesRenamedCourseByCode() {
        val local = listOf(course("高等数学", code = "MATH1001", lessons = 2, from = 1000))
        val incoming = listOf(course("高等数学（A）", code = "MATH1001", lessons = 2, from = 1000))

        val plan = TimetableRefreshMergePolicy.plan(local, incoming, emptyMap(), NOW)

        assertTrue(plan.adopt.isEmpty())
        assertEquals(1, plan.pendingUpdates.size)
        assertTrue(plan.kept.isEmpty())
        assertEquals("高等数学（A）", plan.updated.single().name)
    }

    @Test
    fun plan_holdsBatchWhenOverlapTooLow() {
        val local = (1..4).map { course("本地课程$it", lessons = 2, from = it * 1000L) }
        val incoming = (1..4).map { course("源端课程$it", lessons = 2, from = it * 1000L) }

        val plan = TimetableRefreshMergePolicy.plan(local, incoming, emptyMap(), NOW)

        assertTrue(plan.holdBatch)
        assertTrue(plan.adopt.isEmpty())
        assertEquals(4, plan.holdLocalCount)
        assertEquals(4, plan.holdIncomingCount)
        assertEquals(0, plan.holdMatchedCount)
    }

    @Test
    fun plan_doesNotRestoreCoursesIntoExistingEmptyTimetable() {
        val incoming = listOf(course("高等数学", lessons = 2, from = 1000))
        val plan = TimetableRefreshMergePolicy.plan(emptyList(), incoming, emptyMap(), NOW)
        assertTrue(plan.adopt.isEmpty())
        assertEquals(1, plan.pendingUpdates.size)
        assertTrue(plan.replacedLocal.isEmpty())
    }

    @Test
    fun plan_adoptsAllWhenLocalIsEmpty() {
        val incoming = (1..5).map { course("课程$it", lessons = 2, from = it * 1000L) }

        val plan = TimetableRefreshMergePolicy.plan(emptyList(), incoming, emptyMap(), NOW, allowInitialImport = true)

        assertFalse(plan.holdBatch)
        assertEquals(5, plan.adopt.size)
    }

    @Test
    fun plan_upgradesVetoToDecisionAfterThreeObservationsSpanning48Hours() {
        val local = listOf(
            course("计算机导论", lessons = 2, from = 1000),
            course("高等数学（A）", lessons = 2, from = 2000),
            course("大学物理", lessons = 2, from = 3000)
        )
        val incoming = listOf(
            course("高等数学（A）", lessons = 2, from = 2000),
            course("大学物理", lessons = 2, from = 3000)
        )
        var vetoes = emptyMap<String, CourseVetoRecord>()

        // 第一次：仅记录，静默保留
        var plan = TimetableRefreshMergePolicy.plan(local, incoming, vetoes, NOW)
        vetoes = plan.vetoes
        assertTrue(plan.decisions.isEmpty())
        assertEquals(listOf("计算机导论"), plan.kept.map { it.name })

        // 第二次：一小时后，仍静默保留
        plan = TimetableRefreshMergePolicy.plan(local, incoming, vetoes, NOW + HOUR)
        vetoes = plan.vetoes
        assertTrue(plan.decisions.isEmpty())

        // 第三次：满 48 小时且累计 3 次观察 → 升级为待用户确认
        plan = TimetableRefreshMergePolicy.plan(local, incoming, vetoes, NOW + TWO_DAYS)

        assertEquals(1, plan.decisions.size)
        val decision = plan.decisions.single()
        assertEquals("计算机导论", decision.name)
        assertEquals(CourseVetoReason.MISSING_FROM_SOURCE, decision.reason)
        assertNull(decision.incoming)
        assertEquals(3, decision.observationCount)
    }

    @Test
    fun plan_upgradesReducedLessonCountToDecisionAfterWindow() {
        val local = listOf(course("信号与系统", lessons = 4, from = 1000))
        val incoming = listOf(course("信号与系统", lessons = 2, from = 1000))
        var vetoes = emptyMap<String, CourseVetoRecord>()
        var plan = TimetableRefreshMergePolicy.plan(local, incoming, vetoes, NOW)
        vetoes = plan.vetoes
        plan = TimetableRefreshMergePolicy.plan(local, incoming, vetoes, NOW + HOUR)
        vetoes = plan.vetoes
        plan = TimetableRefreshMergePolicy.plan(local, incoming, vetoes, NOW + TWO_DAYS)

        val decision = plan.decisions.single()
        assertEquals(CourseVetoReason.LESSON_COUNT_REDUCED, decision.reason)
        assertEquals(4, decision.localLessonCount)
        assertEquals(2, decision.incoming?.lessonCount)
    }

    @Test
    fun plan_clearsVetoWhenCourseReappears() {
        val local = listOf(
            course("计算机导论", lessons = 2, from = 1000),
            course("高等数学（A）", lessons = 2, from = 2000)
        )
        val partial = listOf(course("高等数学（A）", lessons = 2, from = 2000))
        var vetoes = TimetableRefreshMergePolicy
            .plan(local, partial, emptyMap(), NOW)
            .vetoes
        assertTrue(vetoes.isNotEmpty())

        val plan = TimetableRefreshMergePolicy.plan(local, local, vetoes, NOW + HOUR)

        assertTrue(plan.vetoes.isEmpty())
        assertTrue(plan.kept.isEmpty())
        // 回归的课程与本地一致：无需重写即恢复（veto 清除即可）
        assertTrue(plan.adopt.isEmpty())
        // 回归课程的 key 进入匹配集合，供仓库层清理过期待确认项
        assertEquals(2, plan.matchedCourseKeys.size)
    }

    @Test
    fun plan_doesNotHoldBatchWhenMissingCoursesAreAlreadyTracked() {
        val local = listOf(
            course("计算机导论", lessons = 2, from = 1000),
            course("大学物理", lessons = 2, from = 2000)
        )
        val incoming = listOf(course("全新课程", lessons = 2, from = 3000))

        // 首次：出现未追踪的新缺失 → 整批挂起等用户决策
        val first = TimetableRefreshMergePolicy.plan(local, incoming, emptyMap(), NOW)
        assertTrue(first.holdBatch)
        assertEquals(2, first.holdUnmatchedLocal.size)

        // 用户选择"保留当前课表"后缺失被追踪，同一模式再次出现不再挂起
        val vetoes = first.holdUnmatchedLocal.associate { course ->
            TimetableRefreshMergePolicy.courseKey(course.name, course.code) to
                CourseVetoRecord(
                    courseKey = TimetableRefreshMergePolicy.courseKey(course.name, course.code),
                    name = course.name,
                    firstSeenMillis = NOW,
                    lastSeenMillis = NOW,
                    observationCount = 1
                )
        }
        val plan = TimetableRefreshMergePolicy.plan(local, incoming, vetoes, NOW + HOUR)

        assertFalse(plan.holdBatch)
        assertEquals(2, plan.kept.size)
        assertEquals(listOf("全新课程"), plan.added.map { it.name })
    }

    @Test
    fun inheritBlankLocalFields_teacherAndPlaceFlapProducesNoChange() {
        // 实验课字段抖动：本地有教师/地点，源端某周返回空 → 沿用本地 → 无变化、不采纳、不提示
        val local = listOf(
            MergeCourse(
                subjectId = "s1",
                name = "物理实验",
                code = null,
                lessons = listOf(
                    lesson(dow = 3, fromNumber = 7, lastNumber = 8, clock = 16 * 60, week = 1, place = "格物楼 201", teacher = "张三"),
                    lesson(dow = 3, fromNumber = 7, lastNumber = 8, clock = 16 * 60, week = 2, place = "格物楼 201", teacher = "张三")
                )
            )
        )
        val incoming = listOf(
            MergeCourse(
                subjectId = "s1",
                name = "物理实验",
                code = null,
                lessons = listOf(
                    lesson(dow = 3, fromNumber = 7, lastNumber = 8, clock = 16 * 60, week = 1, place = "", teacher = ""),
                    lesson(dow = 3, fromNumber = 7, lastNumber = 8, clock = 16 * 60, week = 2, place = "格物楼 201", teacher = "张三")
                )
            )
        )

        val protectedIncoming = incoming.map { course ->
            course.copy(
                lessons = TimetableRefreshMergePolicy.inheritBlankLocalFields(
                    local = local.single().lessons,
                    incoming = course.lessons
                )
            )
        }

        assertEquals("张三", protectedIncoming.single().lessons[0].teacher)
        assertEquals("格物楼 201", protectedIncoming.single().lessons[0].place)
        val plan = TimetableRefreshMergePolicy.plan(local, protectedIncoming, emptyMap(), NOW)
        assertFalse(plan.hasChanges)
        assertTrue(plan.adopt.isEmpty())
        assertTrue(plan.updated.isEmpty())
    }

    @Test
    fun inheritBlankLocalFields_preservesFieldsWhenCourseAdoptedForRealChange() {
        // 同一刷新里时间真变 + 教师/地点抖动：课程被采纳，但教师/地点保留本地值、不作为变更上报
        val local = listOf(
            MergeCourse(
                subjectId = "s1",
                name = "物理实验",
                code = null,
                lessons = listOf(
                    lesson(dow = 3, fromNumber = 7, lastNumber = 8, clock = 16 * 60, week = 1, place = "格物楼 201", teacher = "张三"),
                    lesson(dow = 3, fromNumber = 7, lastNumber = 8, clock = 16 * 60, week = 2, place = "格物楼 201", teacher = "张三")
                )
            )
        )
        val incoming = listOf(
            MergeCourse(
                subjectId = "s1",
                name = "物理实验",
                code = null,
                lessons = listOf(
                    lesson(dow = 3, fromNumber = 7, lastNumber = 8, clock = 18 * 60 + 30, week = 1, place = "", teacher = ""),
                    lesson(dow = 3, fromNumber = 7, lastNumber = 8, clock = 18 * 60 + 30, week = 2, place = "", teacher = "")
                )
            )
        )

        val protectedIncoming = incoming.map { course ->
            course.copy(
                lessons = TimetableRefreshMergePolicy.inheritBlankLocalFields(
                    local = local.single().lessons,
                    incoming = course.lessons
                )
            )
        }

        val plan = TimetableRefreshMergePolicy.plan(local, protectedIncoming, emptyMap(), NOW)
        assertTrue(plan.adopt.isEmpty())
        assertEquals(1, plan.pendingUpdates.size)
        val change = plan.updated.single()
        assertTrue(change.timeAdjusted)
        assertFalse(change.placeAdjusted)
        assertFalse(change.teacherAdjusted)
        assertTrue(plan.pendingUpdates.single().incoming.lessons.all { it.teacher == "张三" && it.place == "格物楼 201" })
    }

    @Test
    fun inheritBlankLocalFields_doesNotFillDifferentSlotOrWeek() {
        // 不同槽位/不同周次的空字段不沿用，避免误填
        val local = listOf(
            MergeCourse(
                subjectId = "s1",
                name = "高等数学（A）",
                code = null,
                lessons = listOf(
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 8 * 60, week = 1, place = "正心楼 101", teacher = "张三")
                )
            )
        )
        val incoming = listOf(
            MergeCourse(
                subjectId = "s1",
                name = "高等数学（A）",
                code = null,
                lessons = listOf(
                    // 同槽位不同周：不沿用
                    lesson(dow = 1, fromNumber = 1, lastNumber = 2, clock = 8 * 60, week = 2, place = "", teacher = ""),
                    // 同周不同槽位：不沿用
                    lesson(dow = 3, fromNumber = 6, lastNumber = 7, clock = 14 * 60, week = 1, place = "", teacher = "")
                )
            )
        )

        val protectedIncoming = TimetableRefreshMergePolicy.inheritBlankLocalFields(
            local = local.single().lessons,
            incoming = incoming.single().lessons
        )

        assertEquals("", protectedIncoming[0].teacher)
        assertEquals("", protectedIncoming[0].place)
        assertEquals("", protectedIncoming[1].teacher)
        assertEquals("", protectedIncoming[1].place)
    }

    @Test
    fun plan_keepsParallelCoursesSeparateWhenOnlyTheNormalizedNameMatches() {
        val local = listOf(
            course("高等数学（A）", code = "MATH-A", lessons = 2, from = 1_000),
            course("高等数学（B）", code = "MATH-B", lessons = 2, from = 5_000),
        )
        val incoming = listOf(
            course("高等数学（A）", code = "MATH-A", lessons = 2, from = 1_000),
            course("高等数学（B）", code = "MATH-B", lessons = 2, from = 5_000, place = "正心楼 202"),
        )

        val plan = TimetableRefreshMergePolicy.plan(local, incoming, emptyMap(), NOW)

        assertFalse(plan.holdBatch)
        assertTrue(plan.kept.isEmpty())
        assertEquals(setOf("code:MATH-A", "code:MATH-B"), plan.matchedCourseKeys)
        assertEquals(listOf("高等数学（B）"), plan.pendingUpdates.map { it.name })
    }

    private fun course(
        name: String,
        code: String? = null,
        lessons: Int,
        from: Long,
        place: String = "正心楼 101"
    ) = MergeCourse(
        subjectId = "subject-$name",
        name = name,
        code = code,
        lessons = (0 until lessons).map { index ->
            MergeLesson(
                name = name,
                place = place,
                teacher = "张三",
                fromMillis = from + index * WEEK_MILLIS,
                toMillis = from + index * WEEK_MILLIS + 45 * 60 * 1000,
                fromNumber = index + 1,
                lastNumber = 2,
                weekOfTerm = index + 1
            )
        }
    )

    /** 2026-09-07（周一）当天时刻。 */
    private fun mondayAt(hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 7, hour, minute, 0)
        }
        check(calendar.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY) {
            "fixture date must be a Monday"
        }
        return calendar.timeInMillis
    }

    private fun lesson(
        dow: Int,
        fromNumber: Int,
        lastNumber: Int,
        clock: Int,
        week: Int,
        place: String = "正心楼 101",
        teacher: String = "张三"
    ) = MergeLesson(
        name = "高等数学（A）",
        place = place,
        teacher = teacher,
        fromMillis = mondayAt(clock / 60, clock % 60) +
            (dow - 1) * DAY_MILLIS + (week - 1) * WEEK_MILLIS,
        toMillis = mondayAt(clock / 60, clock % 60) + 45 * 60 * 1000 +
            (dow - 1) * DAY_MILLIS + (week - 1) * WEEK_MILLIS,
        fromNumber = fromNumber,
        lastNumber = lastNumber,
        weekOfTerm = week
    )

    private companion object {
        const val NOW = 1_000_000_000_000L
        const val HOUR = 60 * 60 * 1000L
        const val TWO_DAYS = 48 * 60 * 60 * 1000L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val WEEK_MILLIS = 7 * DAY_MILLIS
    }
}
