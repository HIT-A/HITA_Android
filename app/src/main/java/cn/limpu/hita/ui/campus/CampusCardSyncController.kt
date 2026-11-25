package cn.limpu.hita.ui.campus

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import cn.limpu.hita.R
import cn.limpu.hita.data.model.campus.CampusCardTransaction
import cn.limpu.hita.data.repository.CampusCardLedger
import cn.limpu.hita.data.repository.CampusCardLedgerStore
import cn.limpu.hita.data.source.web.campus.CampusCardClient
import cn.limpu.hita.data.source.web.campus.CampusCardHistoryRange
import cn.limpu.hita.data.source.web.campus.CampusCardNetworkException
import cn.limpu.hita.data.source.web.campus.CampusCardSignInRequiredException
import java.io.File
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CampusCardSyncState(
    val key: String = "",
    val ledger: CampusCardLedger? = null,
    val running: Boolean = false,
    val failed: Boolean = false,
    val requiresSignIn: Boolean = false,
)

/** Shared foreground-triggered work. Credentials stay in memory; activity navigation never restarts it. */
internal class CampusCardSyncController private constructor(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = CampusCardLedgerStore(File(application.noBackupFilesDir, "campus-card-history"))
    private val client = CampusCardClient()
    private val _state = MutableStateFlow(CampusCardSyncState())
    val state = _state.asStateFlow()
    private var generation = 0
    private var job: Job? = null
    private var cookies: CampusCardCookies? = null
    private var token: String? = null
    private var sessionKey = ""
    private var ledger: CampusCardLedger? = null
    private var syncing = false
    private var syncFailed = false
    private var requiresSignIn = false
    private var lastAutomaticAttempt: Long? = null

    fun start(session: CampusCardCookies, portalTicket: String? = null, automatic: Boolean = false) {
        if (CampusServiceAccount.CARD.isSignedOut(application)) return
        if (!session.hasSession && portalTicket == null) return
        val key = key(session)
        // Coalesce repeated refreshes. A different session (or a newly verified login)
        // supersedes the old job rather than waiting behind its network requests.
        if (job?.isActive == true && portalTicket == null && cookies == session) return
        val now = SystemClock.elapsedRealtime()
        if (automatic && lastAutomaticAttempt?.let { now - it < 60_000 } == true) return
        job?.cancel()
        if (cookies != session || portalTicket != null) token = null
        cookies = session
        if (sessionKey != key) ledger = null
        sessionKey = key
        generation++
        val requestGeneration = generation
        syncing = !automatic
        syncFailed = false
        requiresSignIn = false
        if (!automatic) publish()
        job = scope.launch {
          try {
            val cached = store.read(key)
            ensureActive()
            if (generation != requestGeneration) return@launch
            ledger = cached
            if (ledger == null) {
                val migrated = store.migrateLegacy(identity(session), Instant.now())
                if (migrated != null) save(key, requestGeneration, migrated)
            }
            // Launch refresh only starts after this service has already been used.
            if (automatic && ledger == null) {
                publish()
                return@launch
            }
            if (automatic) lastAutomaticAttempt = now
            synchronize(key, requestGeneration, session, portalTicket)
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
              if (generation == requestGeneration) {
                  syncing = false
                  syncFailed = true
                  publish()
              }
          }
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        cookies = null
        token = null
        ledger = null
        syncing = false
        sessionKey = ""
        _state.value = CampusCardSyncState()
    }

    private fun publish() {
        _state.value = CampusCardSyncState(sessionKey, ledger, syncing, syncFailed, requiresSignIn)
    }

    private suspend fun save(key: String, requestGeneration: Int, value: CampusCardLedger) {
        currentCoroutineContext().ensureActive()
        if (generation != requestGeneration) throw CancellationException()
        store.write(key, value)
        currentCoroutineContext().ensureActive()
        if (generation != requestGeneration) throw CancellationException()
        ledger = value
        publish()
    }

    /** First initialization is sequential and checkpointed; later updates only query the recent interval. */
    private suspend fun synchronize(key: String, activeGeneration: Int, session: CampusCardCookies, portalTicket: String?) {
        syncing = true
        syncFailed = false
        requiresSignIn = false
        publish()
        try {
            val activeToken = token ?: client.acquireToken(session::headerFor, portalTicket).also {
                currentCoroutineContext().ensureActive()
                if (generation != activeGeneration) throw CancellationException()
                token = it
            }
            val now = Instant.now()
            val initial = ledger?.initialComplete != true
            val until = if (initial) ledger?.initialCutoff?.let(Instant::ofEpochMilli) ?: now else now
            val since = if (initial) null else ledger?.updateSince()
            var remotePage = if (initial) ledger?.nextInitialPage ?: 1 else 1
            val batch = mutableListOf<CampusCardTransaction>()
            val existing = if (initial) ledger?.initialRows.orEmpty() else ledger?.rows.orEmpty()
            val visibleRows = ledger?.rows.orEmpty()
            coroutineScope {
                // Start the first history request immediately, concurrently with the small summary requests.
                val first = async { client.fetchTransactions(activeToken, remotePage, range = CampusCardHistoryRange.ALL,
                    since = since, until = until) }
                val balance = async { client.fetchBalance(activeToken) }
                val freshBalance = balance.await()
                save(key, activeGeneration, (ledger ?: CampusCardLedger(freshBalance, initialCutoff = until.toEpochMilli()))
                    .copy(summary = freshBalance, pendingSinceMillis = since?.toEpochMilli()))
                var response = first.await()
                while (true) {
                    val before = batch.size
                    batch.addAll(response.items)
                    val prefix = if (initial) CampusCardLedger.merge(emptyList(), existing + batch) else emptyList()
                    val merged = if (initial) CampusCardLedger.merge(visibleRows, prefix)
                        else CampusCardLedger.merge(existing, batch)
                    val complete = (remotePage.toLong() - 1) * 20 + response.items.size >= response.total
                    if (!complete && (batch.size == before || remotePage >= 5000)) {
                        throw CampusCardNetworkException(application.getString(R.string.campus_card_sync_incomplete))
                    }
                    val current = ledger ?: return@coroutineScope
                    save(key, activeGeneration, current.copy(rows = merged, updatedAt = System.currentTimeMillis(),
                        initialComplete = !initial || complete,
                        nextInitialPage = if (initial) remotePage + 1 else current.nextInitialPage,
                        remoteTotal = if (initial) response.total else current.remoteTotal,
                        pendingSinceMillis = if (complete) null else current.pendingSinceMillis,
                        initialRows = if (initial && !complete) prefix else emptyList()))
                    if (complete) break
                    remotePage++
                    response = client.fetchTransactions(activeToken, remotePage, range = CampusCardHistoryRange.ALL,
                        since = since, until = until)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: CampusCardSignInRequiredException) {
            if (generation != activeGeneration) return
            token = null
            // Retain the local ledger; the user may still read it after a server session expires.
            requiresSignIn = true
            syncFailed = true
        } catch (_: Exception) {
            if (generation != activeGeneration) return
            syncFailed = true

        } finally {
            if (generation == activeGeneration) {
                syncing = false
                publish()
            }
        }
    }
    companion object {
        @Volatile private var instance: CampusCardSyncController? = null
        fun get(application: Application): CampusCardSyncController = instance ?: synchronized(this) {
            instance ?: CampusCardSyncController(application).also { instance = it }
        }

        fun identity(session: CampusCardCookies): String = session.casHeader.split(';').map { it.trim() }
            .firstOrNull { it.substringBefore('=').equals("CASTGC", true) }
            ?: session.portalHeader.split(';').map { it.trim() }.sorted().joinToString(";")
        fun key(session: CampusCardCookies): String = CampusCardLedgerStore.digest(identity(session))

        fun install(application: Application) {
            val controller = get(application)
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private val foreground = CampusCardForegroundGate()
                override fun onActivityStarted(activity: Activity) {
                    if (foreground.started() && !CampusServiceAccount.CARD.isSignedOut(application)) {
                        runCatching { readCampusCardCookies() }.getOrNull()?.let {
                            controller.start(it, automatic = true)
                        }
                    }
                }
                override fun onActivityStopped(activity: Activity) {
                    foreground.stopped(activity.isChangingConfigurations)
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        }
    }
}
