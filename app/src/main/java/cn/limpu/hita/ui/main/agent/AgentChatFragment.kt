package cn.limpu.hita.ui.main.agent

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.viewModels
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.R
import cn.limpu.hita.agent.core.AgentProvider
import cn.limpu.hita.agent.core.AgentSession
import cn.limpu.hita.agent.timetable.TimetableAgentInput
import cn.limpu.hita.agent.timetable.TimetableAgentOutput
import cn.limpu.hita.agent.tools.SearchExternalResourceTool
import cn.limpu.hita.data.repository.AiChatProvider
import cn.limpu.hita.data.repository.AiSettings
import cn.limpu.hita.data.repository.AiSettingsRepository
import cn.limpu.hita.data.model.chat.ChatSession
import cn.limpu.hita.data.model.resource.AgentResourceCard
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaGlassCardBorder
import cn.limpu.hita.ui.design.hitaGlassCardColors
import cn.limpu.hita.ui.design.hitaGlassCardModifier
import cn.limpu.hita.ui.design.hitaUsesMainBackdrop
import cn.limpu.hita.ui.design.hitaIsAppleGlassSurface
import cn.limpu.hita.ui.design.hitaStyleCardShape
import cn.limpu.hita.ui.resource.UnifiedResourceSearchActivity
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.LogUtils
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import javax.inject.Inject

@AndroidEntryPoint
class AgentChatFragment : androidx.fragment.app.Fragment() {

    @Inject lateinit var agentProvider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>

    private val viewModel: AgentChatViewModel by viewModels()
    private var agentSession: AgentSession<TimetableAgentInput, TimetableAgentOutput>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.setPendingAttachment(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel.ensureSession()
        return ComposeView(requireContext()).apply {
            setContent {
                HitaComposeTheme() {
                    AgentChatScreen(
                        viewModel = viewModel,
                        onSend = ::sendMessage,
                        onAttach = ::openFilePicker,
                        onNewSession = { viewModel.createNewSession() },
                        onDeleteSession = ::showDeleteSessionDialog,
                        onSwitchSession = { viewModel.switchToSession(it.id) },
                        onOpenResourceCard = ::openResourceCard,
                        onOpenAiSettings = ::showAiSettingsDialog,
                    )
                }
            }
        }
    }

    private fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (viewModel.isLoading.value == true) return

        val attachmentUri = viewModel.pendingAttachment.value
        viewModel.clearPendingAttachment()

        if (attachmentUri != null) {
            agentSession?.dispose()
            agentSession = null
            viewModel.sendWithAttachment(trimmed, attachmentUri, agentProvider)
        } else {
            viewModel.addMessage(AgentChatMessage(role = AgentChatMessage.Role.USER, text = trimmed))
            agentSession?.dispose()
            agentSession = null
            viewModel.sendToLlm(text = trimmed, agentProvider = agentProvider)
        }
    }

    private fun showDeleteSessionDialog(session: ChatSession) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("删除会话")
            .setMessage("删除「${session.title}」的聊天记录？")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteSession(session)
                Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAiSettingsDialog() {
        val ctx = context ?: return
        val repo = AiSettingsRepository(ctx)
        val settings = repo.getSettings()
        val padding = (16 * ctx.resources.displayMetrics.density).toInt()
        val hasBuiltInKey = BuildConfig.DEEPSEEK_API_KEY.isNotBlank()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }

        container.addView(TextView(ctx).apply {
            text = if (hasBuiltInKey) {
                "AI 将由应用端直连模型服务；自定义 Key 只保存在本机。"
            } else {
                "此版本未配置内置 Key。请填写自定义 DeepSeek Key；它只保存在本机。"
            }
            textSize = 13f
        })

        val builtInId = View.generateViewId()
        val customId = View.generateViewId()
        val providerGroup = RadioGroup(ctx).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(ctx).apply {
                id = builtInId
                text = if (hasBuiltInKey) "内置 DeepSeek" else "内置 DeepSeek（当前不可用）"
                isEnabled = hasBuiltInKey
            })
            addView(RadioButton(ctx).apply {
                id = customId
                text = "自定义 DeepSeek Key"
            })
            check(
                when {
                    !hasBuiltInKey -> customId
                    settings.chatProvider == AiChatProvider.CUSTOM_DEEPSEEK -> customId
                    else -> builtInId
                }
            )
        }
        container.addView(providerGroup)

        val deepSeekInput = settingsEditText(
            initialValue = settings.customDeepSeekApiKey,
            hint = "自定义 DeepSeek API Key（sk-...）",
            isSecret = true,
        )
        container.addView(deepSeekInput)

        val zhipuInput = settingsEditText(
            initialValue = settings.customZhipuApiKey,
            hint = "自定义智谱 API Key（附件识别，可留空）",
            isSecret = true,
        )
        container.addView(zhipuInput)

        AlertDialog.Builder(ctx)
            .setTitle("AI 设置")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val provider = when (providerGroup.checkedRadioButtonId) {
                    customId -> AiChatProvider.CUSTOM_DEEPSEEK
                    else -> AiChatProvider.BUILTIN_DEEPSEEK
                }
                repo.saveSettings(
                    AiSettings(
                        chatProvider = provider,
                        customDeepSeekApiKey = deepSeekInput.text?.toString().orEmpty(),
                        customZhipuApiKey = zhipuInput.text?.toString().orEmpty(),
                    )
                )
                Toast.makeText(ctx, "AI 设置已保存", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun settingsEditText(
        initialValue: String,
        hint: String,
        isSecret: Boolean,
    ): EditText {
        return EditText(requireContext()).apply {
            setText(initialValue)
            this.hint = hint
            setSingleLine(true)
            inputType = if (isSecret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
        }
    }

    private fun openFilePicker() {
        val ctx = context ?: return
        if (viewModel.pendingAttachment.value != null) {
            AlertDialog.Builder(ctx)
                .setTitle("附件提示")
                .setMessage("您已经添加了一个附件，请先发送当前消息后再添加新附件。\n\n每条消息最多支持 3 个附件。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        filePickerLauncher.launch(arrayOf("*/*"))
    }

    private fun openResourceCard(card: AgentResourceCard) {
        val context = this.context ?: return
        if (card.source == SearchExternalResourceTool.SOURCE_HOA) {
            ActivityUtils.startCourseReadmeActivity(
                context,
                card.repoName.ifBlank { card.path },
                card.title,
                card.courseCode,
                card.repoType.ifBlank { "normal" },
            )
            return
        }
        context.startActivity(Intent(context, UnifiedResourceSearchActivity::class.java).apply {
            putExtra("browsePath", card.path)
            putExtra("browseSource", card.source)
            putExtra("browseTitle", card.title)
            putExtra("query", card.query.ifBlank { card.title })
        })
    }

    override fun onDestroyView() {
        agentSession?.dispose()
        agentSession = null
        super.onDestroyView()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentChatScreen(
    viewModel: AgentChatViewModel,
    onSend: (String) -> Unit,
    onAttach: () -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (ChatSession) -> Unit,
    onSwitchSession: (ChatSession) -> Unit,
    onOpenResourceCard: (AgentResourceCard) -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    val messages by viewModel.messages.observeAsState(emptyList())
    val sessions by viewModel.sessions.observeAsState(emptyList())
    val isLoading by viewModel.isLoading.observeAsState(false)
    val pendingAttachment by viewModel.pendingAttachment.observeAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val composeImeBottomPx = WindowInsets.ime.getBottom(density)
    val composeNavBottomPx = WindowInsets.navigationBars.getBottom(density)
    val keyboardVisibilityThresholdPx = with(density) { 100.dp.roundToPx() }
    var visibleKeyboardBottomPx by remember { mutableIntStateOf(0) }
    var rootNavigationBottomPx by remember { mutableIntStateOf(0) }
    DisposableEffect(view, keyboardVisibilityThresholdPx) {
        val rootView = view.rootView
        val rect = Rect()
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            rootView.getWindowVisibleDisplayFrame(rect)
            val bottomInset = (rootView.height - rect.bottom).coerceAtLeast(0)
            visibleKeyboardBottomPx = if (bottomInset > keyboardVisibilityThresholdPx) bottomInset else 0
            rootNavigationBottomPx = androidx.core.view.ViewCompat.getRootWindowInsets(rootView)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                ?.bottom
                ?: 0
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        rootNavigationBottomPx = androidx.core.view.ViewCompat.getRootWindowInsets(rootView)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            ?.bottom
            ?: 0
        onDispose {
            val observer = rootView.viewTreeObserver
            if (observer.isAlive) {
                observer.removeOnGlobalLayoutListener(listener)
            }
        }
    }
    val keyboardBottomPx = maxOf(composeImeBottomPx, visibleKeyboardBottomPx)
    val navBottomPx = maxOf(composeNavBottomPx, rootNavigationBottomPx)
    val keyboardBottomPadding = with(density) { keyboardBottomPx.toDp() }
    val threeButtonThresholdPx = with(density) { 32.dp.toPx() }
    val appNavBottomPadding = if (navBottomPx >= threeButtonThresholdPx) {
        with(density) { navBottomPx.toDp() } + 8.dp
    } else {
        12.dp
    }
    val appNavAvoidance = 52.dp + appNavBottomPadding + 8.dp
    val inputBottomPadding = if (keyboardBottomPx > 0) {
        keyboardBottomPadding + 8.dp
    } else {
        appNavAvoidance
    }
    val listBottomPadding = inputBottomPadding + 64.dp
    val markwon = remember(context) {
        val builder = Markwon.builder(context)
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
        runCatching { builder.usePlugin(JLatexMathPlugin.create(13f)) }
            .onFailure { LogUtils.e("Failed to enable JLatexMathPlugin", it) }
        builder.build()
    }
    var inputText by remember { mutableStateOf("") }
    var sessionMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (hitaUsesMainBackdrop()) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.background
                }
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = tokens.spacing.md,
                        top = tokens.spacing.sm,
                        end = tokens.spacing.md,
                        bottom = tokens.spacing.xs
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    val sessionShape = hitaStyleCardShape(18.dp, 12.dp)
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = sessionShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .hitaGlassCardModifier(sessionShape, elevation = 8.dp)
                            .clickable { sessionMenuExpanded = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = tokens.spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = sessions.firstOrNull { it.id == viewModel.currentSessionId }?.title
                                    ?: sessions.firstOrNull()?.title
                                    ?: "新对话",
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_baseline_arrow_drop_down_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = sessionMenuExpanded,
                        onDismissRequest = { sessionMenuExpanded = false }
                    ) {
                        sessions.forEach { session ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = session.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                onClick = {
                                    sessionMenuExpanded = false
                                    onSwitchSession(session)
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = onNewSession) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_add_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val currentSession = sessions.firstOrNull { it.id == viewModel.currentSessionId }
                    ?: sessions.firstOrNull()
                IconButton(
                    onClick = {
                        currentSession?.let(onDeleteSession)
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_delete_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onOpenAiSettings) {
                    Icon(
                        painter = painterResource(R.drawable.ic_menu_settings),
                        contentDescription = "AI 设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val visibleMessages = messages.filter { it.role != AgentChatMessage.Role.TRACE }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = tokens.spacing.sm,
                    top = tokens.spacing.sm,
                    end = tokens.spacing.sm,
                    bottom = listBottomPadding
                )
            ) {
                if (visibleMessages.isEmpty()) {
                    item(key = "agent-welcome-card") {
                        AgentWelcomeCard(onSend = onSend)
                    }
                } else {
                    itemsIndexed(
                        visibleMessages,
                        key = { index, item -> "${item.timestampMs}-${item.role}-$index" }
                    ) { _, message ->
                        AgentMessageBubble(
                            message = message,
                            markwon = markwon,
                            onOpenResourceCard = onOpenResourceCard,
                        )
                    }
                }
            }

            pendingAttachment?.let { uri ->
                Text(
                    text = "附件: ${getFileName(context, uri)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.xs)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(
                    start = tokens.spacing.md,
                    end = tokens.spacing.md,
                    bottom = inputBottomPadding
                ),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = onAttach) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_add_24),
                    contentDescription = stringResource(R.string.agent_chat_attach),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                enabled = !isLoading,
                placeholder = {
                    Text(
                        text = stringResource(R.string.agent_chat_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                },
                shape = hitaStyleCardShape(22.dp, 14.dp),
                minLines = 1,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val text = inputText
                    inputText = ""
                    keyboardController?.hide()
                    onSend(text)
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp, max = 156.dp)
            )
            IconButton(
                onClick = {
                    val text = inputText
                    inputText = ""
                    keyboardController?.hide()
                    onSend(text)
                },
                enabled = !isLoading,
                modifier = Modifier
                    .padding(start = tokens.spacing.sm)
                    .background(MaterialTheme.colorScheme.primary, hitaStyleCardShape(999.dp, 14.dp))
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                        contentDescription = stringResource(R.string.send),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentWelcomeCard(
    onSend: (String) -> Unit,
) {
    val tokens = HitaTheme.tokens
    val suggestions = stringArrayResource(R.array.agent_welcome_suggestions)
    val cardShape = hitaStyleCardShape(20.dp, 16.dp)
    val isGlass = hitaIsAppleGlassSurface()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            colors = if (isGlass) {
                hitaGlassCardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    glassAlpha = 0.56f
                )
            } else {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            },
            shape = cardShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = hitaGlassCardBorder(alpha = 0.28f),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .hitaGlassCardModifier(cardShape, elevation = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.agent_welcome_message),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(tokens.spacing.md)
            )
        }

        Spacer(modifier = Modifier.height(tokens.spacing.md))

        FlowRow(
            modifier = Modifier.fillMaxWidth(0.9f),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
        ) {
            suggestions.forEach { suggestion ->
                val chipShape = hitaStyleCardShape(999.dp, 14.dp)
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = chipShape,
                    modifier = Modifier
                        .hitaGlassCardModifier(chipShape, elevation = 4.dp)
                        .clickable { onSend(suggestion) }
                ) {
                    Text(
                        text = suggestion,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(
                            horizontal = tokens.spacing.md,
                            vertical = tokens.spacing.sm
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentMessageBubble(
    message: AgentChatMessage,
    markwon: Markwon,
    onOpenResourceCard: (AgentResourceCard) -> Unit,
) {
    val tokens = HitaTheme.tokens
    var thinkingExpanded by remember(message.timestampMs, message.thinking) {
        mutableStateOf(message.isThinkingExpanded)
    }
    var resourcesExpanded by remember(message.timestampMs, message.resourceCards.size) {
        mutableStateOf(message.areResourceCardsExpanded)
    }
    var thinkingFrame by remember { mutableIntStateOf(0) }
    val thinkingTexts = listOf("正在思考", "正在思考.", "正在思考..", "正在思考...")
    val isUser = message.role == AgentChatMessage.Role.USER

    LaunchedEffect(message.isPlaceholder) {
        if (!message.isPlaceholder) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(500)
            thinkingFrame = (thinkingFrame + 1) % thinkingTexts.size
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.xs),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        val bubbleShape = hitaStyleCardShape(20.dp, 16.dp)
        val isGlass = hitaIsAppleGlassSurface()
        Card(
            colors = if (isGlass) {
                hitaGlassCardColors(
                    containerColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    glassAlpha = if (isUser) 0.38f else 0.56f
                )
            } else {
                CardDefaults.cardColors(
                    containerColor = if (isUser) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                )
            },
            shape = bubbleShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = hitaGlassCardBorder(alpha = if (isUser) 0.32f else 0.28f),
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .hitaGlassCardModifier(bubbleShape, elevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(tokens.spacing.md)) {
                if (message.isPlaceholder) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = thinkingTexts[thinkingFrame],
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = tokens.spacing.sm)
                        )
                    }
                }

                if (!message.thinking.isNullOrBlank()) {
                    Text(
                        text = if (thinkingExpanded) "▼ 思考过程" else "▶ 思考过程",
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .padding(bottom = tokens.spacing.xs)
                            .clickable { thinkingExpanded = !thinkingExpanded }
                    )
                    if (thinkingExpanded) {
                        Text(
                            text = message.thinking,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = tokens.spacing.sm)
                        )
                    }
                }

                if (isUser) {
                    Text(
                        text = message.text,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 14.sp
                    )
                } else {
                    MarkdownMessageText(
                        markdown = message.text,
                        markwon = markwon,
                        color = if (message.isPlaceholder) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                if (!isUser && message.resourceCards.isNotEmpty()) {
                    Text(
                        text = if (resourcesExpanded) {
                            "▼ 相关资料 ${message.resourceCards.size} 个"
                        } else {
                            "▶ 相关资料 ${message.resourceCards.size} 个"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(top = tokens.spacing.sm)
                            .clickable { resourcesExpanded = !resourcesExpanded }
                    )
                    if (resourcesExpanded) {
                        Spacer(modifier = Modifier.height(tokens.spacing.sm))
                        message.resourceCards.forEach { card ->
                            AgentResourceCardView(card = card, onClick = { onOpenResourceCard(card) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownMessageText(
    markdown: String,
    markwon: Markwon,
    color: Color,
) {
    val textColor = color.toArgb()
    AndroidView(
        factory = { context ->
            TextView(context).apply {
                textSize = 14f
                setTextColor(textColor)
                movementMethod = LinkMovementMethod.getInstance()
                linksClickable = true
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            markwon.setMarkdown(textView, markdown)
            textView.movementMethod = LinkMovementMethod.getInstance()
        }
    )
}

@Composable
private fun AgentResourceCardView(
    card: AgentResourceCard,
    onClick: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    val cardShape = hitaStyleCardShape(tokens.radius.md, 12.dp)
    Card(
        colors = hitaGlassCardColors(
            containerColor = MaterialTheme.colorScheme.background,
            glassAlpha = 0.44f
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (hitaIsAppleGlassSurface()) {
            hitaGlassCardBorder(alpha = 0.24f)
        } else {
            CardDefaults.outlinedCardBorder()
        },
        shape = cardShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .hitaGlassCardModifier(cardShape, elevation = 6.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.title.ifBlank { "未命名资料" },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = card.subtitle.ifBlank { card.path }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            ) {
                Text(
                    text = card.sourceTag.ifBlank { "资料" },
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
    }
}

private fun getFileName(context: android.content.Context, uri: Uri): String {
    val path = uri.path
    if (path != null && path.contains("/")) {
        val nameFromPath = path.substringAfterLast("/")
        if (nameFromPath.contains(".") && !nameFromPath.endsWith(".mht")) {
            return nameFromPath
        }
    }
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) {
                val displayName = it.getString(nameIndex)
                if (displayName.endsWith(".mht", ignoreCase = true) && displayName.contains(".pdf", ignoreCase = true)) {
                    val pdfIndex = displayName.indexOf(".pdf", ignoreCase = true)
                    if (pdfIndex > 0) return displayName.substring(0, pdfIndex + 4)
                }
                return displayName
            }
        }
    }
    return uri.lastPathSegment ?: "文件"
}
