package cn.limpu.hita.data.repository

/**
 * 课表快照的备注与保留策略。
 *
 * 只有纯数据变换：剪裁与备注都在这里决定，[TimetableSnapshotStore] 只负责读写文件。
 * 这样可以在 JVM 上直接验证保留规则，不必构造 Android 的 `Application` 与临时文件。
 */
object SnapshotRetention {

    /** 与 [TimetableSnapshotStore] 的私有上限保持一致，避免两处规则漂移。 */
    const val DEFAULT_LIMIT = 12

    /**
     * 保留条数上限。
     *
     * 快照是整份课表的 JSON 副本，越大越占磁盘；40 条约等于一个学期的周数，
     * 再往上用户也很难在列表里翻找，因此作为硬上限而不是仅做 UI 提示。
     */
    const val MAX_LIMIT = 40

    /** 备注统一去首尾空白；空白串等价于没有备注。 */
    fun normalizedNote(raw: String): String = raw.trim()

    /**
     * 只保留 `ownerKey + termId` 这组里最新的 [limit] 条，其它组的快照原样返回。
     *
     * @return 保留下来的快照，顺序与入参一致
     */
    fun apply(
        snapshots: List<TimetableVersionSnapshot>,
        ownerKey: String,
        termId: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<TimetableVersionSnapshot> {
        require(limit > 0) { "保留条数必须为正" }
        val target = snapshots.filter { it.ownerKey == ownerKey && it.termId == termId }
        if (target.size <= limit) return snapshots
        val keep = target.sortedByDescending { it.createdAtMillis }
            .take(limit)
            .mapTo(hashSetOf()) { it.id }
        return snapshots.filterNot {
            it.ownerKey == ownerKey && it.termId == termId && it.id !in keep
        }
    }

    /** 被 [apply] 丢弃的条数，用于给用户回执。 */
    fun removedCount(
        snapshots: List<TimetableVersionSnapshot>,
        ownerKey: String,
        termId: String,
        limit: Int = DEFAULT_LIMIT,
    ): Int = snapshots.size - apply(snapshots, ownerKey, termId, limit).size
}
