package cn.limpu.hita.data.repository

import android.content.Context
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.source.dao.TimetableDao
import cn.limpu.hita.data.source.web.eas.CampusDefaultSchedule
import cn.limpu.hita.utils.LogUtils

/**
 * 一次性迁移：修正**已导入**课表中残留的作废旧作息结构。
 *
 * 背景：新课表作息此前硬编码在各 WebSource，且取的是已作废的 `08:30–09:20` 版本。
 * 导入时会把它写入 `timetable.scheduleStructure`，因此只改常量无法让老用户自愈，
 * 必须主动回填一次。
 *
 * 设计原则（只替换“确定是旧常量”的记录）：
 * - 只有整份结构与某个已知旧特征**逐节匹配**时才替换；
 * - 未命中一律不动，避免误伤用户手动编辑过的作息；
 * - 研究生作息不在迁移范围（其常量本次未改动）；
 * - **深圳校区一律不迁移**：深圳真实作息第 1 节即 08:30 起，与“作废版”特征撞车，
 *   若纳入会被误改成本部/威海时间（这正是上一轮回归的成因）。
 *
 * 另有 `resolveShenzhenRepair` 补救：上一轮错误迁移已把部分深圳用户作息改成本部/威海版本，
 * 这里按校区特征把它们改回深圳表。
 */
object TimetableScheduleStructureMigration {

    private const val SP_NAME = "timetable_structure_migration"
    private const val KEY_DONE = "obsolete_structure_v1_done"
    private const val KEY_SHENZHEN_REPAIR_DONE = "shenzhen_repair_v1_done"

    /**
     * 已作废的**威海**本科生作息特征：第 1 节 08:30–09:20、第 2 节 09:25–10:15，
     * 且第 5 节 14:00 起。
     *
     * ⚠️ 该前两节特征与**深圳**真实作息完全相同，只是深圳已改为自己的表，
     * 不能再把深圳记录按此特征“迁移”。故迁移入口对深圳校区直接跳过。
     */
    private fun isObsoleteUndergraduate(schedule: List<TimePeriodInDay>): Boolean {
        if (schedule.size < 12) return false
        val first = schedule[0]
        val second = schedule[1]
        return first.from.hour == 8 && first.from.minute == 30 &&
            first.to.hour == 9 && first.to.minute == 20 &&
            second.from.hour == 9 && second.from.minute == 25 &&
            second.to.hour == 10 && second.to.minute == 15
    }

    /** 更早的遗留本科生作息：第 2 节 09:30 起、第 5 节 13:45 起。 */
    private fun isLegacyUndergraduate(schedule: List<TimePeriodInDay>): Boolean {
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
     * `BenbuScheduleParser` 曾自带的旧值：第 3 节 10:05–10:55、第 5 节 13:30–14:20。
     * 该值只可能出现在本部。
     */
    private fun isBenbuParserLegacy(schedule: List<TimePeriodInDay>): Boolean {
        if (schedule.size < 12) return false
        val third = schedule[2]
        val fifth = schedule[4]
        return third.from.hour == 10 && third.from.minute == 5 &&
            third.to.hour == 10 && third.to.minute == 55 &&
            fifth.from.hour == 13 && fifth.from.minute == 30 &&
            fifth.to.hour == 14 && fifth.to.minute == 20
    }

    /** 从 `code`（形如 `BENBU:2025-2026-2`）推断校区；无法判断时返回 null。 */
    private fun campusOf(code: String?): EASToken.Campus? {
        val raw = code?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val prefix = raw.substringBefore(':', "").uppercase()
        return EASToken.Campus.entries.firstOrNull { it.name == prefix }
    }

    /**
     * 纯函数：根据当前作息与课表 code，计算应当替换成的新作息。
     *
     * 返回 null 表示“不要动这条记录”。抽出来是为了能在 JVM 单测里直接验证，
     * 无需 Room / Android 环境。
     */
    internal fun resolveTargetSchedule(
        current: List<TimePeriodInDay>,
        code: String?
    ): List<TimePeriodInDay>? {
        if (current.isEmpty()) return null
        val campus = campusOf(code)
        // 深圳有其独立的真实作息（第 1 节 08:30 起），与“作废版”特征撞车，
        // 绝不能纳入迁移，否则深圳课表会被改成本部/威海时间。
        if (campus == EASToken.Campus.SHENZHEN) return null
        val target = when {
            // 作废本科版，本部/威海通用。
            isObsoleteUndergraduate(current) -> campus?.let { CampusDefaultSchedule.undergraduate(it) }
            // 更早的遗留本科版。
            isLegacyUndergraduate(current) -> campus?.let { CampusDefaultSchedule.undergraduate(it) }
            // 本部 Parser 旧值，必定属于本部。
            isBenbuParserLegacy(current) -> CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU)
            else -> null
        }
        if (target.isNullOrEmpty()) return null
        if (target == current) return null
        return target
    }

    /**
     * 补救上一轮误迁移：把**深圳**记录中已是本部/威海作息的课表改回深圳表。
     *
     * 命中条件：整份结构与本部或威海的本科表逐节一致（即已被误改）。
     * 返回 null 表示无需修改。
     */
    internal fun resolveShenzhenRepair(
        current: List<TimePeriodInDay>,
        code: String?
    ): List<TimePeriodInDay>? {
        if (current.isEmpty()) return null
        if (campusOf(code) != EASToken.Campus.SHENZHEN) return null
        val shenzhen = CampusDefaultSchedule.undergraduate(EASToken.Campus.SHENZHEN)
        if (current == shenzhen) return null
        val looksLikeOtherCampus =
            current == CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU) ||
                current == CampusDefaultSchedule.undergraduate(EASToken.Campus.WEIHAI)
        return if (looksLikeOtherCampus) shenzhen else null
    }

    /**
     * 执行迁移。幂等：完成后写入标记，后续调用直接跳过。
     *
     * 注意：本方法做磁盘 IO，必须在后台线程调用。
     */
    fun run(context: Context, timetableDao: TimetableDao) {
        val preference = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val migrationDone = preference.getBoolean(KEY_DONE, false)
        val repairDone = preference.getBoolean(KEY_SHENZHEN_REPAIR_DONE, false)
        if (migrationDone && repairDone) return

        val timetables = try {
            timetableDao.getTimetablesSync()
        } catch (e: Exception) {
            LogUtils.e("TimetableScheduleStructureMigration: failed to read timetables", e)
            return
        }

        var migrated = 0
        var repaired = 0
        for (timetable in timetables) {
            val current = timetable.scheduleStructure
            // 补救优先：先看是否需要把深圳记录改回深圳表。
            var target: List<TimePeriodInDay>? = null
            if (!repairDone) {
                target = resolveShenzhenRepair(current, timetable.code)
            }
            if (target == null && !migrationDone) {
                target = resolveTargetSchedule(current, timetable.code)
            }
            if (target == null) continue

            try {
                timetable.scheduleStructure = target.toMutableList()
                timetableDao.saveTimetableSync(timetable)
                if (campusOf(timetable.code) == EASToken.Campus.SHENZHEN) repaired++ else migrated++
                LogUtils.d(
                    "TimetableScheduleStructureMigration: migrated timetable id=${timetable.id} " +
                        "code=${timetable.code}"
                )
            } catch (e: Exception) {
                LogUtils.e(
                    "TimetableScheduleStructureMigration: failed for id=${timetable.id}",
                    e
                )
            }
        }

        // 即使本轮没有命中，也打标记：避免每次启动都全表扫描。
        preference.edit()
            .putBoolean(KEY_DONE, true)
            .putBoolean(KEY_SHENZHEN_REPAIR_DONE, true)
            .apply()
        LogUtils.d(
            "TimetableScheduleStructureMigration: done, migrated=$migrated " +
                "repaired=$repaired total=${timetables.size}"
        )
    }
}
