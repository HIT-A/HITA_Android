package cn.limpu.hita.data.model.campus

/**
 * 静音仓分类。
 *
 * 服务端只用 `spaceTypeId` 区分三类空间，展示名与人数/时长规则各不相同；
 * 声明顺序即页面分组顺序（有门禁的 4 人仓在前，单人仓最后）。
 */
enum class SilentPodCategory(val spaceTypeId: Int) {
    QUAD_LOCKED(40),
    QUAD(42),
    SINGLE(41),
    ;

    companion object {
        fun fromSpaceTypeId(id: Int): SilentPodCategory? = entries.firstOrNull { it.spaceTypeId == id }
    }
}

/**
 * 单个静音仓空间及其当日可约时间轴。
 *
 * `allowDurations` / `disabledDurations` 保存服务端原始 `HH:mm-HH:mm` 字符串，
 * 由 [SilentPodSlotPlanner] 换算成可提交的起止时间。
 *
 * `isTeam` 决定预约时是否需要同行成员：多人仓（`TEAM`）服务端要求
 * `subUserContactsIds` 至少 1 个，否则返回 `TEAM_PEOPLE_NUM_NO_EQUAL`。
 */
data class SilentPodSpace(
    val id: Int,
    val codeName: String,
    val areaFullName: String,
    val spaceTypeName: String?,
    val category: SilentPodCategory,
    val capacity: Int,
    val teamCapacity: Int,
    val isTeam: Boolean,
    val isAccessControl: Boolean,
    val allowDurations: List<String>,
    val disabledDurations: List<String>,
    val minDurationMinutes: Int?,
    val maxDurationMinutes: Int?,
) {
    /**
     * 多人仓必须选的同行成员数量（不含本人）。
     *
     * 与 iOS 保持一致：4 人仓 `capacity=4`、`teamCapacity=2` → 需选 1–3 名同行。
     * 单人仓返回 `null`，表示不需要选人。
     */
    val requiredGuestRange: IntRange?
        get() {
            if (!isTeam) return null
            val minGuests = maxOf(1, teamCapacity - 1)
            val maxGuests = maxOf(minGuests, capacity - 1)
            return minGuests..maxGuests
        }
}

/** 已保存的同行成员（多人静音仓预约时需要）。 */
data class SilentPodContact(
    val id: Int,
    val name: String,
    val cardNo: String,
)

/** 读者核验结果：添加同行成员前先用证号查姓名，确认没输错人。 */
data class SilentPodReader(
    val name: String,
    val cardNo: String,
)

/**
 * 同行成员列表。
 *
 * `defaultMobile` 来自服务端 `other.mobile`，用于预填手机号，省得每次重输。
 */
data class SilentPodContacts(
    val contacts: List<SilentPodContact>,
    val defaultMobile: String?,
)

/** 一类静音仓及其下辖空间，用于页面分组展示。 */
data class SilentPodGroup(
    val category: SilentPodCategory,
    val spaces: List<SilentPodSpace>,
)

/**
 * 个人预约记录。
 *
 * 页面只读展示；写操作（创建/取消）已在 [cn.limpu.hita.data.source.web.campus.SilentPodClient] 就绪，
 * 但尚未接入本页 UI，见 docs/audit/2026-11-15-parity-recheck.md 的遗留项。
 */
data class SilentPodReservation(
    val id: Int,
    val spaceName: String,
    val areaName: String,
    val spaceTypeName: String?,
    val date: String,
    val startTime: String,
    val endTime: String,
    val typeText: String?,
    val status: String,
    val statusText: String,
    val cancelReason: String?,
    val isMain: Boolean,
    val isNeedSign: Boolean,
    val isAllowCancel: Boolean,
)

/** 一次成功加载的完整快照：所选日期的空间分组 + 我的预约。 */
data class SilentPodContent(
    val date: String,
    val groups: List<SilentPodGroup>,
    val reservations: List<SilentPodReservation>,
)
