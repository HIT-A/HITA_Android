package cn.limpu.hita.ui.main.timeline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.limpu.hita.R
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseFragmentWithReceiver
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.eas.exam.ExamActivity
import cn.limpu.hita.ui.eas.imp.ImportTimetableActivity
import cn.limpu.hita.ui.timetable.manager.TimetableManagerActivity
import cn.limpu.hita.ui.widgets.WidgetUtils
import cn.limpu.hita.utils.EventsUtils
import cn.limpu.hita.utils.TimeTools
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FragmentTimeLine : HiltBaseFragmentWithReceiver<ComposeViewBinding>() {
    private val viewModel: FragmentTimelineViewModel by viewModels()
    private var controller: MainPageController? = null
    private val rendered = mutableStateOf<TodayUiState?>(null)

    override var receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.onTimeChanged()
        }
    }
    override fun onAttach(context: Context) { super.onAttach(context); controller = context as? MainPageController }
    override fun onDetach() { controller = null; super.onDetach() }
    fun onTabActivated() { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.onTimeChanged() }
    override fun initViewBinding() = ComposeViewBinding(ComposeView(requireContext()))
    override fun initViews(view: View) {
        (view as ComposeView).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { HitaComposeTheme {
                rendered.value?.let { state -> TodayScreen(state, onAction = { handleAction(state, it) }) }
            } }
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.onVisible()
                try {
                    viewModel.uiState.collectLatest { state ->
                        rendered.value = state
                        val calendar = Calendar.getInstance().apply { timeInMillis = state.now }
                        val context = requireContext()
                        // 主标题是日期，次级是教学周与星期；周次未知时只留星期。
                        val date = TimeTools.getDateString(context, calendar, true, TimeTools.TTY_NONE)
                        val weekday = context.resources.getStringArray(R.array.dow2)[TimeTools.getDow(calendar.timeInMillis) - 1]
                        controller?.setTimelineTitleText(date)
                        controller?.setTimelineSubtitleText(state.weekNumber?.let { "第${it}周 $weekday" } ?: weekday)
                        if (viewModel.consumeWidgetChange(state)) activity?.let(WidgetUtils::sendRefreshToAll)
                        // New Room content must reschedule an earlier event boundary immediately.
                        delay(viewModel.nextWakeDelay(state))
                        viewModel.onTimeChanged()
                    }
                } finally { viewModel.onHidden() }
            }
        }
    }
    private fun handleAction(state: TodayUiState, action: TodayAction) {
        if (action is TodayAction.SelectScope) {
            if (controller?.selectTodayScope(action.scope) != true) stale()
            return
        }
        val accepted = if (action is TodayAction.OpenHomework) viewModel.acceptsHomework(state, action.source) else viewModel.accepts(state)
        if (!accepted) { stale(); return }
        when (action) {
            is TodayAction.OpenEvent -> viewLifecycleOwner.lifecycleScope.launch {
                val event = viewModel.resolveEvent(state, action.id)
                if (event != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) context?.let { EventsUtils.showEventItem(it, event) }
                else if (isAdded) stale()
            }
            is TodayAction.OpenHomework -> controller?.openTodayHomework()
            TodayAction.OpenExams -> startActivity(Intent(requireContext(), ExamActivity::class.java))
            TodayAction.SelectTimetable -> startActivity(Intent(requireContext(), TimetableManagerActivity::class.java))
            TodayAction.ImportTimetable -> startActivity(Intent(requireContext(), ImportTimetableActivity::class.java))
            is TodayAction.Retry -> viewModel.retry(action.source)
            is TodayAction.SelectScope -> Unit // handled before snapshot acceptance
        }
    }
    private fun stale() {
        Toast.makeText(requireContext(), R.string.today_stale_action, Toast.LENGTH_SHORT).show()
        viewModel.onTimeChanged()
    }
    interface MainPageController {
        fun setTimelineTitleText(string: String)
        fun setTimelineSubtitleText(string: String)
        fun selectTodayScope(scope: cn.limpu.hita.data.model.timetable.DisplayScope): Boolean
        fun openTodayHomework()
    }
    override fun getIntentFilter() = IntentFilter().apply {
        addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_TICK)
        addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }
}
