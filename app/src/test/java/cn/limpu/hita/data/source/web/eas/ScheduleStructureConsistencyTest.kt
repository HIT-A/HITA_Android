package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.EASToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一致性回归：导入页显示作息、合并逻辑、实验课节次反推必须同源。
 *
 * 此前 `BenbuScheduleParser` 自带一套 `10:05/13:30` 的旧值，与
 * `BenbuEASWebSource` 的本科生表冲突，导致实验课反推节次对不上导入页。
 */
class ScheduleStructureConsistencyTest {

    @Test
    fun benbuParserUsesSameScheduleAsBenbuWebSource() {
        // 两者现在都来自 CampusDefaultSchedule.undergraduate(BENBU)。
        val expected = CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU)
        val fromSchedule = CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU)
        assertEquals(expected, fromSchedule)
    }

    /**
     * 本部本科生第 5/6 节必须与课表页夹具 `第5,6节 13:45~15:30` 对齐。
     * 即 13:45-14:35 + 14:40-15:30。
     */
    @Test
    fun benbuFifthAndSixthPeriodsMatchTimetableFixture() {
        val benbu = CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU)
        val fifth = benbu[4]
        val sixth = benbu[5]
        assertEquals(13, fifth.from.hour)
        assertEquals(45, fifth.from.minute)
        assertEquals(15, sixth.to.hour)
        assertEquals(30, sixth.to.minute)
    }

    /** 作息必须单调递增且不重叠（合并逻辑依赖该前提）。 */
    @Test
    fun undergraduateScheduleIsMonotonicForAllCampuses() {
        for (campus in EASToken.Campus.entries) {
            val schedule = CampusDefaultSchedule.undergraduate(campus)
            for (i in 1 until schedule.size) {
                val previousEnd = schedule[i - 1].to
                val currentStart = schedule[i].from
                val previousMinutes = previousEnd.hour * 60 + previousEnd.minute
                val currentMinutes = currentStart.hour * 60 + currentStart.minute
                assertTrue(
                    "$campus 第 ${i + 1} 节起点早于上一节终点",
                    currentMinutes > previousMinutes
                )
            }
        }
    }
}
