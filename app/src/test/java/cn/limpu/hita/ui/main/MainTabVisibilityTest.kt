package cn.limpu.hita.ui.main

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.source.preference.OptionalTabFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainTabVisibilityTest {
    @Test
    fun `logged out default token does not show blog`() {
        assertFalse(MainTab.showsInfo(null))
        assertFalse(MainTab.showsInfo(EASToken()))
        assertFalse(MainTab.showsBlog(null))
        assertFalse(MainTab.BLOG in MainTab.visibleFor(EASToken()))
    }

    @Test
    fun `homework is on by default while info and agent stay hidden`() {
        val loggedIn = EASToken().apply {
            campus = EASToken.Campus.SHENZHEN
            accessToken = "token"
        }
        val defaults = MainTab.visibleFor(loggedIn)
        assertEquals(
            listOf(MainTab.TIMELINE, MainTab.HOMEWORK, MainTab.TIMETABLE, MainTab.NAVIGATION),
            defaults
        )
        assertTrue(MainTab.HOMEWORK in defaults)
        assertFalse(MainTab.BLOG in defaults)
        assertFalse(MainTab.AGENT in defaults)
        assertEquals(defaults, MainTab.visibleFor(loggedIn, OptionalTabFeatures()))

        val enabled = MainTab.visibleFor(
            loggedIn,
            OptionalTabFeatures(homework = true, info = true, agent = true)
        )
        assertEquals(
            listOf(MainTab.TIMELINE, MainTab.HOMEWORK, MainTab.TIMETABLE, MainTab.AGENT, MainTab.BLOG, MainTab.NAVIGATION),
            enabled
        )
        assertEquals(
            listOf(MainTab.TIMELINE, MainTab.TIMETABLE, MainTab.NAVIGATION),
            MainTab.visibleFor(null, OptionalTabFeatures(homework = false))
        )
        assertEquals(
            listOf(MainTab.TIMELINE, MainTab.HOMEWORK, MainTab.TIMETABLE, MainTab.NAVIGATION),
            MainTab.visibleFor(null, OptionalTabFeatures(info = true))
        )
    }

    @Test
    fun `shenzhen session shows blog`() {
        val appApi = EASToken().apply {
            campus = EASToken.Campus.SHENZHEN
            accessToken = "token"
        }
        val web = EASToken().apply {
            campus = EASToken.Campus.SHENZHEN
            webCookies["JSESSIONID"] = "session"
            webCookies["route"] = "route"
        }
        assertTrue(MainTab.showsBlog(appApi))
        assertTrue(MainTab.showsInfo(appApi))
        assertFalse(MainTab.BLOG in MainTab.visibleFor(appApi))
        assertTrue(MainTab.showsBlog(web))
        assertTrue(MainTab.BLOG in MainTab.visibleFor(web, OptionalTabFeatures(info = true)))
    }

    @Test
    fun `saved password without a session does not show blog`() {
        val token = EASToken().apply {
            campus = EASToken.Campus.SHENZHEN
            username = "20240001"
            password = "secret"
        }
        assertFalse(MainTab.showsInfo(token))
    }

    @Test
    fun `benbu and weihai sessions show notice-only info tab`() {
        listOf(EASToken.Campus.BENBU, EASToken.Campus.WEIHAI).forEach { campus ->
            val token = EASToken().apply {
                this.campus = campus
                accessToken = "token"
            }
            assertTrue(MainTab.showsInfo(token))
            assertTrue(MainTab.BLOG in MainTab.visibleFor(token, OptionalTabFeatures(info = true)))
            assertFalse(MainTab.showsBlog(token))
        }
    }

    @Test
    fun `benbu and weihai saved password without session stays hidden`() {
        listOf(EASToken.Campus.BENBU, EASToken.Campus.WEIHAI).forEach { campus ->
            val token = EASToken().apply {
                this.campus = campus
                username = "20240001"
                password = "secret"
            }
            assertFalse(MainTab.showsInfo(token))
            assertFalse(MainTab.BLOG in MainTab.visibleFor(token))
        }
    }
}
