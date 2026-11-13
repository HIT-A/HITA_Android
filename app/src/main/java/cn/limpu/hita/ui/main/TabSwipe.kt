package cn.limpu.hita.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 主界面左右滑动切页的纯数学部分。
 *
 * 不引用 Android 类型，因此可以在 JVM 单测里直接断言判定与阻尼行为；
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
}

/** 手势句柄：[offsetPx] 为当前跟手位移（px，正数向右）。 */
internal class TabSwipeHandle internal constructor(
    val modifier: Modifier,
    private val offsetState: MutableFloatState,
) {
    val offsetPx: Float get() = offsetState.floatValue
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
 * 边界：
 * - 当前页是时间表时不启用：它自身的左右拖动用于切换周次。
 * - 相邻页取自 `visibleTabs`，被关掉的 Tab 不在列表里，无法滑到。
 * - 首/尾页朝外方向只做阻尼位移，不会回绕。
 *
 * 拖动阶段只写 [MutableFloatState]（`pointerInput` 是受限挂起作用域，不能驱动动画）；
 * 松手把归零请求交给 [LaunchedEffect]，由 `Animatable` 跑 spring，位移因此始终单一来源。
 */
@Composable
internal fun rememberTabSwipe(
    enabled: Boolean,
    currentIndex: Int,
    pageCount: Int,
    onSelect: (Int) -> Unit,
): TabSwipeHandle {
    val offset = remember(enabled) { mutableFloatStateOf(0f) }
    val indexState by rememberUpdatedState(currentIndex)
    val countState by rememberUpdatedState(pageCount)
    val selectState by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    val velocityThreshold = with(density) { TabSwipe.COMMIT_VELOCITY_DP.dp.toPx() }

    // 每次松手 +1，触发一次从当前位移回到 0 的动画；拖动中它不变，因此不会打断跟手。
    var settleRequest by remember(enabled) { mutableIntStateOf(0) }
    // 一次手势只允许提交一次切页：注入式滑动（`input swipe`）会在极短时间内结束，
    // 若不加这道闸，松手后的收尾与下一次手势叠加会把索引推进两页。
    val committedGeneration = remember(enabled) { mutableIntStateOf(0) }

    val modifier = Modifier.pointerInput(enabled) {
        if (!enabled) {
            offset.floatValue = 0f
            return@pointerInput
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
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
            // 每次手势开始都读最新索引；闭包里的旧值会让连续手势按上一页算，一次滑动翻两页。
            val index = indexState
            val canPrevious = index > 0
            val canNext = index < countState - 1
            fun follow(raw: Float) {
                travelled = raw
                val allowed = (raw < 0f && canNext) || (raw > 0f && canPrevious)
                offset.floatValue = if (allowed) {
                    raw.coerceIn(-width, width)
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
            if (!consumedByChild) {
                val velocity = tracker.calculateVelocity().x
                val direction = TabSwipe.commitDirection(
                    travelled = travelled,
                    widthPx = width,
                    velocityPxPerSecond = velocity,
                    velocityThresholdPxPerSecond = velocityThreshold,
                    canPrevious = canPrevious,
                    canNext = canNext,
                )
                if (direction != 0 && committedGeneration.intValue == 0) {
                    // 用手势结束时刻的最新索引，而不是手势开始时的快照。
                    val latest = indexState
                    selectState((latest + direction).coerceIn(0, countState - 1))
                    // 新页面从相反方向轻微推入，视觉上接住原页面的甩出。
                    offset.floatValue = -direction * width * ENTER_FRACTION
                    committedGeneration.intValue = 1
                }
            }
            // 收尾交给组合层动画；手势协程到此结束，不再挂起。
            settleRequest++
        }
    }

    LaunchedEffect(settleRequest) {
        if (settleRequest == 0) return@LaunchedEffect
        val animatable = Animatable(offset.floatValue)
        animatable.animateTo(0f, spring(dampingRatio = 0.88f, stiffness = 620f)) {
            offset.floatValue = value
        }
        // 收尾结束才允许下一次提交。
        committedGeneration.intValue = 0
    }

    return TabSwipeHandle(modifier, offset)
}

/** 新页面进入时的起始偏移（屏宽比例），只为让切页有个方向感。 */
private const val ENTER_FRACTION = 0.08f
