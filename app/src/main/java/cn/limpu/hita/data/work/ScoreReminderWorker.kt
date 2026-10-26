package cn.limpu.hita.data.work

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.EasQuerySession
import cn.limpu.hita.data.repository.awaitEasDataState
import cn.limpu.hita.data.repository.TimetableChangeStore
import cn.limpu.hita.data.repository.TimetableMutationLock
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.EasCredentialStore
import cn.limpu.hita.data.source.preference.ScoreReminderStore
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import cn.limpu.hita.data.source.web.service.EASService
import cn.limpu.hita.ui.eas.score.ScoreInquiryActivity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

class ScoreReminderWorker private constructor(
    appContext: Context,
    params: WorkerParameters,
    private val repositoryFactory: (Application) -> EASRepository,
    private val notifyScores: ((List<CourseScoreItem>) -> Unit)?,
) : CoroutineWorker(appContext, params) {
    // Preserve this public signature and class name for already persisted WorkSpecs.
    constructor(appContext: Context, params: WorkerParameters) : this(
        appContext, params, ::createRepository, null,
    )

    internal constructor(
        appContext: Context,
        params: WorkerParameters,
        repository: EASRepository,
        notifyScores: (List<CourseScoreItem>) -> Unit,
    ) : this(appContext, params, { repository }, notifyScores)

    override suspend fun doWork(): Result {
        val store = ScoreReminderStore(applicationContext)
        if (!store.isEnabled()) return Result.success()
        val app = applicationContext as? Application ?: return Result.failure()
        val repository = repositoryFactory(app)
        val session = repository.captureQuerySession()
        if (!session.loggedIn) return Result.success()
        val ownerKey = session.ownerKey ?: return Result.retry()
        return session.whileCurrent { checkScores(repository, session, ownerKey, store) }
    }

    private suspend fun checkScores(
        repository: EASRepository,
        session: EasQuerySession,
        ownerKey: String,
        store: ScoreReminderStore,
    ): Result {
        val termsState = withTimeoutOrNull(6_000L) { repository.queryTermsRemoteOnce(session) }
            ?: return Result.retry()
        if (termsState.state != DataState.STATE.SUCCESS || termsState.data.isNullOrEmpty()) {
            return Result.retry()
        }
        val changedItems = mutableListOf<CourseScoreItem>()
        val baselineUpdates = mutableMapOf<String, Set<String>>()
        var queryFailed = false
        for (term in ScoreReminderPolicy.termsToCheck(termsState.data.orEmpty())) {
            currentCoroutineContext().ensureActive()
            session.ensureCurrent()
            val scoresState = withTimeoutOrNull(12_000L) {
                awaitEasDataState {
                    repository.getPersonalScoresWithSummary(session, term, EASService.TestType.NORMAL)
                }
            }
            if (scoresState == null) {
                queryFailed = true
                continue
            }
            if (scoresState.state != DataState.STATE.SUCCESS || scoresState.data == null) {
                queryFailed = true
                continue
            }
            val items = scoresState.data?.items ?: emptyList()
            val currentKeys = items.map(ScoreReminderPolicy::scoreKey).toSet()
            val scopeKey = ScoreReminderPolicy.baselineKey(ownerKey, term.id)
            val known = store.getKnownScores(scopeKey)
            if (known == null) {
                baselineUpdates[scopeKey] = currentKeys
                continue
            }
            val diff = ScoreReminderPolicy.newKeys(known, currentKeys)
            if (diff.isNotEmpty()) {
                changedItems += items.filter { ScoreReminderPolicy.scoreKey(it) in diff }
                baselineUpdates[scopeKey] = known + diff
            }
        }
        currentCoroutineContext().ensureActive()
        session.ensureCurrent()
        if (changedItems.isNotEmpty()) (notifyScores ?: ::sendNotification)(changedItems)
        baselineUpdates.forEach { (scopeKey, keys) ->
            currentCoroutineContext().ensureActive()
            session.ensureCurrent()
            store.setKnownScores(scopeKey, keys)
        }
        return if (queryFailed) Result.retry() else Result.success()
    }

    private fun sendNotification(items: List<CourseScoreItem>) {
        if (items.isEmpty()) return
        val title = applicationContext.getString(R.string.score_reminder_notification_title)
        val names = items.mapNotNull { it.courseName?.trim()?.ifEmpty { null } ?: it.courseCode }
        val text = if (names.size <= 3) {
            names.joinToString("、")
        } else {
            val shown = names.take(3).joinToString("、")
            applicationContext.getString(
                R.string.score_reminder_notification_more,
                shown,
                names.size
            )
        }

        val intent = Intent(applicationContext, ScoreInquiryActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bc_score)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(NOTIF_ID, notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.score_reminder_title),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "score_reminder"
        private const val NOTIF_ID = 2101

        private fun createRepository(app: Application): EASRepository {
            val preferences = EasPreferenceSource(app)
            return EASRepository(
                app, preferences, EasCredentialStore(app),
                TimetablePreferenceSource(app, preferences), TimetableChangeStore(app),
                TimetableMutationLock(),
            )
        }
    }
}
