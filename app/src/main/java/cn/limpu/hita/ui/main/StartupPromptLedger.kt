package cn.limpu.hita.ui.main

import cn.limpu.hita.data.notice.AppNotice

/**
 * 前台提示的进程内账本。
 *
 * 两类记录语义不同，不能合并：
 * - 关键公告是排队项：宿主重建不表示用户已处理，只有实际关掉才 acknowledge。
 * - 版本提示按「目标版本号」去重：版本公告与更新对话框指向同一版本时只能出现一个。
 */
internal class StartupPromptLedger {
    private val acknowledged = mutableSetOf<String>()
    private val shownVersions = mutableSetOf<Long>()

    fun nextCritical(notices: List<AppNotice>, seen: Set<String>, now: Long): AppNotice? =
        notices.firstOrNull { it.isCritical && it.id !in seen && it.id !in acknowledged && activeAt(it, now) }

    fun acknowledge(notice: AppNotice) {
        acknowledged.add(notice.id)
    }

    fun activeAt(notice: AppNotice, now: Long): Boolean =
        (notice.startsAt == null || notice.startsAt <= now) &&
            (notice.endsAt == null || notice.endsAt >= now)

    /** 返回 true 仅当这个目标版本第一次被占用。 */
    fun claimVersionPrompt(versionCode: Long): Boolean = shownVersions.add(versionCode)
}
