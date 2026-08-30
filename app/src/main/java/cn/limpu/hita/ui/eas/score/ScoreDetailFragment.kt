package cn.limpu.hita.ui.eas.score

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.utils.formatCredits
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme

class ScoreDetailFragment : BottomSheetDialogFragment() {

    private val score: CourseScoreItem by lazy {
        Gson().fromJson(
            requireArguments().getString(ARG_SCORE).orEmpty(),
            CourseScoreItem::class.java
        )
    }

    companion object {
        private const val ARG_SCORE = "score"

        fun newInstance(score: CourseScoreItem): ScoreDetailFragment =
            ScoreDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SCORE, Gson().toJson(score))
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.limpu.style.R.style.TransparentBottomSheetDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                HitaComposeTheme() {
                    ScoreDetailSheet(score = score)
                }
            }
        }
    }
}

@Composable
private fun ScoreDetailSheet(score: CourseScoreItem) {
    val tokens = HitaTheme.tokens
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(tokens.spacing.xl)
        ) {
            Text(
                text = score.courseName ?: "-",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(tokens.spacing.xl))
            DetailRow(
                leftLabel = stringResource(R.string.score_credit),
                leftValue = formatCredits(score.credits),
                rightLabel = stringResource(R.string.score_hours),
                rightValue = score.hours.toString()
            )
            DetailRow(
                leftLabel = stringResource(R.string.score_code),
                leftValue = score.courseCode.orEmpty(),
                rightLabel = stringResource(R.string.score_property),
                rightValue = score.courseProperty.orEmpty()
            )
            DetailRow(
                leftLabel = stringResource(R.string.score_category),
                leftValue = score.courseCategory.orEmpty(),
                rightLabel = stringResource(R.string.score_school_name),
                rightValue = score.schoolName.orEmpty()
            )
            DetailRow(
                leftLabel = stringResource(R.string.score_assess_method),
                leftValue = score.assessMethod.orEmpty(),
                rightLabel = "",
                rightValue = ""
            )
            Spacer(modifier = Modifier.height(46.dp))
        }
    }
}

@Composable
private fun DetailRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String
) {
    val tokens = HitaTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = tokens.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.xl)
    ) {
        DetailColumn(
            label = leftLabel,
            value = leftValue,
            modifier = Modifier.weight(1f)
        )
        DetailColumn(
            label = rightLabel,
            value = rightValue,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DetailColumn(
    label: String,
    value: String,
    modifier: Modifier
) {
    Row(modifier = modifier) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp
        )
        Text(
            text = if (label.isBlank()) "" else value.ifBlank { "-" },
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
