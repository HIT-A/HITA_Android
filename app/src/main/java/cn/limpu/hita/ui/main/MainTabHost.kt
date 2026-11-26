package cn.limpu.hita.ui.main

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import cn.limpu.hita.ui.main.timeline.FragmentTimeLine

/**
 * Fragment ownership is independent of Compose recomposition and animation frames.
 *
 * 静止时只 show 选中页；翻页时由 [showFrame] 同时 show 选中页与相邻页，
 * 并按进度平移两个容器，形成真实的双页互推。
 */
internal class MainTabHost(
    private val manager: FragmentManager,
    savedState: Bundle?,
    private val factory: (MainTab) -> Fragment,
    private val activate: (Fragment) -> Unit = { (it as? FragmentTimeLine)?.onTabActivated() },
) : AutoCloseable {
    private val ids = MainTab.entries.associateWith { tab ->
        manager.findFragmentByTag(tag(tab))?.id?.takeIf { it > 0 }
            ?: savedState?.getInt("main_container_${tab.name}", 0)?.takeIf { it > 0 }
            ?: View.generateViewId()
    }
    private val containers = mutableMapOf<MainTab, FragmentContainerView>()
    private var root: FrameLayout? = null
    private var selected = MainTab.TIMELINE
    private var visible = MainTab.visibleFor(null)
    private var lastActivated: MainTab? = null

    /** 当前已 show 的页面集合：静止时只有选中页，翻页时多一页相邻页。 */
    private var shown: Set<MainTab> = emptySet()

    /** 结构（可见集合 + 选中页）最近一次应用的值；翻页提交后选中页变化必须重设交互。 */
    private var structure: Pair<Set<MainTab>, MainTab>? = null

    /** 当前画面；null 表示静止在 [selected] 上。 */
    private var frame: PagingFrame? = null
    private var closed = false

    fun createView(context: Context): FrameLayout {
        root?.let { return it }
        return FrameLayout(context).also { layout ->
            root = layout
            MainTab.entries.forEach { tab ->
                val container = FragmentContainerView(context).apply {
                    id = ids.getValue(tab)
                    visibility = View.INVISIBLE
                }
                containers[tab] = container
                layout.addView(container, ViewGroup.LayoutParams(-1, -1))
            }
        }
    }

    fun render(selectedTab: MainTab, visibleTabs: List<MainTab>) {
        if (closed) return
        visible = visibleTabs.toList()
        selected = selectedTab.takeIf { it in visible } ?: visible.firstOrNull { it == MainTab.TIMELINE } ?: visible.first()
        if (frame?.let { validPair(it) } != true) frame = null
        sync()
        // 首帧时 View 往往还没 attach，sync 会提前返回；等这一帧布局完成再补一次，
        // 否则冷启动会停在“容器存在但全部 INVISIBLE”的空白状态，直到用户切换 Tab 才恢复。
        root?.post { sync() }
    }
    /**
     * 应用一次翻页画面：两页同屏、按进度各自平移；[frame] 为 null 时回到静止状态。
     *
     * 相邻页只保持 STARTED：可见但不可交互，焦点与读屏都不会进入。
     */
    fun showFrame(frame: PagingFrame?) {
        if (closed) return
        this.frame = frame?.takeIf { validPair(it) }
        sync()
    }

    /** State-saved changes are applied after FragmentActivity clears its saved-state barrier. */
    fun resume() {
        // 系统重建或返回前台后，容器的可见性/`show`-`hide` 可能已被打乱，
        // 而 `shown`/`structure` 还是旧值会让 sync 直接返回，页面就一直是空白。
        shown = emptySet()
        structure = null
        sync()
    }

    /** 画面里涉及的页面：静止时是选中页，翻页时再加锚点页与相邻页。 */
    private fun targets(): Set<MainTab> {
        val current = frame ?: return setOf(selected)
        val anchor = visible.getOrNull(current.anchorIndex) ?: return setOf(selected)
        val neighbor = neighborOf(current) ?: return setOf(selected, anchor)
        return setOf(selected, anchor, neighbor)
    }

    private fun validPair(current: PagingFrame): Boolean {
        val anchor = visible.getOrNull(current.anchorIndex) ?: return false
        if (anchor == selected) return true
        // 松手提交后 selected 已经是相邻页；此时锚点页仍要留在画面上收尾。
        return neighborOf(current) == selected || anchor == selected
    }

    private fun neighborOf(current: PagingFrame): MainTab? {
        if (current.direction == 0) return null
        return visible.getOrNull(current.anchorIndex + current.direction)
    }

    private fun sync() {
        if (closed || root?.isAttachedToWindow != true || manager.isDestroyed || manager.isStateSaved) return
        val targetTabs = targets()
        if (targetTabs != shown) showFragments(targetTabs)
        val next = targetTabs to selected
        if (next != structure) {
            applyStructure(targetTabs)
            structure = next
        }
        applyTranslations()
    }

    private fun showFragments(targetTabs: Set<MainTab>) {
        val transaction = manager.beginTransaction().setReorderingAllowed(true)
        var active: Fragment? = null
        MainTab.entries.forEach { tab ->
            val wanted = tab in targetTabs
            val fragment = manager.findFragmentByTag(tag(tab)) ?: if (wanted) factory(tab).also {
                transaction.add(ids.getValue(tab), it, tag(tab))
            } else {
                null
            } ?: return@forEach
            if (fragment.isHidden == wanted) {
                if (wanted) transaction.show(fragment) else transaction.hide(fragment)
            }
            // 选中页 RESUMED 并接收交互；翻页中的另一页保持 STARTED，可见但不可交互。
            transaction.setMaxLifecycle(fragment, if (tab == selected) Lifecycle.State.RESUMED else Lifecycle.State.STARTED)
            if (tab == selected) active = fragment
        }
        transaction.commitNow()
        shown = targetTabs
        if (lastActivated != selected) {
            lastActivated = selected
            active?.let(activate)
        }
    }

    /** 可见性、交互、焦点与读屏：只在参与页集合或选中页变化时设置，避免逐帧触发无障碍事件。 */
    private fun applyStructure(targetTabs: Set<MainTab>) {
        MainTab.entries.forEach { tab ->
            val container = containers[tab] ?: return@forEach
            val isSelected = tab == selected
            container.visibility = if (tab in targetTabs) View.VISIBLE else View.INVISIBLE
            container.isEnabled = isSelected
            container.descendantFocusability = if (isSelected) {
                ViewGroup.FOCUS_BEFORE_DESCENDANTS
            } else {
                ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            container.importantForAccessibility = if (isSelected) {
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            if (isSelected) {
                container.bringToFront()
            } else {
                container.findFocus()?.clearFocus()
            }
        }
    }

    /** 两页互推只改 translationX；每帧执行，不触发事务或无障碍事件。 */
    private fun applyTranslations() {
        val width = root?.width?.toFloat() ?: 0f
        val current = frame
        val anchor = current?.let { visible.getOrNull(it.anchorIndex) }
        val neighbor = current?.let(::neighborOf)
        MainTab.entries.forEach { tab ->
            val container = containers[tab] ?: return@forEach
            container.translationX = when {
                current == null || width <= 0f -> 0f
                tab == anchor -> TabSwipe.selectedOffset(current.direction, current.progress) * width
                tab == neighbor -> TabSwipe.neighborOffset(current.direction, current.progress) * width
                else -> 0f
            }
        }
    }

    fun save(outState: Bundle) {
        ids.forEach { (tab, id) -> outState.putInt("main_container_${tab.name}", id) }
    }

    fun find(tab: MainTab): Fragment? = manager.findFragmentByTag(tag(tab))

    override fun close() { closed = true; containers.clear(); root = null }

    companion object {
        fun tag(tab: MainTab): String = "main_tab_${tab.name}"
    }
}

@Composable
internal fun MainFragmentPager(
    host: MainTabHost,
    selectedTab: MainTab,
    visibleTabs: List<MainTab>,
    onSelectTab: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 时间表自身的左右拖动用于切换周次，不参与翻页；其余页都可左右滑。
    val swipeEnabled = selectedTab != MainTab.TIMETABLE && visibleTabs.size > 1
    val swipe = rememberTabSwipe(
        enabled = swipeEnabled,
        currentIndex = visibleTabs.indexOf(selectedTab),
        pageCount = visibleTabs.size,
        onSelect = { index -> visibleTabs.getOrNull(index)?.let(onSelectTab) },
    )
    // Tab 显隐由 MainTabHost 决定；翻页进度只驱动两个容器的 translationX，
    // 不再对整个页面容器做 graphicsLayer 偏移，那会连同相邻页一起推走。
    val frame = swipe.frame
    LaunchedEffect(host, frame) {
        snapshotFlow { frame.value }.collect { host.showFrame(it) }
    }
    Box(modifier = modifier.then(swipe.modifier)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                host.createView(context).also { view ->
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) = host.resume()
                        override fun onViewDetachedFromWindow(v: View) = Unit
                    })
                }
            },
            update = { host.render(selectedTab, visibleTabs) },
        )
    }
    LaunchedEffect(host, frame) {
        snapshotFlow { frame.value }.collect { host.showFrame(it) }
    }
}
