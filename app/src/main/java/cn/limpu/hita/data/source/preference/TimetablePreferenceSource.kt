package cn.limpu.hita.data.source.preference


import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.source.web.eas.CampusDefaultSchedule


private const val SP_NAME_TIMETABLE = "timetable"

/**
 * 本科生默认作息。实际取值由 [CampusDefaultSchedule] 按校区解析，
 * 避免本文件再持有一份会漂移的副本，也避免把深圳表当作全局共享常量。
 */
private fun defaultUndergraduate(campus: EASToken.Campus): MutableList<TimePeriodInDay> =
    CampusDefaultSchedule.fallbackUndergraduate(campus)

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

// 注：历史上这里曾对 SP 兜底作息做过“旧常量识别 + 自愈覆盖”。
// 但那会在用户手动编辑作息后将其顶掉，且无法与深圳真实作息（第 1 节 08:30 起）区分。
// 现已移除该覆盖：用户数据一律原样返回；作废作息的修正在
// cn.limpu.hita.data.repository.TimetableScheduleStructureMigration 中对已导入课表处理。

class TimetablePreferenceSource(
    private val context: Context,
    private val easPreferenceSource: EasPreferenceSource
) {
    private var sharedPreferences: SharedPreferences? = null
    private val preference: SharedPreferences
        get() {
            if (sharedPreferences == null) {
                sharedPreferences = context.getSharedPreferences(SP_NAME_TIMETABLE, Context.MODE_PRIVATE)
            }
            return sharedPreferences!!
        }

    /**
     * 读取本科生/研究生作息。
     *
     * 本科生的默认兜底按**当前登录校区**解析（三校区各用各的，不共享）。
     *
     * 重要：一旦 SP 中已有用户数据（`class_num` 已写入），一律**原样返回**，
     * 不再被默认值覆盖——否则用户在导入页的手动编辑会被顶掉。历史作废作息
     * 的修正由 [cn.limpu.hita.data.repository.TimetableScheduleStructureMigration]
     * 对已导入课表处理，不在本读取路径重复自愈。
     */
    fun getSchedule(isUndergraduate: Boolean? = null): MutableList<TimePeriodInDay> {
        if (isUndergraduate == false) return graduate_default.toMutableList()
        val total = preference.getInt("class_num", -1)
        if (total < 0) {
            // 首启：返回默认值供调用方展示/兜底，但**不落盘**。
            // 只有用户主动编辑（saveSchedules）后才写入，这样“SP 有值”才意味着用户偏好，
            // 否则会反过来挡住教务接口返回的真实作息。
            return defaultUndergraduate(currentCampus())
        }
        val result: MutableList<TimePeriodInDay> = mutableListOf()
        for (i in 0 until total) {
            val tp: TimePeriodInDay = Gson().fromJson(preference.getString("class_$i", "{}"), TimePeriodInDay::class.java)
            result.add(tp)
        }
        return result
    }

    /** SP 中是否已有用户偏好作息（用户编辑过或导入过）。 */
    fun hasSavedSchedule(): Boolean = preference.getInt("class_num", -1) >= 0

    /** 当前登录校区；未登录时回退到深圳（与教务 token 默认一致）。 */
    private fun currentCampus(): EASToken.Campus = easPreferenceSource.getEasToken().campus

    fun saveSchedules(sch: List<TimePeriodInDay>) {
        val editor = preference.edit()
        for (i in sch.indices) {
            editor.putString("class_$i", Gson().toJson(sch[i]))
        }
        editor.putInt("class_num", sch.size).apply()
    }

}
