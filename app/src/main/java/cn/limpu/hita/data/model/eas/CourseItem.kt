package cn.limpu.hita.data.model.eas

import com.google.gson.Gson

/**
 * 导入课表时使用：总课表里的课程条目
 */
class CourseItem {
    var code: String? = null
    var name: String? = null
    var weeks: MutableList<Int> = mutableListOf()
    // 上游明确给出的上课日期（yyyy-MM-dd），导入时优先于推算周次。
    var scheduledDate: String? = null
    var teacher: String? = null
    var classroom: String? = null
    var rawName: String? = null
    var notes: String? = null
    var dow = -1
    var begin = -1
    var last = -1
    // 实验课的具体时间（自由时间，不按节次）
    var startTime: String? = null   // 格式："15:40"
    var endTime: String? = null     // 格式："18:10"


    override fun toString(): String {
        return Gson().toJson(this)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CourseItem

        if (name != other.name) return false
        if (weeks != other.weeks) return false
        if (scheduledDate != other.scheduledDate) return false
        if (teacher != other.teacher) return false
        if (classroom != other.classroom) return false
        if (dow != other.dow) return false
        if (begin != other.begin) return false
        if (last != other.last) return false
        // Include time fields for free time courses
        if (startTime != other.startTime) return false
        if (endTime != other.endTime) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name?.hashCode() ?: 0
        result = 31 * result + weeks.hashCode()
        result = 31 * result + (scheduledDate?.hashCode() ?: 0)
        result = 31 * result + (teacher?.hashCode() ?: 0)
        result = 31 * result + (classroom?.hashCode() ?: 0)
        result = 31 * result + dow
        result = 31 * result + begin
        result = 31 * result + last
        result = 31 * result + (startTime?.hashCode() ?: 0)
        result = 31 * result + (endTime?.hashCode() ?: 0)
        return result
    }
}
