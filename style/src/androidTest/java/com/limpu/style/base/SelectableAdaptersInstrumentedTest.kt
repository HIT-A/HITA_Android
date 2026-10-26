package com.limpu.style.base

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewbinding.ViewBinding
import com.limpu.style.testing.onMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectableAdaptersInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun selectionKeepsDataIndexAndNotifiesBothOldAndNewPositions() {
        onMain {
            val adapter = SingleAdapter(context, mutableListOf("first", "second", "third"))
            val notifications = Changes()
            adapter.registerAdapterDataObserver(notifications)
            try {
                adapter.setSelected("second")
                assertEquals(1, adapter.selectedIndex)
                assertEquals("second", adapter.selectedData)
                adapter.setSelected("missing")
                assertEquals(1, adapter.selectedIndex)
                assertEquals("second", adapter.selectedData)
                assertTrue(notifications.changed.isEmpty())
                adapter.choose(2)
                assertEquals(listOf(1, 2), notifications.changed)
                assertEquals(2, adapter.selectedIndex)
                assertEquals("third", adapter.selectedData)
                val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
                adapter.onBindViewHolder(holder, adapter.selectedIndex)
                assertEquals("third", (holder.itemView as TextView).text.toString())
            } finally { adapter.unregisterAdapterDataObserver(notifications) }
        }
    }

    @Test
    fun multipleSelectionEnforcesMinimumAndIgnoresOutOfRangeClicks() {
        onMain {
            val adapter = MultipleAdapter(context, mutableListOf("first", "second", "third"), 1)
            adapter.setChecked(listOf("first"))
            val notifications = Changes()
            adapter.registerAdapterDataObserver(notifications)
            try {
                adapter.toggle(0)
                assertEquals(listOf("first"), adapter.getCheckedData())
                adapter.toggle(1)
                assertEquals(listOf("first", "second"), adapter.getCheckedData())
                adapter.toggle(0)
                assertEquals(listOf("second"), adapter.getCheckedData())
                adapter.toggle(-1)
                adapter.toggle(3)
                assertEquals(listOf("second"), adapter.getCheckedData())
                assertEquals(listOf(0, 1, 0), notifications.changed)
            } finally { adapter.unregisterAdapterDataObserver(notifications) }
        }
    }

    @Test
    fun matcherSelectsExistingItemsInRequestOrderAndZeroMinimumAllowsEmpty() {
        onMain {
            val adapter = MultipleAdapter(context, mutableListOf("one", "four", "seven"), 0)
            val notifications = Changes()
            adapter.registerAdapterDataObserver(notifications)
            try {
                adapter.setChecked(listOf(5, 99, 3), object : BasicMultipleCheckableListAdapter.TargetMatcher<String, Int> {
                    override fun match(target: String, value: Int) = target.length == value
                })
                assertEquals(listOf("seven", "one"), adapter.getCheckedData())
                assertEquals(1, notifications.completeChanges)
                adapter.toggle(2)
                adapter.toggle(0)
                assertTrue(adapter.getCheckedData().isEmpty())
                assertEquals(listOf(2, 0), notifications.changed)
                val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
                adapter.onBindViewHolder(holder, 1)
                assertEquals("four", (holder.itemView as TextView).text.toString())
            } finally { adapter.unregisterAdapterDataObserver(notifications) }
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)
    private class TextBinding(context: Context) : ViewBinding {
        private val text = TextView(context)
        override fun getRoot(): View = text
    }
    private class SingleAdapter(context: Context, rows: MutableList<String>) :
        BasicSelectableListAdapter<String, Holder>(context, rows) {
        override fun getViewBinding(parent: ViewGroup, viewType: Int): ViewBinding = TextBinding(parent.context)
        override fun createViewHolder(viewBinding: ViewBinding, viewType: Int) = Holder(viewBinding.root)
        override fun bindHolder(holder: Holder, data: String?, position: Int) { (holder.itemView as TextView).text = data }
        fun choose(index: Int) { selectItem(index, beans[index]) }
    }
    private class MultipleAdapter(context: Context, rows: MutableList<String>, minimum: Int) :
        BasicMultipleCheckableListAdapter<String, Holder>(context, rows, minimum) {
        override fun getViewBinding(parent: ViewGroup, viewType: Int): ViewBinding = TextBinding(parent.context)
        override fun createViewHolder(viewBinding: ViewBinding, viewType: Int) = Holder(viewBinding.root)
        override fun bindHolder(holder: Holder, data: String?, position: Int) { (holder.itemView as TextView).text = data }
        fun toggle(index: Int) { checkItem(index) }
    }
    private class Changes : RecyclerView.AdapterDataObserver() {
        val changed = mutableListOf<Int>()
        var completeChanges = 0
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            assertEquals(1, itemCount)
            changed.add(positionStart)
        }
        override fun onChanged() { completeChanges++ }
    }
}
