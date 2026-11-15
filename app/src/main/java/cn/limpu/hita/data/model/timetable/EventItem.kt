package cn.limpu.hita.data.model.timetable

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import cn.limpu.hita.utils.TimeTools
import java.io.Serializable
import java.sql.Timestamp
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

@Entity(
    tableName = "events",
    indices = [Index(value = ["timetableId", "type", "source"])]
)
class EventItem :Serializable,Comparable<EventItem>{
    enum class TYPE {
        CLASS, EXAM, OTHER, TAG
    }

    @PrimaryKey
    var id: String = UUID.randomUUID().toString()
    var type: TYPE = TYPE.CLASS
    var source: String = SOURCE_EAS_IMPORT
    var name //名称
            : String = "NULL"
    var place //地点
            : String? = ""
    var teacher //教师
            : String? = ""
    var subjectId //科目id
            : String = ""
    var timetableId //课表的id
            : String = ""
    var from //开始时间
            : Timestamp = Timestamp(0)
    var to //结束时间
            : Timestamp = Timestamp(0)
    var fromNumber:Int = 0
    var lastNumber:Int = 0

    @ColumnInfo(name = "created_at")
    var createdAt //创建时间
            : Timestamp = Timestamp(System.currentTimeMillis())
    /** 1 means a teaching-source course was hidden instead of physically deleted. */
    var hidden: Int = 0


    @Ignore
    var color:Int = 0//科目颜色，不进行存储

    override fun toString(): String {
        return "EventItem(id='$id', type=$type, source='$source', name='$name', place=$place, teacher=$teacher, subjectId='$subjectId', timetableId='$timetableId', from=$from, to=$to, createdAt=$createdAt"
    }


    /**
     * 获取fromTime和当前时间的距离（分钟）
     */
    fun getFromTimeDistance(): Int {
        return (abs(from.time - System.currentTimeMillis()) / 60000.0).roundToInt()
    }

    /**
     * 获取持续时长
     */
    fun getDurationInMinutes(): Int {
        return ((to.time - from.time) / 60000).toInt()
    }

    fun getDurationInMills(): Long{
        return (to.time - from.time)
    }
    /**
     * 获取周几，周一为1
     */
    fun getDow():Int{
        return TimeTools.getDow(from.time)
    }

    /**
     * 该事件的时间范围是否包括某时间戳
     */
    fun containsTimeStamp(ts: Long): Boolean {
        val timestamp = Timestamp(ts)
        return from.before(timestamp) && to.after(timestamp)
    }

    /**
     * 在某时间戳之后发生
     */
    fun happensAfterTimeStamp(ts:Long):Boolean{
        val timestamp = Timestamp(ts)
        return from.after(timestamp)
    }

    /**
     * 计算某时间戳在本事件中的进度
     */
    fun getProgress(ts:Long):Float{
        val timestamp = Timestamp(ts)
        return when {
            timestamp.before(from) -> {
                0f
            }
            timestamp.after(to) -> {
                1f
            }
            else -> {
                ((ts-from.time).toFloat()/(to.time-from.time).toFloat())
            }
        }
    }



    override fun compareTo(other: EventItem): Int {
        return from.compareTo(other.from)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EventItem

        if (id != other.id) return false
        if (source != other.source) return false
        if (name != other.name) return false
        if (place != other.place) return false
        if (teacher != other.teacher) return false
        if (subjectId != other.subjectId) return false
        if (timetableId != other.timetableId) return false
        if (from != other.from) return false
        if (to != other.to) return false
        if (fromNumber != other.fromNumber) return false
        if (lastNumber != other.lastNumber) return false
        if (color != other.color) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (place?.hashCode() ?: 0)
        result = 31 * result + (teacher?.hashCode() ?: 0)
        result = 31 * result + subjectId.hashCode()
        result = 31 * result + timetableId.hashCode()
        result = 31 * result + from.hashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + fromNumber
        result = 31 * result + lastNumber
        result = 31 * result + color
        return result
    }

    companion object{
        const val SOURCE_EAS_IMPORT = "EAS_IMPORT"
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_AGENT = "AGENT"
        const val SOURCE_ICS_IMPORT = "ICS_IMPORT"
        const val SOURCE_SHARE_IMPORT = "SHARE_IMPORT"
        const val SOURCE_FOLLOWED_SCHOOL = "FOLLOWED_SCHOOL"
        const val SOURCE_COURSE_PLAN = "COURSE_PLAN"

        fun getTagInstance(name:String):EventItem{
            val result = EventItem()
            result.type = TYPE.TAG
            result.source = SOURCE_MANUAL
            result.name = name
            return result
        }
    }


}
