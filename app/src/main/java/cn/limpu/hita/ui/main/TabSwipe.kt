package cn.limpu.hita.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 主界面左右滑动切页的纯数学部分。
 *
 * 不引用 Android 类型，因此可以在 JVM 单测里直接断言判定、阻尼与动画端点；
 * 手势接线见 [rememberTabSwipe]。
 */
internal object TabSwipe {

    /** 松手后判定为「左滑下一页」需要越过的屏宽比例。 */
    const val COMMIT_FRACTION = 0.22f

    /** 速度阈值（dp/s）：快速轻扫在位移不足时也提交。 */
    const val COMMIT_VELOCITY_DP = 560f

    /** 无目标页时（首/尾页）回弹的阻尼系数，位移按该比例缩量。 */
    const val RUBBER_DAMPING = 0.30f

    /** 无目标页时的跟手位移上限（屏宽比例）。 */
    const val RUBBER_LIMIT_FRACTION = 0.08f

    /** 跟手位移上限（屏宽比例）；有目标页时两页一起平移，因此不需要阻尼。 */
    const val FOLLOW_LIMIT_FRACTION = 1f

    /** 滑出/滑入单段时长（ms）。与时间表切周次保持同一节奏。 */
    const val SETTLE_MS = 150

    /**
     * 松手方向：+1 下一页，-1 上一页，0 回弹。
     *
     * 位移达阈值或速度达阈值都算提交；速度越过阈值时以速度为准（与 PagerSnapHelper 一致），
     * 「拖出去再甩回来」因此不会误切。目标方向没有相邻页时一律回弹。
     */
    fun commitDirection(
        travelled: Float,
        widthPx: Float,
        velocityPxPerSecond: Float,
        velocityThresholdPxPerSecond: Float,
        canPrevious: Boolean,
        canNext: Boolean,
    ): Int {
        if (widthPx <= 0f) return 0
        val byDistance = when {
            travelled <= -widthPx * COMMIT_FRACTION -> 1
            travelled >= widthPx * COMMIT_FRACTION -> -1
            else -> 0
        }
        val byVelocity = when {
            velocityPxPerSecond <= -velocityThresholdPxPerSecond -> 1
            velocityPxPerSecond >= velocityThresholdPxPerSecond -> -1
            else -> 0
        }
        val direction = if (byVelocity != 0) byVelocity else byDistance
        return when {
            direction > 0 && canNext -> 1
            direction < 0 && canPrevious -> -1
            else -> 0
        }
    }

    /** 首/尾页的跟手位移：按 [RUBBER_DAMPING] 缩量并封顶。 */
    fun rubberBand(travelled: Float, widthPx: Float): Float {
        if (widthPx <= 0f) return 0f
        val limit = widthPx * RUBBER_LIMIT_FRACTION
        return (travelled * RUBBER_DAMPING).coerceIn(-limit, limit)
    }

    /**
     * 提交后当前页继续推离屏幕的终点：方向与手势一致，因此全程没有回头。
     *
     * 推到这个端点时整页恰好完全离屏（[alpha] 同时归零），换页在这一帧发生，
     * 用户看不到内容跳变。
     */
    fun exitTarget(direction: Int, widthPx: Float): Float = -direction * widthPx

    /** 新页的入场起点：位于推离方向的对侧边缘，随后滑回 0。 */
    fun enterSource(direction: Int, widthPx: Float): Float = direction * widthPx

    /**
     * 位移对应的不透明度：静止为 1，整页离屏为 0。
     *
     * 时间表切周次用的是同一套做法——淡出既给出速度感，也掩盖了换页本身的异步代价
     * （Fragment 事务、首帧测量），因此不会出现「先白屏再出内容」的断层。
     */
    fun alpha(offsetPx: Float, widthPx: Float): Float {
        if (widthPx <= 0f) return 1f
        return (1f - abs(offsetPx) / widthPx).coerceIn(0f, 1f)
    }
}

/** 手势句柄：位移与不透明度都是 [State]，请在 layer 阶段读取，避免逐帧重组。 */
internal class TabSwipeHandle internal constructor(
    val modifier: Modifier,
    /** 当前位移（px，正数向右）。 */
    val offset: State<Float>,
    /** 位移对应的不透明度。 */
    val alpha: State<Float>,
)

/**
 * 主界面左右滑动切页手势。
 *
 * 为什么在 Compose 层：页面容器由 `AndroidView` 托管，而 `AndroidViewHolder` 通过
 * `pointerInteropFilter` 直接接管触摸、不实现 `dispatchTouchEvent`/`onInterceptTouchEvent`，
 * 因此 Android 视图层的 `onInterceptTouchEvent` 永远不会被调用。挂在 Compose 祖先节点上，
 * 子级（内部横向 Pager、竖向列表）消费事件时 `awaitHorizontalTouchSlopOrCancellation`
 * 会直接取消，切页不会与内容滚动抢事件。
 *
 * 收尾动线与时间表切周次一致：松手后当前页沿手势方向继续推完整个屏宽，
 * 在完全离屏（[TabSwipe.alpha] 为 0）的那一帧换页，再从对侧边缘滑回。
 * 方向自始至终不变，因此不会出现「松手瞬间反向跳一下再慢慢弹回」的顿挫。
 *
 * 边界：
 * - 当前页是时间表时不启用：它自身的左右拖动用于切换周次。
 * - 相邻页取自 `visibleTabs`，被关掉的 Tab 不在列表里，无法滑到。
 * - 首/尾页朝外方向只做阻尼位移，不会回绕。
 *
 * 位移只有一个来源：拖动阶段写 [dragAccum]，收尾阶段写 [animOffset]，由 [settling] 二选一。
 */
@Composable
internal fun rememberTabSwipe(
    enabled: Boolean,
    currentIndex: Int,
    pageCount: Int,
    onSelect: (Int) -> Unit,
): TabSwipeHandle {
    // 刻意不按 enabled 重建：滑向时间表时 enabled 会立刻变 false，
    // 若此时丢弃动画状态，入场动画被打断，新页会直接"啪"地出现。
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val animOffset = remember { Animatable(0f) }
    var settling by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val indexState by rememberUpdatedState(currentIndex)
    val countState by rememberUpdatedState(pageCount)
    val selectState by rememberUpdatedState(onSelect)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val velocityThreshold = with(density) { TabSwipe.COMMIT_VELOCITY_DP.dp.toPx() }

    // derivedStateOf 让读取落在 layer 阶段：动画每帧只失效绘制层，不重组整棵子树。
    val offset = remember { derivedStateOf { if (settling) animOffset.value else dragAccum } }
    val alpha = remember { derivedStateOf { TabSwipe.alpha(offset.value, widthPx) } }

    val modifier = Modifier.pointerInput(enabled) {
        if (!enabled) {
            dragAccum = 0f
            return@pointerInput
        }
        awaitEachGesture {
            // 必须先挂起等一次真实按下，再判断闸门：
            // `awaitEachGesture` 的块体若在不挂起的情况下直接返回，外层 while 会立刻重入，
            // 形成空转并烧死主线程（实测表现为「输入分发超时」的 ANR）。
            val down = awaitFirstDown(requireUnconsumed = false)
            // 收尾动画未结束时忽略这次手势：否则一次注入式滑动会叠加出两页。
            if (settling) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var travelled = 0f
            // 子级已消费（内部横向 Pager / 列表越过 slop 先接管）时返回 null，切页不触发。
            awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                travelled = overSlop
                change.consume()
            } ?: return@awaitEachGesture
            val width = size.width.toFloat()
            if (width <= 0f) return@awaitEachGesture
            widthPx = width
            // 每次手势开始都读最新索引；闭包里的旧值会让连续手势按上一页算，一次滑动翻两页。
            val index = indexState
            val canPrevious = index > 0
            val canNext = index < countState - 1
            fun follow(raw: Float) {
                travelled = raw
                val allowed = (raw < 0f && canNext) || (raw > 0f && canPrevious)
                dragAccum = if (allowed) {
                    raw.coerceIn(-width * TabSwipe.FOLLOW_LIMIT_FRACTION, width * TabSwipe.FOLLOW_LIMIT_FRACTION)
                } else {
                    TabSwipe.rubberBand(raw, width)
                }
            }
            follow(travelled)
            var consumedByChild = false
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                if (change.isConsumed) {
                    consumedByChild = true
                    break
                }
                follow(travelled + change.positionChange().x)
                change.consume()
                if (!change.pressed) break
            }
            val from = dragAccum
            settling = true
            dragAccum = 0f
            val direction = if (consumedByChild) {
                0
            } else {
                TabSwipe.commitDirection(
                    travelled = travelled,
                    widthPx = width,
                    velocityPxPerSecond = tracker.calculateVelocity().x,
                    velocityThresholdPxPerSecond = velocityThreshold,
                    canPrevious = canPrevious,
                    canNext = canNext,
                )
            }
            if (direction == 0 || from == 0f) {
                // 回弹：同一段 spring 收尾，不换页。
                if (from != 0f) {
                    scope.launch {
                        try {
                            animOffset.snapTo(from)
                            animOffset.animateTo(0f, spring())
                        } finally {
                            withContext(NonCancellable) { animOffset.snapTo(0f) }
                            settling = false
                        }
                    }
                } else {
                    settling = false
                }
                return@awaitEachGesture
            }
            val target = (index + direction).coerceIn(0, countState - 1)
            scope.launch {
                try {
                    animOffset.snapTo(from)
                    // 沿手势方向把当前页推离屏幕，方向不变、不停顿。
                    animOffset.animateTo(TabSwipe.exitTarget(direction, width), tween(TabSwipe.SETTLE_MS))
                    selectState(target)
                    // 等到组合真的换成本页再滑入：否则会先露出旧页，再"换脸"一次。
                    awaitIndexChange(index, maxFrames = 12) { indexState }
                    animOffset.snapTo(TabSwipe.enterSource(direction, width))
                    animOffset.animateTo(0f, tween(TabSwipe.SETTLE_MS))
                } finally {
                    // 组合被销毁时协程已被取消，这是最后一次挂起机会：必须 NonCancellable，
                    // 否则位移会停在半途，下次进入整页偏移。
                    withContext(NonCancellable) { animOffset.snapTo(0f) }
                    settling = false
                }
            }
        }
    }

    // 页面不可滑动时（时间表）清掉拖动残值；不碰 animOffset，让进行中的入场动画走完。
    LaunchedEffect(enabled) {
        if (!enabled) dragAccum = 0f
    }

    return TabSwipeHandle(modifier, offset, alpha)
}

/**
 * 等待组合切换到目标页，最多 [maxFrames] 帧。
 *
 * 换页要走 ViewModel → 状态流 → 重组 → Fragment 事务，不是同一帧完成的事。
 * 有界等待保证状态异常时也能收尾，不会把页面永久留在离屏位置。
 */
private suspend fun awaitIndexChange(previous: Int, maxFrames: Int, current: () -> Int) {
    repeat(maxFrames) {
        if (current() != previous) return
        withFrameNanos { }
    }
}
