package cn.limpu.hita.ui.widgets.today.normal

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.data.work.WidgetRefreshScheduler
import cn.limpu.hita.ui.widgets.WidgetUtils
import cn.limpu.hita.ui.widgets.WidgetUtils.EVENT_REFRESH
import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.ui.widgets.today.TodayUtils.goAsync
import cn.limpu.hita.ui.widgets.today.TodayUtils.setUpOneWidget
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of App Widget functionality.
 */
class TodayWidget : AppWidgetProvider() {
    companion object {
        const val EVENT_CLICK = "cn.limpu.hita.WIDGET_EVENT_CLICK"
        const val EVENT_EXTRA = "cn.limpu.hita.EXTRA_ITEM"
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // 系统会复用 appWidgetId；不清理会让新实例继承旧实例的范围与背景。
        val prefs = cn.limpu.hita.ui.widgets.WidgetPrefs(context)
        appWidgetIds.forEach(prefs::delete)
        super.onDeleted(context, appWidgetIds)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // There may be multiple widgets active, so update all of them
        val timetableRepo =
            TimetableRepository(context.applicationContext as Application)
        goAsync{
            // 确保数据库操作在后台线程执行
            val (events, upcomingExams) = withContext(Dispatchers.IO) {
                run {
                    val scopeIds = DisplayScopeResolver(context.applicationContext as Application).currentSync()?.timetableIds.orEmpty()
                    timetableRepo.getTodayEventsSync(scopeIds) to
                            timetableRepo.getUpcomingExamsWithinReminderWindowSync(System.currentTimeMillis(), scopeIds)
                }
            }
            for (appWidgetId in appWidgetIds) {
                LogUtils.d("UPDATE:$appWidgetId")
                setUpOneWidget(context, events, upcomingExams, appWidgetManager, appWidgetId,false)
            }
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        when (intent?.action) {
            Intent.ACTION_CONFIGURATION_CHANGED -> {
                val mgr = AppWidgetManager.getInstance(context)
                val cn = ComponentName(context, TodayWidget::class.java)
                val ids = mgr.getAppWidgetIds(cn)
                if (ids.isNotEmpty()) {
                    onUpdate(context, mgr, ids)
                }
            }
            EVENT_CLICK -> {
//                Log.e("WI", "click")
//                val bd = intent.extras
//                val position = intent.getStringExtra(EVENT_EXTRA)
//                Toast.makeText(context, "打开...$position", Toast.LENGTH_SHORT).show()
            }
            EVENT_REFRESH -> {
                val cn = ComponentName(context, TodayWidget::class.java)
                val mgr = AppWidgetManager.getInstance(context)
                val timetableRepo =
                    TimetableRepository(context.applicationContext as Application)
                goAsync {
                    // 确保数据库操作在后台线程执行
                    val (events, upcomingExams) = withContext(Dispatchers.IO) {
                        run {
                    val scopeIds = DisplayScopeResolver(context.applicationContext as Application).currentSync()?.timetableIds.orEmpty()
                    timetableRepo.getTodayEventsSync(scopeIds) to
                            timetableRepo.getUpcomingExamsWithinReminderWindowSync(System.currentTimeMillis(), scopeIds)
                }
                    }
                    for (appWidgetId in mgr.getAppWidgetIds(cn)) {
                        LogUtils.d("refressh$appWidgetId")
                        setUpOneWidget(context, events, upcomingExams, mgr, appWidgetId,false)
                    }
                }

            }
        }

    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshScheduler.schedule(context)
    }

    override fun onDisabled(context: Context) {
        LogUtils.d("onDisabled")
        if (!WidgetUtils.hasAnyWidget(context)) {
            WidgetRefreshScheduler.cancel(context)
        }
        super.onDisabled(context)
    }


}
