package cn.limpu.hita.data.analytics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.utils.LogUtils
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Versioned, consent-scoped, durable telemetry. No account or user content is collected. */
object UsageAnalyticsClient {
    private const val SETTINGS = "usage_analytics_settings"
    private const val ONCE = "usage-analytics-send-v2"
    private const val PERIODIC = "usage-analytics-rescue-v2"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Session snapshots must land in the order they were taken; parallel IO threads could
    // otherwise resurrect a cleared `last_background` with an older snapshot.
    private val sessionWriter = Dispatchers.IO.limitedParallelism(1)
    private val delivery = Mutex()
    private val gate = Any()
    private val main = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    @Volatile private var contextRef: Context? = null
    @Volatile private var storeRef: UsageAnalyticsQueueStore? = null
    @Volatile private var activeCall: Call? = null
    private var catalog: JsonObject? = null
    private val resumed = mutableSetOf<Activity>()
    private var foreground = false
    private var qualified = false
    private var sessionId: String? = null
    private var activeDay = ""
    private var screen: String? = null
    private var backgroundElapsed: Long? = null
    // Session/consent state is owned in memory (guarded by [gate]) and persisted on [scope].
    // Reading it from SharedPreferences on the main thread bought nothing: the values are only
    // consulted here, while every persisted write cost a synchronous fsync on main.
    private var lastBackgroundMillis: Long? = null
    private var lastSeenMillis: Long = 0L
    @Volatile private var enabledState: Boolean? = null
    @Volatile private var generationValue: Long = -1L
    // Re-enqueueing unique work on every captured event only repeats two IPC calls; the
    // enqueued requests already survive process death. Reset when consent flips, which is
    // the only time WorkManager cancels them out from under us.
    @Volatile private var workScheduledGeneration: Long = -1L

    class Operation internal constructor(
        val id: String = UUID.randomUUID().toString(),
        internal val startedElapsed: Long = SystemClock.elapsedRealtime(),
        internal val generation: Long,
        internal val session: String?,
        internal val ended: AtomicBoolean = AtomicBoolean(false),
    )

    @Synchronized fun initialize(context: Context) {
        if (contextRef != null) return
        val app = context.applicationContext
        if (android.os.Build.VERSION.SDK_INT >= 28 && Application.getProcessName() != app.packageName) return
        catalog = JsonParser().parse(app.assets.open("analytics-catalog-v2.json").bufferedReader().use { it.readText() }).asJsonObject.getAsJsonObject("events")
        // One read at startup mirrors the SharedPreferences first-load that `isEnabled` already
        // performed; afterwards every consent/session read is served from memory.
        val settings = app.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        enabledState = settings.getBoolean("enabled", true)
        generationValue = settings.getLong("generation", 0)
        app.getSharedPreferences("usage_analytics_session", Context.MODE_PRIVATE).let { session ->
            sessionId = session.getString("session_id", null)
            lastSeenMillis = session.getLong("last_seen", 0)
            lastBackgroundMillis = session.getLong("last_background", 0).takeIf { it > 0 }
        }
        contextRef = app
        main.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { synchronized(gate) { foreground = true; qualified = false }; main.postDelayed(qualifier, 2000) }
                override fun onStop(owner: LifecycleOwner) {
                    synchronized(gate) {
                        foreground = false; qualified = false; backgroundElapsed = SystemClock.elapsedRealtime()
                        lastBackgroundMillis = System.currentTimeMillis()
                    }
                    persistSession()
                    main.removeCallbacks(qualifier); main.removeCallbacks(heartbeat); flush()
                }
            })
            (app as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    val name = when (activity.javaClass.simpleName) {
                        "MainActivity" -> "today"; "ScoreInquiryActivity" -> "scores"; "ExamActivity" -> "exams"
                        "CreditStatsActivity" -> "credits"; "EmptyClassroomActivity" -> "empty_room"
                        "ImportTimetableActivity" -> "timetable"; "AppNoticesActivity" -> "notices"; else -> null
                    }
                    synchronized(gate) {
                        resumed.add(activity); screen = name
                        if (qualified && foreground) { if (name != null) capture("screen_viewed", mapOf("screen" to name)); main.removeCallbacks(heartbeat); main.post(heartbeat) }
                        else { main.removeCallbacks(qualifier); main.postDelayed(qualifier, 2000) }
                    }
                }
                override fun onActivityCreated(a: Activity, b: Bundle?) {} ; override fun onActivityStarted(a: Activity) {}
                override fun onActivityPaused(a: Activity) { synchronized(gate) {
                    if (qualified && resumed.size == 1 && Instant.now().atZone(zone).toLocalDate().toString() != activeDay) {
                        activeDay = Instant.now().atZone(zone).toLocalDate().toString()
                        capture("app_active_day", mapOf("cause" to "midnight"))
                    }
                    resumed.remove(a)
                } } ; override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {} ; override fun onActivityDestroyed(a: Activity) { synchronized(gate) { resumed.remove(a) } }
            })
        }
        scope.launch {
            runCatching {
                val store = store()
                if (isEnabled(app)) { store.migrateLegacy(generation()); schedule(); flush() }
                else store.clearBefore(generation() + 1)
            }.onFailure { reportLocalFailure(it) }
        }
    }

    private val qualifier = Runnable { synchronized(gate) { qualify("resume") } }
    private val heartbeat = object : Runnable {
        override fun run() {
            synchronized(gate) {
                if (!foreground || resumed.isEmpty() || !qualified || !enabled()) return
                val day = Instant.now().atZone(zone).toLocalDate().toString()
                if (day != activeDay) { activeDay = day; capture("app_active_day", mapOf("cause" to "midnight")) }
                lastSeenMillis = System.currentTimeMillis()
            }
            persistSession(); flush(); main.postDelayed(this, 30_000)
        }
    }

    // Must hold gate. A background process can never qualify itself as foreground.
    private fun qualify(cause: String) {
        if (!foreground || resumed.isEmpty() || qualified || !enabled()) return
        val now = System.currentTimeMillis()
        val last = lastBackgroundMillis ?: lastSeenMillis
        val gap = backgroundElapsed?.let { SystemClock.elapsedRealtime() - it } ?: (now - last)
        val newSession = sessionId == null || gap < 0 || gap >= 30 * 60 * 1000L
        if (newSession) sessionId = UUID.randomUUID().toString()
        qualified = true
        activeDay = Instant.now().atZone(zone).toLocalDate().toString()
        lastBackgroundMillis = null
        lastSeenMillis = now
        persistSession()
        if (newSession) capture("session_started", mapOf("cause" to if (last == 0L) "cold_start" else cause))
        capture("app_foreground", emptyMap())
        screen?.let { capture("screen_viewed", mapOf("screen" to it)) }
        main.removeCallbacks(heartbeat); main.post(heartbeat)
    }

    /**
     * Persists the session snapshot that outlives the process. The disk write itself always
     * happens on [scope]; callers may hold [gate] because [synchronized] is reentrant.
     */
    private fun persistSession() {
        val app = contextRef ?: return
        val snapshot = synchronized(gate) {
            SessionSnapshot(sessionId, lastBackgroundMillis, lastSeenMillis)
        }
        scope.launch(sessionWriter) {
            runCatching {
                val editor = app.getSharedPreferences("usage_analytics_session", Context.MODE_PRIVATE).edit()
                editor.putString("session_id", snapshot.sessionId)
                if (snapshot.lastBackground == null) editor.remove("last_background")
                else editor.putLong("last_background", snapshot.lastBackground)
                editor.putLong("last_seen", snapshot.lastSeen)
                editor.commit()
            }.onFailure { reportLocalFailure(it) }
        }
    }

    private data class SessionSnapshot(val sessionId: String?, val lastBackground: Long?, val lastSeen: Long)

    fun isEnabled(context: Context): Boolean = enabledState
        ?: context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getBoolean("enabled", true)

    /**
     * Consent is published in memory first so callers observe it immediately, and the durable
     * write happens on [scope]. A disk failure is reported locally instead of crashing the UI
     * thread (`check` inside a Compose switch previously threw a raw exception at the caller).
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        val cutoff: Long
        synchronized(gate) {
            cutoff = generation() + 1
            enabledState = enabled
            generationValue = cutoff
            activeCall?.cancel(); qualified = false
            if (!enabled) { main.removeCallbacks(qualifier); main.removeCallbacks(heartbeat) }
            else { sessionId = null; main.postDelayed(qualifier, 2000) }
        }
        val work = WorkManager.getInstance(context)
        if (!enabled) {
            // WorkManager cancels both unique names; the next consent grant must enqueue again.
            workScheduledGeneration = -1
            work.cancelUniqueWork(ONCE); work.cancelUniqueWork(PERIODIC)
        }
        scope.launch {
            runCatching {
                val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
                if (!prefs.edit().putBoolean("enabled", enabled).putLong("generation", cutoff).remove("retry_at").commit()) {
                    reportLocalFailure(IllegalStateException("consent write rejected"))
                    return@runCatching
                }
                store().clearBefore(cutoff + if (enabled) 0 else 1)
                if (enabled && enabled()) schedule()
            }.onFailure { reportLocalFailure(it) }
        }
    }

    fun record(event: UsageAnalyticsEvent, dimensions: Map<String, String> = emptyMap()) = synchronized(gate) {
        if (event == UsageAnalyticsEvent.APP_FOREGROUND) return@synchronized // owned by actual lifecycle
        if (!enabled()) return@synchronized
        val spec = catalog?.getAsJsonObject(event.eventName) ?: return@synchronized
        if (spec.get("operation_required").asBoolean) { localCount("missing_operation"); return@synchronized }
        if (spec.get("active").asBoolean && !event.eventName.endsWith("_viewed")) qualify("user_action")
        capture(event.eventName, dimensions)
    }
    fun begin(event: UsageAnalyticsEvent, dimensions: Map<String, String> = emptyMap()): Operation = synchronized(gate) {
        qualify("user_action")
        val op = Operation(generation = generation(), session = sessionId)
        capture(event.eventName, dimensions, op)
        op
    }
    fun finish(operation: Operation?, event: UsageAnalyticsEvent, dimensions: Map<String, String> = emptyMap()) {
        if (operation == null || !operation.ended.compareAndSet(false, true)) return
        synchronized(gate) {
            if (operation.generation != generation()) return
            capture(event.eventName, dimensions, operation, (SystemClock.elapsedRealtime() - operation.startedElapsed).coerceIn(0, 86_400_000))
        }
    }
    fun <T> withinOperation(operation: Operation, block: () -> T): T =
        AnalyticsOperationContext.withOperation(AnalyticsOperationContext.Value(operation.id, operation.generation), block)

    internal fun currentOperationId(): String? = synchronized(gate) {
        AnalyticsOperationContext.get()?.takeIf { enabled() && it.generation == generation() }?.id
    }
    fun measurement(event: UsageAnalyticsEvent, dimensions: Map<String, String>, durationMs: Long, inputTokens: Long? = null, outputTokens: Long? = null) = synchronized(gate) {
        val op = Operation(generation = generation(), session = sessionId)
        capture(event.eventName, dimensions, op, durationMs.coerceIn(0, 86_400_000), inputTokens, outputTokens)
    }

    private fun capture(name: String, dimensions: Map<String, String>, op: Operation? = null, duration: Long? = null, inputTokens: Long? = null, outputTokens: Long? = null) {
        if (!enabled()) return
        val spec = catalog?.getAsJsonObject(name) ?: return
        if (spec.get("operation_required").asBoolean && op == null) { localCount("missing_operation"); return }
        val allowed = spec.getAsJsonObject("dimensions")
        val safe = dimensions.mapNotNull { (key, value) ->
            val values = allowed.getAsJsonArray(key)?.map { it.asString } ?: return@mapNotNull null
            val normalized = when { value in values -> value; "other" in values -> "other"; "unknown" in values -> "unknown"; else -> return@mapNotNull null }
            key to normalized
        }.toMap()
        val id = UUID.randomUUID().toString()
        val capturedGeneration = generation()
        val payload = mutableMapOf<String, Any>("event_id" to id, "event_name" to name, "occurred_at" to Instant.now().toString(), "platform" to "android", "app_version" to BuildConfig.VERSION_NAME, "build_number" to BuildConfig.VERSION_CODE.toString(), "sdk_version" to "2", "environment" to if (BuildConfig.DEBUG) "test" else "production", "visibility" to if (foreground && resumed.isNotEmpty() && qualified) "foreground" else "background", "dimensions" to safe)
        (op?.session ?: sessionId)?.let { payload["session_id"] = it }
        op?.let { payload["operation_id"] = it.id }
        duration?.let { payload["duration_ms"] = it }
        if (name == "model_request_finished") { inputTokens?.takeIf { it >= 0 }?.let { payload["input_tokens"] = it }; outputTokens?.takeIf { it >= 0 }?.let { payload["output_tokens"] = it } }
        val serialized = gson.toJson(payload)
        if (serialized.toByteArray(Charsets.UTF_8).size > 2048) { localCount("oversized_event"); return }
        val priority = if (name in setOf("app_foreground", "session_started", "app_active_day") || name.endsWith("_finished") || name.endsWith("_succeeded") || name.endsWith("_failed")) 1 else 0
        val queued = QueuedUsageEvent(id, serialized, 2, System.currentTimeMillis(), priority, capturedGeneration)
        scope.launch {
            runCatching {
                if (!enabled() || capturedGeneration != generation()) return@launch
                store().enqueue(queued)
                if (!enabled() || capturedGeneration != generation()) { store().clearBefore(generation() + if (enabled()) 0 else 1); return@launch }
                schedule(); if (name == "app_foreground" || store().size() >= 20) flush()
            }.onFailure { reportLocalFailure(it) }
        }
    }

    fun flush() { if (enabled()) scope.launch { runCatching { if (!drain()) schedule() }.onFailure { reportLocalFailure(it); schedule() } } }
    suspend fun drain(): Boolean = withContext(Dispatchers.IO) {
        delivery.withLock {
            if (!enabled()) return@withLock true
            val prefs = contextRef!!.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            if (System.currentTimeMillis() < prefs.getLong("retry_at", 0)) return@withLock false
            val store = store(); val owner = UUID.randomUUID().toString()
            if (!store.acquire(owner)) return@withLock false
            try {
                val deadline = SystemClock.elapsedRealtime() + 60_000
                var limit = 50
                while (enabled() && SystemClock.elapsedRealtime() < deadline) {
                    val gen = generation(); val batch = store.batch(gen, limit)
                    if (batch.isEmpty()) return@withLock true
                    val legacy = batch.first().schema == 1
                    val events = batch.map { JsonParser().parse(it.payload) }
                    val payload = mutableMapOf<String, Any>("app_id" to "hita-android", "installation_id" to store.installationId, "events" to events)
                    if (legacy) { payload["platform"] = "android"; payload["app_version"] = "legacy-unknown"; payload["build_number"] = "unknown" }
                    else { payload["schema_version"] = 2; payload["sent_at"] = Instant.now().toString() }
                    val endpoint = BuildConfig.AGENT_BACKEND_BASE_URL.trimEnd('/') + if (legacy) "/api/usage/events/batch" else "/api/telemetry/v2/events"
                    val call = http.newCall(Request.Builder().url(endpoint).post(gson.toJson(payload).toRequestBody("application/json".toMediaType())).build())
                    synchronized(gate) { if (!enabled() || generation() != gen) return@withLock true; activeCall = call }
                    try {
                        call.execute().use { response ->
                            if (response.code == 413 && limit > 1) { limit = (limit / 2).coerceAtLeast(1); return@use }
                            if (response.code == 429 || response.code in setOf(401, 403, 404)) {
                                val delay = if (response.code == 429) retryDelay(response.header("Retry-After")) else 60 * 60 * 1000L
                                prefs.edit().putLong("retry_at", System.currentTimeMillis() + delay).commit(); store.count("http_${response.code}"); return@withLock false
                            }
                            val body = response.body?.source()?.let { source -> source.request(256L * 1024 + 1); if (source.buffer.size > 256L * 1024) null else source.readUtf8() }.orEmpty()
                            val ids = AnalyticsAck.confirmed(response.code, if (legacy) 202 else 200, body, batch.map { it.eventId }.toSet(), legacy)
                            if (ids.isEmpty()) { store.count("unconfirmed_batch"); return@withLock false }
                            synchronized(gate) { if (enabled() && generation() == gen) store.remove(ids) }
                        }
                    } catch (_: Exception) { store.count("transport_failure"); return@withLock false }
                    finally { activeCall = null }
                    if (!store.acquire(owner)) return@withLock false
                }
                store.size() == 0
            } finally { store.release(owner) }
        }
    }
    private fun retryDelay(value: String?): Long {
        val seconds = value?.toLongOrNull()
        val delay = seconds?.coerceIn(0, 86_400)?.times(1000) ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis() }.getOrDefault(30_000)
        return delay.coerceIn(30_000, 86_400_000)
    }
    /**
     * Both unique requests survive process death, so re-enqueueing them for every captured event
     * only repeated two WorkManager IPC calls. Consent changes reset the marker because that is
     * the only path that cancels the requests.
     */
    private fun schedule() {
        val app = contextRef ?: return; if (!isEnabled(app)) return
        val gen = generation()
        if (workScheduledGeneration == gen) return
        workScheduledGeneration = gen
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val once = OneTimeWorkRequestBuilder<UsageAnalyticsWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(app).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, once)
        val periodic = PeriodicWorkRequestBuilder<UsageAnalyticsWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }
    @Synchronized private fun store(): UsageAnalyticsQueueStore = storeRef ?: UsageAnalyticsQueueStore(contextRef!!).also { storeRef = it }
    private fun generation(): Long = generationValue.takeIf { it >= 0 } ?: 0
    private fun enabled(): Boolean = enabledState ?: false
    private fun localCount(reason: String) { scope.launch { runCatching { store().count(reason) } } }
    private fun reportLocalFailure(error: Throwable) { LogUtils.w("UsageAnalytics: local storage unavailable (${error.javaClass.simpleName})") }
}
