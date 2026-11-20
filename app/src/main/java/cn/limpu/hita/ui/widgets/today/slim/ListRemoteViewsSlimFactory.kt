package cn.limpu.hita.ui.widgets.today.slim

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.ui.widgets.WidgetThemeUtils
import cn.limpu.hita.ui.widgets.today.slim.TodayWidgetSlim.Companion.EVENT_EXTRA2
import cn.limpu.hita.utils.TimeTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal class ListRemoteViewsSlimFactory(val mContext: Context, intent: Intent) :
    RemoteViewsService.RemoteViewsFactory {
    private val appWidgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )
    private val mBeans = mutableListOf<EventItem>()
    override fun getViewAt(position: Int): RemoteViews {
        // 获取 item_widget_device.xml 对应的RemoteViews
        val rv = RemoteViews(mContext.packageName, R.layout.widget_today_item_slim)
        val palette = WidgetThemeUtils.palette(mContext)

        // 设置 第position位的“视图”的数据
        val event = mBeans[position]
        val timeText = TimeTools.printTime(event.from.time) + "-" + TimeTools.printTime(event.to.time)
        rv.setTextViewText(R.id.name, event.name)
        rv.setTextViewText(R.id.time, timeText)
        val place = event.place?.trim().orEmpty()
        val result =
            if (TextUtils.isEmpty(place)) mContext.getString(R.string.unknown_location_widget) else place
        rv.setTextViewText(R.id.location, result)
        rv.setInt(R.id.ic_sub, "setBackgroundResource", palette.clockDrawableRes)

        if (position == highlightIndex) {
            val accent = palette.accentColor
            rv.setInt(R.id.icon, "setBackgroundResource", R.drawable.element_round_primary)
            rv.setTextColor(R.id.name, accent)
            rv.setTextColor(R.id.time, accent)
            rv.setTextColor(R.id.location, accent)
        } else {
            rv.setInt(R.id.icon, "setBackgroundResource", R.drawable.element_round_blue)
            rv.setTextColor(R.id.name, palette.primaryTextColor)
            rv.setTextColor(R.id.time, palette.secondaryTextColor)
            rv.setTextColor(R.id.location, palette.secondaryTextColor)
        }

        val lockIntent = Intent()
        lockIntent.putExtra(EVENT_EXTRA2, event.id)
        // 深链需要 (eventId, date) 才能定位到具体那一天。
        cn.limpu.hita.ui.widgets.WidgetDeepLink.fillIn(
            lockIntent,
            event.id,
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(event.from),
        )
        val bd = Bundle()
        bd.putString("eventId", event.id)
        bd.putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        lockIntent.putExtras(bd)
        rv.setOnClickFillInIntent(R.id.item, lockIntent)
        return rv
    }

    override fun getLoadingView(): RemoteViews? {
        return null//return RemoteViews(mContext.packageName, R.layout.widget_today_loading)
    }

    override fun getViewTypeCount(): Int {
        return 1
    }


    override fun onCreate() {
    }

    override fun onDataSetChanged() {
        val app = mContext.applicationContext as Application
        val timetableRepo = TimetableRepository(app)
        // 确保数据库操作在后台线程执行；跟随主课表显示目标过滤。
        val events = runBlocking(Dispatchers.IO) {
            val scopeIds = DisplayScopeResolver(app).currentSync()?.timetableIds.orEmpty()
            timetableRepo.getTodayEventsSync(scopeIds)
        }
        mBeans.clear()
        mBeans.addAll(events.sortedBy { it.from.time })
        highlightIndex = findHighlightIndex(mBeans)
    }


    override fun getItemId(position: Int): Long {
        // 返回当前项在“集合视图”中的位置
        return position.toLong()
    }


    override fun hasStableIds(): Boolean {
        return false
    }

    override fun getCount(): Int {
        return mBeans.size
    }

    override fun onDestroy() {
        mBeans.clear()
    }

    private var highlightIndex: Int = -1

    private fun findHighlightIndex(events: List<EventItem>): Int {
        val now = System.currentTimeMillis()
        val currentIndex = events.indexOfFirst { now in it.from.time until it.to.time }
        if (currentIndex >= 0) return currentIndex
        return events.indexOfFirst { it.from.time > now }
    }

}
