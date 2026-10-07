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
 * 已作废的本科生作息识别（仅本部/威海旧常量）。
 *
 * ⚠️ **不要**再把“第 1 节 08:30-09:20、第 2 节 09:25-10:15”当作作废特征：
 * 那正是深圳校区的真实作息，此前因被误判为作废版，导致深圳课表被改成本部/威海时间。
 * 该模式在无校区上下文的 SP 兜底路径里无法与威海作废版区分，故一律不动。
 */
internal fun isObsoleteUndergraduateScheduleAny(schedule: List<TimePeriodInDay>): Boolean =
    isLegacyUndergraduateSchedule(schedule)

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
