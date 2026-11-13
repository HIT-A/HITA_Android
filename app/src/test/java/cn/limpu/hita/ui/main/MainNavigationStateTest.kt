package cn.limpu.hita.ui.main

import cn.limpu.hita.data.source.preference.OptionalTabFeatures
import org.junit.Assert.assertEquals
import org.junit.Test

class MainNavigationStateTest {
    private val signedOutTabs = MainTab.visibleFor(null)
    private val signedInTabs = MainTab.visibleFor(loggedIn(), OptionalTabFeatures(homework = true, info = true, agent = true))

    @Test fun restoredNameTakesPriorityOverLegacyPosition() {
        val state = MainNavigationState.restore(signedInTabs, "BLOG", 0)
        assertEquals(MainTab.BLOG, state.selectedTab)
        assertEquals(4, state.selectedIndex)
    }

    @Test fun legacyIndexKeepsTheOriginalPreLoginInterpretation() {
        assertEquals(MainTab.NAVIGATION, MainNavigationState.restore(signedOutTabs, legacySelectedIndex = 3).selectedTab)
        val signedIn = MainNavigationState.restore(signedInTabs, legacySelectedIndex = 3)
        assertEquals(MainTab.NAVIGATION, signedIn.selectedTab)
        assertEquals(5, signedIn.selectedIndex)
        assertEquals(MainTab.TIMELINE, MainNavigationState.restore(signedInTabs, legacySelectedIndex = 4).selectedTab)
    }

    @Test fun unknownOrHiddenSavedNameKeepsLegacyFallback() {
        assertEquals(MainTab.AGENT, MainNavigationState.restore(signedInTabs, "removed_tab", 2).selectedTab)
        assertEquals(MainTab.NAVIGATION, MainNavigationState.restore(signedOutTabs, "BLOG", 3).selectedTab)
        assertEquals(MainTab.TIMELINE, MainNavigationState.restore(signedOutTabs, "BLOG", -1).selectedTab)
    }

    @Test fun appearingBlogPreservesNavigationIdentityInsteadOfItsOldPosition() {
        val state = MainNavigationState.restore(signedOutTabs).selectIndex(2).withVisibleTabs(signedInTabs)
        // 底栏顺序是 今日 → 时间表 → 待办：索引 2 是待办，登录后仍按身份保持选中。
        assertEquals(MainTab.HOMEWORK, state.selectedTab)
        assertEquals(2, state.selectedIndex)
        assertEquals(MainTab.HOMEWORK, state.withVisibleTabs(signedOutTabs).selectedTab)
    }

    @Test fun disappearingSelectedTabReturnsToToday() {
        val state = MainNavigationState.restore(signedInTabs, "BLOG").withVisibleTabs(signedOutTabs)
        assertEquals(MainTab.TIMELINE, state.selectedTab)
        assertEquals(MainTab.TIMELINE, state.withVisibleTabs(signedInTabs).selectedTab)
        assertEquals(state, state.select(MainTab.BLOG))
        assertEquals(state, state.selectIndex(99))
    }

    @Test fun callerListMutationCannotChangeTheCapturedNavigation() {
        val tabs = signedInTabs.toMutableList()
        val state = MainNavigationState.restore(tabs, "NAVIGATION")
        tabs.clear()
        assertEquals(signedInTabs, state.visibleTabs)
        val next = signedOutTabs.toMutableList()
        val updated = state.withVisibleTabs(next)
        next.clear()
        assertEquals(signedOutTabs, updated.visibleTabs)
        assertEquals(3, updated.selectedIndex)
    }

    @Test fun homeworkRemainsSelectedAcrossLoginAndSavedStateRestoration() {
        val withHomework = MainTab.visibleFor(null, OptionalTabFeatures(homework = true))
        val original = MainNavigationState.restore(withHomework, "HOMEWORK")
        assertEquals(MainTab.HOMEWORK, original.selectedTab)
        val loggedIn = original.withVisibleTabs(signedInTabs)
        assertEquals(MainTab.HOMEWORK, loggedIn.selectedTab)
        assertEquals(original, loggedIn.withVisibleTabs(withHomework))
        assertEquals(MainTab.HOMEWORK, MainNavigationState.restore(signedInTabs, loggedIn.selectedTab.name).selectedTab)
    }

    private fun loggedIn() = cn.limpu.hita.data.model.eas.EASToken().apply {
        campus = cn.limpu.hita.data.model.eas.EASToken.Campus.SHENZHEN
        accessToken = "token"
    }
}
