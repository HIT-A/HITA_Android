package cn.limpu.hita.ui.main

import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.hitauser.data.model.UserLocal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainDrawerMapperTest {
    private val text = MainDrawerText("not signed in", "sign in to continue", "session expired")
    private val local = MainLocalUserSnapshot(false, null, null, "cached-avatar")
    private val eas = MainEasUserSnapshot(true, "Student Name", "20240001", "account", null, null, null, null)

    @Test fun validLocalProfileWinsOverAcademicProfileAndExpiredFlag() {
        val state = MainDrawerMapper.map(local.copy(valid = true, username = "local", nickname = "nickname"), eas, true, text)
        assertEquals(DrawerUserState("local", "nickname", "cached-avatar", true), state)
        val emptyLocal = MainDrawerMapper.map(local.copy(valid = true), eas, false, text)
        assertEquals("", emptyLocal.title)
        assertEquals("", emptyLocal.subtitle)
    }

    @Test fun academicSubtitleTrimsFieldsAndKeepsTheirOrderAndCachedAvatar() {
        val profile = eas.copy(studentId = " 20240001 ", school = " School ", major = " Major ", grade = " 2024 ", className = " Class 1 ")
        val state = MainDrawerMapper.map(local, profile, true, text)
        assertEquals("Student Name", state.title)
        assertEquals("20240001\nSchool · Major · 2024 · Class 1\nsession expired", state.subtitle)
        assertEquals("cached-avatar", state.avatar)
        assertFalse(state.loggedInLocalUser)
    }

    @Test fun academicTitleFallbackPreservesBlankAndUntrimmedLegacyValues() {
        assertEquals("20240001", MainDrawerMapper.map(local, eas.copy(name = "  "), false, text).title)
        assertEquals("account", MainDrawerMapper.map(local, eas.copy(name = null, studentId = ""), false, text).title)
        assertEquals("  Name  ", MainDrawerMapper.map(local, eas.copy(name = "  Name  "), false, text).title)
        assertEquals("", MainDrawerMapper.map(local, eas.copy(name = null, studentId = null, username = ""), false, text).title)
        assertEquals(text.notLoggedInTitle, MainDrawerMapper.map(local, eas.copy(name = null, studentId = null, username = null), false, text).title)
    }

    @Test fun missingStudentIdDoesNotAddExtraLineBeforeOtherProfileFields() {
        val state = MainDrawerMapper.map(local, eas.copy(studentId = " ", school = "", major = " Major ", grade = null, className = " "), false, text)
        assertEquals("Major", state.subtitle)
        assertEquals("account", MainDrawerMapper.map(local, eas.copy(studentId = null), false, text).subtitle)
        assertEquals("\nsession expired", MainDrawerMapper.map(local, eas.copy(studentId = null, username = null), true, text).subtitle)
    }

    @Test fun loggedOutUserUsesExplicitDefaultsAndDropsStaleAvatar() {
        val state = MainDrawerMapper.map(local, eas.copy(loggedIn = false), true, text)
        assertEquals(text.notLoggedInTitle, state.title)
        assertEquals(text.notLoggedInSubtitle, state.subtitle)
        assertNull(state.avatar)
        assertFalse(state.loggedInLocalUser)
    }

    @Test fun snapshotsKeepLoginAndProfileValuesWhenMutableModelsChange() {
        val user = UserLocal().apply { publicKey = "public"; token = "session"; username = "local"; nickname = "nick"; avatar = "avatar" }
        val token = EASToken().apply { webCookies["SESSION"] = "session"; name = "academic"; stuId = "20240001" }
        val localSnapshot = MainLocalUserSnapshot.from(user)
        val easSnapshot = MainEasUserSnapshot.from(token)
        user.token = null; user.username = "changed"; user.avatar = null
        token.webCookies.clear(); token.name = "changed"
        assertTrue(localSnapshot.valid)
        assertTrue(easSnapshot.loggedIn)
        assertEquals("local", localSnapshot.username)
        assertEquals("academic", easSnapshot.name)
        assertEquals("avatar", MainDrawerMapper.map(localSnapshot, easSnapshot, false, text).avatar)
    }
}
