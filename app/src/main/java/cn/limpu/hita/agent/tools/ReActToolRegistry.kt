package cn.limpu.hita.agent.tools

import cn.limpu.hita.data.analytics.AnalyticsOperationContext
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import kotlinx.coroutines.withContext

class ReActToolRegistry {
    private val tools = mutableMapOf<String, SuspendingReActTool>()
    private val outcomeTools = mutableMapOf<String, OutcomeReActTool>()

    fun register(name: String, tool: ReActTool) {
        register(name, SuspendingReActTool { input -> tool.execute(input) })
    }

    fun register(name: String, tool: SuspendingReActTool) {
        val normalized = normalizeName(name)
        tools[normalized] = observedLegacy(normalized, tool)
        outcomeTools.remove(normalized)
    }

    /**
     * 注册可声明状态的工具。写入类工具应走这里，
     * 让「没有抛异常」不再被当成业务成功。
     */
    fun register(name: String, tool: OutcomeReActTool) {
        val normalized = normalizeName(name)
        val adapter = SuspendingReActTool { input -> executeTracked(normalized, tool, input).observation }
        tools[normalized] = adapter
        outcomeTools[normalized] = tool
    }

    fun get(name: String): SuspendingReActTool? = tools[normalizeName(name)]

    /** 返回已声明状态的工具；未注册或不声明状态时返回 null。 */
    fun getOutcomeTool(name: String): OutcomeReActTool? = outcomeTools[normalizeName(name)]?.let { tool ->
        val normalized = normalizeName(name)
        OutcomeReActTool { input -> executeTracked(normalized, tool, input) }
    }

    fun declaresOutcome(name: String): Boolean = outcomeTools.containsKey(normalizeName(name))

    private fun observedLegacy(normalized: String, tool: SuspendingReActTool): SuspendingReActTool {
        return SuspendingReActTool { input ->
            val dimensions = dimensionsFor(normalized)
            val operation = UsageAnalyticsClient.begin(UsageAnalyticsEvent.AI_TOOL_STARTED, dimensions)
            // 旧工具只返回自由文本，可能包含错误说明；未抛异常不等于业务成功。
            var outcome = "unknown"
            try {
                withContext(analyticsContext(operation)) { tool.execute(input) }
            } catch (cancelled: java.util.concurrent.CancellationException) {
                outcome = "cancelled"
                throw cancelled
            } catch (error: Exception) {
                outcome = "failure"
                throw error
            } finally {
                UsageAnalyticsClient.finish(
                    operation,
                    UsageAnalyticsEvent.AI_TOOL_FINISHED,
                    dimensions + ("outcome" to outcome),
                )
            }
        }
    }

    private suspend fun executeTracked(
        normalized: String,
        tool: OutcomeReActTool,
        input: ReActToolInput,
    ): ReActToolOutcome {
        val dimensions = dimensionsFor(normalized)
        val operation = UsageAnalyticsClient.begin(UsageAnalyticsEvent.AI_TOOL_STARTED, dimensions)
        var outcome = "unknown"
        return try {
            val result = withContext(analyticsContext(operation)) { tool.executeWithOutcome(input) }
            outcome = ReActToolOutcome.statusName(result)
            result
        } catch (cancelled: java.util.concurrent.CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } catch (error: Exception) {
            outcome = "failure"
            throw error
        } finally {
            UsageAnalyticsClient.finish(
                operation,
                UsageAnalyticsEvent.AI_TOOL_FINISHED,
                dimensions + ("outcome" to outcome),
            )
        }
    }

    private fun analyticsContext(operation: UsageAnalyticsClient.Operation) =
        AnalyticsOperationContext.coroutineContext(
            AnalyticsOperationContext.Value(operation.id, operation.generation)
        )

    private fun dimensionsFor(normalized: String): Map<String, String> = mapOf(
        "tool" to normalized,
        "execution" to if (normalized in LOCAL_TOOLS) "local" else "remote",
    )

    fun names(): List<String> = tools.keys.toList()

    companion object {
        private val LOCAL_TOOLS = setOf(
            "get_timetable", "add_activity", "search_empty_classroom", "search_timetable",
        )

        fun normalizeName(name: String): String {
            return name
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
        }

        fun createDefault(): ReActToolRegistry = ReActToolRegistry().apply {
            register("get_timetable", GetTimetableTool())
            register("add_activity", AddActivityTool())
            register("search_empty_classroom", SearchEmptyClassroomTool())
            register("search_course", SearchCourseTool())
            register("get_course_detail", GetCourseDetailTool())
            register("search_external_resource", SearchExternalResourceTool())
            register("search_teacher", SearchTeacherTool())
            register("web_search", WebSearchTool())
            register("rag_search", RagSearchTool())
            register("search_timetable", SearchTimetableTool())
            register("crawl_page", CrawlPageTool())
            register("crawl_site", CrawlSiteTool())
            register("crawl_status", CrawlStatusTool())
            register("submit_review", SubmitReviewTool())
        }
    }
}
