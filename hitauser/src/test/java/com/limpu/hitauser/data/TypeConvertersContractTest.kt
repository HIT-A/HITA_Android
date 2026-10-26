package com.limpu.hitauser.data

import com.limpu.hitauser.data.model.UserLocal
import org.junit.Assert.*
import org.junit.Test
import java.sql.Timestamp

class TypeConvertersContractTest {
    @Test
    fun nullableAndMillisecondTimestampsRoundTripAcrossEpoch() {
        assertNull(TypeConverters.timestampToLong(null))
        assertNull(TypeConverters.longToTimestamp(null))
        for (millis in listOf(-1234567890123L, -1L, 0L, 1L, 1234567890123L)) {
            val timestamp = Timestamp(millis)
            assertEquals(millis, TypeConverters.timestampToLong(timestamp))
            assertEquals(timestamp, TypeConverters.longToTimestamp(millis))
        }
    }

    @Test
    fun allPersistedGenderValuesRoundTripWithoutCoercion() {
        for (gender in UserLocal.GENDER.entries) {
            assertEquals(gender.name, TypeConverters.genderToString(gender))
            assertEquals(gender, TypeConverters.stringToGender(TypeConverters.genderToString(gender)))
        }
    }

    @Test
    fun invalidStoredGenderDoesNotSilentlyBecomeAnotherGender() {
        for (stored in listOf("", "male", "UNKNOWN")) {
            assertThrows(IllegalArgumentException::class.java) { TypeConverters.stringToGender(stored) }
        }
    }
}
