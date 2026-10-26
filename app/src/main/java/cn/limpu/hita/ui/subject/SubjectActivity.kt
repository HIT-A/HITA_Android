package cn.limpu.hita.ui.subject

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.limpu.hita.R
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.event.add.PopupAddEvent
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.CourseCodeUtils
import cn.limpu.hita.utils.EventsUtils
import com.limpu.style.widgets.PopUpFloatPicker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SubjectActivity : HiltBaseActivity<ComposeViewBinding>() {
    protected val viewModel: SubjectViewModel by viewModels()
    private val launcher by lazy { SubjectResourceLauncher(this) }
    private val renderer by lazy { SubjectMarkdownRenderer(this, launcher::openLink) }

    override fun initViewBinding() = ComposeViewBinding(ComposeView(this))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.screen.value.selectedEventIds.isNotEmpty()) {
                    viewModel.clearSelection()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun initViews() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.notices.collect { notice ->
                    val message = if (!notice.success) getString(R.string.operation_failed)
                    else getString(if (notice.updated) R.string.subject_batch_updated else R.string.subject_batch_deleted, notice.count)
                    Toast.makeText(this@SubjectActivity, message, Toast.LENGTH_SHORT).show()
                    if (notice.success && notice.skippedTime) {
                        Toast.makeText(this@SubjectActivity, R.string.subject_batch_week_missing, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        (binding.root as ComposeView).setContent {
            val state by viewModel.screen.collectAsState()
            val resources by viewModel.resources.state.collectAsState()
            HitaComposeTheme {
                SubjectScreen(
                    state = state,
                    resources = resources,
                    renderer = renderer,
                    actions = SubjectScreenActions(
                        onBack = { onBackPressedDispatcher.onBackPressed() },
                        onOpenTimetable = { ActivityUtils.startTimetableDetailActivity(this@SubjectActivity, it.id) },
                        onTeacherLongClick = {
                            state.teachers.firstOrNull()?.takeIf(String::isNotBlank)?.let {
                                ActivityUtils.startTeacherHomepageSearch(this@SubjectActivity, it)
                            }
                        },
                        onCreditClick = ::pickCredit,
                        onContribute = ::startContribute,
                        onCandidateClick = viewModel.resources::toggleCandidate,
                        onExternalClick = viewModel.resources::toggleExternal,
                        onExternalBack = viewModel.resources::backExternal,
                        onExternalEntryClick = { key, entry -> viewModel.resources.selectEntry(key, entry)?.let(launcher::open) },
                        onToggleCourseExpand = viewModel::toggleCourseExpand,
                        onCourseClick = { event ->
                            if (viewModel.screen.value.selectedEventIds.isNotEmpty()) viewModel.selectCourse(event.id)
                            else EventsUtils.showEventItem(this@SubjectActivity, event)
                        },
                        onCourseLongClick = {
                            viewModel.selectCourse(it.id, toggle = false)
                            binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        },
                        onAddCourse = ::addCourse,
                        onClearSelection = viewModel::clearSelection,
                        onUpdateCourses = viewModel::updateCourses,
                        onDeleteCourses = viewModel::deleteCourses,
                    ),
                )
            }
        }
    }

    private fun pickCredit() {
        if (!viewModel.screen.value.subjectMetaSupported) return
        val key = viewModel.resources.state.value.key ?: return
        val subject = viewModel.screen.value.subject ?: return
        PopUpFloatPicker()
            .setDialogTitle(R.string.subject_credit)
            .setInitialValue(subject.credit)
            .setOnDialogConformListener(object : PopUpFloatPicker.OnDialogConformListener {
                override fun onClick(result: Float) { viewModel.saveCredit(key, result) }
            })
            .show(supportFragmentManager, "pick")
    }

    private fun startContribute() {
        val key = viewModel.resources.state.value.key ?: return
        val resolved = viewModel.resources.state.value.selectedCandidate
        val fallbackCode = CourseCodeUtils.normalize(key.code)?.takeIf(String::isNotBlank)
            ?: key.code?.takeIf(String::isNotBlank) ?: key.name
        ActivityUtils.startCourseContributionActivity(
            this,
            resolved?.repoName?.takeIf(String::isNotBlank) ?: fallbackCode,
            resolved?.courseName?.takeIf(String::isNotBlank) ?: key.name,
            resolved?.courseCode?.takeIf(String::isNotBlank) ?: fallbackCode,
            resolved?.repoType?.takeIf(String::isNotBlank) ?: "normal",
        )
    }

    private fun addCourse() {
        val subject = viewModel.subjectLiveData.value ?: return
        val timetable = viewModel.timetableLiveData.value ?: return
        if (subject.id != viewModel.resources.state.value.key?.subjectId) return
        PopupAddEvent().setInitTimetable(timetable).setInitSubject(subject)
            .show(supportFragmentManager, "add_event")
    }

    override fun onStart() {
        super.onStart()
        intent.getStringExtra("subjectId")?.let(viewModel::startRefresh)
    }
}
