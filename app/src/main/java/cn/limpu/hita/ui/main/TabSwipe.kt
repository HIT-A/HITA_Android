package cn.limpu.hita.ui.main

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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
import cn.limpu.hita.ui.design.rememberHorizontalMotion
import kotlin.math.abs

/**
 * 主界面左右滑动切页的纯数学部分。
 *
 * 不引用 Android 类型，因此可以在 JVM 单测里直接断言判定和跟手位移。
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

    private const val FOLLOW_LIMIT_FRACTION = 0.12f

    /** 单容器不能展示相邻页，限制露出的空白，同时仍按真实手势距离判断提交。 */
    fun follow(travelled: Float, widthPx: Float): Float {
        val limit = widthPx * FOLLOW_LIMIT_FRACTION
        if (limit <= 0f) return 0f
        return travelled.coerceIn(-limit, limit)
    }

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
     * 拖动时仅作轻量跟手反馈；内容在切换前后都保持可见。
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
 * 松手即提交目标，当前页和目标页在同一容器交接；收尾位移随时可被新手势接管。
 * 边界：
 * - 当前页是时间表时不启用：它自身的左右拖动用于切换周次。
 * - 相邻页取自 `visibleTabs`，被关掉的 Tab 不在列表里，无法滑到。
 * - 首/尾页朝外方向只做阻尼位移，不会回绕。
 *
 * 位移由手势与收尾共用；页面身份独立于位移动画。
 */
@Composable
internal fun rememberTabSwipe(
    enabled: Boolean,
    currentIndex: Int,
    pageCount: Int,
    onSelect: (Int) -> Unit,
): TabSwipeHandle {
    val motion = rememberHorizontalMotion()
    var widthPx by remember { mutableFloatStateOf(0f) }
    val reduced = LocalHitaReducedMotion.current
    val reducedState by rememberUpdatedState(reduced)
    val indexState by rememberUpdatedState(currentIndex)
    val countState by rememberUpdatedState(pageCount)
    val selectState by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    val velocityThreshold = with(density) { TabSwipe.COMMIT_VELOCITY_DP.dp.toPx() }

    val offset = remember(motion) { derivedStateOf { motion.offset } }
    val alpha = remember { derivedStateOf { TabSwipe.alpha(offset.value, widthPx) } }

    val modifier = Modifier.onSizeChanged {
        val width = it.width.toFloat()
        if (widthPx != width) motion.reset()
        widthPx = width
    }.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var travelled = 0f
            awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                travelled = overSlop
                change.consume()
            } ?: return@awaitEachGesture
            val width = size.width.toFloat()
            if (width <= 0f) return@awaitEachGesture
            val startOffset = motion.takeOver()
            val index = indexState
            val count = countState
            val canPrevious = index > 0
            val canNext = index < count - 1
            fun follow() {
                val allowed = if (travelled < 0f) canNext else canPrevious
                motion.follow(if (allowed) {
                    TabSwipe.follow(startOffset + travelled, width)
                } else {
                    TabSwipe.follow(startOffset + TabSwipe.rubberBand(travelled, width), width)
                })
            }
            try {
                follow()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || size.width.toFloat() != width) break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    travelled += change.positionChange().x
                    follow()
                    change.consume()
                    if (!change.pressed) {
                        if (index == indexState && count == countState) {
                            val direction = TabSwipe.commitDirection(
                                travelled, width, tracker.calculateVelocity().x, velocityThreshold, canPrevious, canNext,
                            )
                            if (direction != 0) selectState(index + direction)
                        }
                        break
                    }
                }
            } finally {
                motion.settle(reducedState)
            }
        }
    }
    LaunchedEffect(reduced) { if (reduced) motion.reset() }
    return TabSwipeHandle(modifier, offset, alpha)
}
