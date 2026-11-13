package cn.limpu.hita.ui.main

/** Selection follows a tab identity when login changes the visible positions. */
data class MainNavigationState private constructor(
    val visibleTabs: List<MainTab>,
    val selectedTab: MainTab,
) {
    val selectedIndex: Int get() = visibleTabs.indexOf(selectedTab)

    fun select(tab: MainTab): MainNavigationState =
        if (tab in visibleTabs) copy(selectedTab = tab) else this

    fun selectIndex(index: Int): MainNavigationState =
        visibleTabs.getOrNull(index)?.let(::select) ?: this

    fun withVisibleTabs(next: List<MainTab>): MainNavigationState {
        require(next.isNotEmpty())
        val snapshot = next.toList()
        return MainNavigationState(snapshot, selectedTab.takeIf { it in snapshot } ?: snapshot.firstOrNull { it == MainTab.TIMELINE } ?: snapshot.first())
    }

    companion object {
        fun restore(
            visibleTabs: List<MainTab>,
            restoredTabName: String? = null,
            legacySelectedIndex: Int = 0,
        ): MainNavigationState {
            require(visibleTabs.isNotEmpty())
            val snapshot = visibleTabs.toList()
            val named = MainTab.fromName(restoredTabName)?.takeIf { it in snapshot }
            // MainActivity read the old index before applying the authenticated tab list.
            // 这是历史版本保存的「四页签名」，必须保持当日顺序，与当前底栏顺序无关：
            // 底栏顺序变化（如待办与时间表对调）后，旧安装包的索引仍要解回原来的页面。
            val legacy = listOf(MainTab.TIMELINE, MainTab.TIMETABLE, MainTab.AGENT, MainTab.NAVIGATION)
                .getOrNull(legacySelectedIndex)?.takeIf { it in snapshot }
            return MainNavigationState(snapshot, named ?: legacy ?: snapshot.firstOrNull { it == MainTab.TIMELINE } ?: snapshot.first())
        }
    }
}
