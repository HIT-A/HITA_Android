package cn.limpu.hita.ui.main

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
        selected = selectedTab.takeIf { it in visible } ?: visible.first()
        applySelection()
    }

    /** State-saved changes are applied after FragmentActivity clears its saved-state barrier. */
    fun resume() { applySelection() }

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

    fun setTabAlpha(tab: MainTab, alpha: Float) { containers[tab]?.alpha = alpha }

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
    MainTab.entries.forEach { tab ->
        val selected = tab == selectedTab && tab in visibleTabs
        val alpha by animateFloatAsState(
            targetValue = if (selected) 1f else 0f,
            animationSpec = if (selected) spring(dampingRatio = 0.9f, stiffness = 300f) else snap(),
            label = "tab_alpha_${tab.name}",
        )
        // Drawing updates cannot create fragments or trigger tab activation.
        SideEffect { host.setTabAlpha(tab, alpha) }
    }
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
