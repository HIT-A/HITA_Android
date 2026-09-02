package cn.limpu.hita.ui.subject

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.graphics.Typeface
import android.text.Layout
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.agent.core.AgentProvider
import cn.limpu.hita.agent.core.AgentSession
import cn.limpu.hita.agent.subject.SubjectReadmeAgentFactory
import cn.limpu.hita.agent.subject.SubjectReadmeAgentInput
import cn.limpu.hita.agent.subject.SubjectReadmeAgentOutput
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.resource.CourseReadmeData
import cn.limpu.hita.data.model.resource.CourseResourceItem
import cn.limpu.hita.data.model.resource.ExternalCourseItem
import cn.limpu.hita.data.model.resource.ExternalResourceEntry
import cn.limpu.hita.data.model.resource.ResourceSource
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.ExternalResourceRepository
import cn.limpu.hita.data.repository.HoaRepository
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.event.add.PopupAddEvent
import cn.limpu.hita.ui.resource.MarkdownViewerActivity
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.CourseCodeUtils
import cn.limpu.hita.utils.CourseNameUtils
import cn.limpu.hita.utils.CourseResourceLinker
import cn.limpu.hita.utils.EventsUtils
import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.utils.TimeTools
import com.limpu.style.widgets.PopUpFloatPicker
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.text.DecimalFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Comparator
import java.util.regex.Pattern
import kotlin.math.roundToInt
import javax.inject.Inject

@AndroidEntryPoint
class SubjectActivity : HiltBaseActivity<ComposeViewBinding>() {

    @Inject lateinit var easRepository: EASRepository
    @Inject lateinit var hoaRepository: HoaRepository
    @Inject lateinit var externalResourceRepository: ExternalResourceRepository

    protected val viewModel: SubjectViewModel by viewModels()

    private val hoaCampus by lazy { easRepository.getHoaCampus() }
    private val subjectMetaSupported by lazy { easRepository.isSubjectMetaSupported() }
    private val readmeAgentProvider: AgentProvider<SubjectReadmeAgentInput, SubjectReadmeAgentOutput> by lazy {
        SubjectReadmeAgentFactory.createProvider()
    }

    private var readmeAgentSession: AgentSession<SubjectReadmeAgentInput, SubjectReadmeAgentOutput>? = null
    private var readmeResolveKey: String? = null
    private var currentReadmeSource: String = ""
    private var selectedReadmeCandidate: CourseResourceItem? = null
    private var readmeLiveData: LiveData<DataState<CourseReadmeData>>? = null
    private var readmeObserver: Observer<DataState<CourseReadmeData>>? = null
    private var externalResourceLiveData: LiveData<DataState<List<ExternalCourseItem>>>? = null
    private var externalResourceObserver: Observer<DataState<List<ExternalCourseItem>>>? = null

    private var courseExpanded by mutableStateOf(false)
    private var selectedEventIds by mutableStateOf(emptySet<String>())
    private var visibleClasses by mutableStateOf(emptyList<EventItem>())
    private var courseProgress by mutableStateOf(0)
    private var readmeUiState by mutableStateOf<SubjectReadmeUiState>(SubjectReadmeUiState.Loading)
    private var campusSourceHint by mutableStateOf("")
    private var hoaCandidates by mutableStateOf(emptyList<CourseResourceItem>())
    private var externalResources by mutableStateOf(emptyList<ExternalCourseItem>())

    private val candidateExpanded = mutableStateMapOf<String, Boolean>()
    private val candidateReadmeStates = mutableStateMapOf<String, CandidateReadmeState>()
    private val candidateObservers = mutableMapOf<String, Pair<LiveData<DataState<CourseReadmeData>>, Observer<DataState<CourseReadmeData>>>>()

    private val externalExpanded = mutableStateMapOf<String, Boolean>()
    private val externalBrowseStates = mutableStateMapOf<String, ExternalBrowseUiState>()
    private val externalBrowseStacks = mutableMapOf<String, ArrayDeque<InlineBrowseState>>()
    private val externalObservers = mutableMapOf<String, Pair<LiveData<DataState<List<ExternalResourceEntry>>>, Observer<DataState<List<ExternalResourceEntry>>>>>()

    private val markwon: Markwon by lazy {
        Markwon.builder(this)
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .usePlugin(TaskListPlugin.create(this))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(JLatexMathPlugin.create(15f))
            .usePlugin(GlideImagesPlugin.create(this))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .linkColor(ContextCompat.getColor(this@SubjectActivity, R.color.primary))
                        .isLinkUnderlined(true)
                        .blockMargin(dpToPx(8))
                        .blockQuoteWidth(dpToPx(3))
                        .blockQuoteColor(ContextCompat.getColor(this@SubjectActivity, R.color.primary))
                        .bulletWidth(dpToPx(5))
                        .headingBreakHeight(0)
                        .headingTypeface(Typeface.create("sans-serif", Typeface.BOLD))
                        .headingTextSizeMultipliers(floatArrayOf(1.6f, 1.38f, 1.22f, 1.12f, 1.05f, 1f))
                }
            })
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { view: View, link: String ->
                        val source = (view.tag as? String)?.takeIf { it.isNotBlank() } ?: currentReadmeSource
                        openLink(resolveReadmeLink(link, source))
                    }
                }
            })
            .build()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).roundToInt()

    override fun initViewBinding(): ComposeViewBinding {
        return ComposeViewBinding(ComposeView(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    override fun initViews() {
        bindLiveData()
        (binding.root as ComposeView).setContent {
            HitaComposeTheme() {
                SubjectScreen(
                    viewModel = viewModel,
                    subjectMetaSupported = subjectMetaSupported,
                    visibleClasses = visibleClasses,
                    courseExpanded = courseExpanded,
                    selectedEventIds = selectedEventIds,
                    courseProgress = courseProgress,
                    readmeUiState = readmeUiState,
                    campusSourceHint = campusSourceHint,
                    hoaCandidates = hoaCandidates,
                    candidateExpanded = candidateExpanded,
                    candidateReadmeStates = candidateReadmeStates,
                    externalResources = externalResources,
                    externalExpanded = externalExpanded,
                    externalBrowseStates = externalBrowseStates,
                    markwon = markwon,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenTimetable = { timetable ->
                        ActivityUtils.startTimetableDetailActivity(this@SubjectActivity, timetable.id)
                    },
                    onTeacherLongClick = {
                        viewModel.teachersLiveData.value?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { teacher ->
                            ActivityUtils.startTeacherHomepageSearch(getThis(), teacher)
                        }
                    },
                    onCreditClick = { pickCredit() },
                    onContribute = { startContribute() },
                    onCandidateClick = { toggleHoaCandidate(it) },
                    onExternalClick = { toggleExternalCourse(it) },
                    onExternalBack = { goBackExternalCourse(it) },
                    onExternalEntryClick = { key, entry -> handleExternalEntryClick(key, entry) },
                    onToggleCourseExpand = { toggleCourseExpand() },
                    onCourseClick = { event ->
                        if (selectedEventIds.isNotEmpty()) {
                            selectedEventIds = if (selectedEventIds.contains(event.id)) {
                                selectedEventIds - event.id
                            } else {
                                selectedEventIds + event.id
                            }
                        } else {
                            EventsUtils.showEventItem(getThis(), event)
                        }
                    },
                    onCourseLongClick = { event ->
                        selectedEventIds = selectedEventIds + event.id
                        binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    },
                    onAddCourse = { addCourse() },
                    onClearSelection = { selectedEventIds = emptySet() },
                    onDeleteSelected = { deleteSelectedCourses() },
                    onResolveReadmeLink = ::resolveReadmeLink,
                    onOpenLink = ::openLink
                )
            }
        }
    }

    private fun bindLiveData() {
        viewModel.subjectLiveData.observe(this) { subject ->
            if (subjectMetaSupported) {
                getSubjectCategoryDisplay(subject)
                getSubjectCreditKey(subject)
            }
            loadReadmeForSubject(subject)
        }

        viewModel.classesLiveData.observe(this) { classes ->
            refreshVisibleClasses(classes)
            val total = classes.size
            courseProgress = if (total == 0) {
                0
            } else {
                (classes.count { TimeTools.passed(it.to) }.toFloat() * 100f / total).toInt()
            }
        }
    }

    private fun refreshVisibleClasses(classes: List<EventItem> = viewModel.classesLiveData.value.orEmpty()) {
        val comparator: Comparator<EventItem> = Comparator { o1, o2 ->
            if (o1.type === EventItem.TYPE.TAG && o2.type === EventItem.TYPE.TAG) 0 else o1.compareTo(o2)
        }
        visibleClasses = if (classes.isEmpty()) {
            courseExpanded = false
            emptyList()
        } else if (courseExpanded) {
            ArrayList(classes).apply { add(EventItem.getTagInstance("less")) }.sortedWith(comparator)
        } else {
            val max = classes.size.coerceAtMost(5)
            ArrayList(classes.subList(0, max)).apply {
                if (classes.size > 5) add(EventItem.getTagInstance("more"))
            }.sortedWith(comparator)
        }
    }

    private fun toggleCourseExpand() {
        courseExpanded = !courseExpanded
        refreshVisibleClasses()
    }

    private fun pickCredit() {
        if (!subjectMetaSupported) return
        viewModel.subjectLiveData.value?.let {
            PopUpFloatPicker()
                .setDialogTitle(R.string.subject_credit)
                .setInitialValue(it.credit)
                .setOnDialogConformListener(object : PopUpFloatPicker.OnDialogConformListener {
                    override fun onClick(result: Float) {
                        it.credit = result
                        viewModel.startSaveSubject()
                    }
                })
                .show(supportFragmentManager, "pick")
        }
    }

    private fun startContribute() {
        viewModel.subjectLiveData.value?.let { subject ->
            val resolved = selectedReadmeCandidate
            val fallbackCode = CourseCodeUtils.normalize(subject.code)
                ?.takeIf { it.isNotBlank() }
                ?: subject.code?.takeIf { it.isNotBlank() }
                ?: subject.name
            ActivityUtils.startCourseContributionActivity(
                getThis(),
                resolved?.repoName?.takeIf { it.isNotBlank() } ?: fallbackCode,
                resolved?.courseName?.takeIf { it.isNotBlank() } ?: subject.name,
                resolved?.courseCode?.takeIf { it.isNotBlank() } ?: fallbackCode,
                resolved?.repoType?.takeIf { it.isNotBlank() } ?: "normal",
            )
        }
    }

    private fun addCourse() {
        viewModel.subjectLiveData.value?.let { subject ->
            viewModel.timetableLiveData.value?.let { timetable ->
                PopupAddEvent().setInitTimetable(timetable).setInitSubject(subject)
                    .show(supportFragmentManager, "add_event")
            }
        }
    }

    private fun deleteSelectedCourses() {
        val toDelete = viewModel.classesLiveData.value.orEmpty().filter { selectedEventIds.contains(it.id) }
        if (toDelete.isNotEmpty()) {
            viewModel.deleteCourses(toDelete)
            selectedEventIds = emptySet()
        }
    }

    private fun loadReadmeForSubject(subject: TermSubject) {
        val key = "${subject.id}|${subject.code}|${subject.name}"
        if (key == readmeResolveKey) return
        readmeResolveKey = key
        showReadmeLoading()
        updateCampusSourceHint()
        searchExternalResources(subject)

        readmeAgentSession?.dispose()
        val session = readmeAgentProvider.createSession()
        readmeAgentSession = session
        session.run(
            input = SubjectReadmeAgentInput(
                owner = this,
                subjectId = subject.id,
                courseCode = subject.code,
                courseName = subject.name,
                campus = hoaCampus,
            ),
            onTrace = { trace ->
                LogUtils.d("agent trace: stage=${trace.stage} message=${trace.message} payload=${trace.payload}")
            },
            onResult = { result ->
                runOnUiThread {
                    if (!result.ok) {
                        LogUtils.d("agent resolve failed: ${result.error}, fallback to direct resolver")
                        resolveReadmeCandidatesDirectly(subject)
                        return@runOnUiThread
                    }
                    handleResolvedReadmeCandidates(subject, result.data?.candidates.orEmpty())
                }
            }
        )
    }

    private fun resolveReadmeCandidatesDirectly(subject: TermSubject) {
        CourseResourceLinker.resolveCandidates(
            owner = this,
            courseCodeRaw = subject.code,
            courseNameRaw = subject.name,
            campus = hoaCampus,
        ) { candidates ->
            handleResolvedReadmeCandidates(subject, candidates)
        }
    }

    private fun handleResolvedReadmeCandidates(
        subject: TermSubject,
        candidates: List<CourseResourceItem>,
    ) {
        LogUtils.d(
            "loadReadmeForSubject: subjectId=${subject.id} code=${subject.code} name=${subject.name} " +
                "candidateCount=${candidates.size} candidates=${candidates.take(8).joinToString { "${it.repoType}|${it.repoName}|${it.courseCode}|${it.courseName}" }}"
        )
        backfillCourseCodeFromHoa(subject, candidates)
        hoaCandidates = candidates
        candidateExpanded.clear()
        candidateReadmeStates.clear()
        clearCandidateObservers()
        selectedReadmeCandidate = null
        if (candidates.isEmpty()) {
            showReadmeMessage(getString(R.string.course_readme_missing))
        } else {
            showReadmeMessage("点击上方卡片查看深圳资源")
        }
        clearReadmeObserver()
    }

    private fun backfillCourseCodeFromHoa(
        subject: TermSubject,
        candidates: List<CourseResourceItem>
    ) {
        if (!subject.code.isNullOrBlank()) return
        val code = CourseResourceLinker.uniqueExactCourseCodeForName(candidates, subject.name)
            ?: return
        subject.code = code
        viewModel.startSaveSubject()
        LogUtils.d("backfillCourseCodeFromHoa: subjectId=${subject.id} code=$code")
    }

    private fun toggleHoaCandidate(item: CourseResourceItem) {
        val key = itemKey(item)
        val expanded = !(candidateExpanded[key] ?: false)
        candidateExpanded[key] = expanded
        if (!expanded) {
            candidateObservers.remove(key)?.let { (live, observer) -> live.removeObserver(observer) }
            return
        }
        selectedReadmeCandidate = item
        if (candidateReadmeStates[key]?.markdown?.isNotBlank() == true) return
        val repoName = item.repoName.trim()
        if (repoName.isBlank()) return
        candidateReadmeStates[key] = CandidateReadmeState(loading = true)
        candidateObservers.remove(key)?.let { (live, observer) -> live.removeObserver(observer) }
        val liveData = hoaRepository.getCourseReadme(repoName, hoaCampus)
        val observer = Observer<DataState<CourseReadmeData>> { state ->
            when (state.state) {
                DataState.STATE.SUCCESS -> {
                    val data = state.data
                    if (data == null) {
                        candidateReadmeStates[key] = CandidateReadmeState(message = "加载失败")
                        return@Observer
                    }
                    val scoped = filterReadmeForCandidate(data.markdown, item)
                    candidateReadmeStates[key] = CandidateReadmeState(
                        markdown = preprocessReadme(scoped),
                        source = data.source,
                    )
                }
                DataState.STATE.NOTHING, DataState.STATE.LOADING -> {
                    candidateReadmeStates[key] = CandidateReadmeState(loading = true)
                }
                else -> {
                    candidateReadmeStates[key] = CandidateReadmeState(
                        message = state.message?.takeIf { it.isNotBlank() } ?: "加载失败"
                    )
                }
            }
        }
        candidateObservers[key] = liveData to observer
        liveData.observe(this, observer)
    }

    private fun observeReadme(repoName: String) {
        clearReadmeObserver()
        LogUtils.d("observeReadme: repoName=$repoName")
        readmeLiveData = hoaRepository.getCourseReadme(repoName, hoaCampus)
        readmeObserver = Observer { state ->
            when (state.state) {
                DataState.STATE.NOTHING, DataState.STATE.LOADING -> {
                    showReadmeLoading()
                }
                DataState.STATE.SUCCESS -> {
                    val data = state.data
                    if (data == null) {
                        showReadmeMessage(getString(R.string.course_resource_failed))
                        return@Observer
                    }
                    currentReadmeSource = data.source
                    val scoped = filterReadmeForCandidate(data.markdown, selectedReadmeCandidate)
                    readmeUiState = SubjectReadmeUiState.Success(
                        markdown = preprocessReadme(scoped),
                        source = data.source,
                    )
                }
                else -> {
                    val rawMessage = state.message?.trim().orEmpty()
                    val friendly = if (rawMessage.contains("invalid repo name", ignoreCase = true)) {
                        getString(R.string.course_readme_missing)
                    } else {
                        rawMessage.ifBlank { getString(R.string.course_resource_failed) }
                    }
                    showReadmeMessage(friendly)
                }
            }
        }
        readmeLiveData?.observe(this, readmeObserver!!)
    }

    private fun showReadmeLoading() {
        readmeUiState = SubjectReadmeUiState.Loading
    }

    private fun showReadmeMessage(message: String) {
        readmeUiState = SubjectReadmeUiState.Message(message)
    }

    private fun clearReadmeObserver() {
        val live = readmeLiveData
        val observer = readmeObserver
        if (live != null && observer != null) {
            live.removeObserver(observer)
        }
        readmeLiveData = null
        readmeObserver = null
    }

    private fun searchExternalResources(subject: TermSubject) {
        clearExternalResourceObserver()
        clearExternalObservers()
        externalExpanded.clear()
        externalBrowseStates.clear()
        externalBrowseStacks.clear()
        externalResources = emptyList()

        val query = subject.name.trim().takeIf { it.isNotBlank() }
            ?: subject.code?.trim()?.takeIf { it.isNotBlank() }
            ?: return
        val liveData = externalResourceRepository.searchCourses(query)
        externalResourceLiveData = liveData
        val observer = Observer<DataState<List<ExternalCourseItem>>> { state ->
            if (state.state == DataState.STATE.SUCCESS) {
                externalResources = state.data.orEmpty()
            }
        }
        externalResourceObserver = observer
        liveData.observe(this, observer)
    }

    private fun clearExternalResourceObserver() {
        val live = externalResourceLiveData
        val observer = externalResourceObserver
        if (live != null && observer != null) {
            live.removeObserver(observer)
        }
        externalResourceLiveData = null
        externalResourceObserver = null
    }

    private fun toggleExternalCourse(item: ExternalCourseItem) {
        val key = itemKey(item)
        val expanded = !(externalExpanded[key] ?: false)
        externalExpanded[key] = expanded
        if (!expanded) {
            externalObservers.remove(key)?.let { (live, observer) -> live.removeObserver(observer) }
            return
        }
        if (externalBrowseStacks[key].isNullOrEmpty()) {
            val stack = ArrayDeque<InlineBrowseState>()
            stack.add(InlineBrowseState(item.path, item.source, item.courseName))
            externalBrowseStacks[key] = stack
            loadDirectoryForExternal(key, item.path, item.source, item.courseName)
        }
    }

    private fun goBackExternalCourse(item: ExternalCourseItem) {
        val key = itemKey(item)
        val stack = externalBrowseStacks[key] ?: return
        if (stack.size <= 1) return
        stack.removeLast()
        val previous = stack.last()
        loadDirectoryForExternal(key, previous.path, previous.source, previous.breadcrumb)
    }

    private fun handleExternalEntryClick(key: String, entry: ExternalResourceEntry) {
        if (!entry.isDir) {
            handleInlineFileClick(entry)
            return
        }
        val stack = externalBrowseStacks[key] ?: ArrayDeque<InlineBrowseState>().also {
            externalBrowseStacks[key] = it
        }
        val parent = stack.lastOrNull()
        val breadcrumb = if (parent == null) entry.name else "${parent.breadcrumb} / ${entry.name}"
        stack.add(InlineBrowseState(entry.path, entry.source, breadcrumb))
        loadDirectoryForExternal(key, entry.path, entry.source, breadcrumb)
    }

    private fun loadDirectoryForExternal(
        key: String,
        path: String,
        source: ResourceSource,
        breadcrumb: String,
    ) {
        externalObservers.remove(key)?.let { (live, observer) -> live.removeObserver(observer) }
        externalBrowseStates[key] = ExternalBrowseUiState(
            breadcrumb = breadcrumb,
            loading = true,
            entries = emptyList(),
            message = "",
        )
        val liveData = externalResourceRepository.listDirectory(path, source)
        val observer = Observer<DataState<List<ExternalResourceEntry>>> { state ->
            externalBrowseStates[key] = when (state.state) {
                DataState.STATE.SUCCESS -> {
                    val entries = state.data.orEmpty()
                    ExternalBrowseUiState(
                        breadcrumb = breadcrumb,
                        loading = false,
                        entries = entries,
                        message = if (entries.isEmpty()) "空文件夹" else "",
                    )
                }
                DataState.STATE.NOTHING, DataState.STATE.LOADING -> {
                    ExternalBrowseUiState(breadcrumb, loading = true, entries = emptyList(), message = "")
                }
                else -> {
                    ExternalBrowseUiState(
                        breadcrumb = breadcrumb,
                        loading = false,
                        entries = emptyList(),
                        message = state.message?.takeIf { it.isNotBlank() } ?: "加载失败",
                    )
                }
            }
        }
        externalObservers[key] = liveData to observer
        liveData.observe(this, observer)
    }

    private fun clearCandidateObservers() {
        candidateObservers.values.forEach { (live, observer) -> live.removeObserver(observer) }
        candidateObservers.clear()
    }

    private fun clearExternalObservers() {
        externalObservers.values.forEach { (live, observer) -> live.removeObserver(observer) }
        externalObservers.clear()
    }

    private data class InlineBrowseState(
        val path: String,
        val source: ResourceSource,
        val breadcrumb: String,
    )

    private data class ReadmeSection(
        val level: Int,
        val heading: String,
        val content: String,
    )

    private data class CourseMatchKeys(
        val codeTokens: List<String>,
        val nameKeys: List<String>,
    )

    private fun filterReadmeForCandidate(
        markdown: String,
        candidate: CourseResourceItem?,
    ): String {
        if (markdown.isBlank()) return markdown
        val subject = viewModel.subjectLiveData.value
        val keys = buildCourseMatchKeys(candidate, subject)
        if (keys.codeTokens.isEmpty() && keys.nameKeys.isEmpty()) return markdown
        extractByTomlCourseMeta(markdown, keys)?.let { return it }
        val sections = splitReadmeSections(markdown)
        if (sections.size <= 1) return markdown
        val matched = sections.firstOrNull {
            it.heading.isNotBlank() && sectionMatchesKeys(it.heading, keys)
        } ?: return markdown
        return buildString {
            append("#".repeat(matched.level.coerceAtLeast(2)))
            append(" ")
            append(matched.heading)
            append("\n")
            append(matched.content.trim())
        }.trim()
    }

    private fun splitReadmeSections(markdown: String): List<ReadmeSection> {
        val headingRegex = Regex("(?m)^(#{2,4})\\s+(.+)$")
        val matches = headingRegex.findAll(markdown).toList()
        if (matches.isEmpty()) return listOf(ReadmeSection(level = 0, heading = "", content = markdown))
        val sections = mutableListOf<ReadmeSection>()
        val intro = markdown.substring(0, matches.first().range.first)
        sections.add(ReadmeSection(level = 0, heading = "", content = intro))
        for ((index, match) in matches.withIndex()) {
            val level = match.groupValues[1].length
            val heading = match.groupValues[2].trim()
            val start = match.range.last + 1
            val end = findSectionEnd(markdown, matches, index, level)
            sections.add(ReadmeSection(level = level, heading = heading, content = markdown.substring(start, end)))
        }
        return sections
    }

    private fun findSectionEnd(
        markdown: String,
        headings: List<MatchResult>,
        currentIndex: Int,
        currentLevel: Int,
    ): Int {
        for (i in (currentIndex + 1) until headings.size) {
            val level = headings[i].groupValues[1].length
            if (level <= currentLevel) return headings[i].range.first
        }
        return markdown.length
    }

    private fun buildCourseMatchKeys(
        candidate: CourseResourceItem?,
        subject: TermSubject?,
    ): CourseMatchKeys {
        val subjectCodeRaw = subject?.code?.trim().orEmpty()
        val subjectCodeNormalized = CourseCodeUtils.normalize(subject?.code)?.trim().orEmpty()
        val subjectNameKey = CourseNameUtils.normalizeKey(subject?.name)
        val codeTokens = buildList {
            if (subjectCodeRaw.isNotBlank()) add(subjectCodeRaw.lowercase())
            if (subjectCodeNormalized.isNotBlank()) add(subjectCodeNormalized.lowercase())
            if (subjectCodeRaw.isBlank() && subjectCodeNormalized.isBlank()) {
                candidate?.courseCode?.trim()?.takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
                candidate?.courseCode?.let { CourseCodeUtils.normalize(it) }?.takeIf { it.isNotBlank() }
                    ?.let { add(it.lowercase()) }
                candidate?.repoName?.trim()?.takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
            }
        }.distinct()
        val nameKeys = buildList {
            if (subjectNameKey.isNotBlank()) add(subjectNameKey)
            candidate?.courseName?.let { CourseNameUtils.normalizeKey(it) }
                ?.takeIf { it.isNotBlank() }?.let { add(it) }
            candidate?.aliases
                ?.map { CourseNameUtils.normalizeKey(it) }
                ?.filter { it.isNotBlank() }
                ?.forEach { add(it) }
        }.distinct()
        return CourseMatchKeys(codeTokens = codeTokens, nameKeys = nameKeys)
    }

    private fun extractByTomlCourseMeta(markdown: String, keys: CourseMatchKeys): String? {
        val headingRegex = Regex("(?m)^(#{2,4})\\s+(.+)$")
        val headings = headingRegex.findAll(markdown).toList()
        if (headings.isEmpty()) return null
        val courseMetaRegex = Regex("(?m)^\\s*<!--\\s*TOML-COURSE:\\s*([^>]*)-->\\s*$")
        for ((index, heading) in headings.withIndex()) {
            val level = heading.groupValues[1].length
            val title = heading.groupValues[2].trim()
            val bodyStart = heading.range.last + 1
            val bodyEnd = findSectionEnd(markdown, headings, index, level)
            val body = markdown.substring(bodyStart, bodyEnd)
            val attrs = courseMetaRegex.find(body)?.groupValues?.getOrNull(1) ?: continue
            val code = parseTomlAttr(attrs, "code")
            val name = parseTomlAttr(attrs, "name")
            val tomlHit = tomlCourseMatches(code, name, keys)
            val headingHit = sectionMatchesKeys(title, keys)
            if (!tomlHit && !headingHit) continue
            val cleanedBody = body.replace(courseMetaRegex, "").trim()
            return buildString {
                append("#".repeat(level.coerceAtLeast(2)))
                append(" ")
                append(title)
                if (cleanedBody.isNotBlank()) {
                    append("\n")
                    append(cleanedBody)
                }
            }.trim()
        }
        return null
    }

    private fun parseTomlAttr(attrs: String, key: String): String {
        val regex = Regex("\\b$key\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        return regex.find(attrs)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun tomlCourseMatches(codeRaw: String, nameRaw: String, keys: CourseMatchKeys): Boolean {
        val code = codeRaw.trim().lowercase()
        if (code.isNotBlank()) {
            val normalizedCode = CourseCodeUtils.normalize(code)?.lowercase().orEmpty()
            if (keys.codeTokens.any { token ->
                    token == code ||
                        token == normalizedCode ||
                        code.contains(token) ||
                        token.contains(code) ||
                        (normalizedCode.isNotBlank() && (token.contains(normalizedCode) || normalizedCode.contains(token)))
                }
            ) {
                return true
            }
        }
        val nameKey = CourseNameUtils.normalizeKey(nameRaw)
        return nameKey.isNotBlank() && keys.nameKeys.any { key ->
            key == nameKey || key.contains(nameKey) || nameKey.contains(key)
        }
    }

    private fun sectionMatchesKeys(heading: String, keys: CourseMatchKeys): Boolean {
        val headingKey = CourseNameUtils.normalizeKey(heading)
        val headingLower = heading.lowercase()
        if (headingLower.contains("crossspecialty")) return false
        if (keys.codeTokens.any { token ->
                token.isNotBlank() && (headingLower.contains(token) || token.contains(headingLower))
            }
        ) {
            return true
        }
        if (headingKey.isBlank()) return false
        return keys.nameKeys.any { key ->
            key == headingKey || headingKey.contains(key) || key.contains(headingKey)
        }
    }

    private fun preprocessReadme(markdown: String): String {
        val withTables = convertHtmlTables(markdown)
        val startPattern = Regex("\\{\\{[%<]\\s*details\\s+([^%>]+)\\s*[%>]\\}\\}", RegexOption.IGNORE_CASE)
        val endPattern = Regex("\\{\\{[%<]\\s*/details\\s*[%>]\\}\\}", RegexOption.IGNORE_CASE)
        val replacedStart = startPattern.replace(withTables) { match ->
            val attrs = match.groupValues[1]
            val title = Regex("title\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1) ?: getString(R.string.course_resource_open)
            val closed = Regex("closed\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)?.trim()?.lowercase()
            val openAttr = if (closed == "true") "" else " open"
            "<details$openAttr><summary>$title</summary>"
        }
        return MarkdownPreprocessor.normalizeInlineLatex(
            endPattern.replace(replacedStart, "</details>")
        )
    }

    private fun convertHtmlTables(markdown: String): String {
        val tablePattern = Regex("(?is)<table[^>]*>.*?</table>")
        return tablePattern.replace(markdown) { match ->
            runCatching {
                val doc = Jsoup.parse(match.value)
                val table = doc.selectFirst("table") ?: return@replace match.value
                val rows = table.select("tr")
                if (rows.isEmpty()) return@replace match.value
                val cellsList = rows.map { row ->
                    row.select("th,td").map { it.text().trim() }
                }
                val maxCols = cellsList.maxOfOrNull { it.size } ?: 0
                if (maxCols == 0) return@replace match.value
                fun pad(row: List<String>): List<String> {
                    if (row.size >= maxCols) return row
                    return row + List(maxCols - row.size) { "" }
                }
                val header = pad(cellsList.first())
                val headerRow = header.joinToString(" | ")
                val separator = List(maxCols) { "---" }.joinToString(" | ")
                val body = cellsList.drop(1).joinToString("\n") { row ->
                    pad(row).joinToString(" | ")
                }
                listOf(headerRow, separator, body).filter { it.isNotBlank() }.joinToString("\n")
            }.getOrDefault(match.value)
        }
    }

    private fun resolveReadmeLink(link: String, source: String): String {
        if (link.startsWith("http://") || link.startsWith("https://")) return link
        val base = source.trim()
        if (base.startsWith("http://") || base.startsWith("https://")) {
            return runCatching { URI(base).resolve(link).toString() }.getOrDefault(link)
        }
        return link
    }

    private fun openLink(link: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        }
    }

    private fun getSubjectTypeName(type: TermSubject.TYPE): String {
        return when (type) {
            TermSubject.TYPE.MOOC -> getString(R.string.subject_mooc)
            TermSubject.TYPE.COM_A -> getString(R.string.subject_exam)
            else -> getString(R.string.not_counted_in_GPA)
        }
    }

    private fun getSubjectCategoryDisplay(subject: TermSubject): String {
        val line1 = subject.selectCategory?.takeIf { it.isNotBlank() } ?: getString(R.string.none)
        val line2 = subject.field?.takeIf { it.isNotBlank() } ?: getString(R.string.none)
        val line3 = subject.nature?.takeIf { it.isNotBlank() } ?: getSubjectTypeName(subject.type)
        return listOf(line1, line2, line3).joinToString("\n")
    }

    private fun getSubjectCreditKey(subject: TermSubject): String {
        val pt = Pattern.compile("[0-9]*[.]*[0-9]*")
        val matcher = pt.matcher(subject.credit.toString())
        val df = DecimalFormat("0.0")
        if (matcher.find()) {
            val c = matcher.group(0) ?: return "0.0"
            if (TextUtils.isEmpty(c)) return "0.0"
            return df.format(java.lang.Double.valueOf(c))
        }
        return "0.0"
    }

    private fun updateCampusSourceHint() {
        val easCampus = easRepository.getEasToken().campus
        campusSourceHint = when (easCampus) {
            EASToken.Campus.BENBU -> "深圳资源来自深圳校区（本部数据）"
            EASToken.Campus.WEIHAI -> "深圳资源来自深圳校区（威海数据）"
            else -> ""
        }
    }

    private fun handleInlineFileClick(entry: ExternalResourceEntry) {
        val url = entry.downloadUrl
        if (url.startsWith("https://fireworks.jwyihao.top")) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            return
        }

        val rawUrl = if (entry.path.isNotBlank()) {
            val repo = when (entry.source) {
                ResourceSource.HITCS -> "HITLittleZheng/HITCS"
                ResourceSource.FIREWORKS -> "HIT-Fireworks/fireworks-notes-society"
            }
            val encodedPath = entry.path.split("/").joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            "https://raw.githubusercontent.com/$repo/main/$encodedPath"
        } else if (url.isNotBlank() && !url.startsWith("https://fireworks.")) {
            url
        } else {
            return
        }
        val downloadUrl = "https://ghproxy.net/$rawUrl"
        if (entry.name.endsWith(".md", ignoreCase = true)) {
            startActivity(Intent(this, MarkdownViewerActivity::class.java).apply {
                putExtra("url", downloadUrl)
                putExtra("title", entry.name)
            })
            return
        }
        downloadFile(downloadUrl, entry.name)
    }

    private fun downloadFile(url: String, fileName: String) {
        try {
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("正在下载 $fileName")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            dm.enqueue(request)
            Toast.makeText(this, "开始下载: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            LogUtils.e("Download failed: ${e.message}")
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.onFailure {
                Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (selectedEventIds.isNotEmpty()) {
                selectedEventIds = emptySet()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    override fun onStart() {
        super.onStart()
        intent.getStringExtra("subjectId")?.let { viewModel.startRefresh(it) }
    }

    override fun onDestroy() {
        clearReadmeObserver()
        clearExternalResourceObserver()
        clearCandidateObservers()
        clearExternalObservers()
        readmeAgentSession?.dispose()
        readmeAgentSession = null
        super.onDestroy()
    }
}

private sealed interface SubjectReadmeUiState {
    data object Loading : SubjectReadmeUiState
    data class Message(val text: String) : SubjectReadmeUiState
    data class Success(val markdown: String, val source: String) : SubjectReadmeUiState
}

private data class CandidateReadmeState(
    val loading: Boolean = false,
    val markdown: String = "",
    val source: String = "",
    val message: String = "",
)

private data class ExternalBrowseUiState(
    val breadcrumb: String,
    val loading: Boolean,
    val entries: List<ExternalResourceEntry>,
    val message: String,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SubjectScreen(
    viewModel: SubjectViewModel,
    subjectMetaSupported: Boolean,
    visibleClasses: List<EventItem>,
    courseExpanded: Boolean,
    selectedEventIds: Set<String>,
    courseProgress: Int,
    readmeUiState: SubjectReadmeUiState,
    campusSourceHint: String,
    hoaCandidates: List<CourseResourceItem>,
    candidateExpanded: Map<String, Boolean>,
    candidateReadmeStates: Map<String, CandidateReadmeState>,
    externalResources: List<ExternalCourseItem>,
    externalExpanded: Map<String, Boolean>,
    externalBrowseStates: Map<String, ExternalBrowseUiState>,
    markwon: Markwon,
    onBack: () -> Unit,
    onOpenTimetable: (Timetable) -> Unit,
    onTeacherLongClick: () -> Unit,
    onCreditClick: () -> Unit,
    onContribute: () -> Unit,
    onCandidateClick: (CourseResourceItem) -> Unit,
    onExternalClick: (ExternalCourseItem) -> Unit,
    onExternalBack: (ExternalCourseItem) -> Unit,
    onExternalEntryClick: (String, ExternalResourceEntry) -> Unit,
    onToggleCourseExpand: () -> Unit,
    onCourseClick: (EventItem) -> Unit,
    onCourseLongClick: (EventItem) -> Unit,
    onAddCourse: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onResolveReadmeLink: (String, String) -> String,
    onOpenLink: (String) -> Unit,
) {
    val tokens = HitaTheme.tokens
    val subject by viewModel.subjectLiveData.observeAsState()
    val timetable by viewModel.timetableLiveData.observeAsState()
    val teachers by viewModel.teachersLiveData.observeAsState(emptyList())
    val currentSubject = subject
    val selectionMode = selectedEventIds.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = currentSubject?.name?.let { CourseNameUtils.normalize(it) ?: it }.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.rotate(180f)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        if (selectionMode) {
            SelectionBar(
                selectedCount = selectedEventIds.size,
                onClearSelection = onClearSelection,
                onDeleteSelected = onDeleteSelected,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = tokens.spacing.xl)
        ) {
            item {
                timetable?.let { tt ->
                    TimetableInfoCard(timetable = tt, onClick = { onOpenTimetable(tt) })
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
                ) {
                    ProgressInfoCard(
                        progress = courseProgress,
                        modifier = Modifier
                            .weight(1f)
                            .height(144.dp)
                    )
                    if (subjectMetaSupported && currentSubject != null) {
                        SubjectInfoCard(
                            title = subjectCategoryDisplay(currentSubject),
                            subtitle = stringResource(R.string.subject_type),
                            icon = R.drawable.ic_bx_type,
                            modifier = Modifier
                                .weight(1f)
                                .height(144.dp)
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = tokens.spacing.lg,
                            top = tokens.spacing.sm,
                            end = tokens.spacing.lg
                        ),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
                ) {
                    SubjectInfoCard(
                        title = teachers.joinToString(" ").ifBlank { stringResource(R.string.none) },
                        subtitle = stringResource(R.string.subject_teacher),
                        icon = R.drawable.ic_bx_teacher,
                        modifier = Modifier
                            .weight(1f)
                            .height(144.dp)
                            .combinedClickable(onClick = {}, onLongClick = onTeacherLongClick)
                    )
                    if (subjectMetaSupported && currentSubject != null) {
                        SubjectInfoCard(
                            title = subjectCreditKey(currentSubject),
                            subtitle = stringResource(R.string.subject_credit),
                            icon = R.drawable.ic_bx_credit,
                            modifier = Modifier
                                .weight(1f)
                                .height(144.dp)
                                .clickable(onClick = onCreditClick)
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = onContribute,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.md),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(text = stringResource(R.string.course_resource_contribute), fontWeight = FontWeight.Bold)
                }
            }
            item {
                ResourceSection(
                    readmeUiState = readmeUiState,
                    campusSourceHint = campusSourceHint,
                    hoaCandidates = hoaCandidates,
                    candidateExpanded = candidateExpanded,
                    candidateReadmeStates = candidateReadmeStates,
                    markwon = markwon,
                    onCandidateClick = onCandidateClick,
                    onResolveReadmeLink = onResolveReadmeLink,
                    onOpenLink = onOpenLink,
                )
            }
            if (externalResources.isNotEmpty()) {
                item {
                    ExternalResourceSection(
                        externalResources = externalResources,
                        externalExpanded = externalExpanded,
                        externalBrowseStates = externalBrowseStates,
                        onExternalClick = onExternalClick,
                        onExternalBack = onExternalBack,
                        onExternalEntryClick = onExternalEntryClick,
                    )
                }
            }
            item {
                AllCoursesSection(
                    visibleClasses = visibleClasses,
                    courseExpanded = courseExpanded,
                    selectedEventIds = selectedEventIds,
                    onToggleCourseExpand = onToggleCourseExpand,
                    onCourseClick = onCourseClick,
                    onCourseLongClick = onCourseLongClick,
                    onAddCourse = onAddCourse,
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "已选择 $selectedCount 项",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = onDeleteSelected,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(text = "删除")
            }
            IconButton(onClick = onClearSelection) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_close_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun TimetableInfoCard(timetable: Timetable, onClick: () -> Unit) {
    val tokens = HitaTheme.tokens
    val season = TimeTools.getSeason(timetable.startTime.time)
    val container = when (season) {
        TimeTools.SEASON.SPRING -> colorResource(R.color.spring)
        TimeTools.SEASON.SUMMER -> colorResource(R.color.summer)
        TimeTools.SEASON.AUTUMN -> colorResource(R.color.autumn)
        else -> colorResource(R.color.winter)
    }
    val textColor = when (season) {
        TimeTools.SEASON.SPRING -> colorResource(R.color.spring_text)
        TimeTools.SEASON.SUMMER -> colorResource(R.color.summer_text)
        TimeTools.SEASON.AUTUMN -> colorResource(R.color.autumn_text)
        else -> colorResource(R.color.winter_text)
    }
    val icon = when (season) {
        TimeTools.SEASON.SPRING -> R.drawable.season_spring
        TimeTools.SEASON.SUMMER -> R.drawable.season_summer
        TimeTools.SEASON.AUTUMN -> R.drawable.season_autumn
        else -> R.drawable.season_winter
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(tokens.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.belonging_timetable),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp
            )
            Surface(
                color = container,
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = tokens.spacing.md, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = timetable.name ?: stringResource(R.string.default_timetable_name),
                        color = textColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressInfoCard(progress: Int, modifier: Modifier) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(tokens.spacing.lg),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "$progress%",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.progress),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            LinearProgressIndicator(
                progress = { progress / 100f },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.spacing.md)
            )
        }
    }
}

@Composable
private fun SubjectInfoCard(
    title: String,
    subtitle: String,
    icon: Int,
    modifier: Modifier
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ResourceSection(
    readmeUiState: SubjectReadmeUiState,
    campusSourceHint: String,
    hoaCandidates: List<CourseResourceItem>,
    candidateExpanded: Map<String, Boolean>,
    candidateReadmeStates: Map<String, CandidateReadmeState>,
    markwon: Markwon,
    onCandidateClick: (CourseResourceItem) -> Unit,
    onResolveReadmeLink: (String, String) -> String,
    onOpenLink: (String) -> Unit,
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Text(
                text = stringResource(R.string.course_resource_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            hoaCandidates.forEach { item ->
                val key = itemKey(item)
                UnifiedResourceCard(
                    title = item.courseName.takeIf { it.isNotBlank() } ?: item.repoName,
                    subtitle = listOfNotNull(
                        item.courseCode.takeIf { it.isNotBlank() },
                        item.repoType.takeIf { it.isNotBlank() },
                    ).joinToString("  ·  "),
                    source = "HOA",
                    sourceColor = MaterialTheme.colorScheme.primary,
                    expanded = candidateExpanded[key] == true,
                    onClick = { onCandidateClick(item) },
                )
                if (candidateExpanded[key] == true) {
                    val state = candidateReadmeStates[key] ?: CandidateReadmeState(loading = true)
                    CandidateReadmeContent(
                        state = state,
                        markwon = markwon,
                        onResolveReadmeLink = onResolveReadmeLink,
                        onOpenLink = onOpenLink,
                    )
                }
            }
            if (campusSourceHint.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = tokens.spacing.sm)
                ) {
                    Text(
                        text = campusSourceHint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(tokens.spacing.md)
                    )
                }
            }
            when (readmeUiState) {
                SubjectReadmeUiState.Loading -> {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = tokens.spacing.md)
                            .size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.course_readme_loading),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = tokens.spacing.sm)
                    )
                }
                is SubjectReadmeUiState.Message -> {
                    Text(
                        text = readmeUiState.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = tokens.spacing.sm)
                    )
                }
                is SubjectReadmeUiState.Success -> {
                    readmeUiState.source.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = stringResource(R.string.course_readme_source, it),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = tokens.spacing.sm)
                        )
                    }
                    MarkdownText(
                        markdown = readmeUiState.markdown,
                        source = readmeUiState.source,
                        markwon = markwon,
                        onResolveReadmeLink = onResolveReadmeLink,
                        onOpenLink = onOpenLink,
                        modifier = Modifier.padding(top = tokens.spacing.sm)
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateReadmeContent(
    state: CandidateReadmeState,
    markwon: Markwon,
    onResolveReadmeLink: (String, String) -> String,
    onOpenLink: (String) -> Unit,
) {
    val tokens = HitaTheme.tokens
    when {
        state.loading -> CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(tokens.spacing.md)
                .size(22.dp)
        )
        state.markdown.isNotBlank() -> MarkdownText(
            markdown = state.markdown,
            source = state.source,
            markwon = markwon,
            onResolveReadmeLink = onResolveReadmeLink,
            onOpenLink = onOpenLink,
            modifier = Modifier.padding(
                start = tokens.spacing.sm,
                top = tokens.spacing.sm,
                end = tokens.spacing.sm,
                bottom = tokens.spacing.md
            )
        )
        else -> Text(
            text = state.message.ifBlank { "加载失败" },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(tokens.spacing.md)
        )
    }
}

@Composable
private fun ExternalResourceSection(
    externalResources: List<ExternalCourseItem>,
    externalExpanded: Map<String, Boolean>,
    externalBrowseStates: Map<String, ExternalBrowseUiState>,
    onExternalClick: (ExternalCourseItem) -> Unit,
    onExternalBack: (ExternalCourseItem) -> Unit,
    onExternalEntryClick: (String, ExternalResourceEntry) -> Unit,
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Text(
                text = stringResource(R.string.external_resource_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            externalResources.forEach { item ->
                val key = itemKey(item)
                UnifiedResourceCard(
                    title = item.courseName,
                    subtitle = item.category,
                    source = when (item.source) {
                        ResourceSource.HITCS -> "HITCS"
                        ResourceSource.FIREWORKS -> "薪火"
                    },
                    sourceColor = colorResource(
                        when (item.source) {
                            ResourceSource.HITCS -> R.color.subject3
                            ResourceSource.FIREWORKS -> R.color.subject4
                        }
                    ),
                    expanded = externalExpanded[key] == true,
                    onClick = { onExternalClick(item) },
                )
                if (externalExpanded[key] == true) {
                    val state = externalBrowseStates[key]
                    ExternalBrowseContent(
                        item = item,
                        key = key,
                        state = state,
                        onBack = { onExternalBack(item) },
                        onEntryClick = { entry -> onExternalEntryClick(key, entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedResourceCard(
    title: String,
    subtitle: String,
    source: String,
    sourceColor: Color,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = CardDefaults.outlinedCardBorder(),
        shape = RoundedCornerShape(tokens.radius.lg),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = tokens.spacing.sm)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(tokens.spacing.lg)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = tokens.spacing.xs)
                    )
                }
            }
            SourceChip(
                text = source,
                color = sourceColor,
                modifier = Modifier.padding(start = tokens.spacing.sm)
            )
            Icon(
                painter = painterResource(R.drawable.ic_baseline_arrow_drop_down_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 180f else 0f)
            )
        }
    }
}

@Composable
private fun SourceChip(text: String, color: Color, modifier: Modifier = Modifier) {
    val tokens = HitaTheme.tokens
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = CircleShape,
        modifier = modifier
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = tokens.spacing.sm, vertical = tokens.spacing.xs)
        )
    }
}

@Composable
private fun ExternalBrowseContent(
    item: ExternalCourseItem,
    key: String,
    state: ExternalBrowseUiState?,
    onBack: () -> Unit,
    onEntryClick: (ExternalResourceEntry) -> Unit,
) {
    val tokens = HitaTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.sm, vertical = tokens.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, enabled = state?.breadcrumb != item.courseName) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.rotate(180f)
            )
        }
        Text(
            text = state?.breadcrumb ?: item.courseName,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
    when {
        state == null || state.loading -> CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(tokens.spacing.md)
                .size(22.dp)
        )
        state.message.isNotBlank() -> Text(
            text = state.message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(tokens.spacing.md)
        )
        else -> state.entries.forEach { entry ->
            ExternalEntryRow(entry = entry, onClick = { onEntryClick(entry) })
        }
    }
}

@Composable
private fun ExternalEntryRow(entry: ExternalResourceEntry, onClick: () -> Unit) {
    val tokens = HitaTheme.tokens
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(tokens.radius.md),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.xs)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(tokens.spacing.md)
        ) {
            Icon(
                painter = painterResource(
                    if (entry.isDir) R.drawable.ic_baseline_menu_24 else R.drawable.ic_baseline_cloud_download_24
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = tokens.spacing.md)
            ) {
                Text(
                    text = entry.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!entry.isDir && entry.size > 0) {
                    Text(
                        text = formatFileSize(entry.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun AllCoursesSection(
    visibleClasses: List<EventItem>,
    courseExpanded: Boolean,
    selectedEventIds: Set<String>,
    onToggleCourseExpand: () -> Unit,
    onCourseClick: (EventItem) -> Unit,
    onCourseLongClick: (EventItem) -> Unit,
    onAddCourse: () -> Unit,
) {
    val tokens = HitaTheme.tokens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(tokens.spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.subject_title_all_courses),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onAddCourse) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_add_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = tokens.spacing.sm)
            ) {
                visibleClasses.forEach { event ->
                    if (event.type == EventItem.TYPE.TAG) {
                        ExpandCourseChip(
                            expanded = courseExpanded,
                            onClick = onToggleCourseExpand
                        )
                    } else {
                        CourseChip(
                            event = event,
                            selected = selectedEventIds.contains(event.id),
                            selectionMode = selectedEventIds.isNotEmpty(),
                            onClick = { onCourseClick(event) },
                            onLongClick = { onCourseLongClick(event) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CourseChip(
    event: EventItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val passed = TimeTools.passed(event.to)
    val calendar = Calendar.getInstance().apply { timeInMillis = event.from.time }
    val context = LocalContext.current
    val text = TimeTools.getDateString(context, calendar, true, TimeTools.TTY_REPLACE)
    Surface(
        color = if (passed) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(28.dp)
                )
            } else if (!passed) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                color = if (passed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ExpandCourseChip(expanded: Boolean, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.outlineVariant,
        shape = CircleShape,
        modifier = Modifier
            .size(32.dp)
            .clickable(onClick = onClick)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_baseline_arrow_drop_down_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(if (expanded) 180f else 0f)
        )
    }
}

@Composable
private fun MarkdownText(
    markdown: String,
    source: String,
    markwon: Markwon,
    onResolveReadmeLink: (String, String) -> String,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            TextView(context).apply {
                textSize = 15f
                includeFontPadding = false
                setLineSpacing(0f, 1.28f)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
                    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
                }
                setTextColor(textColor)
                movementMethod = LinkMovementMethod.getInstance()
                linksClickable = true
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            textView.tag = source
            markwon.setMarkdown(textView, markdown)
            textView.movementMethod = LinkMovementMethod.getInstance()
            textView.setOnClickListener {
                val link = (it.tag as? String).orEmpty()
                if (link.startsWith("http://") || link.startsWith("https://")) {
                    onOpenLink(onResolveReadmeLink(link, source))
                }
            }
        }
    )
}

private fun itemKey(item: CourseResourceItem): String {
    return listOf(item.repoType, item.repoName, item.courseCode, item.courseName).joinToString("|")
}

private fun itemKey(item: ExternalCourseItem): String {
    return listOf(item.source.name, item.path, item.courseName).joinToString("|")
}

private fun subjectCategoryDisplay(subject: TermSubject): String {
    val line1 = subject.selectCategory?.takeIf { it.isNotBlank() } ?: "无"
    val line2 = subject.field?.takeIf { it.isNotBlank() } ?: "无"
    val line3 = subject.nature?.takeIf { it.isNotBlank() } ?: when (subject.type) {
        TermSubject.TYPE.COM_A -> "必修 · 考试"
        TermSubject.TYPE.COM_B -> "必修 · 考查"
        TermSubject.TYPE.OPT_A -> "选修 · 专选"
        TermSubject.TYPE.OPT_B -> "选修 · 任选"
        TermSubject.TYPE.MOOC -> "MOOC"
        TermSubject.TYPE.TAG -> "标签"
    }
    return listOf(line1, line2, line3).joinToString("\n")
}

private fun subjectCreditKey(subject: TermSubject): String {
    val pt = Pattern.compile("[0-9]*[.]*[0-9]*")
    val matcher = pt.matcher(subject.credit.toString())
    val df = DecimalFormat("0.0")
    if (matcher.find()) {
        val c = matcher.group(0) ?: return "0.0"
        if (TextUtils.isEmpty(c)) return "0.0"
        return df.format(java.lang.Double.valueOf(c))
    }
    return "0.0"
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
