package com.limpu.hitauser.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limpu.hitauser.data.model.UserLocal
import com.limpu.hitauser.data.model.UserProfile
import com.limpu.hitauser.testing.ObservedValues
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UserDatabaseInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun batchInsertReplaceAndClearDeliverRealRoomUpdatesWithoutCrossUserLoss() {
        val database = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).build()
        try {
            val dao = database.userProfileDao()
            ObservedValues(dao.queryProfile("first")).use { first ->
                ObservedValues(dao.queryProfile("second")).use { second ->
                    assertNull(first.next())
                    assertNull(second.next())
                    val original = sampleProfile("first")
                    val other = sampleProfile("second").apply { gender = null }
                    dao.saveProfiles(listOf(original, other))
                    assertProfile(original, requireNotNull(first.awaitMatching { it != null }))
                    assertProfile(other, requireNotNull(second.awaitMatching { it != null }))
                    val replacement = sampleProfile("first").apply { nickname = "replacement"; gender = UserLocal.GENDER.OTHER }
                    dao.saveProfile(replacement)
                    assertProfile(replacement, requireNotNull(first.awaitMatching { it?.nickname == "replacement" }))
                    ObservedValues(dao.queryProfile("second")).use { reread ->
                        assertProfile(other, requireNotNull(reread.next()))
                    }
                    dao.clearTable()
                    assertNull(first.awaitMatching { it == null })
                    assertNull(second.awaitMatching { it == null })
                }
            }
        } finally { database.close() }
    }

    @Test
    fun profilePersistsAcrossClosingAndReopeningAnActualDiskDatabase() {
        val name = "user-contract-${UUID.randomUUID()}.db"
        var database: UserDatabase? = null
        try {
            val expected = sampleProfile("persistent").apply { gender = UserLocal.GENDER.FEMALE }
            database = Room.databaseBuilder(context, UserDatabase::class.java, name).build()
            database.userProfileDao().saveProfile(expected)
            database.close()
            assertTrue(context.getDatabasePath(name).isFile)
            database = Room.databaseBuilder(context, UserDatabase::class.java, name).build()
            ObservedValues(database.userProfileDao().queryProfile(expected.id)).use {
                assertProfile(expected, requireNotNull(it.next()))
            }
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }

    private fun sampleProfile(identifier: String) = UserProfile().apply {
        id = identifier; username = "student-$identifier"; nickname = "同学"
        gender = UserLocal.GENDER.MALE; signature = "signature"; avatar = "fixture-avatar"
        school = "威海"; studentId = "2026002"; followed = true; fansNum = 7; followingNum = 11
    }

    private fun assertProfile(expected: UserProfile, actual: UserProfile) {
        assertEquals(expected.id, actual.id)
        assertEquals(expected.username, actual.username)
        assertEquals(expected.nickname, actual.nickname)
        assertEquals(expected.gender, actual.gender)
        assertEquals(expected.signature, actual.signature)
        assertEquals(expected.avatar, actual.avatar)
        assertEquals(expected.school, actual.school)
        assertEquals(expected.studentId, actual.studentId)
        assertEquals(expected.followed, actual.followed)
        assertEquals(expected.fansNum, actual.fansNum)
        assertEquals(expected.followingNum, actual.followingNum)
    }
}
