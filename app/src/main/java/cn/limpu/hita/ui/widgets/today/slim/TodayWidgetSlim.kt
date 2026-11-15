package cn.limpu.hita.ui.widgets.today.slim

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
import cn.limpu.hita.ui.widgets.today.TodayUtils
import cn.limpu.hita.ui.widgets.today.TodayUtils.goAsync
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of App Widget functionality.
 */
@OptIn(DelicateCoroutinesApi::class)
class TodayWidgetSlim : AppWidgetProvider() {
    companion object {
        const val EVENT_CLICK2 = "cn.limpu.hita.WIDGET_EVENT_CLICK2"
        const val EVENT_EXTRA2 = "cn.limpu.hita.EXTRA_ITEM2"
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // 与普通小组件一致：删除实例时清掉它自己的配置。
        val prefs = cn.limpu.hita.ui.widgets.WidgetPrefs(context)
        appWidgetIds.forEach(prefs::delete)
        super.onDeleted(context, appWidgetIds)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
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
            for (appWidgetId in appWidgetIds) {
                //Log.e("WI2", "UPDATE:$appWidgetId")
                TodayUtils.setUpOneWidget(context, events, upcomingExams, appWidgetManager, appWidgetId, true)
            }
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        when (intent?.action) {
            Intent.ACTION_CONFIGURATION_CHANGED -> {
                val mgr = AppWidgetManager.getInstance(context)
                val cn = ComponentName(context, TodayWidgetSlim::class.java)
                val ids = mgr.getAppWidgetIds(cn)
                if (ids.isNotEmpty()) {
                    onUpdate(context, mgr, ids)
                }
            }
            EVENT_REFRESH -> {
                val cn = ComponentName(context, TodayWidgetSlim::class.java)
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
                       // Log.e("WI2", "refressh$appWidgetId")
                        TodayUtils.setUpOneWidget(context, events, upcomingExams, mgr, appWidgetId, true)
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
        //Log.e("WI2", "onDisabled")
        if (!WidgetUtils.hasAnyWidget(context)) {
            WidgetRefreshScheduler.cancel(context)
        }
        super.onDisabled(context)
    }


}
