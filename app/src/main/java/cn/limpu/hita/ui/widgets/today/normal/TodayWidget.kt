package cn.limpu.hita.ui.widgets.today.normal

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import cn.limpu.hita.data.work.WidgetRefreshScheduler
import cn.limpu.hita.ui.widgets.WidgetUtils
import cn.limpu.hita.ui.widgets.WidgetUtils.EVENT_REFRESH
import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.ui.widgets.today.TodayUtils.goAsync
import cn.limpu.hita.ui.widgets.today.TodayUtils.updateWidgets

/**
 * Implementation of App Widget functionality.
 */
class TodayWidget : AppWidgetProvider() {

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
        goAsync { updateWidgets(context, appWidgetManager, appWidgetIds, slim = false) }
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
            EVENT_REFRESH -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
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
        LogUtils.d("onDisabled")
        if (!WidgetUtils.hasAnyWidget(context)) {
            WidgetRefreshScheduler.cancel(context)
        }
        super.onDisabled(context)
    }


}
