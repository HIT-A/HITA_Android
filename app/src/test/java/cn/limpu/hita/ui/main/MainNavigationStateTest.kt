package cn.limpu.hita.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class MainNavigationStateTest {
    private val signedOutTabs = MainTab.visibleFor(null)
    private val signedInTabs = MainTab.entries.toList()

    @Test fun restoredNameTakesPriorityOverLegacyPosition() {
        val state = MainNavigationState.restore(signedInTabs, "BLOG", 0)
        assertEquals(MainTab.BLOG, state.selectedTab)
        assertEquals(3, state.selectedIndex)
    }

    @Test fun legacyIndexKeepsTheOriginalPreLoginInterpretation() {
        assertEquals(MainTab.NAVIGATION, MainNavigationState.restore(signedOutTabs, legacySelectedIndex = 3).selectedTab)
        val signedIn = MainNavigationState.restore(signedInTabs, legacySelectedIndex = 3)
        assertEquals(MainTab.NAVIGATION, signedIn.selectedTab)
        assertEquals(4, signedIn.selectedIndex)
        assertEquals(MainTab.TIMELINE, MainNavigationState.restore(signedInTabs, legacySelectedIndex = 4).selectedTab)
    }

    @Test fun unknownOrHiddenSavedNameKeepsLegacyFallback() {
        assertEquals(MainTab.AGENT, MainNavigationState.restore(signedInTabs, "removed_tab", 2).selectedTab)
        assertEquals(MainTab.NAVIGATION, MainNavigationState.restore(signedOutTabs, "BLOG", 3).selectedTab)
        assertEquals(MainTab.TIMELINE, MainNavigationState.restore(signedOutTabs, "BLOG", -1).selectedTab)
    }

    @Test fun appearingBlogPreservesNavigationIdentityInsteadOfItsOldPosition() {
        val state = MainNavigationState.restore(signedOutTabs).selectIndex(3).withVisibleTabs(signedInTabs)
        assertEquals(MainTab.NAVIGATION, state.selectedTab)
        assertEquals(4, state.selectedIndex)
        assertEquals(MainTab.NAVIGATION, state.withVisibleTabs(signedOutTabs).selectedTab)
    }

    @Test fun disappearingSelectedTabReturnsToFirstVisibleTab() {
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
}
