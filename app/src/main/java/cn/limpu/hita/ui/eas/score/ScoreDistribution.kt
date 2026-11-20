package cn.limpu.hita.ui.eas.score

import cn.limpu.hita.data.model.eas.CourseScoreItem

data class ScoreBucket(val label: String, val count: Int)

data class ScoreDistribution(
    val buckets: List<ScoreBucket>,
    val scoredCount: Int,
    val excludedCount: Int,
) {
    val isRenderable: Boolean get() = scoredCount > 0
}

object ScoreDistributionCalculator {
    private val labels = listOf("90-100", "80-89", "70-79", "<70")

    fun calculate(items: List<CourseScoreItem>): ScoreDistribution {
        val counts = IntArray(labels.size)
        var excluded = 0
        items.forEach { item ->
            val score = percentScore(item)
            if (score == null) {
                excluded += 1
            } else {
                counts[band(score)] += 1
            }
        }
        return ScoreDistribution(
            buckets = labels.mapIndexed { index, label -> ScoreBucket(label, counts[index]) },
            scoredCount = counts.sum(),
            excludedCount = excluded,
        )
    }

    /**
     * A percentage score is a numeric value in 0..100.
     * Letter grades and pass/fail text stay excluded even when a parser stored a mapped integer.
     */
    private fun percentScore(item: CourseScoreItem): Double? {
        // 显式给出的数值就是最终答案：越界说明数据本身不可用，不能回退到默认的 0 分，
        // 否则脏数据会被当成「不及格」计入分布，统计结果失真。
        item.numericScore?.let { numeric ->
            return numeric.takeIf { it.isFinite() && it in 0.0..100.0 }
        }
        val text = item.finalScoresText?.trim().orEmpty()
        if (text.isNotEmpty()) {
            return text.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
        }
        // 两者都没有时才用解析器写入的整数分。
        return item.finalScores.takeIf { it in 0..100 }?.toDouble()
    }

    private fun band(score: Double): Int = when {
        score >= 90.0 -> 0
        score >= 80.0 -> 1
        score >= 70.0 -> 2
        else -> 3
    }
}
