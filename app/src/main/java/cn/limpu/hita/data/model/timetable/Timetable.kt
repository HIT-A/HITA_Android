package cn.limpu.hita.data.model.timetable

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.sql.Timestamp
import java.util.*

@Entity(tableName = "timetable", indices = [Index(value = ["groupId"])])
class Timetable {
    @PrimaryKey
    var id: String = UUID.randomUUID().toString()
    var name //课表名称
            : String? = null
    var code //适配教务的课表code
            : String? = null
    var startTime //开始时间
            : Timestamp = Timestamp(0)
    var endTime //结束时间
            : Timestamp = Timestamp(0)
    var createdAt //创建时间
            : Timestamp = Timestamp(System.currentTimeMillis())
    var groupId //所属组合课表;null = 独立课表
            : String? = null
    var scheduleStructure: List<TimePeriodInDay> = getDefaultTimeStructure()//时间表结构


    /**
     * 获取某时间戳所对应的周数 =
     *
     * 周起点统一按中国时区归一：教务与课表时间戳都是校区墙钟，若按设备默认时区
     * 归一（海外或 CI 的 UTC），周日会回退到前一周，周次整体偏移一周。
     */
    fun getWeekNumber(ts: Long): Int {
        val c = startOfWeek(ts)
        if (c > endTime.time) return -1
        val termStart = startOfWeek(startTime.time)
        val x = Math.floorDiv(c - termStart, WEEK_MILLS)
        return when {
            x < 0 -> {
                -1
            }
            else -> {
                x.toInt() + 1
            }
        }
    }

    /** 把时间戳归一到中国时区所在周的周一 00:00:00.000。 */
    private fun startOfWeek(ts: Long): Long = Calendar.getInstance(chinaZone).apply {
        timeInMillis = ts
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis


    fun getTimestamps(week:Int,dow:Int,start:Int,end:Int): List<Long> {
        val startOfDay:Long = startTime.time + (week-1).toLong()*7*24*60*60*1000 + (dow-1).toLong()*24*60*60*1000
        val s = if (start - 1 in scheduleStructure.indices) scheduleStructure[start - 1].from.toMills() else 0L
        val e = if (end - 1 in scheduleStructure.indices) scheduleStructure[end - 1].to.toMills() else 0L
        return listOf(startOfDay + s, startOfDay + e)
    }

    fun getTimestamps(week:Int,dow:Int,period: TimePeriodInDay): List<Long> {
        val startOfDay:Long = startTime.time + (week-1).toLong()*7*24*60*60*1000 + (dow-1).toLong()*24*60*60*1000
        return listOf(startOfDay+period.from.toMills(),startOfDay+ period.to.toMills())
    }

    fun transformTimePeriod(start:Int,end:Int):TimePeriodInDay{
        val s = if (start - 1 in scheduleStructure.indices) scheduleStructure[start - 1].from else TimeInDay(0, 0)
        val e = if (end - 1 in scheduleStructure.indices) scheduleStructure[end - 1].to else TimeInDay(0, 0)
        return TimePeriodInDay(s, e)
    }

    fun transformCourseNumber(period:TimePeriodInDay):Pair<Int,Int>{
        var start = 0
        var end = 0
        for(i in scheduleStructure.indices){
            if(scheduleStructure[i].contains(period.from)) start = i
            if(scheduleStructure[i].contains(period.to)) end = i
        }
        return Pair(start+1,end+1)
    }
    fun setScheduleStructure(tp: TimePeriodInDay, position: Int) {
        if (position < scheduleStructure.size) {
            scheduleStructure[position].from = tp.from
            scheduleStructure[position].to = tp.to
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Timetable

        if (id != other.id) return false
        if (name != other.name) return false
        if (code != other.code) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (code?.hashCode() ?: 0)
        return result
    }


    fun getDefaultTimeStructure():List<TimePeriodInDay>{
        val res = mutableListOf<TimePeriodInDay>()
        res.add(TimePeriodInDay(TimeInDay(8,30), TimeInDay(10,15)))
        res.add(TimePeriodInDay(TimeInDay(10,30),TimeInDay(12,15)))
        res.add(TimePeriodInDay(TimeInDay(14,0),TimeInDay(15,45)))
        res.add(TimePeriodInDay(TimeInDay(16,0),TimeInDay(17,45)))
        res.add(TimePeriodInDay(TimeInDay(18,45),TimeInDay(20,30)))
        res.add(TimePeriodInDay(TimeInDay(20,45),TimeInDay(22,30)))
        return res
    }

    private companion object {
        /** 教务与课表时间戳都是校区墙钟，周次必须按中国时区归一。 */
        val chinaZone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }
}
