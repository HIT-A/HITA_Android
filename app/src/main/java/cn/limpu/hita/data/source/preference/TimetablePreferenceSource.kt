package cn.limpu.hita.data.source.preference


import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.source.web.eas.CampusDefaultSchedule


private const val SP_NAME_TIMETABLE = "timetable"

/**
 * 本科生默认作息。实际取值由 [CampusDefaultSchedule] 统一维护，
 * 避免本文件再持有一份会漂移的副本。
 */
private val undergraduate_default: MutableList<TimePeriodInDay>
    get() = CampusDefaultSchedule.fallbackUndergraduate()

private val graduate_default = mutableListOf(
        TimePeriodInDay(TimeInDay(8, 0), TimeInDay(8, 50)),
        TimePeriodInDay(TimeInDay(8, 55), TimeInDay(9, 45)),
        TimePeriodInDay(TimeInDay(10, 0), TimeInDay(10, 50)),
        TimePeriodInDay(TimeInDay(10, 55), TimeInDay(11, 45)),
        TimePeriodInDay(TimeInDay(14, 0), TimeInDay(14, 50)),
        TimePeriodInDay(TimeInDay(14, 55), TimeInDay(15, 45)),
        TimePeriodInDay(TimeInDay(16, 0), TimeInDay(16, 50)),
        TimePeriodInDay(TimeInDay(16, 55), TimeInDay(17, 45)),
        TimePeriodInDay(TimeInDay(18, 45), TimeInDay(19, 35)),
        TimePeriodInDay(TimeInDay(19, 40), TimeInDay(20, 30)),
        TimePeriodInDay(TimeInDay(20, 45), TimeInDay(21, 35)),
        TimePeriodInDay(TimeInDay(21, 40), TimeInDay(22, 30)))

/**
 * 更早的遗留本科生作息识别（第 2 节 09:30 起、第 5 节 13:45）。
 */
private fun isLegacyUndergraduateSchedule(schedule: List<TimePeriodInDay>): Boolean {
    if (schedule.size < 12) return false
    val first = schedule[0]
    val second = schedule[1]
    val fifth = schedule.getOrNull(4)
    return first.from.hour == 8 && first.from.minute == 30 &&
        first.to.hour == 9 && first.to.minute == 20 &&
        second.from.hour == 9 && second.from.minute == 30 &&
        second.to.hour == 10 && second.to.minute == 15 &&
        fifth?.from?.hour == 13 && fifth.from.minute == 45
}

/**
 * 当前已作废的本科生作息识别（第 1 节 08:30-09:20、第 2 节 09:25-10:15）。
 *
 * 该版本曾被写入 SP 与 Room，且 `isLegacyUndergraduateSchedule` 认不出它，
 * 导致旧数据不会自愈。此处补上识别。
 */
internal fun isObsoleteUndergraduateSchedule(schedule: List<TimePeriodInDay>): Boolean {
    if (schedule.size < 12) return false
    val first = schedule[0]
    val second = schedule[1]
    return first.from.hour == 8 && first.from.minute == 30 &&
        first.to.hour == 9 && first.to.minute == 20 &&
        second.from.hour == 9 && second.from.minute == 25 &&
        second.to.hour == 10 && second.to.minute == 15
}

/** 命中任意一套已作废的本科生作息。 */
internal fun isObsoleteUndergraduateScheduleAny(schedule: List<TimePeriodInDay>): Boolean =
    isLegacyUndergraduateSchedule(schedule) || isObsoleteUndergraduateSchedule(schedule)

class TimetablePreferenceSource(private val context: Context) {
    private var sharedPreferences: SharedPreferences? = null
    private val preference: SharedPreferences
        get() {
            if (sharedPreferences == null) {
                sharedPreferences = context.getSharedPreferences(SP_NAME_TIMETABLE, Context.MODE_PRIVATE)
            }
            return sharedPreferences!!
        }

    fun getSchedule(isUndergraduate: Boolean? = null): MutableList<TimePeriodInDay> {
        if (isUndergraduate == false) return graduate_default.toMutableList()
        var result: MutableList<TimePeriodInDay> = mutableListOf()
        val total = preference.getInt("class_num", -1)
        if (total < 0) {
            result = undergraduate_default
            saveSchedules(result)
            return result
        }
        for (i in 0 until total) {
            val tp: TimePeriodInDay = Gson().fromJson(preference.getString("class_$i", "{}"), TimePeriodInDay::class.java)
            result.add(tp)
        }
        if (isObsoleteUndergraduateScheduleAny(result)) {
            result = undergraduate_default
            saveSchedules(result)
        }
        return result
    }

    fun saveSchedules(sch: List<TimePeriodInDay>) {
        val editor = preference.edit()
        for (i in sch.indices) {
            editor.putString("class_$i", Gson().toJson(sch[i]))
        }
        editor.putInt("class_num", sch.size).apply()
    }

}
