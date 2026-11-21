package cn.limpu.hita.ui.design

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatCachedAtTest {

    @Test
    fun formatsLocalDateAndMinute() {
        val millis = LocalDateTime.of(2026, 11, 20, 10, 32)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        assertEquals("11-20 10:32", formatCachedAt(millis))
    }
}
