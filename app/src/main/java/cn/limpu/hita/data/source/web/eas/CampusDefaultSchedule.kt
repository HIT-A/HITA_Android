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
 *   威海 45 分钟版），不在本对象内。
 * - 深圳本科生作息仍保留在 `EASWebSource`，仅在接口失败时作为兜底。
 *
 * 取值来源：本部表与 iOS 端 `AcademicWebVPNResourceProfile.benbu.defaultSchedule`
 * 逐条一致；威海表经教务课表页确认。
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
     * 取指定校区的本科生作息。返回**深拷贝**，调用方可安全修改元素。
     *
     * @param campus 目标校区；深圳返回本部表兜底（深圳真实作息由接口返回）。
     */
    fun undergraduate(campus: EASToken.Campus): MutableList<TimePeriodInDay> =
        when (campus) {
            EASToken.Campus.WEIHAI -> copy(WEIHAI_UNDERGRADUATE)
            EASToken.Campus.BENBU -> copy(BENBU_UNDERGRADUATE)
            EASToken.Campus.SHENZHEN -> copy(BENBU_UNDERGRADUATE)
        }

    /**
     * 无校区上下文时的保守默认（用于旧版 SP 兜底等拿不到校区的场景）。
     *
     * 取威海表：威海是本次作息错误的直接受害方，且本部导入主链路会先拿到接口数据，
     * 仅在校区接口失败时才落到兜底。
     */
    fun fallbackUndergraduate(): MutableList<TimePeriodInDay> =
        copy(WEIHAI_UNDERGRADUATE)

    /**
     * 深拷贝：避免调用方原地修改（如设置界面的编辑作息）污染共享常量。
     */
    private fun copy(source: List<TimePeriodInDay>): MutableList<TimePeriodInDay> =
        source.mapTo(mutableListOf()) { it.clone() }
}
