package cn.limpu.hita.ui.widgets.today

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.feature.timetable.TimetableEventTarget
import cn.limpu.hita.ui.main.MainActivity
import cn.limpu.hita.ui.widgets.WidgetConfigCodec
import cn.limpu.hita.ui.widgets.WidgetPrefs
import cn.limpu.hita.ui.widgets.WidgetUtils
import cn.limpu.hita.ui.widgets.WidgetThemeUtils
import cn.limpu.hita.ui.widgets.today.normal.TodayWidget
import cn.limpu.hita.ui.widgets.today.slim.TodayWidgetSlim
import cn.limpu.hita.utils.SpecialEventReminderUtils
import cn.limpu.hita.utils.TimeTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

object TodayUtils {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun BroadcastReceiver.goAsync(
        coroutineScope: CoroutineScope = scope,
        block: suspend () -> Unit
    ) {
        val result = goAsync()
        coroutineScope.launch {
            try {
                block()
            } finally {
                // Always call finish(), even if the coroutineScope was cancelled
                result.finish()
            }
        }
    }

    /** A deleted pinned target must stay empty, never silently display a different timetable. */
    internal fun scopeIds(resolver: DisplayScopeResolver, scopeKey: String?): List<String> =
        if (scopeKey == null) resolver.currentSync()?.timetableIds.orEmpty()
        else WidgetConfigCodec.decodeScope(scopeKey)?.let(resolver::exactTimetableIdsSync).orEmpty()

    internal suspend fun updateWidgets(
        context: Context, manager: AppWidgetManager, ids: IntArray, slim: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val app = context.applicationContext as Application
        val prefs = WidgetPrefs(app)
        val resolver = DisplayScopeResolver(app)
        val repository = TimetableRepository(app)
        val now = System.currentTimeMillis()
        val byScope = HashMap<String?, Pair<List<EventItem>, List<EventItem>>>()
        for (id in ids) {
            val config = prefs.read(id)
            val (events, exams) = byScope.getOrPut(config.scopeKey) {
                val timetableIds = scopeIds(resolver, config.scopeKey)
                repository.getTodayEventsSync(timetableIds) to
                    repository.getUpcomingExamsWithinReminderWindowSync(now, timetableIds)
            }
            setUpOneWidget(app, events, exams, manager, id, slim, config.background)
        }
    }

    @Suppress("DEPRECATION")
    private fun setUpOneWidget(
        context: Context,
        events: List<EventItem>,
        upcomingExams: List<EventItem>,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        slim: Boolean,
        background: WidgetConfigCodec.Background,
    ) {
        val palette = WidgetThemeUtils.palette(context)
        val views = RemoteViews(
            context.packageName ?: "",
            if (slim) R.layout.widget_today_slim else R.layout.widget_today
        )
        views.setInt(R.id.widget_root, "setBackgroundResource", when (background) {
            WidgetConfigCodec.Background.PLAIN -> palette.backgroundDrawableRes
            WidgetConfigCodec.Background.CARD -> if (WidgetThemeUtils.useDarkPalette(context)) {
                R.drawable.widget_today_card_dark
            } else R.drawable.widget_today_card_light
        })
        views.setTextColor(R.id.tv_title, palette.primaryTextColor)
        views.setTextColor(R.id.exam_reminder_time, palette.accentColor)
        views.setTextColor(R.id.exam_reminder_name, palette.primaryTextColor)
        views.setInt(R.id.imageView12, "setBackgroundResource", palette.dividerDrawableRes)
        views.setInt(R.id.loading_icon, "setBackgroundResource", palette.placeholderDrawableRes)
        views.setTextColor(R.id.loading, palette.secondaryTextColor)
        val btIntent = Intent().setAction(WidgetUtils.EVENT_REFRESH)
        btIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        btIntent.setClass(
            context,
            if (slim) TodayWidgetSlim::class.java else TodayWidget::class.java
        )
        val btPendingIntent: PendingIntent =
            PendingIntent.getBroadcast(
                context,
                appWidgetId,
                btIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        views.setOnClickPendingIntent(R.id.refresh, btPendingIntent)
        val ai = Intent(context, MainActivity::class.java)
        val bi =
            PendingIntent.getActivity(
                context,
                0,
                ai,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        views.setOnClickPendingIntent(R.id.wid, bi)
        val gridIntent = Intent(context, MainActivity::class.java).apply {
            action = if (slim) TimetableEventTarget.ACTION_WIDGET_SLIM_ROW else TimetableEventTarget.ACTION_WIDGET_ROW
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("hita-widget://row/$appWidgetId")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // Collection rows supply event/date extras; the target component and action are fixed.
        val pendingIntent = PendingIntent.getActivity(
            context, appWidgetId, gridIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        views.setPendingIntentTemplate(R.id.list, pendingIntent)
        views.setTextViewText(
            R.id.tv_title,
            TimeTools.getDateString(
                context,
                Calendar.getInstance(),
                simplified = true,
                TTYMode = if (slim) TimeTools.TTY_NONE else TimeTools.TTY_WK2_FOLLOWING
            )
        )
        val serviceIntent = Intent(context, ListWidgetService::class.java)
        serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        serviceIntent.putExtra("slim", slim)
        serviceIntent.data = Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME))
        views.setRemoteAdapter(R.id.list, serviceIntent)
        if (upcomingExams.isNotEmpty()) {
            val firstExam = upcomingExams.first()
            val text = buildString {
                append(SpecialEventReminderUtils.formatExamReminderLine(firstExam))
                if (upcomingExams.size > 1) {
                    append(" 等 ")
                    append(upcomingExams.size)
                    append(" 场考试")
                }
            }
            views.setViewVisibility(R.id.exam_reminder, View.VISIBLE)
            views.setTextViewText(
                R.id.exam_reminder_time,
                context.getString(R.string.timeline_exam_reminder_tag)
            )
            views.setTextViewText(
                R.id.exam_reminder_name,
                text
            )
        } else {
            views.setViewVisibility(R.id.exam_reminder, View.GONE)
        }
        if (events.isEmpty()) {
            views.setTextViewText(
                R.id.loading,
                context.getString(R.string.timeline_head_free_title)
            )
            views.setViewVisibility(R.id.list, View.GONE)
            views.setViewVisibility(R.id.loading_icon, View.VISIBLE)
            views.setViewVisibility(R.id.place_holder, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.place_holder, View.GONE)
            views.setViewVisibility(R.id.list, View.VISIBLE)
        }
        appWidgetManager.updateAppWidget(appWidgetId, views)
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.list)
    }
}
