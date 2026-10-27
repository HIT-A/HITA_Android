package cn.limpu.hita.agent.llm

import android.app.Application
import cn.limpu.hita.agent.core.AgentProvider
import cn.limpu.hita.agent.core.AgentTraceEvent
import cn.limpu.hita.agent.tools.ReActToolInput
import cn.limpu.hita.agent.tools.ReActToolRegistry
import cn.limpu.hita.agent.timetable.TimetableAgentInput
import cn.limpu.hita.agent.timetable.TimetableAgentOutput
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsDimensions
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.resource.AgentResourceCard
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.utils.LogUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object LlmChatService {

    private val toolRegistry = ReActToolRegistry.createDefault()

    suspend fun chat(
        history: List<ChatMessage>,
        timetableId: String?,
        application: Application,
        easRepository: EASRepository,
        agentProvider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
        onTrace: (AgentTraceEvent) -> Unit,
        onResult: (LlmChatResult) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val userMessage = history.lastOrNull { it.role == "user" }?.content ?: ""

                emitTraceToMain(onTrace, AgentTraceEvent(stage = "react_start", message = "分析中…", payload = ""))

                val contextMessage = ReactPromptBuilder.build(
                    context = application,
                    userMessage = userMessage,
                    history = history,
                )

                // BLOCKED: Backend ReAct is intentionally disabled.
                // Reason: Backend cannot execute Android-local tools (timetable query, add activity).
                // Also, cloud ReAct causes concurrency issues with multi-user shared state.
                // Use localReAct() below instead.
                // val result = AgentBackendClient.aiReactSync(contextMessage)
                // if (result == null) { onResult(...); return@withContext }
                val resourceCards = mutableListOf<AgentResourceCard>()
                val reactResult = localReAct(
                    contextMessage = contextMessage,
                    userMessage = userMessage,
                    application = application,
                    easRepository = easRepository,
                    timetableId = timetableId,
                    agentProvider = agentProvider,
                    onTrace = onTrace,
                    onResourceCards = { cards ->
                        resourceCards.addUnique(cards)
                    },
                )
                if (reactResult == null) {
                    return@withContext emitResultToMain(onResult, LlmChatResult.Error(error = "本地 AI 推理失败"))
                }
                val (answer, thinking) = reactResult

                emitResultToMain(onResult, LlmChatResult.Success(text = answer, thinking = thinking, resourceCards = resourceCards))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LogUtils.e( "Chat failed", e)
                emitResultToMain(onResult, LlmChatResult.Error(error = e.message ?: "调用失败"))
            }
        }
    }

    private suspend fun localReAct(
        contextMessage: String,
        userMessage: String,
        application: Application,
        easRepository: EASRepository,
        timetableId: String?,
        agentProvider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
        onTrace: (AgentTraceEvent) -> Unit,
        onResourceCards: (List<AgentResourceCard>) -> Unit,
    ): Pair<String, String>? {
        val messages = mutableListOf(
            ChatMessage(role = "system", content = contextMessage),
            ChatMessage(role = "user", content = userMessage),
        )
        val thinkingSteps = mutableListOf<String>()
        val usefulObservations = mutableListOf<String>()
        val executedToolCalls = mutableSetOf<String>()
        var forcedExploration = false

        repeat(MAX_REACT_STEPS) { step ->
            emitTraceToMain(onTrace, AgentTraceEvent(stage = "react_step", message = "步骤 ${step + 1}/$MAX_REACT_STEPS: 正在思考…", payload = ""))

            val request = ChatCompletionRequest(
                model = LlmClient.MODEL,
                messages = messages,
            )
            var response: retrofit2.Response<ChatCompletionResponse>? = null
            var retryCount = 0
            val maxRetries = 3
            while (retryCount < maxRetries) {
                val currentResponse = try {
                    LlmClient.chatCompletion(application, request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    retryCount++
                    if (retryCount >= maxRetries) {
                        LogUtils.e("DeepSeek API error in step ${step + 1} after $maxRetries attempts: ${e.javaClass.simpleName}: ${e.message}", e)
                        return null
                    }
                    val delayMs = 2000L * retryCount
                    LogUtils.w("DeepSeek network error in step ${step + 1}, attempt $retryCount/$maxRetries (${e.javaClass.simpleName}), retrying in ${delayMs}ms...")
                    delay(delayMs)
                    continue
                }
                response = currentResponse
                if (currentResponse.isSuccessful) break
                val code = currentResponse.code()
                if (code == 529 || code == 503 || code == 429) {
                    retryCount++
                    val delayMs = 3000L * retryCount
                    LogUtils.w("DeepSeek HTTP $code in step ${step + 1}, attempt $retryCount/$maxRetries, retrying after ${delayMs}ms...")
                    delay(delayMs)
                } else {
                    break
                }
            }
            val finalResponse = response ?: return null

            if (!finalResponse.isSuccessful) {
                val errorBody = finalResponse.errorBody()?.string()
                LogUtils.e( "DeepSeek HTTP ${finalResponse.code()} in step ${step + 1}: $errorBody")
                return null
            }

            val body = finalResponse.body()
            if (body == null) {
                LogUtils.e("DeepSeek body is null in step ${step + 1}")
                return null
            }
            val choice = body.choices?.firstOrNull()
            if (choice == null) {
                LogUtils.e("DeepSeek choices empty in step ${step + 1}")
                return null
            }
            val content = choice.message?.content
            if (content == null) {
                LogUtils.e("DeepSeek content null in step ${step + 1}, message=${choice.message}")
                return null
            }

            val parsed = parseLocalReActStep(content)

            thinkingSteps.add("步骤 ${step + 1}: ${parsed.thought.take(100)}${if (parsed.action.isNotBlank()) " → ${toolDisplayName(parsed.action)}" else ""}")

            emitTraceToMain(onTrace, AgentTraceEvent(
                stage = "react_step",
                message = "思考: ${parsed.thought.take(80)}${if (parsed.action.isNotBlank()) " → ${toolDisplayName(parsed.action)}" else ""}",
                payload = parsed.thought.take(500),
            ))

            if (parsed.action == "答案" || parsed.action.isBlank()) {
                delay(FINAL_THOUGHT_PREVIEW_MS)
                val thinking = thinkingSteps.joinToString("\n")
                val answer = parsed.thought.ifBlank {
                    synthesizeFallbackAnswer(application, userMessage, usefulObservations)
                }
                if (
                    shouldReviewExplorationBeforeAnswer(
                        executedToolCalls = executedToolCalls,
                        alreadyForced = forcedExploration,
                    )
                ) {
                    forcedExploration = true
                    messages.add(ChatMessage(role = "assistant", content = content))
                    messages.add(ChatMessage(
                        role = "user",
                        content = "你只查过一个宽泛数据源就准备结束。请先判断已有观察是否已经直接回答了用户问题：如果证据足够，按证据整理最终答案；如果不足，请根据用户问题和可用工具，自行选择下一步，可以换查询词、换数据源、读取具体网页或详情页。不要指定固定流程，也不要直接回答没有。"
                    ))
                    return@repeat
                }
                return Pair(answer.ifBlank { "我找到了相关资料，但模型没有生成文字总结。你可以先查看下方资料卡片。" }, thinking)
            }

            LogUtils.d("[ReAct] tool=${parsed.action} step=${step + 1}")
            val normalizedAction = ReActToolRegistry.normalizeName(parsed.action)
            val callKey = "$normalizedAction:${parsed.actionInput.trim()}"
            if (!executedToolCalls.add(callKey)) {
                val thinking = thinkingSteps.joinToString("\n")
                val fallbackAnswer = synthesizeFallbackAnswer(application, userMessage, usefulObservations)
                return Pair(
                    fallbackAnswer.ifBlank {
                        "我已经用相同条件查询过${toolDisplayName(parsed.action)}，没有获得更多新信息。请换一个更具体的关键词或限定课程、教师或校区。"
                    },
                    thinking
                )
            }
            val observation = toolRegistry.get(parsed.action)
                ?.execute(ReActToolInput(
                    actionInput = parsed.actionInput,
                    userMessage = userMessage,
                    application = application,
                    easRepository = easRepository,
                    timetableId = timetableId,
                    agentProvider = agentProvider,
                    onTrace = onTrace,
                    onResourceCards = onResourceCards,
                ))
                ?: "未知工具：${toolDisplayName(parsed.action)}"
            runCatching {
                UsageAnalyticsClient.record(
                    UsageAnalyticsEvent.AI_TOOL_INVOKED,
                    mapOf(UsageAnalyticsDimensions.TOOL to normalizedAction)
                )
            }
            collectUsefulObservation(parsed.action, observation, usefulObservations)
            emitTraceToMain(onTrace, AgentTraceEvent(stage = "react_step", message = "观察: ${observation.take(100)}", payload = observation.take(500)))

            messages.add(ChatMessage(role = "assistant", content = content))
            messages.add(ChatMessage(role = "user", content = "观察结果: $observation"))
        }

        val thinking = thinkingSteps.joinToString("\n")
        val fallbackAnswer = synthesizeFallbackAnswer(application, userMessage, usefulObservations)
        return Pair(fallbackAnswer.ifBlank { "达到最大步骤限制（${MAX_REACT_STEPS}步），请简化您的问题" }, thinking)
    }

    internal data class ParsedStep(val thought: String, val action: String, val actionInput: String)

    internal fun parseLocalReActStep(text: String): ParsedStep {
        val cleanedText = text
            .replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "")
            .trim()

        val thoughtRegex = Regex("(?is)思考[：:]\\s*(.+?)(?=\\n动作[：:]|\\n答案[：:]|$)")
        val actionRegex = Regex("(?im)^[ \\t]*动作[：:]\\s*(\\S+)")
        val actionInputRegex = Regex("(?is)动作输入[：:]\\s*(.+?)(?=\\n思考[：:]|\\n观察[：:]|$)")
        val answerRegex = Regex("(?is)答案[：:]\\s*(.+)")

        val thought = thoughtRegex.find(cleanedText)?.groupValues?.get(1)?.trim() ?: ""
        val action = actionRegex.find(cleanedText)?.groupValues?.get(1)?.trim() ?: ""
        val actionInput = actionInputRegex.find(cleanedText)?.groupValues?.get(1)?.trim() ?: ""

        if (action.isBlank() && thought.isNotBlank()) {
            val answerMatch = answerRegex.find(cleanedText)
            if (answerMatch != null) {
                return ParsedStep(thought = answerMatch.groupValues[1].trim(), action = "答案", actionInput = "")
            }
        }

        if (thought.isBlank() && action.isBlank()) {
            return ParsedStep(thought = cleanedText.trim(), action = "答案", actionInput = "")
        }

        return ParsedStep(thought = thought, action = action, actionInput = actionInput)
    }

    private fun collectUsefulObservation(
        action: String,
        observation: String,
        usefulObservations: MutableList<String>,
    ) {
        if (observation.isBlank() || observation.startsWith("未知工具")) return
        val shouldKeep = when (action) {
            "get_timetable",
            "search_timetable",
            "search_empty_classroom",
            "search_course",
            "get_course_detail",
            "search_external_resource",
            "web_search",
            "rag_search",
            "search_teacher",
            "crawl_page",
            "crawl_site",
            "crawl_status" -> true
            else -> false
        }
        if (!shouldKeep) return
        usefulObservations.add(
            buildString {
                append("【")
                append(toolDisplayName(action))
                append("】\n")
                append(observation.trim().take(MAX_OBSERVATION_CHARS))
            }
        )
    }

    private fun toolDisplayName(action: String): String {
        return when (ReActToolRegistry.normalizeName(action)) {
            "get_timetable" -> "查询课表"
            "search_timetable" -> "搜索课表事件"
            "search_empty_classroom" -> "查询空教室"
            "search_course" -> "搜索课程信息"
            "get_course_detail" -> "读取课程详情"
            "search_external_resource" -> "搜索课程资料"
            "search_teacher" -> "搜索教师信息"
            "web_search" -> "网页搜索"
            "rag_search" -> "知识库查询"
            "crawl_page" -> "读取网页内容"
            "crawl_site" -> "爬取网站"
            "crawl_status" -> "查询爬取状态"
            "submit_review" -> "提交课程评价"
            "add_activity" -> "添加活动"
            "答案" -> "整理答案"
            else -> action.ifBlank { "未知工具" }
        }
    }

    private fun shouldReviewExplorationBeforeAnswer(
        executedToolCalls: Set<String>,
        alreadyForced: Boolean,
    ): Boolean {
        if (alreadyForced) return false
        val informationToolCalls = executedToolCalls.count { call ->
            val tool = call.substringBefore(":")
            tool in BROAD_DISCOVERY_TOOLS
        }
        return informationToolCalls in 1 until MIN_DISCOVERY_TOOL_CALLS_BEFORE_UNCHECKED_ANSWER
    }

    private suspend fun synthesizeFallbackAnswer(
        application: Application,
        userMessage: String,
        usefulObservations: List<String>,
    ): String {
        if (usefulObservations.isEmpty()) return ""
        val rawFallback = buildFallbackAnswer(usefulObservations)
        val prompt = buildString {
            append("请根据下面已经查到的工具结果，直接回答用户问题。")
            append("要求：不要原样粘贴工具输出；先筛选相关信息，再按条目整理；")
            append("如果资料不足以推荐具体对象，要明确说明缺口和下一步该查什么；不要编造。\n\n")
            append("用户问题：")
            append(userMessage)
            append("\n\n工具结果：\n")
            append(rawFallback)
        }
        return localLlmGenerate(application, prompt)?.takeIf { it.isNotBlank() }
            ?: rawFallback
    }

    private fun buildFallbackAnswer(usefulObservations: List<String>): String {
        if (usefulObservations.isEmpty()) return ""
        return buildString {
            append("我查到了这些信息，但还没能完成最终整理：\n\n")
            usefulObservations.takeLast(MAX_FALLBACK_OBSERVATIONS).forEachIndexed { index, observation ->
                if (index > 0) append("\n\n")
                append(observation)
            }
        }
    }
}

private fun MutableList<AgentResourceCard>.addUnique(newCards: List<AgentResourceCard>) {
    val existingKeys = map { "${it.source}|${it.path}|${it.title}" }.toMutableSet()
    newCards.forEach { card ->
        val key = "${card.source}|${card.path}|${card.title}"
        if (existingKeys.add(key)) {
            add(card)
        }
    }
}

private suspend fun localLlmGenerate(application: Application, prompt: String): String? {
    val request = ChatCompletionRequest(
        model = LlmClient.MODEL,
        messages = listOf(ChatMessage(role = "user", content = prompt)),
    )
    return try {
        val response = LlmClient.chatCompletion(application, request)
        if (!response.isSuccessful) {
            LogUtils.e("localLlmGenerate HTTP ${response.code()}")
            return null
        }
        response.body()?.choices?.firstOrNull()?.message?.content?.trim()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        LogUtils.e("localLlmGenerate error: ${e.message}", e)
        null
    }
}

sealed class LlmChatResult {
    data class Success(
        val text: String,
        val thinking: String? = null,
        val resourceCards: List<AgentResourceCard> = emptyList(),
    ) : LlmChatResult()
    data class Error(val error: String) : LlmChatResult()
}

private const val FINAL_THOUGHT_PREVIEW_MS = 120L
private const val MAX_REACT_STEPS = 30
private const val MAX_OBSERVATION_CHARS = 1800
private const val MAX_FALLBACK_OBSERVATIONS = 6
private const val MIN_DISCOVERY_TOOL_CALLS_BEFORE_UNCHECKED_ANSWER = 2
private val BROAD_DISCOVERY_TOOLS = setOf(
    "search_course",
    "search_external_resource",
    "web_search",
    "rag_search",
    "crawl_page",
    "crawl_site",
)

private suspend fun emitTraceToMain(
    onTrace: (AgentTraceEvent) -> Unit,
    event: AgentTraceEvent,
) {
    withContext(Dispatchers.Main.immediate) {
        onTrace(event)
    }
}

private suspend fun emitResultToMain(
    onResult: (LlmChatResult) -> Unit,
    result: LlmChatResult,
) {
    withContext(Dispatchers.Main.immediate) {
        onResult(result)
    }
}

/**
 * 处理带附件的消息，使用智谱多模态API理解附件内容
 * 仅用于图片和视频理解，文档类文件优先本地处理
 * 然后将理解结果添加到对话历史，继续使用 DeepSeek 进行对话
 */
suspend fun LlmChatService.chatWithAttachment(
    history: List<ChatMessage>,
    attachmentBase64: String,
    attachmentMimeType: String,
    timetableId: String?,
    application: Application,
    easRepository: EASRepository,
    agentProvider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
    onTrace: (AgentTraceEvent) -> Unit,
    onResult: (LlmChatResult) -> Unit,
) {
    withContext(Dispatchers.IO) {
        try {
            emitTraceToMain(onTrace, AgentTraceEvent(stage = "react_start", message = "正在理解附件内容…", payload = ""))

            // 1. 判断附件类型并处理
            val contentType = when {
                attachmentMimeType.startsWith("image/") -> "image_url"
                attachmentMimeType.startsWith("video/") -> "video_url"
                else -> {
                    LogUtils.e("Unsupported attachment type: $attachmentMimeType")
                    return@withContext emitResultToMain(onResult, LlmChatResult.Error("不支持的附件类型：$attachmentMimeType\n\n支持的类型：\n- 图片：JPG、PNG、GIF、WebP\n- 视频：MP4、MOV\n\n注意：PDF、Word、Excel、PowerPoint 请直接上传，系统会自动本地解析"))
                }
            }

            val userMessage = history.lastOrNull { it.role == "user" }?.content ?: ""
            // 提取纯文本问题（移除附件标记）
            val textQuestion = userMessage.lines().filterNot {
                it.startsWith("[") || it.startsWith("[附件") || it.startsWith("[文件")
            }.joinToString("\n").trim()

            val attachmentContent: ZhipuContent = when {
                contentType == "image_url" -> {
                    val imageType = when {
                        attachmentMimeType.contains("jpeg") || attachmentMimeType.contains("jpg") -> "image/jpeg"
                        attachmentMimeType.contains("png") -> "image/png"
                        attachmentMimeType.contains("gif") -> "image/gif"
                        attachmentMimeType.contains("webp") -> "image/webp"
                        else -> "image/jpeg"
                    }
                    ZhipuContent(
                        type = "image_url",
                        image_url = ZhipuImageUrl("data:$imageType;base64,$attachmentBase64")
                    )
                }
                contentType == "video_url" -> {
                    ZhipuContent(
                        type = "video_url",
                        video_url = ZhipuVideoUrl("data:$attachmentMimeType;base64,$attachmentBase64")
                    )
                }
                else -> {
                    // 不应该到这里
                    ZhipuContent(
                        type = "text",
                        text = "[错误：不支持的文件类型]"
                    )
                }
            }

            val zhipuMessages = listOf(
                ZhipuMessage(
                    role = "user",
                    content = listOf(
                        attachmentContent,
                        ZhipuContent(
                            type = "text",
                            text = when (contentType) {
                                "image_url" -> "请详细描述这张图片的内容，包括主要物体、场景、文字、颜色等细节。"
                                "video_url" -> "请详细描述这个视频的主要内容，包括场景、人物、动作、关键信息等。"
                                "file_upload" -> "请详细分析这个PDF文档的内容，包括：\n1. 文档的主要主题和目的\n2. 关键信息和要点\n3. 重要的数据、公式或结论\n4. 文档的结构和章节\n5. 如果是作业或试卷，请列出题目内容"
                                else -> "请描述这个文件的内容。"
                            }
                        )
                    )
                )
            )

            val zhipuRequest = ZhipuChatRequest(
                model = when (contentType) {
                    "file_upload" -> "glm-4-flash"  // PDF 使用支持文档的模型
                    else -> "glm-4.6v-flash"  // 图片/视频使用视觉模型
                },
                messages = zhipuMessages,
                stream = false
            )

            val attachmentDescription = try {
                val response = ZhipuClient.service.chatCompletion(
                    ZhipuClient.authHeader(application),
                    zhipuRequest
                ).awaitCancellable()

                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string()
                    LogUtils.e("Zhipu API error: ${response.code()} - $errorBody")
                    return@withContext emitResultToMain(onResult, LlmChatResult.Error("附件理解失败：HTTP ${response.code()}"))
                }

                val body = response.body()
                if (body == null) {
                    LogUtils.e("Zhipu response body is null")
                    return@withContext emitResultToMain(onResult, LlmChatResult.Error("附件理解失败：响应为空"))
                }

                body.choices.firstOrNull()?.message?.content ?: "无法理解附件内容"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LogUtils.e("chatWithAttachment error: ${e.message}", e)
                return@withContext emitResultToMain(onResult, LlmChatResult.Error("附件理解失败：${e.message}"))
            }

            // 2. 将附件理解结果添加到对话历史
            val enhancedUserMessage = "$textQuestion\n\n[附件内容理解]\n$attachmentDescription"

            val newHistory = history.toMutableList()
            // 替换最后一条用户消息
            if (newHistory.isNotEmpty() && newHistory.last().role == "user") {
                newHistory[newHistory.size - 1] = ChatMessage(role = "user", content = enhancedUserMessage)
            }

            // 3. 使用 DeepSeek 继续对话
            LlmChatService.chat(
                history = newHistory,
                timetableId = timetableId,
                application = application,
                easRepository = easRepository,
                agentProvider = agentProvider,
                onTrace = onTrace,
                onResult = onResult
            )

        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            LogUtils.e("chatWithAttachment error: ${e.message}", e)
            emitResultToMain(onResult, LlmChatResult.Error("处理失败：${e.message}"))
        }
    }
}
