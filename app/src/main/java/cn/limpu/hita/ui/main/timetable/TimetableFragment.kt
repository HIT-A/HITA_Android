package cn.limpu.hita.ui.main.timetable

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseFragment
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.event.add.PopupAddEvent
import cn.limpu.hita.ui.main.timetable.compare.TimetableSocialSheet
import cn.limpu.hita.ui.main.timetable.compare.TimetableComparisonActivity
import cn.limpu.hita.ui.timetable.friend.FriendTimetableActivity
import cn.limpu.hita.ui.timetable.share.TimetableShareActivity
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.EventsUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TimetableFragment : HiltBaseFragment<ComposeViewBinding>() {
    protected val viewModel: TimetableViewModel by viewModels()
    private var socialSheetOpen by mutableStateOf(false)
    private var mainPageController: MainPageController? = null
    private var viewOwner: Long? = null
    private var menu: PopupMenu? = null
    private var anchor: View? = null
    private var menuOpen: Runnable? = null
    private var scopeDialog: AlertDialog? = null

    fun openTimetableSocialSheet() { socialSheetOpen = true }
    fun navigateToWeek(mondayMillis: Long) = viewModel.navigateToWeek(mondayMillis)

    override fun initViewBinding() = ComposeViewBinding(ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    })

    override fun initViews(view: View) = Unit

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val owner = viewModel.attachView().also { viewOwner = it }
        (view as ComposeView).setContent {
            HitaComposeTheme {
                if (socialSheetOpen) TimetableSocialSheet(
                    onDismiss = { socialSheetOpen = false },
                    onShare = { navigate(owner, Intent(requireContext(), TimetableShareActivity::class.java)) },
                    onImport = { navigate(owner, FriendTimetableActivity.intent(requireContext(), true)) },
                    onFriends = { navigate(owner, FriendTimetableActivity.intent(requireContext())) },
                    onCompare = { navigate(owner, TimetableComparisonActivity.intent(requireContext())) },
                )
                TimetableRoute(
                    viewModel = viewModel,
                    onTitleState = { if (owns(owner)) applyTitleState(it) },
                    onEventClick = { if (owns(owner)) EventsUtils.showEventItem(requireActivity(), it) },
                    onEventLongClick = { event, position -> if (owns(owner)) showEventMenu(owner, event, position) },
                    onAddClick = { dow, period -> if (owns(owner)) showAddEvent(dow, period) },
                    onChangeInfoViewed = viewModel::markChangeInfoViewed,
                    onAdoptCourse = { viewModel.adoptIncomingCourse(it.termId, it.courseKey) },
                    onDismissCourse = { viewModel.dismissIncomingCourse(it.termId, it.courseKey) },
                    onAdoptBatch = { viewModel.adoptHeldBatch() },
                    onDismissBatch = { viewModel.dismissHeldBatch() },
                    onConfirmPending = viewModel::confirmPendingChanges,
                )
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.batches.notices.collect { if (owns(owner)) showNotice(it) }
            }
        }
    }

    private fun owns(owner: Long) = viewOwner == owner && viewModel.batches.owns(owner)

    override fun onAttach(context: Context) {
        super.onAttach(context)
        mainPageController = context as? MainPageController
    }

    override fun onDetach() {
        mainPageController = null
        super.onDetach()
    }

    override fun onStart() {
        super.onStart()
        viewModel.startRefresh()
    }

    override fun onDestroyView() {
        viewOwner?.let(viewModel::detachView)
        viewOwner = null
        socialSheetOpen = false
        closeMenu()
        scopeDialog?.setOnCancelListener(null)
        scopeDialog?.dismiss()
        scopeDialog = null
        super.onDestroyView()
    }

    private fun navigate(owner: Long, intent: Intent) {
        if (!owns(owner)) return
        socialSheetOpen = false
        startActivity(intent)
    }

    private fun applyTitleState(state: TimetableTitleState) = when (state) {
        is TimetableTitleState.Single -> mainPageController?.setSingleTitle(state.title)
        is TimetableTitleState.Week -> {
            mainPageController?.setTitleText(state.title)
            mainPageController?.setTimetableName(state.name)
        }
    }

    private fun showAddEvent(dow: Int, period: TimePeriodInDay) {
        val (timetable, week) = viewModel.currentTimetableAndWeek()
        if (timetable != null) {
            PopupAddEvent().setInitTimetable(timetable).setInitTime(dow, (week ?: 1).coerceAtLeast(1), period)
                .show(childFragmentManager, "add")
        } else {
            Toast.makeText(context, R.string.add_timetable_first, Toast.LENGTH_SHORT).show()
            activity?.let(ActivityUtils::startTimetableManager)
        }
    }

    private fun showEventMenu(owner: Long, event: EventItem, position: IntOffset) {
        closeMenu()
        viewModel.batches.cancel()
        val selection = viewModel.batches.selection(owner) ?: return
        if (TimetableEventPolicy.isReadOnlyProjection(event)) {
            showNotice(TimetableNotice.ReadOnly)
            return
        }
        val root = requireActivity().findViewById<FrameLayout>(android.R.id.content) ?: return
        val location = IntArray(2).also(root::getLocationInWindow)
        val target = View(requireContext()).apply { alpha = 0f; isHapticFeedbackEnabled = true }
        anchor = target
        root.addView(target, FrameLayout.LayoutParams(1, 1).apply {
            leftMargin = (position.x - location[0]).coerceIn(0, (root.width - 1).coerceAtLeast(0))
            topMargin = (position.y - location[1]).coerceIn(0, (root.height - 1).coerceAtLeast(0))
        })
        val open = Runnable {
            if (!viewModel.batches.owns(selection) || anchor !== target || target.parent == null) return@Runnable
            menu = PopupMenu(requireContext(), target, Gravity.NO_GRAVITY).apply {
                menu.add(0, R.id.menu_edit_event, 0, R.string.menu_edit)
                menu.add(0, R.id.menu_delete_event, 1, R.string.menu_delete).setIcon(R.drawable.ic_baseline_delete_24)
                setOnMenuItemClickListener { item ->
                    if (viewModel.batches.owns(selection)) showBatchScopePicker(owner, event, item.itemId == R.id.menu_edit_event)
                    true
                }
                setOnDismissListener { closeMenu() }
                show()
            }
        }
        menuOpen = open
        target.post(open)
    }

    private fun closeMenu() {
        menuOpen?.let { anchor?.removeCallbacks(it) }
        menuOpen = null
        menu?.setOnDismissListener(null)
        menu?.setOnMenuItemClickListener(null)
        menu?.dismiss()
        menu = null
        (anchor?.parent as? ViewGroup)?.removeView(anchor)
        anchor = null
    }

    private fun showBatchScopePicker(owner: Long, event: EventItem, edit: Boolean) {
        val selection = viewModel.batches.selection(owner) ?: return
        scopeDialog?.dismiss()
        scopeDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.subject_batch_scope_title)
            .setItems(arrayOf(getString(R.string.subject_batch_scope_this), getString(R.string.subject_batch_scope_slot), getString(R.string.subject_batch_scope_all))) { _, which ->
                viewModel.batches.request(selection, event, edit, TimetableBatchScope.entries[which])
            }
            .setNegativeButton(R.string.button_cancel) { _, _ -> if (viewModel.batches.owns(selection)) viewModel.batches.cancel() }
            .setOnCancelListener { if (viewModel.batches.owns(selection)) viewModel.batches.cancel() }
            .show()
    }

    private fun showNotice(notice: TimetableNotice) {
        val text = when (notice) {
            is TimetableNotice.Failure -> notice.message ?: getString(R.string.fail)
            is TimetableNotice.Updated -> getString(R.string.subject_batch_updated, notice.count)
            TimetableNotice.MissingTarget -> getString(R.string.loading)
            TimetableNotice.ReadOnly -> getString(R.string.timetable_projection_read_only)
        }
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
        if (notice is TimetableNotice.Updated && notice.skippedTime) {
            Toast.makeText(requireContext(), R.string.subject_batch_week_missing, Toast.LENGTH_SHORT).show()
        }
    }

    interface MainPageController {
        fun setTitleText(string: String)
        fun setTimetableName(String: String)
        fun setSingleTitle(string: String)
    }
}
