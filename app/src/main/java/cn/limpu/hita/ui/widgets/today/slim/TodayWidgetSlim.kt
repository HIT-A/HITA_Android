package cn.limpu.hita.ui.widgets.today.slim

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import cn.limpu.hita.data.work.WidgetRefreshScheduler
import cn.limpu.hita.ui.widgets.WidgetUtils
import cn.limpu.hita.ui.widgets.WidgetUtils.EVENT_REFRESH
import cn.limpu.hita.ui.widgets.today.TodayUtils
import cn.limpu.hita.ui.widgets.today.TodayUtils.goAsync

/**
 * Implementation of App Widget functionality.
 */
class TodayWidgetSlim : AppWidgetProvider() {

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
        goAsync { TodayUtils.updateWidgets(context, appWidgetManager, appWidgetIds, slim = true) }
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
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetSlim::class.java))
                val requested = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                onUpdate(context, manager, if (requested == AppWidgetManager.INVALID_APPWIDGET_ID) ids
                    else ids.filter { it == requested }.toIntArray())
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
