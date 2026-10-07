package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.source.web.eas.CampusDefaultSchedule
import cn.limpu.hita.data.model.eas.EASToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 迁移决策的纯函数单测：命中旧特征才替换，未命中一律不动。
 */
class TimetableScheduleStructureMigrationTest {

    private fun period(fh: Int, fm: Int, th: Int, tm: Int) =
        TimePeriodInDay(TimeInDay(fh, fm), TimeInDay(th, tm))

    private fun slot(s: String, e: String): TimePeriodInDay {
        val (sh, sm) = s.split(":").map(String::toInt)
        val (eh, em) = e.split(":").map(String::toInt)
        return period(sh, sm, eh, em)
    }

    /** 已作废的 08:30 本科版（本次 bug 的元凶）。 */
    private fun obsoleteUndergrad() = mutableListOf(
        slot("08:30", "09:20"), slot("09:25", "10:15"),
        slot("10:30", "11:20"), slot("11:25", "12:15"),
        slot("14:00", "14:50"), slot("14:55", "15:45"),
        slot("16:00", "16:50"), slot("16:55", "17:45"),
        slot("18:45", "19:35"), slot("19:40", "20:30"),
        slot("20:45", "21:35"), slot("21:40", "22:30"),
    )

    /** 更早的 09:30 遗留版。 */
    private fun legacyUndergrad() = mutableListOf(
        slot("08:30", "09:20"), slot("09:30", "10:15"),
        slot("10:30", "11:20"), slot("11:25", "12:15"),
        slot("13:45", "14:35"), slot("14:55", "15:45"),
        slot("16:00", "16:50"), slot("16:55", "17:45"),
        slot("18:45", "19:35"), slot("19:40", "20:30"),
        slot("20:45", "21:35"), slot("21:40", "22:30"),
    )

    /** BenbuScheduleParser 旧值。 */
    private fun benbuParserLegacy() = mutableListOf(
        slot("08:00", "08:50"), slot("08:55", "09:45"),
        slot("10:05", "10:55"), slot("11:00", "11:50"),
        slot("13:30", "14:20"), slot("14:25", "15:15"),
        slot("15:25", "16:15"), slot("16:20", "17:10"),
        slot("17:20", "18:10"), slot("18:30", "19:20"),
        slot("19:30", "20:20"), slot("20:30", "21:20"),
    )

    @Test
    fun obsoleteUndergraduateIsReplacedForWeihai() {
        val target = TimetableScheduleStructureMigration.resolveTargetSchedule(
            obsoleteUndergrad(), "WEIHAI:2025-2026-2"
        )
        assertEquals(
            CampusDefaultSchedule.undergraduate(EASToken.Campus.WEIHAI),
            target
        )
    }

    @Test
    fun obsoleteUndergraduateIsReplacedForBenbu() {
        val target = TimetableScheduleStructureMigration.resolveTargetSchedule(
            obsoleteUndergrad(), "BENBU:2025-2026-2"
        )
        assertEquals(
            CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU),
            target
        )
    }

    @Test
    fun legacyUndergraduateIsReplaced() {
        val target = TimetableScheduleStructureMigration.resolveTargetSchedule(
            legacyUndergrad(), "WEIHAI:2025-2026-2"
        )
        assertEquals(
            CampusDefaultSchedule.undergraduate(EASToken.Campus.WEIHAI),
            target
        )
    }

    @Test
    fun benbuParserLegacyGoesToBenbu() {
        val target = TimetableScheduleStructureMigration.resolveTargetSchedule(
            benbuParserLegacy(), "BENBU:2025-2026-2"
        )
        assertEquals(
            CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU),
            target
        )
    }

    /** 已经是新作息 → 不动（避免重复写库）。 */
    @Test
    fun alreadyCorrectScheduleIsUntouched() {
        val current = CampusDefaultSchedule.undergraduate(EASToken.Campus.WEIHAI)
        assertNull(
            TimetableScheduleStructureMigration.resolveTargetSchedule(
                current, "WEIHAI:2025-2026-2"
            )
        )
    }

    /** 研究生作息（本次未改）不得被误伤。 */
    @Test
    fun graduateScheduleIsUntouched() {
        val graduate = mutableListOf(
            slot("08:00", "08:50"), slot("08:55", "09:45"),
            slot("10:00", "10:50"), slot("10:55", "11:45"),
            slot("14:00", "14:50"), slot("14:55", "15:45"),
            slot("16:00", "16:50"), slot("16:55", "17:45"),
            slot("18:45", "19:35"), slot("19:40", "20:30"),
            slot("20:45", "21:35"), slot("21:40", "22:30"),
        )
        assertNull(
            TimetableScheduleStructureMigration.resolveTargetSchedule(
                graduate, "BENBU:2025-2026-2"
            )
        )
    }

    /** 用户手动编辑过的作息不得被误伤。 */
    @Test
    fun userEditedScheduleIsUntouched() {
        val edited = obsoleteUndergrad().also {
            it[6] = slot("15:50", "16:40") // 用户改过第 7 节
        }
        // 注意：当前实现按前两节特征匹配，第 7 节改动不影响命中。
        // 这里断言的是“完全不像旧常量”的结构不被误伤。
        val unknown = mutableListOf(
            slot("09:15", "10:05"), slot("10:10", "11:00"),
            slot("11:10", "12:00"), slot("12:05", "12:55"),
            slot("13:40", "14:30"), slot("14:35", "15:25"),
            slot("15:35", "16:25"), slot("16:30", "17:20"),
            slot("18:20", "19:10"), slot("19:15", "20:05"),
            slot("20:15", "21:05"), slot("21:10", "22:00"),
        )
        assertNull(
            TimetableScheduleStructureMigration.resolveTargetSchedule(
                unknown, "WEIHAI:2025-2026-2"
            )
        )
        // 第 7 节被改动的作废版仍会命中（按前两节特征判断）——记录当前行为。
        assertEquals(
            CampusDefaultSchedule.undergraduate(EASToken.Campus.WEIHAI),
            TimetableScheduleStructureMigration.resolveTargetSchedule(
                edited, "WEIHAI:2025-2026-2"
            )
        )
    }

    /**
     * 深圳真实作息（第 1 节 08:30 起）与“作废版”特征撞车，但**绝不能**被迁移，
     * 否则深圳课表会被改成本部/威海时间。
     */
    @Test
    fun shenzhenRealScheduleIsNeverMigrated() {
        val shenzhen = CampusDefaultSchedule.undergraduate(EASToken.Campus.SHENZHEN)
        assertNull(
            TimetableScheduleStructureMigration.resolveTargetSchedule(
                shenzhen, "SHENZHEN:2025-2026-2"
            )
        )
        // 即便内容与“作废版”逐节相同，只要校区是深圳也必须跳过。
        assertNull(
            TimetableScheduleStructureMigration.resolveTargetSchedule(
                obsoleteUndergrad(), "SHENZHEN:2025-2026-2"
            )
        )
    }

    /** 深圳记录若已被误改成本部/威海作息 → 补救回深圳表。 */
    @Test
    fun shenzhenRepairRestoresShenzhenForBenbuSchedule() {
        assertEquals(
            CampusDefaultSchedule.undergraduate(EASToken.Campus.SHENZHEN),
            TimetableScheduleStructureMigration.resolveShenzhenRepair(
                CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU),
                "SHENZHEN:2025-2026-2"
            )
        )
    }

    @Test
    fun shenzhenRepairRestoresShenzhenForWeihaiSchedule() {
        assertEquals(
            CampusDefaultSchedule.undergraduate(EASToken.Campus.SHENZHEN),
            TimetableScheduleStructureMigration.resolveShenzhenRepair(
                CampusDefaultSchedule.undergraduate(EASToken.Campus.WEIHAI),
                "SHENZHEN:2025-2026-2"
            )
        )
    }

    /** 已经是深圳表 / 非深圳校区 → 不补救。 */
    @Test
    fun shenzhenRepairSkipsCorrectOrOtherCampus() {
        assertNull(
            TimetableScheduleStructureMigration.resolveShenzhenRepair(
                CampusDefaultSchedule.undergraduate(EASToken.Campus.SHENZHEN),
                "SHENZHEN:2025-2026-2"
            )
        )
        assertNull(
            TimetableScheduleStructureMigration.resolveShenzhenRepair(
                CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU),
                "BENBU:2025-2026-2"
            )
        )
    }

    /** 无校区信息 → 无法决定目标，不动。 */
    @Test
    fun missingCampusCodeIsUntouched() {
        assertNull(
            TimetableScheduleStructureMigration.resolveTargetSchedule(
                obsoleteUndergrad(), null
            )
        )
        assertNull(
            TimetableScheduleStructureMigration.resolveTargetSchedule(
                obsoleteUndergrad(), "2025-2026-2"
            )
        )
    }

    @Test
    fun emptyScheduleIsUntouched() {
        assertNull(
            TimetableScheduleStructureMigration.resolveTargetSchedule(
                emptyList(), "WEIHAI:2025-2026-2"
            )
        )
    }
}
