package cn.limpu.hita.data.work

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.limpu.component.data.DataState
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.TimetableChangeStore
import cn.limpu.hita.data.repository.TimetableMutationLock
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.EasCredentialStore
import cn.limpu.hita.data.source.preference.ScoreReminderStore
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import cn.limpu.hita.data.source.web.service.EASService
import cn.limpu.hita.ui.eas.score.ScoreInquiryActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ScoreReminderWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        val store = ScoreReminderStore(applicationContext)
        if (!store.isEnabled()) return Result.success()
        val app = applicationContext as? Application ?: return Result.failure()
        val easPreferenceSource = EasPreferenceSource(applicationContext)
        val repository = EASRepository(
            app,
            easPreferenceSource,
            EasCredentialStore(applicationContext),
            TimetablePreferenceSource(applicationContext, easPreferenceSource),
            TimetableChangeStore(app),
            TimetableMutationLock()
        )
        val token = repository.getEasToken()
        if (!token.isLogin()) return Result.success()
        val ownerKey = ScoreReminderPolicy.ownerKey(token) ?: return Result.retry()

        val termsState = awaitLiveData(repository.getAllTerms(useCache = false), 6)
        if (termsState.state != DataState.STATE.SUCCESS || termsState.data.isNullOrEmpty()) {
            return Result.retry()
        }
        val changedItems = mutableListOf<CourseScoreItem>()
        val baselineUpdates = mutableMapOf<String, Set<String>>()
        var queryFailed = false
        for (term in ScoreReminderPolicy.termsToCheck(termsState.data!!)) {
            val scoresState = awaitLiveData(
                repository.getPersonalScoresWithSummary(
                    term,
                    EASService.TestType.NORMAL,
                    useCache = false
                ),
                12
            )
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
        if (changedItems.isNotEmpty()) sendNotification(changedItems)
        baselineUpdates.forEach { (scopeKey, keys) -> store.setKnownScores(scopeKey, keys) }
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

    private fun <T> awaitLiveData(
        liveData: androidx.lifecycle.LiveData<DataState<T>>,
        timeoutSeconds: Long
    ): DataState<T> {
        val latch = CountDownLatch(1)
        val result = AtomicReference(DataState<T>(DataState.STATE.FETCH_FAILED))
        val observer = androidx.lifecycle.Observer<DataState<T>> { state ->
            if (ScoreReminderPolicy.isTerminal(state.state)) {
                result.set(state)
                latch.countDown()
            }
        }
        val handler = Handler(Looper.getMainLooper())
        handler.post { liveData.observeForever(observer) }
        latch.await(timeoutSeconds, TimeUnit.SECONDS)
        handler.post { liveData.removeObserver(observer) }
        return result.get()
    }

    companion object {
        private const val CHANNEL_ID = "score_reminder"
        private const val NOTIF_ID = 2101
    }
}
