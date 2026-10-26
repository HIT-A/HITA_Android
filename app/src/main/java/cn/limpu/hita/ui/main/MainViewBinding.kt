package cn.limpu.hita.ui.main

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.viewbinding.ViewBinding

/** Mount containers synchronously so Fragment restoration can resolve ids before Compose measures. */
class MainViewBinding(context: Context) : ViewBinding {
    private val layout = FrameLayout(context)
    val composeView = ComposeView(context)
    private val parking = FrameLayout(context).apply { visibility = View.GONE }

    init {
        layout.addView(parking, ViewGroup.LayoutParams(-1, -1))
        layout.addView(composeView, ViewGroup.LayoutParams(-1, -1))
    }

    fun parkTabContainers(view: View) { parking.addView(view, ViewGroup.LayoutParams(-1, -1)) }
    override fun getRoot(): View = layout
}
