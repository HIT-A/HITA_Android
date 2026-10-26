package com.limpu.hitauser.data

import org.junit.Assert.assertNull
import org.junit.Test

class TypeConvertersNullabilityTest {
    @Test
    fun nullableProfileGenderRemainsNullOnBothDatabaseBoundaries() {
        assertNull(TypeConverters.genderToString(null))
        assertNull(TypeConverters.stringToGender(null))
        assertNull(TypeConverters.stringToGender(TypeConverters.genderToString(null)))
    }
}
