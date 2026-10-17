package cn.limpu.hita.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperCreditsTest {
    @Test
    fun allNamesFitOnOneLineWithSeparators() {
        assertEquals(
            listOf(0..5),
            partitionDeveloperNames(listOf(26, 26, 39, 26, 26, 90), separatorWidth = 12, maxWidth = 320)
        )
    }

    @Test
    fun narrowWidthMovesWholeNamesToTheNextLine() {
        assertEquals(
            listOf(0..2, 3..4, 5..5),
            partitionDeveloperNames(listOf(26, 26, 39, 26, 26, 90), separatorWidth = 12, maxWidth = 120)
        )
    }

    @Test
    fun separatorWidthIsIncludedAtTheExactBoundary() {
        assertEquals(
            listOf(0..1, 2..2),
            partitionDeveloperNames(listOf(20, 20, 20), separatorWidth = 10, maxWidth = 50)
        )
        assertEquals(
            listOf(0..0, 1..1, 2..2),
            partitionDeveloperNames(listOf(20, 20, 20), separatorWidth = 10, maxWidth = 49)
        )
    }

    @Test
    fun nextLineDoesNotReserveALeadingSeparator() {
        assertEquals(
            listOf(0..0, 1..2),
            partitionDeveloperNames(listOf(60, 60, 10), separatorWidth = 10, maxWidth = 80)
        )
    }

    @Test
    fun largerMeasuredFontsWrapWithoutDroppingOrReorderingNames() {
        assertEquals(
            listOf(0..1, 2..3, 4..4),
            partitionDeveloperNames(listOf(52, 52, 78, 52, 52), separatorWidth = 24, maxWidth = 196)
        )
    }

    @Test
    fun anOversizedNameStillOccupiesItsOwnLine() {
        assertEquals(
            listOf(0..0, 1..1),
            partitionDeveloperNames(listOf(150, 20), separatorWidth = 10, maxWidth = 100)
        )
    }

    @Test
    fun emptyCreditsHaveNoRows() {
        assertEquals(
            emptyList<IntRange>(),
            partitionDeveloperNames(emptyList(), separatorWidth = 12, maxWidth = 196)
        )
    }
}
