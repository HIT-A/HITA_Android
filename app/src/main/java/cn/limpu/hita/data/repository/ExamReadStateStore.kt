package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.EasQueryScope
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.repository.today.TodayReadState
import cn.limpu.hita.data.source.preference.EasQuerySessionState
import com.limpu.component.data.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Immutable remote record: never contains credentials or personal exam memos. */
data class ExamReadItem(
    val courseName: String?, val examDate: String?, val examTime: String?, val examLocation: String?,
    val termId: String?, val examType: String?,
) {
    fun toExamItem() = ExamItem().also {
        it.courseName = courseName; it.examDate = examDate; it.examTime = examTime
        it.examLocation = examLocation; it.termId = termId; it.examType = examType
    }
}

data class ExamReadResult(
    val requestedTermId: String?,
    /** Non-null only after a successful guarded import transaction into this target. */
    val targetTimetableId: String?,
    val items: List<ExamReadItem>,
    val truncated: Boolean = false,
    val partial: Boolean = false,
)

data class ExamReadSnapshot(
    val ownerKey: String? = null,
    val sessionGeneration: Long = 0,
    /** '*' denotes a query without a term filter, not a claim that every term is supported. */
    val terms: Map<String, TodayReadState<ExamReadResult>> = emptyMap(),
)

internal class ExamReadFailure(val reason: DataState.STATE) : Exception("Exam read: ${reason.name}")

/** Bounded process-local results. Session guards and request order apply independently. */
internal class ExamReadStateStore(private val currentSession: () -> EasQueryScope) {
    internal data class Ticket(
        val scope: EasQueryScope, val termId: String?, val sequence: Long,
        val previous: TodayReadState<ExamReadResult>?,
    ) {
        val key get() = termId ?: "*"
    }
    private var scope: EasQueryScope? = null
    private var sequence = 0L
    private val latest = linkedMapOf<String, Long>()
    private val active = mutableSetOf<Long>()
    private val pendingBefore = mutableMapOf<String, TodayReadState<ExamReadResult>?>()
    private val mutable = MutableStateFlow(ExamReadSnapshot())
    val state: StateFlow<ExamReadSnapshot> = mutable.asStateFlow()

    @Synchronized fun selectSession(next: EasQueryScope) {
        // Delayed watcher deliveries must not restore a superseded account.
        if (next != currentSession() || scope == next) return
        scope = next
        latest.clear()
        active.clear()
        pendingBefore.clear()
        mutable.value = ExamReadSnapshot(next.ownerKey, next.sessionGeneration)
    }

    @Synchronized fun begin(captured: EasQueryScope, termId: String?): Ticket? {
        selectSession(currentSession())
        if (captured != scope || !captured.loggedIn || captured.ownerKey == null) return null
        val key = termId ?: "*"
        val previous = mutable.value.terms[key].let { if (it == TodayReadState.Loading) pendingBefore[key] else it }
        val ticket = Ticket(captured, termId, ++sequence, previous)
        pendingBefore[key] = previous
        latest.remove(key)?.let(active::remove)
        active += ticket.sequence
        latest[key] = ticket.sequence
        while (latest.size > MAX_TERMS) {
            val evicted = latest.keys.first()
            latest.remove(evicted)?.let(active::remove)
            pendingBefore.remove(evicted)
        }
        mutable.value = mutable.value.copy(terms = mutable.value.terms.filterKeys { it in latest } + (key to TodayReadState.Loading))
        return ticket
    }

    @Synchronized fun finish(ticket: Ticket?, result: DataState<List<ExamItem>>, targetId: String? = null): Boolean {
        val accepted = ticket?.takeIf { accepts(it) } ?: return false
        active.remove(accepted.sequence)
        pendingBefore.remove(accepted.key)
        val next = if (result.state == DataState.STATE.SUCCESS && result.data != null) {
            val remote = result.data.orEmpty().asSequence().filterNot { it.isMemo() }.take(MAX_ITEMS + 1).toList()
            var truncated = remote.size > MAX_ITEMS
            fun bounded(value: String?): String? {
                if (value != null && value.length > MAX_FIELD) truncated = true
                return value?.take(MAX_FIELD)
            }
            val items = remote.take(MAX_ITEMS).map {
                ExamReadItem(bounded(it.courseName), bounded(it.examDate), bounded(it.examTime), bounded(it.examLocation),
                    bounded(it.termId), bounded(it.examType))
            }
            TodayReadState.Ready(ExamReadResult(accepted.termId, targetId, items, truncated, !result.message.isNullOrBlank()))
        } else TodayReadState.Failed(retained(accepted.previous), ExamReadFailure(result.state))
        mutable.value = mutable.value.copy(terms = mutable.value.terms + (accepted.key to next))
        return true
    }

    @Synchronized fun cancel(ticket: Ticket?) {
        val accepted = ticket?.takeIf { accepts(it) } ?: return
        if (mutable.value.terms[accepted.key] != TodayReadState.Loading) return
        active.remove(accepted.sequence)
        pendingBefore.remove(accepted.key)
        val terms = mutable.value.terms.toMutableMap()
        if (accepted.previous == null) terms.remove(accepted.key) else terms[accepted.key] = accepted.previous
        mutable.value = mutable.value.copy(terms = terms)
    }

    private fun accepts(ticket: Ticket?): Boolean {
        selectSession(currentSession())
        return ticket != null && ticket.scope == scope && latest[ticket.key] == ticket.sequence && ticket.sequence in active
    }

    private fun retained(state: TodayReadState<ExamReadResult>?): ExamReadResult? = when (state) {
        is TodayReadState.Ready -> state.value
        is TodayReadState.Failed -> state.retained
        else -> null
    }

    companion object {
        internal const val MAX_TERMS = 16
        internal const val MAX_ITEMS = 256
        internal const val MAX_FIELD = 512
        private val stores = mutableMapOf<EasQuerySessionState, ExamReadStateStore>()
        fun forSession(session: EasQuerySessionState, applicationScope: CoroutineScope): ExamReadStateStore = synchronized(stores) {
            stores.getOrPut(session) {
                ExamReadStateStore(session::current).also { store ->
                    applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        session.changes.collect(store::selectSession)
                    }
                }
            }
        }
    }
}
