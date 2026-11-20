package cn.limpu.hita.ui.main.navigation

import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.EASToken

/**
 * 「扩展」栏可用的校园服务。
 *
 * 这些入口都依赖深圳校区的独立门户会话（dxcdp / utszlib / lab.hitsz.edu.cn），
 * 因此只在深圳校区且已登录时出现。判定收敛到 [available]，`!isNotEmpty()` 时不渲染整栏。
 */
enum class CampusExtension(
    val titleRes: Int,
    val subtitleRes: Int,
    val iconRes: Int,
) {
    CAMPUS_CARD(
        titleRes = R.string.extension_campus_card_title,
        subtitleRes = R.string.extension_campus_card_subtitle,
        iconRes = R.drawable.ic_bx_credit,
    ),
    SILENT_POD(
        titleRes = R.string.extension_silent_pod_title,
        subtitleRes = R.string.extension_silent_pod_subtitle,
        iconRes = R.drawable.ic_baseline_access_time_24,
    ),
    LIBRARY(
        titleRes = R.string.extension_library_title,
        subtitleRes = R.string.extension_library_subtitle,
        iconRes = R.drawable.ic_baseline_search_24,
    ),
    LAB_CENTER(
        titleRes = R.string.extension_lab_center_title,
        subtitleRes = R.string.extension_lab_center_subtitle,
        iconRes = R.drawable.ic_baseline_done_24,
    ),
    ;

    companion object {
        /**
         * 只有深圳校区的已登录账号才有可用扩展。
         *
         * 未登录时 [EASToken.campus] 仍默认深圳，因此必须同时判断登录态；
         * 只看校区会把未登录用户当成本部之外的深圳用户。
         */
        fun available(campus: EASToken.Campus?, loggedIn: Boolean): List<CampusExtension> {
            if (!loggedIn) return emptyList()
            if (campus != EASToken.Campus.SHENZHEN) return emptyList()
            return entries
        }
    }
}
