package cn.limpu.hita.ui.main.agent

import android.app.AlertDialog
import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.compose.ui.text.input.TextFieldValue
import cn.limpu.hita.agent.attachment.normalizeChatAttachmentName
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.R
import cn.limpu.hita.agent.core.AgentProvider
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
import cn.limpu.hita.ui.main.rememberMainBottomBarClearance
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class AgentChatFragment : androidx.fragment.app.Fragment() {

    @Inject lateinit var agentProvider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>

    private val viewModel: AgentChatViewModel by viewModels()
    private var foreground by mutableStateOf(false)

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        viewModel.finishAttachmentPicker(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel.ensureSession()
        return ComposeView(requireContext()).apply {
            id = R.id.agent_chat_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HitaComposeTheme() {
                    AgentChatScreen(
                        viewModel = viewModel,
                        foreground = this@AgentChatFragment.foreground,
                        onResolveConfirmation = { pending, approved ->
                            if (isResumed) viewModel.resolveConfirmation(pending, approved)
                        },
                        onSend = { viewModel.sendDraft(it, agentProvider) },
                        onSuggestion = { sessionId, text -> viewModel.sendSuggestion(sessionId, text, agentProvider) },
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


    private fun showDeleteSessionDialog(session: ChatSession) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.agent_chat_delete_session)
            .setMessage("删除「${session.title}」的聊天记录？")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteSession(session)
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

    private fun openFilePicker(sessionId: String) {
        val ctx = context ?: return
        if (!viewModel.beginAttachmentPicker(sessionId)) return
        try {
            filePickerLauncher.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            viewModel.finishAttachmentPicker(null)
            Toast.makeText(ctx, R.string.agent_chat_attachment_picker_failed, Toast.LENGTH_SHORT).show()
        }
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

    override fun onResume() {
        super.onResume()
        foreground = true
    }

    override fun onPause() {
        foreground = false
        super.onPause()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentChatScreen(
    viewModel: AgentChatViewModel,
    foreground: Boolean,
    onResolveConfirmation: (ChatConfirmationGate.Pending, Boolean) -> Unit,
    onSend: (String) -> Boolean,
    onSuggestion: (String, String) -> Boolean,
    onAttach: (String) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (ChatSession) -> Unit,
    onSwitchSession: (ChatSession) -> Unit,
    onOpenResourceCard: (AgentResourceCard) -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    val messages by viewModel.messages.observeAsState(emptyList())
    val sessions by viewModel.sessions.observeAsState(emptyList())
    val sessionId by viewModel.selectedSessionId.observeAsState()
    val isLoading by viewModel.isLoading.observeAsState(false)
    val restoring by viewModel.isRestoring.observeAsState(false)
    val activeRequest by viewModel.activeRequest.observeAsState()
    val draft by viewModel.draft.observeAsState(ChatDraft())
    val deleting by viewModel.deleting.observeAsState(emptySet())
    val notice by viewModel.status.observeAsState("")
    val deletionNotice by viewModel.deletionNotice.observeAsState("")
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    pendingConfirmation?.takeIf {
        it.isVisibleIn(sessionId, foreground) && it.owner.sessionId !in deleting
    }?.let { pending ->
        key(pending) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { onResolveConfirmation(pending, false) },
                title = { Text(pending.request.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs)) {
                        pending.request.detailLines.forEach { line ->
                            Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onResolveConfirmation(pending, true) }) { Text(pending.request.confirmLabel) }
                },
                dismissButton = {
                    TextButton(onClick = { onResolveConfirmation(pending, false) }) { Text(pending.request.cancelLabel) }
                },
            )
        }
    }
    val context = LocalContext.current
    val ime = rememberChatImeOverlap()
    val bottomClearance = (ime ?: rememberMainBottomBarClearance()) + 8.dp
    val keyboardController = LocalSoftwareKeyboardController.current
    val positions = rememberSaveableStateHolder()
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
    var recoverRowId by remember(sessionId) { mutableStateOf<String?>(null) }
    var sessionMenuExpanded by remember { mutableStateOf(false) }

    val currentId = sessionId
    val deletingCurrent = currentId != null && currentId in deleting
    val restoreFailed = notice == stringResource(R.string.chat_restore_failed)
    recoverRowId?.let { rowId ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { recoverRowId = null },
            title = { Text(stringResource(R.string.agent_chat_replace_draft_title)) },
            text = { Text(stringResource(R.string.agent_chat_replace_draft_detail)) },
            confirmButton = {
                TextButton(onClick = {
                    if (currentId != null) viewModel.recoverInput(currentId, rowId, replace = true)
                    recoverRowId = null
                }) { Text(stringResource(R.string.agent_chat_restore_input)) }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .background(
                if (hitaUsesMainBackdrop()) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.background
                }
            )
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(bottom = bottomClearance)) {
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
                            .heightIn(min = 48.dp)
                            .hitaGlassCardModifier(sessionShape, elevation = 8.dp)
                            .clickable { sessionMenuExpanded = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = tokens.spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = sessions.firstOrNull { it.id == currentId }?.title
                                    ?: stringResource(R.string.agent_chat_new_session),
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
                                enabled = session.id !in deleting,
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
                        contentDescription = stringResource(R.string.agent_chat_new_session),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val currentSession = sessions.firstOrNull { it.id == currentId }
                IconButton(
                    enabled = currentSession != null && deleting.isEmpty(),
                    onClick = {
                        currentSession?.let(onDeleteSession)
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_delete_24),
                        contentDescription = stringResource(R.string.agent_chat_delete_session),
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

            if (currentId == null || restoring) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (restoring) Text(stringResource(R.string.agent_chat_processing))
                    if (restoreFailed) TextButton(onClick = viewModel::retryRestore) {
                        Text(stringResource(R.string.agent_chat_retry_load))
                    }
                }
            } else {
                positions.SaveableStateProvider(currentId) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val fieldHeight = (maxHeight * 0.36f).coerceIn(56.dp, 156.dp)
                        Column(Modifier.fillMaxSize()) {
                            val listState = rememberLazyListState()
                            AgentChatHistory(
                                messages = messages,
                                listState = listState,
                                markwon = markwon,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                suggestionsEnabled = !isLoading && !restoreFailed,
                                onSuggestion = { if (onSuggestion(currentId, it)) keyboardController?.hide() },
                                onOpenResourceCard = onOpenResourceCard,
                                canRecoverInput = { !isLoading && viewModel.hasRecoverableInput(currentId, it) },
                                onRecoverInput = { rowId ->
                                    if (!viewModel.recoverInput(currentId, rowId) && viewModel.hasRecoverableInput(currentId, rowId)) {
                                        recoverRowId = rowId
                                    }
                                },
                            )
                            if (deletingCurrent) ChatNotice(stringResource(R.string.agent_chat_delete_pending))
                            if (deletionNotice.isNotEmpty()) ChatNotice(deletionNotice)
                            if (notice.isNotEmpty()) ChatNotice(notice)
                            if (restoreFailed) TextButton(onClick = viewModel::retryRestore) {
                                Text(stringResource(R.string.agent_chat_retry_load))
                            }
                            AgentChatComposer(
                                draft = draft,
                                fieldHeight = fieldHeight,
                                editable = !deletingCurrent,
                                canSend = !isLoading && !restoreFailed && draft.hasContent,
                                onEdit = { viewModel.editDraft(currentId, it) },
                                onAttach = { onAttach(currentId) },
                                onRemoveAttachment = { viewModel.removeAttachment(currentId) },
                                onSend = { if (onSend(currentId)) keyboardController?.hide() },
                                onStop = activeRequest?.takeIf { it.sessionId == currentId }?.let { owner ->
                                    { viewModel.stopRequest(owner) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Measure clearance in the Fragment view's coordinates so adjustResize is not counted twice. */
@Composable
private fun rememberChatImeOverlap(): Dp? {
    val view = LocalView.current
    val density = LocalDensity.current
    var overlap by remember(view) { mutableStateOf<Int?>(null) }
    DisposableEffect(view) {
        val root = view.rootView
        val frame = Rect()
        val location = IntArray(2)
        fun update() {
            val insets = ViewCompat.getRootWindowInsets(view)
            if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                root.getWindowVisibleDisplayFrame(frame)
                view.getLocationOnScreen(location)
                overlap = (location[1] + view.height - frame.bottom).coerceAtLeast(0)
            } else {
                overlap = null
            }
        }
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener { update() }
        val observer = root.viewTreeObserver
        observer.addOnGlobalLayoutListener(listener)
        update()
        onDispose { if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener) }
    }
    return overlap?.let { with(density) { it.toDp() } }
}

@Composable
private fun ChatNotice(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = HitaTheme.tokens.spacing.md)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
internal fun AgentChatHistory(
    messages: List<AgentChatMessage>,
    listState: LazyListState,
    markwon: Markwon,
    modifier: Modifier = Modifier,
    suggestionsEnabled: Boolean = true,
    onSuggestion: (String) -> Unit,
    onOpenResourceCard: (AgentResourceCard) -> Unit,
    canRecoverInput: (String) -> Boolean = { false },
    onRecoverInput: (String) -> Unit = {},
) {
    val tokens = HitaTheme.tokens
    var previousLatest by remember { mutableStateOf<String?>(null) }
    val latestId = messages.lastOrNull()?.id
    SideEffect {
        if (previousLatest != null && latestId != null && previousLatest != latestId &&
            listState.firstVisibleItemScrollOffset == 0 && !listState.isScrollInProgress) {
            val first = listState.firstVisibleItemIndex
            val anchoredOnPreviousLatest = listState.layoutInfo.visibleItemsInfo.any {
                it.index == first && it.key == previousLatest
            }
            if (first == 0 || anchoredOnPreviousLatest) listState.requestScrollToItem(0)
        }
        previousLatest = latestId
    }
    val showLatest by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    Column(modifier) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            verticalArrangement = if (messages.isEmpty()) Arrangement.Top else Arrangement.Bottom,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(tokens.spacing.sm),
        ) {
            if (messages.isEmpty()) {
                item(key = "agent-welcome-card") {
                    AgentWelcomeCard(enabled = suggestionsEnabled, onSend = onSuggestion)
                }
            } else {
                items(messages.size, key = { messages[messages.lastIndex - it].id }) { index ->
                    val message = messages[messages.lastIndex - index]
                    AgentMessageBubble(
                        message = message,
                        markwon = markwon,
                        onOpenResourceCard = onOpenResourceCard,
                        onRecoverInput = if (canRecoverInput(message.id)) ({ onRecoverInput(message.id) }) else null,
                    )
                }
            }
        }
        if (showLatest && messages.isNotEmpty()) {
            TextButton(onClick = { listState.requestScrollToItem(0) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.agent_chat_latest))
            }
        }
    }
}

@Composable
internal fun AgentChatComposer(
    draft: ChatDraft,
    fieldHeight: Dp,
    editable: Boolean,
    canSend: Boolean,
    onEdit: (TextFieldValue) -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onSend: () -> Unit,
    onStop: (() -> Unit)?,
) {
    val tokens = HitaTheme.tokens
    Column(Modifier.padding(horizontal = tokens.spacing.sm)) {
        draft.attachment?.let { uri ->
            val context = LocalContext.current
            val fallback = uri.lastPathSegment ?: stringResource(R.string.agent_chat_attachment_fallback)
            val fileName by produceState(fallback, uri) {
                value = fallback
                value = withContext(Dispatchers.IO) { getFileName(context, uri) ?: fallback }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.agent_chat_attachment_name, fileName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = tokens.spacing.sm),
                )
                IconButton(onClick = onRemoveAttachment, enabled = editable) {
                    Icon(painterResource(R.drawable.ic_baseline_close_24), stringResource(R.string.agent_chat_remove_attachment))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = onAttach, enabled = editable) {
                Icon(
                    painterResource(R.drawable.ic_baseline_add_24),
                    contentDescription = stringResource(
                        if (draft.attachment == null) R.string.agent_chat_attach_one else R.string.agent_chat_replace_attachment
                    ),
                )
            }
            TextField(
                value = draft.text,
                onValueChange = onEdit,
                enabled = editable,
                placeholder = { Text(stringResource(R.string.agent_chat_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                shape = hitaStyleCardShape(22.dp, 14.dp),
                minLines = 1,
                maxLines = 8,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = fieldHeight),
            )
            if (onStop != null) {
                TextButton(onClick = onStop) { Text(stringResource(R.string.agent_chat_stop)) }
            } else {
                IconButton(onClick = onSend, enabled = canSend) {
                    Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24), stringResource(R.string.send))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentWelcomeCard(
    enabled: Boolean,
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
                        .heightIn(min = 48.dp)
                        .clickable(enabled = enabled) { onSend(suggestion) },
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
    onRecoverInput: (() -> Unit)?,
) {
    val tokens = HitaTheme.tokens
    var thinkingExpanded by rememberSaveable { mutableStateOf(message.isThinkingExpanded) }
    var resourcesExpanded by rememberSaveable { mutableStateOf(message.areResourceCardsExpanded) }
    val isUser = message.role == AgentChatMessage.Role.USER

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
                } else if (message.isPlaceholder || message.role == AgentChatMessage.Role.TRACE) {
                    ChatNotice(message.text)
                } else {
                    MarkdownMessageText(markdown = message.text, markwon = markwon, color = MaterialTheme.colorScheme.onSurface)
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
                if (onRecoverInput != null) {
                    TextButton(onClick = onRecoverInput) { Text(stringResource(R.string.agent_chat_restore_input)) }
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
    val parsed = remember(markwon, markdown) { markwon.toMarkdown(markdown) }
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
            if (textView.tag !== parsed) {
                markwon.setParsedMarkdown(textView, parsed)
                textView.tag = parsed
            }
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

private fun getFileName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.let(::normalizeChatAttachmentName) else null
        }
    }.getOrNull()
