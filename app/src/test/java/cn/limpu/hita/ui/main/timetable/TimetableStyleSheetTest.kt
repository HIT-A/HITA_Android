package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.TimetableStyleSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableStyleSheetTest {
    @Test
    fun copy_createsIndependentStyleSnapshot() {
        val original = TimetableStyleSheet()
        val updated = original.copy(
            startTime = 945,
            drawBGLine = false,
            isFadeEnabled = false,
            usePeriodLabel = true,
        )

        assertNotSame(original, updated)
        assertEquals(830, original.startTime)
        assertTrue(original.drawBGLine)
        assertTrue(original.isFadeEnabled)
        assertFalse(original.usePeriodLabel)
        assertEquals(945, updated.startTime)
        assertFalse(updated.drawBGLine)
        assertFalse(updated.isFadeEnabled)
        assertTrue(updated.usePeriodLabel)
    }

    @Test
    fun withCardOpacity_clampsWithoutMutatingOriginal() {
        val original = TimetableStyleSheet()

        assertEquals(95, original.cardOpacity)
        assertEquals(20, original.withCardOpacity(-10).cardOpacity)
        assertEquals(68, original.withCardOpacity(68).cardOpacity)
        assertEquals(100, original.withCardOpacity(120).cardOpacity)
        assertEquals(95, original.cardOpacity)
    }

    @Test
    fun startTimeObject_followsSnapshotStartTime() {
        val style = TimetableStyleSheet(startTime = 945)

        assertEquals(9, style.getStartTimeObject().hour)
        assertEquals(45, style.getStartTimeObject().minute)
    }
}
