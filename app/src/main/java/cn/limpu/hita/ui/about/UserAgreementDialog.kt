package cn.limpu.hita.ui.about

import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.DialogFragment
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class UserAgreementDialog : DialogFragment() {

    var onResponseListener: OnResponseListener? = null
    private var showActionButtons = false
    var initialPage: Int = 0

    interface OnResponseListener {
        fun onAgree()
        fun onRefuse()
    }

    fun setShowActionButtons(show: Boolean): UserAgreementDialog {
        showActionButtons = show
        return this
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.AppTheme)
        isCancelable = onResponseListener == null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                HitaComposeTheme {
                    UserAgreementScreen(
                        showActions = showActionButtons || onResponseListener != null,
                        initialPage = initialPage,
                        onAgree = {
                            onResponseListener?.onAgree()
                            dismiss()
                        },
                        onRefuse = {
                            onResponseListener?.onRefuse()
                            dismiss()
                        },
                        onDismiss = { dismiss() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setWindowAnimations(R.style.DialogSlideAnimation)
        }
    }
}

@Composable
private fun UserAgreementScreen(
    showActions: Boolean,
    initialPage: Int = 0,
    onAgree: () -> Unit,
    onRefuse: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = HitaTheme.tokens
    val tabs = listOf(
        stringResource(R.string.name_user_agreement),
        stringResource(R.string.name_privacy_agreement)
    )
    val uaContent = stringResource(R.string.user_agreement)
    val ppContent = stringResource(R.string.privacy_policy)
    val pages = listOf(uaContent, ppContent)

    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, pages.size - 1),
        pageCount = { pages.size }
    )
    val scope = rememberCoroutineScope()

    // --- Pull-to-dismiss when scrolled to top ---
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    var dismissOffset by remember { mutableFloatStateOf(0f) }
    var snapBackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val onDismissUpdated = rememberUpdatedState(onDismiss)

    // Track whether any pointer is currently down, to distinguish
    // "finger held still" (no scroll events but finger down) from
    // "finger lifted without velocity" (no scroll events and no finger)
    var pointerCount by remember { mutableStateOf(0) }
    val touchModifier = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                pointerCount = event.changes.count { it.pressed }
            }
        }
    }

    // Non-fling release: debounce detects when scroll events stop, then decide
    LaunchedEffect(Unit) {
        snapshotFlow { dismissOffset }
            .debounce(200)
            .collect { offset ->
                if (pointerCount > 0) return@collect  // finger still down
                if (offset >= dismissThresholdPx) {
                    handler.post {
                        dismissOffset = 0f
                        onDismissUpdated.value()
                    }
                } else if (offset > 0f) {
                    snapBackJob?.cancel()
                    snapBackJob = scope.launch {
                        animate(
                            offset, 0f,
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                        ) { value, _ -> dismissOffset = value }
                    }
                }
            }
    }

    val dismissConnection = remember(dismissThresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                snapBackJob?.cancel()
                snapBackJob = null
                if (dismissOffset > 0f) {
                    dismissOffset =
                        (dismissOffset + available.y).coerceAtLeast(0f)
                    return available
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    snapBackJob?.cancel()
                    snapBackJob = null
                    val dY = available.y
                    if (dY > 0f) {
                        dismissOffset =
                            (dismissOffset + dY * 0.4f).coerceAtLeast(0f)
                        return Offset(0f, dY)
                    } else if (dY < 0f && dismissOffset > 0f) {
                        dismissOffset =
                            (dismissOffset + dY).coerceAtLeast(0f)
                        return Offset(0f, dY)
                    }
                }
                return Offset.Zero
            }

            // Intercept high-velocity fling BEFORE it runs — dismiss immediately
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dismissOffset > 0f && available.y > dismissThresholdPx) {
                    handler.post {
                        dismissOffset = 0f
                        onDismissUpdated.value()
                    }
                    return available
                }
                return Velocity.Zero
            }

            // After fling settles: handle threshold-based dismiss or snap-back
            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                if (dismissOffset > 0f) {
                    if (dismissOffset >= dismissThresholdPx) {
                        handler.post {
                            dismissOffset = 0f
                            onDismissUpdated.value()
                        }
                    } else {
                        snapBackJob?.cancel()
                        snapBackJob = scope.launch {
                            animate(
                                dismissOffset, 0f,
                                animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                            ) { value, _ -> dismissOffset = value }
                        }
                    }
                }
                return Velocity.Zero
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .then(touchModifier)
            .nestedScroll(dismissConnection)
            .graphicsLayer { translationY = dismissOffset }
    ) {
        // Top bar: close button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_close_24),
                    contentDescription = stringResource(R.string.cancel),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Tab row — synced with HorizontalPager
        PrimaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                    text = {
                        Text(
                            text = title,
                            fontSize = 16.sp,
                            color = if (pagerState.currentPage == index) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                )
            }
        }

        // Pager content — each page is independently scrollable
        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
        val linkColor = MaterialTheme.colorScheme.primary.toArgb()

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { pageIndex ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = tokens.spacing.md,
                        top = tokens.spacing.sm,
                        end = tokens.spacing.md,
                        bottom = tokens.spacing.lg
                    )
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { context ->
                        TextView(context).apply {
                            movementMethod = LinkMovementMethod.getInstance()
                            textSize = 15f
                        }
                    },
                    update = { textView ->
                        val newContent = pages[pageIndex]
                        if (textView.text.toString() != newContent) {
                            textView.text =
                                Html.fromHtml(newContent, Html.FROM_HTML_MODE_LEGACY)
                            textView.setTextColor(textColor)
                            textView.setLinkTextColor(linkColor)
                        }
                    }
                )
            }
        }

        if (showActions) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(
                        start = tokens.spacing.sm,
                        end = tokens.spacing.sm,
                        top = tokens.spacing.xs,
                        bottom = tokens.spacing.sm
                    )
            ) {
                TextButton(
                    onClick = onRefuse,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.refuse),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = onAgree,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .padding(start = tokens.spacing.xs),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(text = stringResource(R.string.agree))
                }
            }
        }

        if (!showActions) {
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
