package cn.limpu.hita.feature.timetableshare.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class FriendImportPolicyTest {
    @Test fun classifiesNewDuplicateAndUpdate() {
        assertEquals(ImportKind.NEW, FriendImportPolicy.classify("a", null))
        assertEquals(ImportKind.SAME, FriendImportPolicy.classify("a", "a"))
        assertEquals(ImportKind.UPDATE, FriendImportPolicy.classify("b", "a"))
    }
}
