package cn.limpu.hita.ui.eas.score

import cn.limpu.hita.data.model.eas.CourseScoreItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreDistributionTest {

    private fun item(
        numeric: Double? = null,
        text: String? = null,
        finalScores: Int = 0,
        name: String = "课程",
    ) = CourseScoreItem().apply {
        this.courseName = name
        this.numericScore = numeric
        this.finalScoresText = text
        this.finalScores = finalScores
    }

    @Test
    fun `numeric scores fall into the documented bands`() {
        val distribution = ScoreDistributionCalculator.calculate(
            listOf(
                item(numeric = 95.0),
                item(numeric = 90.0),
                item(numeric = 85.0),
                item(numeric = 79.0),
                item(numeric = 60.0),
            ),
        )

        assertEquals(listOf("90-100", "80-89", "70-79", "<70"), distribution.buckets.map { it.label })
        assertEquals(listOf(2, 1, 1, 1), distribution.buckets.map { it.count })
        assertEquals(5, distribution.scoredCount)
        assertEquals(0, distribution.excludedCount)
        assertTrue(distribution.isRenderable)
    }

    @Test
    fun `text scores are parsed when they are plain percentages`() {
        val distribution = ScoreDistributionCalculator.calculate(listOf(item(text = "88.5")))

        assertEquals(1, distribution.buckets[1].count)
        assertEquals(1, distribution.scoredCount)
    }

    @Test
    fun `letter grades are excluded rather than mapped into a band`() {
        val distribution = ScoreDistributionCalculator.calculate(
            listOf(item(text = "优秀"), item(text = "合格"), item(numeric = 91.0)),
        )

        assertEquals(1, distribution.scoredCount)
        assertEquals(2, distribution.excludedCount)
        assertEquals(1, distribution.buckets[0].count)
    }

    @Test
    fun `out of range numbers are excluded`() {
        val distribution = ScoreDistributionCalculator.calculate(
            listOf(item(numeric = 120.0), item(numeric = -1.0), item(text = "150")),
        )

        assertEquals(0, distribution.scoredCount)
        assertEquals(3, distribution.excludedCount)
        assertFalse(distribution.isRenderable)
    }

    @Test
    fun `empty input is not renderable`() {
        val distribution = ScoreDistributionCalculator.calculate(emptyList())

        assertFalse(distribution.isRenderable)
        assertEquals(4, distribution.buckets.size)
    }
}
