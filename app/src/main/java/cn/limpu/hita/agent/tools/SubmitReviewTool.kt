package cn.limpu.hita.agent.tools

import cn.limpu.hita.agent.remote.PrServerClient
import cn.limpu.hita.data.model.resource.CourseContributionOps
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SubmitReviewTool : OutcomeReActTool {
    override suspend fun executeWithOutcome(input: ReActToolInput): ReActToolOutcome {
        val courseCode = Regex(""""course_code"\s*:\s*"([^"]+)"""").find(input.actionInput)?.groupValues?.get(1) ?: ""
        val content = Regex(""""content"\s*:\s*"([^"]+)"""").find(input.actionInput)?.groupValues?.get(1) ?: ""
        val authorName = Regex(""""author_name"\s*:\s*"([^"]*)"""").find(input.actionInput)?.groupValues?.get(1) ?: "匿名"
        val lecturerName = Regex(""""lecturer_name"\s*:\s*"([^"]*)"""").find(input.actionInput)?.groupValues?.get(1) ?: ""
        val teacherName = Regex(""""teacher_name"\s*:\s*"([^"]*)"""").find(input.actionInput)?.groupValues?.get(1) ?: ""
        val courseName = Regex(""""course_name"\s*:\s*"([^"]*)"""").find(input.actionInput)?.groupValues?.get(1) ?: ""
        val reviewType = Regex(""""review_type"\s*:\s*"([^"]*)"""").find(input.actionInput)?.groupValues?.get(1) ?: "lecturer"
        val title = Regex(""""title"\s*:\s*"([^"]*)"""").find(input.actionInput)?.groupValues?.get(1) ?: "课程评价"

        if (courseCode.isBlank() || content.isBlank()) {
            return ReActToolOutcome.Failed("提交评价失败：缺少课程代码或评价内容", retryable = true)
        }

        val author = mapOf(
            "name" to authorName,
            "link" to "",
            "date" to SimpleDateFormat("yyyy-MM", Locale.CHINA).format(Date()),
        )

        val ops = when (reviewType) {
            "course", "new_course" -> {
                if (courseName.isBlank()) {
                    return ReActToolOutcome.Failed("提交评价失败：多项目课程评价需要 course_name", retryable = true)
                }
                CourseContributionOps.courseSection(
                    courseName = courseName,
                    sectionTitle = title,
                    content = content,
                    author = author,
                    createCourse = reviewType == "new_course",
                )
            }
            "course_teacher" -> {
                if (courseName.isBlank() || teacherName.isBlank()) {
                    return ReActToolOutcome.Failed("提交评价失败：课程教师评价需要 course_name 和 teacher_name", retryable = true)
                }
                CourseContributionOps.courseTeacherReview(
                    courseName = courseName,
                    teacherName = teacherName,
                    content = content,
                    author = author,
                    createCourse = false,
                )
            }
            "section" -> {
                if (title.isBlank()) {
                    return ReActToolOutcome.Failed("提交评价失败：章节内容需要 title", retryable = true)
                }
                listOf(
                    mapOf(
                        "op" to "add_section_item",
                        "title" to title,
                        "content" to content,
                        "author" to author,
                    )
                )
            }
            else -> {
                if (lecturerName.isBlank()) {
                    return ReActToolOutcome.Failed("提交评价失败：缺少教师姓名（lecturer_name）", retryable = true)
                }
                listOf(
                    mapOf(
                        "op" to "add_lecturer_review",
                        "lecturer_name" to lecturerName,
                        "content" to content,
                        "author" to author,
                    )
                )
            }
        }

        val request = ConfirmationRequest(
            title = "提交课程评价",
            detailLines = buildList {
                add("课程：${courseName.ifBlank { courseCode }}（$courseCode）")
                if (lecturerName.isNotBlank()) add("教师：$lecturerName")
                if (teacherName.isNotBlank()) add("任课教师：$teacherName")
                add("标题：$title")
                add("内容：${content.take(120)}${if (content.length > 120) "…" else ""}")
            },
            confirmLabel = "提交评价",
        )
        val decision = WriteConfirmation.decide(
            gate = input.confirmationGate,
            request = request,
            pendingText = "需要用户确认后才会提交评价：$courseCode。",
            cancelledText = "用户取消提交评价，未创建任何 PR。",
        )
        if (decision is WriteConfirmation.Decision.Halt) return decision.outcome

        val preview = PrServerClient.previewSync(courseCode, ops)
        if (!preview.ok) {
            return ReActToolOutcome.Failed(
                "评价预览失败：${preview.error?.message ?: "未知错误"}",
                retryable = false,
            )
        }

        val result = PrServerClient.submitSync(courseCode, ops)
        if (result.ok) {
            val prUrl = result.data?.pr?.url
            return if (!prUrl.isNullOrBlank()) {
                ReActToolOutcome.Succeeded("评价提交成功！PR 链接：$prUrl")
            } else {
                // 服务端返回 ok 但没有 PR 链接：提交可能已发生，不能当作明确成功。
                ReActToolOutcome.OutcomeUnknown("评价请求已发送，但未收到 PR 链接，请到 PR 历史确认。")
            }
        }
        // 网络类失败可能已到达服务端，标记为结果未知而不是可重试失败。
        val message = result.error?.message ?: "未知错误"
        return if (WriteConfirmation.isUncertainFailure(message)) {
            ReActToolOutcome.OutcomeUnknown("评价提交结果未知：$message。请到 PR 历史确认后再决定是否重试。")
        } else {
            ReActToolOutcome.Failed("评价提交失败：$message", retryable = false)
        }
    }

}
