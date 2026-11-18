package cn.limpu.hita.ui.main

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import cn.limpu.hita.ui.design.hitaDeepSpaceCardModifier
import com.limpu.style.ThemeTools
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 三条弹簧的换算依据：`stiffness = (2π/T)²`、`dampingRatio = 1 - bounce`，
 * 其中 T 为期望周期、bounce 为回弹比例。
 */

/** 拖拽跟手：120 ms、bounce 0。 */
private val DockTrackSpring = spring<Float>(dampingRatio = 1f, stiffness = 2740f)

/** 按下抬升与栏体膨胀：280 ms、bounce 0.2。 */
private val DockLiftSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 504f)

/** 松手落定：500 ms、bounce 0.32。 */
private val DockSettleSpring = spring<Float>(dampingRatio = 0.68f, stiffness = 158f)

private const val DOCK_LENS_ALPHA = 0.12f
private const val DOCK_LENS_ALPHA_GLASS = 0.16f
private const val DOCK_LENS_ALPHA_DEEP_SPACE = 0.18f
private const val DOCK_LENS_LIFT_ALPHA = 0.08f

/** 超椭圆平滑系数：0 为纯圆弧，1 为最强过渡；连续圆角常用 0.5~0.7。issue #19 */
private const val DOCK_SMOOTHING = 0.6f

/**
 * 超椭圆胶囊路径。顶点直接给像素坐标：官方示例的归一化图形+矩阵缩放到目标尺寸
 * 会把圆角半径一起缩放，底栏宽高比随屏宽与标签数变化，非等比缩放会把圆角压变形。
 * issue #19
 */
private fun dockPillNativePath(
    width: Float,
    height: Float,
    smoothing: Float = DOCK_SMOOTHING,
): android.graphics.Path {
    val radius = min(width, height) / 2f
    val rounding = List(4) { CornerRounding(radius, smoothing) }
    return RoundedPolygon(
        vertices = floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height),
        perVertexRounding = rounding,
        centerX = 0f,
        centerY = 0f,
    ).toPath()
}

private fun dockPillPath(
    width: Float,
    height: Float,
    smoothing: Float = DOCK_SMOOTHING,
): Path = dockPillNativePath(width, height, smoothing).asComposePath()

private class DockPillShape(private val smoothing: Float = DOCK_SMOOTHING) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Generic(dockPillPath(size.width, size.height, smoothing))
}

private val DockShape: Shape = DockPillShape()

/**
 * 阴影继续用平台友好的胶囊轮廓：API 26~28 的系统 HWUI 只认矩形/圆角矩形/椭圆，
 * 通用路径轮廓从 API 29 起才支持投影。与超椭圆本体的轮廓差只在端头几个像素，
 * 实际不可见。issue #19
 */
private val DockShadowShape = RoundedCornerShape(percent = 50)

private val DockHazeStyle = HazeStyle(
    tint = Color.White.copy(alpha = 0.16f),
    blurRadius = 24.dp,
    noiseFactor = 0.05f,
)

/**
 * 底栏外边距：左右固定，底部取 `max(21dp, 系统导航栏)`。
 *
 * 交给调用方施加，是为了让抽屉动画那层 `graphicsLayer` 紧贴栏体，缩放原点不被外边距带偏。
 */
@Composable
internal fun dockBarChromePadding(): PaddingValues {
    val bottomInset = rememberMainNavigationBottom()
    return PaddingValues(
        start = DockMetrics.EDGE_MARGIN.dp,
        end = DockMetrics.EDGE_MARGIN.dp,
        top = DockMetrics.SHADOW_ROOM.dp,
        bottom = maxOf(DockMetrics.EDGE_MARGIN.dp, bottomInset),
    )
}

/** 已接入悬浮 dock 的风格。其余风格仍走 `MainPillTabBar`，物理与尺寸都不变。 */
internal fun usesDockTabBar(style: ThemeTools.STYLE): Boolean =
    style == ThemeTools.STYLE.CLASSIC ||
        style == ThemeTools.STYLE.APPLE_GLASS ||
        style == ThemeTools.STYLE.DEEP_SPACE

/**
 * 悬浮胶囊底栏。
 *
 * 选中态是一个以「项」为单位的浮点索引 lens：按下抬升、拖拽跟手、越界走橡皮筋、
 * 松手按横向速度投影落点。lens 掠过的项，其图标与文字会被逐块染成高亮色。
 *
 * 物理与尺寸与风格无关；风格只改形状、配色与表面效果，见 `usesDockTabBar`。
 */
@Composable
internal fun DockTabBar(
    selectedTab: Int,
    tabs: List<MainTab>,
    showBlogDot: Boolean,
    alpha: Float,
    themeStyle: ThemeTools.STYLE,
    hazeState: HazeState?,
    liquidGlassBackdrop: LiquidGlassBackdropHandle?,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val lens = remember { Animatable(selectedTab.toFloat(), Float.VectorConverter) }
    val lift = remember { Animatable(0f, Float.VectorConverter) }
    val swell = remember { Animatable(0f, Float.VectorConverter) }
    val stretch = remember { Animatable(0f, Float.VectorConverter) }

    val latestSelected = rememberUpdatedState(selectedTab)
    val latestTabs = rememberUpdatedState(tabs)
    val latestOnSelect = rememberUpdatedState(onSelectTab)

    val interaction = remember {
        DockInteraction(
            scope = scope,
            lens = lens,
            lift = lift,
            swell = swell,
            stretch = stretch,
            onTick = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
            onSelect = { latestOnSelect.value(it) },
        )
    }

    LaunchedEffect(selectedTab) {
        // 松手那一帧已经朝同一个目标起飞了，重复下发只会打断正在收敛的速度。
        if (interaction.isIdle && (lens.targetValue != selectedTab.toFloat())) {
            lens.animateTo(selectedTab.toFloat(), DockSettleSpring)
        }
    }

    val barHeight = dockBarHeight(DockMetrics.LABEL_SIZE * density.fontScale).dp
    val maxBarWidth = dockBarMaxWidth(tabs.size)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = DockMetrics.LABEL_SIZE.sp,
        lineHeight = DockMetrics.LABEL_SIZE.sp,
        letterSpacing = 0.sp,
    )
    val titles = tabs.map { context.getString(it.titleRes) }
    val textMeasurer = rememberTextMeasurer()
    val labelWidths = List(tabs.size) { index ->
        textMeasurer.measure(
            text = AnnotatedString(titles[index]),
            style = labelStyle.copy(fontWeight = labelFontWeight(index == selectedTab)),
            maxLines = 1,
        ).size.width.toFloat()
    }
    val widestLabel = labelWidths.maxOrNull() ?: 0f

    val isAppleGlass = themeStyle == ThemeTools.STYLE.APPLE_GLASS
    val isDeepSpace = themeStyle == ThemeTools.STYLE.DEEP_SPACE
    // 栏体在所有 dock 风格下都是胶囊。lens 本身就是胶囊，栏体用圆角矩形会与它不协调。
    val barShape: Shape = DockShape
    val barColor = if (isAppleGlass) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val barElevation = when {
        isAppleGlass -> 14.dp
        isDeepSpace -> 4.dp
        else -> 6.dp
    }
    val lensColor = when {
        isAppleGlass -> MaterialTheme.colorScheme.primary.copy(alpha = DOCK_LENS_ALPHA_GLASS)
        isDeepSpace -> MaterialTheme.colorScheme.primary.copy(alpha = DOCK_LENS_ALPHA_DEEP_SPACE)
        else -> CapsuleBlue.copy(alpha = DOCK_LENS_ALPHA)
    }
    val activeColor = if (isAppleGlass || isDeepSpace) {
        MaterialTheme.colorScheme.primary
    } else {
        ActiveBlue
    }

    // 底色与风格效果单独成层。这些效果（Surface 的 shape、深空卡片的 clip）都会裁剪子项，
    // 而 lens 必须能溢出栏体，因此只把这一层交给它们，lens 与内容留在外层。
    val barSurface = Modifier
        .then(
            if (isAppleGlass && liquidGlassBackdrop != null) {
                Modifier.liquidGlassSurface(
                    backdropHandle = liquidGlassBackdrop,
                    shape = DockShadowShape,
                    blurRadius = 22f,
                    refractionHeight = 26f,
                    refractionAmount = 40f,
                )
            } else {
                Modifier
            }
        )
        .then(
            if (isAppleGlass && hazeState != null) {
                Modifier.hazeChild(state = hazeState, shape = barShape, style = DockHazeStyle)
            } else {
                Modifier
            }
        )
        .then(
            when {
                isAppleGlass -> Modifier.border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.52f),
                    shape = barShape,
                )
                isDeepSpace -> Modifier.hitaDeepSpaceCardModifier(barShape, shadowShape = DockShadowShape)
                else -> Modifier
            }
        )

    val tintPaint = remember(activeColor) {
        Paint().apply { colorFilter = ColorFilter.tint(activeColor, BlendMode.SrcIn) }
    }
    val clipPath = remember { Path() }
    // 指示器超椭圆路径：remember + rewind 复用，避免每帧构造（issue #19 坑 5）。
    val lensPath = remember { Path() }
    val lensGrowthPx = with(density) { DockMetrics.LENS_GROWTH_DP.dp.toPx() }

    Box(
        modifier = modifier
            .alpha(alpha)
            .height(barHeight)
            .then(
                if (maxBarWidth == null) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.widthIn(max = maxBarWidth.dp).fillMaxWidth()
                }
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val count = latestTabs.value.size
                    if (count == 0) return@awaitEachGesture
                    val barWidth = size.width.toFloat()
                    val shortSide = min(size.width, size.height).toFloat()
                    val paddingPx = DockMetrics.BAR_PADDING.dp.toPx()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    interaction.press(down.position.x, barWidth, count, paddingPx)
                    var handled = false
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes
                                .firstOrNull { it.id == down.id } ?: break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (!change.pressed) {
                                interaction.release(
                                    velocityX = tracker.calculateVelocity().x,
                                    barWidth = barWidth,
                                    count = count,
                                    paddingPx = paddingPx,
                                    selected = latestSelected.value,
                                    commit = true,
                                )
                                handled = true
                                break
                            }
                            interaction.drag(
                                x = change.position.x,
                                barWidth = barWidth,
                                shortSide = shortSide,
                                count = count,
                                paddingPx = paddingPx,
                                touchSlop = viewConfiguration.touchSlop,
                            )
                        }
                    } finally {
                        // 手势被系统抢走时不能提交选择，回到已选项。
                        if (!handled) {
                            interaction.release(
                                velocityX = 0f,
                                barWidth = barWidth,
                                count = count,
                                paddingPx = paddingPx,
                                selected = latestSelected.value,
                                commit = false,
                            )
                        }
                    }
                }
            }
            .graphicsLayer {
                val scale = dockPressScale(
                    swell = swell.value,
                    maxDimensionPx = size.maxDimension,
                    maxGrowthPx = DockMetrics.MAX_PRESS_GROWTH_DP.dp.toPx(),
                )
                scaleX = scale * (1f + abs(stretch.value) / size.width * DockMetrics.PULL_STRETCH)
                scaleY = scale
                translationX = stretch.value
            }
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .shadow(barElevation, DockShadowShape, clip = false)
                .then(barSurface)
                .background(barColor, barShape)
        )

        if (tabs.isEmpty()) return@Box

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(DockMetrics.BAR_PADDING.dp)
        ) {
                val contentHeight = with(density) { maxHeight.toPx() }
                val extent = with(density) { maxWidth.toPx() } / tabs.size
                val room = extent - with(density) { (DockMetrics.LABEL_INSET * 2f).dp.toPx() }
                val labelScale = if (widestLabel <= 0f || widestLabel <= room) {
                    1f
                } else {
                    max(room / widestLabel, DockMetrics.MIN_LABEL_SIZE / DockMetrics.LABEL_SIZE)
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val box = dockLensRect(
                        position = lens.value,
                        velocity = lens.velocity,
                        extent = size.width / tabs.size,
                        height = size.height,
                        lift = lift.value,
                        growthPx = lensGrowthPx,
                    )
                    // 指示器与栏体同形：超椭圆，只在端头曲率处与胶囊不同。
                    // drawPath 没有 topLeft 参数，路径先平移到 box 左上角。issue #19
                    lensPath.rewind()
                    lensPath.asAndroidPath().apply {
                        set(dockPillNativePath(box.width, box.height))
                        offset(box.left, box.top)
                    }
                    drawPath(
                        path = lensPath,
                        color = lensColor.copy(
                            alpha = (
                                lensColor.alpha +
                                    DOCK_LENS_LIFT_ALPHA * lift.value.coerceIn(0f, 1f)
                                ).coerceAtMost(1f)
                        ),
                    )
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    tabs.forEachIndexed { index, tab ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .dockLensTint(
                                    lens = lens,
                                    lift = lift,
                                    index = index,
                                    extent = extent,
                                    height = contentHeight,
                                    growthPx = lensGrowthPx,
                                    tintPaint = tintPaint,
                                    clipPath = clipPath,
                                )
                                // 整条栏共享一个指针手势，单项只补无障碍语义，
                                // 否则 TalkBack 点不到任何 tab。
                                .semantics(mergeDescendants = true) {
                                    role = Role.Tab
                                    selected = index == selectedTab
                                    contentDescription = titles[index]
                                    onClick {
                                        onSelectTab(index)
                                        true
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box {
                                    Icon(
                                        painter = painterResource(tab.iconRes),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(DockMetrics.ICON_SIZE.dp),
                                    )
                                    // 资讯未读红点仍只留在资讯 tab。
                                    if (showBlogDot && tab == MainTab.BLOG) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 4.dp, y = (-2).dp)
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.error)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(DockMetrics.LABEL_GAP.dp))
                                Text(
                                    text = titles[index],
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = labelStyle.copy(
                                        fontSize = (DockMetrics.LABEL_SIZE * labelScale).sp,
                                        fontWeight = labelFontWeight(index == selectedTab),
                                    ),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
        }
    }
}

private fun labelFontWeight(active: Boolean): FontWeight =
    if (active) FontWeight.Bold else FontWeight.Normal

/**
 * 把落在 lens 内的内容重绘一遍并染成高亮色，内容本身再按 lens 的接近程度放大，
 * 形变只作用于绘制，不影响布局与命中。
 *
 * 数值一律在绘制阶段读取，因此每帧只失效绘制，不会重组整条栏。
 */
private fun Modifier.dockLensTint(
    lens: Animatable<Float, AnimationVector1D>,
    lift: Animatable<Float, AnimationVector1D>,
    index: Int,
    extent: Float,
    height: Float,
    growthPx: Float,
    tintPaint: Paint,
    clipPath: Path,
): Modifier = drawWithContent {
    val position = lens.value - index
    val box = dockLensRect(position, lens.velocity, extent, height, lift.value, growthPx)
    val emphasis = (1f - abs(position)).coerceIn(0f, 1f)
    val scale = 1f + DockMetrics.LENS_MAGNIFY * emphasis * lift.value
    val pivot = center
    val target = drawContext.canvas

    // `Canvas.scale` 不接受缩放中心，只好自己把它换算成一次平移。
    val paintContent: () -> Unit = {
        target.save()
        if (scale != 1f) {
            target.translate(pivot.x, pivot.y)
            target.scale(scale, scale)
            target.translate(-pivot.x, -pivot.y)
        }
        drawContent()
        target.restore()
    }

    paintContent()

    if (!box.overlaps(size.width, size.height)) return@drawWithContent

    clipPath.rewind()
    // 染色裁剪与指示器本体用同一函数产出的路径，平移到 box 左上角，
    // 否则指示器滑到首尾项时会与栏体端头轮廓错开、露出边缘。issue #19
    clipPath.asAndroidPath().apply {
        set(dockPillNativePath(box.width, box.height))
        offset(box.left, box.top)
    }
    target.save()
    target.clipPath(clipPath)
    target.saveLayer(Rect(Offset.Zero, size), tintPaint)
    paintContent()
    target.restore()
    target.restore()
}

/**
 * 底栏的手势状态机。
 *
 * 状态放在普通对象里而不是 `mutableStateOf`：这些值只在指针回调里读写，
 * 让它们触发重组只会白白重画整条栏。
 */
private class DockInteraction(
    private val scope: CoroutineScope,
    private val lens: Animatable<Float, AnimationVector1D>,
    private val lift: Animatable<Float, AnimationVector1D>,
    private val swell: Animatable<Float, AnimationVector1D>,
    private val stretch: Animatable<Float, AnimationVector1D>,
    private val onTick: () -> Unit,
    private val onSelect: (Int) -> Unit,
) {
    private var pressedIndex = -1
    private var pressX = 0f
    private var dragging = false

    val isIdle: Boolean get() = pressedIndex < 0

    fun press(x: Float, barWidth: Float, count: Int, paddingPx: Float) {
        val index = dockIndexAt(x, barWidth, count, paddingPx)
        pressedIndex = index
        pressX = x
        dragging = false
        scope.launch { lift.animateTo(1f, DockLiftSpring) }
        scope.launch { swell.animateTo(1f, DockLiftSpring) }
        scope.launch { lens.animateTo(index.toFloat(), DockSettleSpring) }
    }

    fun drag(
        x: Float,
        barWidth: Float,
        shortSide: Float,
        count: Int,
        paddingPx: Float,
        touchSlop: Float,
    ) {
        if (pressedIndex < 0) return
        if (!dragging) {
            if (abs(x - pressX) < touchSlop) return
            dragging = true
        }
        val lastIndex = (count - 1).toFloat()
        scope.launch {
            stretch.animateTo(
                dockRubberBand(
                    x - x.coerceIn(0f, barWidth),
                    shortSide * DockMetrics.PULL_LIMIT,
                ),
                DockTrackSpring,
            )
        }
        scope.launch {
            lens.animateTo(
                dockPositionAt(x, barWidth, count, paddingPx, lastIndex, DockMetrics.OVERDRAG),
                DockTrackSpring,
            )
        }
        val index = dockIndexAt(x, barWidth, count, paddingPx)
        if (index != pressedIndex) {
            pressedIndex = index
            onTick()
        }
    }

    fun release(
        velocityX: Float,
        barWidth: Float,
        count: Int,
        paddingPx: Float,
        selected: Int,
        commit: Boolean,
    ) {
        val index = pressedIndex
        val wasDragging = dragging
        pressedIndex = -1
        dragging = false
        scope.launch { lift.animateTo(0f, DockSettleSpring) }
        scope.launch { swell.animateTo(0f, DockSettleSpring) }
        scope.launch { stretch.animateTo(0f, DockSettleSpring) }
        if (!commit || index < 0) {
            scope.launch { lens.animateTo(selected.toFloat(), DockSettleSpring) }
            return
        }
        val target = if (wasDragging) {
            dockFlingIndex(
                lensTarget = lens.value,
                velocityX = velocityX,
                extent = (barWidth - paddingPx * 2f) / count,
                pressedIndex = index,
                lastIndex = count - 1,
                projectionSeconds = DockMetrics.FLING_PROJECTION_SECONDS,
            )
        } else {
            index
        }
        scope.launch { lens.animateTo(target.toFloat(), DockSettleSpring) }
        if (target != selected) {
            onTick()
            onSelect(target)
        }
    }

    private fun dockIndexAt(x: Float, barWidth: Float, count: Int, paddingPx: Float): Int {
        val lastIndex = count - 1
        if (lastIndex < 0) return 0
        return dockPositionAt(
            dx = x,
            barWidth = barWidth,
            count = count,
            padding = paddingPx,
            lastIndex = lastIndex.toFloat(),
            overdrag = DockMetrics.OVERDRAG,
        ).roundToInt().coerceIn(0, lastIndex)
    }
}
