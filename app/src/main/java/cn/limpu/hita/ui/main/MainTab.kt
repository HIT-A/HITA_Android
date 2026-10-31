package cn.limpu.hita.ui.main

import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.EASToken

enum class MainTab(
    val titleRes: Int,
    val iconRes: Int,
) {
    HOMEWORK(R.string.title_homework, R.drawable.ic_baseline_check_24),
    TIMELINE(R.string.title_timeline, R.drawable.ic_nav_today),
    TIMETABLE(R.string.title_timetable, R.drawable.ic_nav_timetable),
    AGENT(R.string.title_agent, R.drawable.ic_baseline_toys_24),
    BLOG(R.string.title_blog, R.drawable.ic_nav_blog),
    NAVIGATION(R.string.title_navigation, R.drawable.ic_nav_navigation);

    companion object {
        private val infoCampuses = setOf(
            EASToken.Campus.SHENZHEN,
            EASToken.Campus.BENBU,
            EASToken.Campus.WEIHAI,
        )

        /** 资讯 Tab：任一校区登录即可见（本部/威海仅校园通知）。 */
        fun showsInfo(token: EASToken?): Boolean {
            return token != null && token.campus in infoCampuses && token.isLogin()
        }

        /** 博客内容（系列阅读）仅深圳。 */
        fun showsBlog(token: EASToken?): Boolean {
            return showsInfo(token) && token?.campus == EASToken.Campus.SHENZHEN
        }

        fun visibleFor(token: EASToken?): List<MainTab> {
            return if (showsInfo(token)) entries.toList() else entries.filter { it != BLOG }
        }

        fun fromName(raw: String?): MainTab? =
            raw?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
