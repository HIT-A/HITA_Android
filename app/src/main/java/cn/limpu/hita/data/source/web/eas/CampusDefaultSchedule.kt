package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay

/**
 * 三校区**本科生**默认作息（单一数据源）。
 *
 * 目的：消除同一校区默认作息散落在多个文件、取值互相冲突的问题。
 * 之前 `WeihaiEASWebSource` / `BenbuEASWebSource` / `BenbuScheduleParser` /
 * `TimetablePreferenceSource` 各写一份且值不一致，导致导入页显示与实际合并逻辑对不上。
 *
 * 注意：
 * - 本部/威海**研究生**作息仍走各自原有路径（本部 yjsgl 下午 14:00 版、
 *   威海无研究生作息），不在本对象内。
 * - 三校区本科生作息**各不相同**，深圳不得再复用本部/威海表。
 *
 * 取值来源：本部表与 iOS 端 `AcademicWebVPNResourceProfile.benbu.defaultSchedule`
 * 逐条一致；威海表经教务课表页确认；深圳表与改版前的深圳作息一致
 * （第 1 节 08:30 起、第 5 节 14:00 起）。
 */
object CampusDefaultSchedule {

    /** 本部本科生（12 小节）。第 5 节 13:45 起，与课表页「第5,6节 13:45~15:30」一致。 */
    private val BENBU_UNDERGRADUATE = listOf(
        TimePeriodInDay(TimeInDay(8, 0), TimeInDay(8, 50)),
        TimePeriodInDay(TimeInDay(8, 55), TimeInDay(9, 45)),
        TimePeriodInDay(TimeInDay(10, 0), TimeInDay(10, 50)),
        TimePeriodInDay(TimeInDay(10, 55), TimeInDay(11, 45)),
        TimePeriodInDay(TimeInDay(13, 45), TimeInDay(14, 35)),
        TimePeriodInDay(TimeInDay(14, 40), TimeInDay(15, 30)),
        TimePeriodInDay(TimeInDay(15, 45), TimeInDay(16, 35)),
        TimePeriodInDay(TimeInDay(16, 40), TimeInDay(17, 30)),
        TimePeriodInDay(TimeInDay(18, 30), TimeInDay(19, 20)),
        TimePeriodInDay(TimeInDay(19, 25), TimeInDay(20, 15)),
        TimePeriodInDay(TimeInDay(20, 30), TimeInDay(21, 20)),
        TimePeriodInDay(TimeInDay(21, 25), TimeInDay(22, 15)),
    )

    /** 威海本科生（12 小节）。老教务（jwts）只有本科生，无研究生作息。 */
    private val WEIHAI_UNDERGRADUATE = listOf(
        TimePeriodInDay(TimeInDay(8, 0), TimeInDay(8, 50)),
        TimePeriodInDay(TimeInDay(8, 55), TimeInDay(9, 45)),
        TimePeriodInDay(TimeInDay(10, 5), TimeInDay(10, 55)),
        TimePeriodInDay(TimeInDay(11, 0), TimeInDay(11, 50)),
        TimePeriodInDay(TimeInDay(14, 0), TimeInDay(14, 50)),
        TimePeriodInDay(TimeInDay(14, 55), TimeInDay(15, 45)),
        TimePeriodInDay(TimeInDay(16, 5), TimeInDay(16, 55)),
        TimePeriodInDay(TimeInDay(17, 0), TimeInDay(17, 50)),
        TimePeriodInDay(TimeInDay(18, 40), TimeInDay(19, 30)),
        TimePeriodInDay(TimeInDay(19, 35), TimeInDay(20, 25)),
        TimePeriodInDay(TimeInDay(20, 45), TimeInDay(21, 35)),
        TimePeriodInDay(TimeInDay(21, 40), TimeInDay(22, 30)),
    )

    /**
     * 深圳本科生（12 小节）。第 1 节 08:30 起、第 5 节 14:00 起。
     *
     * ⚠️ 该表与本部/威海**并非**同一套取值，历史上曾被误判为“作废作息”而被覆盖，
     * 导致深圳用户的课表被改成其它校区时间。请勿再把它并入其它校区。
     */
    private val SHENZHEN_UNDERGRADUATE = listOf(
        TimePeriodInDay(TimeInDay(8, 30), TimeInDay(9, 20)),
        TimePeriodInDay(TimeInDay(9, 25), TimeInDay(10, 15)),
        TimePeriodInDay(TimeInDay(10, 30), TimeInDay(11, 20)),
        TimePeriodInDay(TimeInDay(11, 25), TimeInDay(12, 15)),
        TimePeriodInDay(TimeInDay(14, 0), TimeInDay(14, 50)),
        TimePeriodInDay(TimeInDay(14, 55), TimeInDay(15, 45)),
        TimePeriodInDay(TimeInDay(16, 0), TimeInDay(16, 50)),
        TimePeriodInDay(TimeInDay(16, 55), TimeInDay(17, 45)),
        TimePeriodInDay(TimeInDay(18, 45), TimeInDay(19, 35)),
        TimePeriodInDay(TimeInDay(19, 40), TimeInDay(20, 30)),
        TimePeriodInDay(TimeInDay(20, 45), TimeInDay(21, 35)),
        TimePeriodInDay(TimeInDay(21, 40), TimeInDay(22, 30)),
    )

    /**
     * 取指定校区的本科生作息。返回**深拷贝**，调用方可安全修改元素。
     *
     * @param campus 目标校区。三个校区各返回各自的真实作息。
     */
    fun undergraduate(campus: EASToken.Campus): MutableList<TimePeriodInDay> =
        when (campus) {
            EASToken.Campus.WEIHAI -> copy(WEIHAI_UNDERGRADUATE)
            EASToken.Campus.BENBU -> copy(BENBU_UNDERGRADUATE)
            EASToken.Campus.SHENZHEN -> copy(SHENZHEN_UNDERGRADUATE)
        }

    /**
     * 无明确校区但已知当前上下文时的兜底：直接返回该校区本科生作息。
     *
     * 不再复用深圳表：三校区作息各自独立，深圳表不得被当作全局共享常量。
     */
    fun fallbackUndergraduate(campus: EASToken.Campus): MutableList<TimePeriodInDay> =
        undergraduate(campus)

    /**
     * 深拷贝：避免调用方原地修改（如设置界面的编辑作息）污染共享常量。
     */
    private fun copy(source: List<TimePeriodInDay>): MutableList<TimePeriodInDay> =
        source.mapTo(mutableListOf()) { it.clone() }
}
