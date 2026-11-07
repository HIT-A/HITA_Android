package cn.limpu.hita.ui.main

import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.EASToken

enum class MainTab(
    val titleRes: Int,
    val iconRes: Int,
) {
    TIMELINE(R.string.title_timeline, R.drawable.ic_nav_today),
    HOMEWORK(R.string.title_homework, R.drawable.ic_baseline_check_24),
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

        /**
         * 今日、时间表、更多始终显示。
         * 待办、资讯、助手默认关闭，只在更多-设置-更多功能里分别开启后出现。
         * 资讯还要求已登录。
         */
        fun visibleFor(
            token: EASToken?,
            features: OptionalTabFeatures = OptionalTabFeatures(),
        ): List<MainTab> {
            return entries.filter { tab ->
                when (tab) {
                    HOMEWORK -> features.homework
                    AGENT -> features.agent
                    BLOG -> features.info && showsInfo(token)
                    else -> true
                }
            }
        }

        fun fromName(raw: String?): MainTab? =
            raw?.let { name -> entries.firstOrNull { it.name == name } }
    }
}

/** 待办、资讯、助手三个底栏模块的独立开关，缺省全部关闭。 */
data class OptionalTabFeatures(
    val homework: Boolean = false,
    val info: Boolean = false,
    val agent: Boolean = false,
)
