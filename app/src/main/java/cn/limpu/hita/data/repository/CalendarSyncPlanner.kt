package cn.limpu.hita.data.repository

/**
 * 「把课表写进系统日历」的计划计算。
 *
 * 只做纯数据决策：哪些课次需要新增、哪些已存在、幂等键长什么样。
 * 真正的 CalendarContract 读写放在调用方，避免这里依赖 Android 框架导致无法单测。
 */
data class CalendarSyncEntry(
    val eventId: String,
    val title: String,
    val location: String?,
    val description: String?,
    val startMillis: Long,
    val endMillis: Long,
    val idempotencyKey: String,
)

sealed class CalendarSyncAction {
    data class Insert(val entry: CalendarSyncEntry) : CalendarSyncAction()
    data class Update(val entry: CalendarSyncEntry, val existingId: Long) : CalendarSyncAction()
    data class Skip(val entry: CalendarSyncEntry) : CalendarSyncAction()
}

data class CalendarSyncPlan(
    val inserts: List<CalendarSyncEntry>,
    val updates: List<Pair<Long, CalendarSyncEntry>>,
    val skipped: List<CalendarSyncEntry>,
) {
    val total: Int get() = inserts.size + updates.size
    val isNoOp: Boolean get() = total == 0
}

object CalendarSyncPlanner {

    /**
     * 幂等键必须能区分「同一课表、同一事件、同一次开课时间」。
     *
     * 三个维度缺一不可：同一天同一门课在相邻节次是两次不同的课；
     * 教务把一次课挪到别的时间后，旧记录必须被判为不同条目，而不是覆盖。
     */
    fun idempotencyKey(timetableId: String, eventId: String, startMillis: Long): String =
        "hita:$timetableId:$eventId:$startMillis"

    /**
     * @param entries 本次要同步的课次
     * @param existingByKey 系统日历里已有的记录，键为幂等键，值为日历事件 id
     */
    fun plan(
        entries: List<CalendarSyncEntry>,
        existingByKey: Map<String, Long>,
    ): CalendarSyncPlan {
        val inserts = mutableListOf<CalendarSyncEntry>()
        val updates = mutableListOf<Pair<Long, CalendarSyncEntry>>()
        val skipped = mutableListOf<CalendarSyncEntry>()
        entries.forEach { entry ->
            if (entry.endMillis <= entry.startMillis) {
                // 时间异常不写日历，交给用户修完后重新同步。
                skipped += entry
                return@forEach
            }
            val existingId = existingByKey[entry.idempotencyKey]
            when {
                existingId == null -> inserts += entry
                // 同一键已存在：内容可能变了（教室/教师），按更新处理，不重复新增。
                else -> updates += existingId to entry
            }
        }
        return CalendarSyncPlan(inserts = inserts, updates = updates, skipped = skipped)
    }
}
