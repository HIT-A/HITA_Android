package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固化三校区本科生作息，防止再次漂移。
 *
 * 背景：这些值此前散落在多个文件且互不一致，导致威海/本部导入后显示作废作息。
 */
class CampusDefaultScheduleTest {

    private fun render(schedule: List<TimePeriodInDay>): List<String> =
        schedule.map { period ->
            val from = period.from
            val to = period.to
            "%02d:%02d-%02d:%02d".format(from.hour, from.minute, to.hour, to.minute)
        }

    @Test
    fun benbuUndergraduateMatchesOfficialSchedule() {
        val expected = listOf(
            "08:00-08:50", "08:55-09:45", "10:00-10:50", "10:55-11:45",
            "13:45-14:35", "14:40-15:30", "15:45-16:35", "16:40-17:30",
            "18:30-19:20", "19:25-20:15", "20:30-21:20", "21:25-22:15"
        )
        assertEquals(
            expected,
            render(CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU))
        )
    }

    @Test
    fun weihaiUndergraduateMatchesOfficialSchedule() {
        val expected = listOf(
            "08:00-08:50", "08:55-09:45", "10:05-10:55", "11:00-11:50",
            "14:00-14:50", "14:55-15:45", "16:05-16:55", "17:00-17:50",
            "18:40-19:30", "19:35-20:25", "20:45-21:35", "21:40-22:30"
        )
        assertEquals(
            expected,
            render(CampusDefaultSchedule.undergraduate(EASToken.Campus.WEIHAI))
        )
    }

    @Test
    fun shenzhenUndergraduateMatchesOfficialSchedule() {
        val expected = listOf(
            "08:30-09:20", "09:25-10:15", "10:30-11:20", "11:25-12:15",
            "14:00-14:50", "14:55-15:45", "16:00-16:50", "16:55-17:45",
            "18:45-19:35", "19:40-20:30", "20:45-21:35", "21:40-22:30"
        )
        assertEquals(
            expected,
            render(CampusDefaultSchedule.undergraduate(EASToken.Campus.SHENZHEN))
        )
    }

    @Test
    fun scheduleAlwaysHasTwelvePeriods() {
        for (campus in EASToken.Campus.entries) {
            assertEquals(12, CampusDefaultSchedule.undergraduate(campus).size)
        }
    }

    /**
     * 回归保护：**本部/威海**本科生作息不得再回到已作废的 08:30 版。
     * （该版本正是本轮用户反馈的元凶。）深圳真实作息本就是 08:30 起，不在此列。
     */
    @Test
    fun benbuAndWeihaiNeverStartAtObsolete0830Slot() {
        for (campus in listOf(EASToken.Campus.BENBU, EASToken.Campus.WEIHAI)) {
            val first = CampusDefaultSchedule.undergraduate(campus).first()
            assertTrue(
                "${campus} 第 1 节不应为 08:30 起（已作废版本），实际=${first.from.hour}:${first.from.minute}",
                !(first.from.hour == 8 && first.from.minute == 30)
            )
        }
    }

    /**
     * 回归保护：三校区本科生作息必须**互不相同**，深圳不得再被本部/威海表覆盖。
     */
    @Test
    fun threeCampusSchedulesArePairwiseDistinct() {
        val benbu = CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU)
        val weihai = CampusDefaultSchedule.undergraduate(EASToken.Campus.WEIHAI)
        val shenzhen = CampusDefaultSchedule.undergraduate(EASToken.Campus.SHENZHEN)
        assertTrue("本部与深圳作息不得相同", benbu != shenzhen)
        assertTrue("威海与深圳作息不得相同", weihai != shenzhen)
        assertTrue("本部与威海作息不得相同", benbu != weihai)
    }

    /**
     * 兜底作息必须按校区解析，且**不得**把深圳表当作全局共享常量：
     * 每个校区的兜底值应等于该校区自己的表。
     */
    @Test
    fun fallbackUndergraduateResolvesByCampus() {
        for (campus in EASToken.Campus.entries) {
            assertEquals(
                "$campus 的兜底作息应等于该校区表",
                render(CampusDefaultSchedule.undergraduate(campus)),
                render(CampusDefaultSchedule.fallbackUndergraduate(campus))
            )
        }
        // 回归保护：深圳、本部、威海的兜底值互不相同（不再是同一份深圳表）。
        val benbu = CampusDefaultSchedule.fallbackUndergraduate(EASToken.Campus.BENBU)
        val weihai = CampusDefaultSchedule.fallbackUndergraduate(EASToken.Campus.WEIHAI)
        val shenzhen = CampusDefaultSchedule.fallbackUndergraduate(EASToken.Campus.SHENZHEN)
        assertTrue("本部与威海兜底不得相同", benbu != weihai)
        assertTrue("本部与深圳兜底不得相同", benbu != shenzhen)
        assertTrue("威海与深圳兜底不得相同", weihai != shenzhen)
    }

    /** 本部与威海本科生作息必须不同（第 5 节一个 13:45、一个 14:00）。 */
    @Test
    fun benbuAndWeihaiUndergraduateDifferAtFifthPeriod() {
        val benbuFifth = CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU)[4]
        val weihaiFifth = CampusDefaultSchedule.undergraduate(EASToken.Campus.WEIHAI)[4]
        assertEquals(13, benbuFifth.from.hour)
        assertEquals(45, benbuFifth.from.minute)
        assertEquals(14, weihaiFifth.from.hour)
        assertEquals(0, weihaiFifth.from.minute)
    }

    /** 返回的必须是副本，调用方修改不应污染共享常量。 */
    @Test
    fun returnedScheduleIsMutableCopy() {
        val first = CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU)
        val second = CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU)
        first[0].from = cn.limpu.hita.data.model.timetable.TimeInDay(0, 0)
        assertEquals(8, second[0].from.hour)
    }
}
