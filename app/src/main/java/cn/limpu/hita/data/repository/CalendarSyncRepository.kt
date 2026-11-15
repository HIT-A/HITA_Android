package cn.limpu.hita.data.repository

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把课次写入系统日历。
 *
 * 分工：[CalendarSyncPlanner] 决定「写什么」，这里只负责「怎么写」与失败归类。
 * 失败必须是显式的三种状态之一，方便 UI 给出可操作提示，而不是静默什么都不做。
 */
class CalendarSyncRepository(private val context: Context) {

    sealed class Result {
        data class Success(val inserted: Int, val updated: Int, val skipped: Int) : Result()
        /** 没有授予读写日历权限。 */
        data object PermissionDenied : Result()
        /** 设备上没有可写的本地日历账户。 */
        data object NoWritableCalendar : Result()
        data class Failed(val message: String) : Result()
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    suspend fun sync(entries: List<CalendarSyncEntry>): Result = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext Result.PermissionDenied
        val calendarId = writableCalendarId() ?: return@withContext Result.NoWritableCalendar
        val existing = existingEventIds()
        val plan = CalendarSyncPlanner.plan(entries, existing)
        try {
            plan.updates.forEach { (id, entry) -> update(id, entry) }
            plan.inserts.forEach { insert(calendarId, it) }
            Result.Success(
                inserted = plan.inserts.size,
                updated = plan.updates.size,
                skipped = plan.skipped.size,
            )
        } catch (error: Exception) {
            Result.Failed(error.message ?: "写入系统日历失败")
        }
    }

    private fun writableCalendarId(): Long? {
        context.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID),
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${Calendars._ID} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    /**
     * 用 `DESCRIPTION` 里的幂等键反查已同步的课次。
     *
     * 不用 `Events._SYNC_ID`：那是账号同步适配器专用字段，本应用没有同步适配器，
     * 写进去对其它读取方是噪音。
     */
    private fun existingEventIds(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        context.contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events.DESCRIPTION),
            "${Events.DESCRIPTION} LIKE ?",
            arrayOf("${KEY_PREFIX}%"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val key = cursor.getString(1)?.lineSequence()
                    ?.firstOrNull { it.startsWith(KEY_PREFIX) }
                    ?: continue
                result[key] = id
            }
        }
        return result
    }

    private fun insert(calendarId: Long, entry: CalendarSyncEntry) {
        val values = valuesFor(entry).apply { put(Events.CALENDAR_ID, calendarId) }
        context.contentResolver.insert(Events.CONTENT_URI, values)
    }

    private fun update(id: Long, entry: CalendarSyncEntry) {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, id)
        context.contentResolver.update(uri, valuesFor(entry), null, null)
    }

    private fun valuesFor(entry: CalendarSyncEntry) = ContentValues().apply {
        put(Events.TITLE, entry.title)
        put(Events.EVENT_LOCATION, entry.location)
        put(Events.DESCRIPTION, listOfNotNull(entry.description, entry.idempotencyKey).joinToString("\n"))
        put(Events.DTSTART, entry.startMillis)
        put(Events.DTEND, entry.endMillis)
        put(Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
    }

    companion object {
        private const val KEY_PREFIX = "hita:"
    }
}
