package cn.limpu.hita.agent.tools

import cn.limpu.hita.data.analytics.AnalyticsOperationContext
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import kotlinx.coroutines.withContext

class ReActToolRegistry {
    private val tools = mutableMapOf<String, SuspendingReActTool>()

    fun register(name: String, tool: ReActTool) {
        register(name, SuspendingReActTool { input -> tool.execute(input) })
    }

    fun register(name: String, tool: SuspendingReActTool) {
        val normalized = normalizeName(name)
        tools[normalized] = observed(normalized, tool)
    }

    fun get(name: String): SuspendingReActTool? = tools[normalizeName(name)]

    private fun observed(normalized: String, tool: SuspendingReActTool): SuspendingReActTool {
        return SuspendingReActTool { input ->
            val dimensions = mapOf("tool" to normalized, "execution" to if (normalized in setOf("get_timetable", "add_activity", "search_empty_classroom", "search_timetable")) "local" else "remote")
            val operation = UsageAnalyticsClient.begin(UsageAnalyticsEvent.AI_TOOL_STARTED, dimensions)
            // Current tools return free text, which may contain an error message.
            // A nonthrowing string is not proof of business success.
            var outcome = "unknown"
            try {
                withContext(AnalyticsOperationContext.coroutineContext(
                    AnalyticsOperationContext.Value(operation.id, operation.generation)
                )) { tool.execute(input) }
            }
            catch (error: Exception) { outcome = if (error is java.util.concurrent.CancellationException) "cancelled" else "failure"; throw error }
            finally { UsageAnalyticsClient.finish(operation, UsageAnalyticsEvent.AI_TOOL_FINISHED, dimensions + ("outcome" to outcome)) }
        }
    }

    fun names(): List<String> = tools.keys.toList()

    companion object {
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
