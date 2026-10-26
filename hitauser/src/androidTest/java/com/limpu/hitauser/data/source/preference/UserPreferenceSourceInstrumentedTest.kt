package com.limpu.hitauser.data.source.preference

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limpu.hitauser.data.model.UserLocal
import com.limpu.hitauser.testing.IsolatedPreferencesContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserPreferenceSourceInstrumentedTest {
    @Test
    fun fullLoginRecordSurvivesSourceRecreation() {
        IsolatedPreferencesContext().use { context ->
            val user = sampleUser()
            UserPreferenceSource(context).saveLocalUser(user)
            assertUser(user, UserPreferenceSource(context).localUser)
            assertTrue(UserPreferenceSource(context).localUser.isValid())
        }
    }

    @Test
    fun profileEditsPreserveCredentialsAndUnchangedFields() {
        IsolatedPreferencesContext().use { context ->
            val expected = sampleUser()
            val source = UserPreferenceSource(context)
            source.saveLocalUser(expected)
            source.saveNickname("new nickname")
            expected.nickname = "new nickname"
            assertUser(expected, UserPreferenceSource(context).localUser)
            source.saveSignature("new signature")
            expected.signature = "new signature"
            assertUser(expected, UserPreferenceSource(context).localUser)
            source.saveAvatar("new avatar")
            expected.avatar = "new avatar"
            assertUser(expected, UserPreferenceSource(context).localUser)
            source.saveGender("FEMALE")
            expected.gender = UserLocal.GENDER.FEMALE
            assertUser(expected, UserPreferenceSource(context).localUser)
        }
    }

    @Test
    fun nullableProfileWritesRemoveValuesAndClearRemovesAuthentication() {
        IsolatedPreferencesContext().use { context ->
            val source = UserPreferenceSource(context)
            val expected = sampleUser()
            source.saveLocalUser(expected)
            source.saveNickname(null)
            source.saveSignature(null)
            source.saveAvatar(null)
            expected.nickname = null
            expected.signature = null
            expected.avatar = null
            assertUser(expected, UserPreferenceSource(context).localUser)
            source.clearLocalUser()
            val cleared = UserPreferenceSource(context).localUser
            assertFalse(cleared.isValid())
            assertUser(UserLocal().apply { gender = UserLocal.GENDER.MALE }, cleared)
        }
    }

    @Test
    fun legacyGenderMappingAndAbsentDefaultRemainDistinct() {
        IsolatedPreferencesContext().use { context ->
            val source = UserPreferenceSource(context)
            assertEquals(UserLocal.GENDER.MALE, source.localUser.gender)
            for (stored in listOf("FEMALE", "OTHER", "unknown")) {
                source.saveGender(stored)
                assertEquals(UserLocal.GENDER.FEMALE, UserPreferenceSource(context).localUser.gender)
            }
            source.saveGender(null)
            assertEquals(UserLocal.GENDER.MALE, source.localUser.gender)
            source.saveLocalUser(sampleUser().apply { gender = null })
            // saveLocalUser persists the string "null"; the old reader maps it to FEMALE.
            assertEquals(UserLocal.GENDER.FEMALE, source.localUser.gender)
        }
    }

    private fun sampleUser() = UserLocal().apply {
        id = "user-17"; username = "student"; nickname = "同学"; signature = "signature"
        gender = UserLocal.GENDER.MALE; avatar = "https://example.invalid/avatar"
        token = "fixture-token"; publicKey = "fixture-key"; studentId = "2026001"; school = "深圳"
    }

    private fun assertUser(expected: UserLocal, actual: UserLocal) {
        assertEquals(expected.id, actual.id)
        assertEquals(expected.username, actual.username)
        assertEquals(expected.nickname, actual.nickname)
        assertEquals(expected.signature, actual.signature)
        assertEquals(expected.gender, actual.gender)
        assertEquals(expected.avatar, actual.avatar)
        assertEquals(expected.token, actual.token)
        assertEquals(expected.publicKey, actual.publicKey)
        assertEquals(expected.studentId, actual.studentId)
        assertEquals(expected.school, actual.school)
    }
}
