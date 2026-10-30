package cn.limpu.hita.ui.main

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import cn.limpu.hita.ui.main.timeline.FragmentTimeLine

/** Fragment ownership is independent of Compose recomposition and animation frames. */
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
    private var lastRendered: Pair<MainTab, List<MainTab>>? = null
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
        applySelection()
        // 首帧时 View 往往还没 attach，applySelection 会提前返回；等这一帧布局完成再补一次，
        // 否则冷启动会停在"容器存在但全部 INVISIBLE"的空白状态，直到用户切换 Tab 才恢复。
        root?.post { applySelection() }
    }

    /** State-saved changes are applied after FragmentActivity clears its saved-state barrier. */
    fun resume() {
        // 系统重建或返回前台后，容器的可见性/`show`-`hide` 可能已被打乱，
        // 而 `lastRendered` 还是旧值会让 applySelection 直接返回，页面就一直是空白。
        lastRendered = null
        applySelection()
    }

    private fun applySelection() {
        if (closed || root?.isAttachedToWindow != true || manager.isDestroyed || manager.isStateSaved) return
        val selection = selected to visible
        if (lastRendered == selection) return
        val transaction = manager.beginTransaction().setReorderingAllowed(true)
        var changed = false
        val active = manager.findFragmentByTag(tag(selected)) ?: factory(selected).also {
            transaction.add(ids.getValue(selected), it, tag(selected))
            changed = true
        }
        MainTab.entries.forEach { tab ->
            val fragment = if (tab == selected) active else manager.findFragmentByTag(tag(tab))
            val isSelected = tab == selected && tab in visible
            containers.getValue(tab).apply {
                visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
                isEnabled = isSelected
                if (isSelected) bringToFront()
            }
            if (fragment != null) {
                if (fragment.isHidden == isSelected) {
                    if (isSelected) transaction.show(fragment) else transaction.hide(fragment)
                    changed = true
                }
                // A hidden page retains its view and observable state, with no resumed interaction.
                val desired = if (isSelected) Lifecycle.State.RESUMED else Lifecycle.State.STARTED
                transaction.setMaxLifecycle(fragment, desired)
                changed = true
            }
        }
        if (changed) transaction.commitNow()
        lastRendered = selection
        if (lastActivated != selected) {
            lastActivated = selected
            activate(active)
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
    modifier: Modifier = Modifier,
) {
    // Tab 显隐完全由 MainTabHost 的容器 visibility 决定。
    // 之前这里还叠了一层 animateFloatAsState → containers[tab].alpha，
    // 一旦动画停在 0（切 Tab/登录返回/首帧竞态都可能），容器仍 VISIBLE 但内容全透明：
    // 表现为“整页空白，但点击还能触发功能”，而且只能靠切换 Tab 或旋转屏幕恢复。
    AndroidView(
        modifier = modifier,
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
