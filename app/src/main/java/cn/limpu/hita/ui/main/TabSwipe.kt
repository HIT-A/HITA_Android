package cn.limpu.hita.ui.main

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cn.limpu.hita.ui.design.LocalHitaReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 横向手势的纯数学部分。
 *
 * 主界面翻页与时间表滑周共用这些公式，因此都能在 JVM 单测里直接断言。
 * 主界面用 [progressDelta]、[selectedOffset]、[neighborOffset] 做两页互推；
 * 周视图只有一页，用 [follow]、[rubberBand]、[alpha] 做单页跟手。
 */
internal object TabSwipe {

    /**
     * 速度阈值（dp/s）：快速轻扫在位移不足时也提交。
     *
     * 取 ViewPager 的原始 FLING 阈值（50 dp/s），与原版左右滑手感对齐。
     */
    const val COMMIT_VELOCITY_DP = 50f

    /**
     * 松手时越过该屏宽比例即提交到相邻页。
     *
     * ViewPager 在纯位移分支上是前进 0.6、后退 0.4；双向统一取 0.25，
     * 让两个方向都只需小幅拖动即可换页（用户明确不需要防误滑）。
     */
    const val COMMIT_PROGRESS = 0.25f

    /** 双页互推的收尾时长（S-12：释放收尾 ≤220 ms）。 */
    const val PUSH_SETTLE_MS = 200

    /** 无目标页时的回弹阻尼系数，位移按该比例缩量。 */
    const val RUBBER_DAMPING = 0.30f

    /** 无目标页时的跟手位移上限（屏宽比例）。 */
    const val RUBBER_LIMIT_FRACTION = 0.08f

    /** 单页跟手的位移上限（屏宽比例），只用于时间表滑周。 */
    private const val FOLLOW_LIMIT_FRACTION = 0.12f

    /**
     * 松手是否提交到相邻页。
     *
     * 与 PagerSnapHelper 一致：速度朝目标越过阈值即提交，明显甩回则取消；
     * 速度不越过阈值时才按位移比例判断。目标方向没有相邻页时一律回弹。
     */
    fun commitProgress(
        progress: Float,
        velocityTowardTargetPxPerSecond: Float,
        velocityThresholdPxPerSecond: Float,
        hasTarget: Boolean,
    ): Boolean {
        if (!hasTarget) return false
        if (velocityTowardTargetPxPerSecond >= velocityThresholdPxPerSecond) return true
        if (velocityTowardTargetPxPerSecond <= -velocityThresholdPxPerSecond) return false
        return progress >= COMMIT_PROGRESS
    }

    /** 位移换算成朝 [direction] 推进的进度增量；正负由拖动方向决定。 */
    fun progressDelta(travelledPx: Float, direction: Int, widthPx: Float): Float {
        if (widthPx <= 0f || direction == 0) return 0f
        return -direction * travelledPx / widthPx
    }

    /** 选中页位移（屏宽比例）；方向 0 表示边界阻尼，[progress] 是带符号偏移。 */
    fun selectedOffset(direction: Int, progress: Float): Float =
        if (direction == 0) progress else -direction * progress

    /** 相邻页位移（屏宽比例）。两页间距恒为一屏，画面因此连续互推。 */
    fun neighborOffset(direction: Int, progress: Float): Float = direction * (1f - progress)

    /** 单页跟手位移：按屏宽比例封顶。 */
    fun follow(travelled: Float, widthPx: Float): Float {
        val limit = widthPx * FOLLOW_LIMIT_FRACTION
        if (limit <= 0f) return 0f
        return travelled.coerceIn(-limit, limit)
    }

    /** 首/尾页的跟手位移：按 [RUBBER_DAMPING] 缩量并封顶。 */
    fun rubberBand(travelled: Float, widthPx: Float): Float {
        if (widthPx <= 0f) return 0f
        val limit = widthPx * RUBBER_LIMIT_FRACTION
        return (travelled * RUBBER_DAMPING).coerceIn(-limit, limit)
    }

    /** 单页拖动的不透明度：位移越大越淡，始终保留内容。 */
    fun alpha(offsetPx: Float, widthPx: Float): Float {
        if (widthPx <= 0f) return 1f
        return (1f - abs(offsetPx) / widthPx).coerceIn(0f, 1f)
    }
}

/**
 * 一次翻页的画面状态。
 *
 * [direction] 为 +1 时下一页从右侧推入、-1 时上一页从左侧推入；0 表示已到边界，
 * 此时 [progress] 是带符号的屏宽偏移，不切换页面。
 */
internal data class PagingFrame(val anchorIndex: Int, val direction: Int, val progress: Float)

/** 手势句柄：画面状态在 snapshotFlow 里读取，避免逐帧重组整棵页面树。 */
internal class TabSwipeHandle internal constructor(
    val modifier: Modifier,
    val frame: State<PagingFrame?>,
)

/**
 * 翻页状态机：拖动与收尾共用同一进度，收尾可被下一次拖动接管。
 *
 * 只推进画面进度，不提交选择；选择在手势释放时由手势代码提交，动画结束不触发业务副作用。
 */
private class TabPagingMotion(private val scope: CoroutineScope) {
    private val state = mutableStateOf<PagingFrame?>(null)
    val frameState: State<PagingFrame?> get() = state
    private var job: Job? = null
    private var goal = 0f

    /** 本次收尾预期的选中索引；用于区分“自己提交的选择”和“底栏点击等其它入口”。 */
    private var expectedSelection: Int? = null

    val frame: PagingFrame? get() = state.value

    /** 进入拖动：取消收尾并返回冻结的画面；普通点击不会走到这里。 */
    fun adopt(): PagingFrame? {
        job?.cancel()
        job = null
        return state.value
    }

    fun follow(anchor: Int, direction: Int, progress: Float) {
        state.value = PagingFrame(anchor, direction, progress)
    }

    fun settle(
        anchor: Int,
        direction: Int,
        progress: Float,
        target: Float,
        reduced: Boolean,
        holdUntilSelection: Boolean = false,
    ) {
        job?.cancel()
        goal = target
        if (reduced || progress == target) {
            job = null
            state.value = if (holdUntilSelection && target != 0f) {
                // 减弱动效直接给出终态，但要让选中页先应用：否则先隐藏旧页会闪一帧空白。
                PagingFrame(anchor, direction, target)
            } else {
                null
            }
            return
        }
        job = scope.launch {
            animate(
                progress,
                target,
                animationSpec = tween(TabSwipe.PUSH_SETTLE_MS, easing = LinearOutSlowInEasing),
            ) { value, _ -> state.value = PagingFrame(anchor, direction, value) }
            state.value = null
        }
    }

    /** 提交选择前登记预期落点；选择真的应用后由 [onSelectionChanged] 核对。 */
    fun expect(index: Int) { expectedSelection = index }

    /**
     * 选中页被任何入口改变。
     *
     * 由本次收尾提交的选择保留画面，让动画走完；底栏点击等其它入口改到画面两端之外时
     * 丢弃旧画面，避免旧页盖住新页或继续推动已经不存在的页面。
     */
    fun onSelectionChanged(index: Int) {
        val expected = expectedSelection
        expectedSelection = null
        val current = state.value ?: return
        if (expected != null) {
            if (expected != index) {
                reset()
            } else if (job == null && current.progress == goal) {
                // 减弱动效留下的终态画面：选中页已经换过来，现在可以收起旧页。
                state.value = null
            }
            return
        }
        if (index != current.anchorIndex && index != current.anchorIndex + current.direction) reset()
    }

    /** 收尾被打断但未发生拖动：沿原目标继续。 */
    fun resume(reduced: Boolean) {
        val current = state.value ?: return
        settle(current.anchorIndex, current.direction, current.progress, goal, reduced)
    }

    fun reset() {
        job?.cancel()
        job = null
        goal = 0f
        expectedSelection = null
        state.value = null
    }
}

/**
 * 主界面左右滑动切页手势。
 *
 * 为什么在 Compose 层：页面容器由 `AndroidView` 托管，而 `AndroidViewHolder` 通过
 * `pointerInteropFilter` 直接接管触摸、不实现 `dispatchTouchEvent`/`onInterceptTouchEvent`，
 * 因此 Android 视图层的 `onInterceptTouchEvent` 永远不会被调用。挂在 Compose 祖先节点上，
 * 子级（内部横向 Pager、竖向列表）消费事件时 `awaitHorizontalTouchSlopOrCancellation`
 * 会直接取消，切页不会与内容滚动抢事件。
 *
 * 当前页与目标页真实同屏互推：位移直接跟随指针，松手按位移比例或甩动速度决定提交，
 * 收尾不超过 [TabSwipe.PUSH_SETTLE_MS]，期间再次拖动从当前画面接管。
 * 边界：
 * - 当前页是时间表时不启用：它自身的左右拖动用于切换周次。
 * - 相邻页取自 `visibleTabs`，被关掉的 Tab 不在列表里，无法滑到。
 * - 首/尾页朝外方向只做阻尼位移，不会回绕。
 */
@Composable
internal fun rememberTabSwipe(
    enabled: Boolean,
    currentIndex: Int,
    pageCount: Int,
    onSelect: (Int) -> Unit,
): TabSwipeHandle {
    val scope = rememberCoroutineScope()
    val paging = remember(scope) { TabPagingMotion(scope) }
    val reduced = LocalHitaReducedMotion.current
    val reducedState by rememberUpdatedState(reduced)
    val indexState by rememberUpdatedState(currentIndex)
    val countState by rememberUpdatedState(pageCount)
    val selectState by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    val velocityThreshold = with(density) { TabSwipe.COMMIT_VELOCITY_DP.dp.toPx() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val resize = Modifier.onSizeChanged {
        val width = it.width.toFloat()
        // 宽度变化时旧的进度不再对应屏幕，直接回到静止；S-12 要求不得突然跳页。
        if (widthPx != width) {
            paging.reset()
            widthPx = width
        }
    }

    // 选中页被任何入口改变（含底栏点击）时，丢弃与本次收尾不符的旧画面，避免旧页盖住新页。
    LaunchedEffect(currentIndex) { paging.onSelectionChanged(currentIndex) }

    val modifier = resize.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var travelled = 0f
            var adopted = false
            var settled = false
            try {
                val overSlop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    travelled = overSlop
                    change.consume()
                }
                if (overSlop == null) return@awaitEachGesture
                val width = size.width.toFloat()
                if (width <= 0f) return@awaitEachGesture
                // 真正成为翻页手势才冻结收尾：普通点击不打断、不重启收尾动画。
                val carried = paging.adopt()
                adopted = true
                val count = countState
                val anchor = carried?.anchorIndex ?: indexState
                val direction = carried?.direction ?: when {
                    travelled < 0f && anchor < count - 1 -> 1
                    travelled > 0f && anchor > 0 -> -1
                    else -> 0
                }
                val start = carried?.progress ?: 0f

                fun follow() {
                    val progress = if (direction == 0) {
                        TabSwipe.rubberBand(travelled, width) / width
                    } else {
                        (start + TabSwipe.progressDelta(travelled, direction, width)).coerceIn(0f, 1f)
                    }
                    paging.follow(anchor, direction, progress)
                }

                /** 手势结束（释放或被抢走）：先提交选择，再把画面收到终态。 */
                fun finish(commit: Boolean) {
                    settled = true
                    val live = indexState
                    // direction 为 0 是边界阻尼：没有目标页，只回弹。
                    val switchedPage = commit && direction != 0
                    val next = if (switchedPage) anchor + direction else anchor
                    val switched = next != live && next in 0 until count
                    if (switched) {
                        paging.expect(next)
                        selectState(next)
                    }
                    val progress = paging.frame?.progress ?: 0f
                    paging.settle(
                        anchor,
                        direction,
                        progress,
                        if (switchedPage) 1f else 0f,
                        reducedState,
                        holdUntilSelection = switched,
                    )
                }

                follow()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || size.width.toFloat() != width) break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    travelled += change.positionChange().x
                    follow()
                    change.consume()
                    if (!change.pressed) {
                        val progress = paging.frame?.progress ?: 0f
                        val towardTarget = -direction * tracker.calculateVelocity().x
                        val hasTarget = direction != 0 && anchor + direction in 0 until count
                        finish(TabSwipe.commitProgress(progress, towardTarget, velocityThreshold, hasTarget))
                        break
                    }
                }
                if (!settled) {
                    // 子级抢走事件或宽度变化属于取消：回弹，不提交选择。
                    finish(commit = false)
                }
            } finally {
                if (adopted && !settled) paging.resume(reducedState)
            }
        }
    }
    LaunchedEffect(reduced) { if (reduced) paging.reset() }
    return TabSwipeHandle(modifier, paging.frameState)
}
