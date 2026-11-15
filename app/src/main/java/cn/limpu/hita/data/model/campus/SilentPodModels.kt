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
 * 不做时间段解析：当前页面只做展示，不需要落到分钟级模型。
 */
data class SilentPodSpace(
    val id: Int,
    val codeName: String,
    val areaFullName: String,
    val spaceTypeName: String?,
    val category: SilentPodCategory,
    val capacity: Int,
    val isAccessControl: Boolean,
    val allowDurations: List<String>,
    val disabledDurations: List<String>,
    val minDurationMinutes: Int?,
    val maxDurationMinutes: Int?,
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
