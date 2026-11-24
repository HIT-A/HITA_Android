package cn.limpu.hita.ui.widgets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import com.limpu.style.widgets.MWheel3DView
import java.util.Calendar

class PopUpTimePeriodPicker : BottomSheetDialogFragment() {

    private var hour1 = 0
    private var minute1 = 0
    private var hour2 = 0
    private var minute2 = 0
    private var mOnDialogConformListener: OnDialogConformListener? = null

    @StringRes
    private var init_title = 0
    private var init_fT: TimeInDay? = null
    private var init_tT: TimeInDay? = null

    interface OnDialogConformListener {
        fun onClick(timePeriodInDay: TimePeriodInDay)
    }

    fun setDialogTitle(@StringRes title: Int): PopUpTimePeriodPicker {
        init_title = title
        return this
    }

    fun setInitialValue(fT: TimeInDay?, tT: TimeInDay?): PopUpTimePeriodPicker {
        init_fT = fT
        init_tT = tT
        return this
    }

    fun setOnDialogConformListener(m: OnDialogConformListener): PopUpTimePeriodPicker {
        mOnDialogConformListener = m
        return this
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        isCancelable = false
        return ComposeView(requireContext()).apply {
            setContent {
                HitaComposeTheme() {
                    TimePeriodPickerScreen(
                        titleRes = init_title,
                        initFromTime = init_fT,
                        initToTime = init_tT,
                        onCancel = { dismiss() },
                        onConfirm = { from, to ->
                            mOnDialogConformListener?.onClick(TimePeriodInDay(from, to))
                            dismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TimePeriodPickerScreen(
    @StringRes titleRes: Int,
    initFromTime: TimeInDay?,
    initToTime: TimeInDay?,
    onCancel: () -> Unit,
    onConfirm: (TimeInDay, TimeInDay) -> Unit
) {
    val tokens = HitaTheme.tokens
    val now = Calendar.getInstance()
    val initH1 = initFromTime?.hour ?: now.get(Calendar.HOUR_OF_DAY)
    val initM1 = initFromTime?.minute ?: now.get(Calendar.MINUTE)
    val initH2 = initToTime?.hour ?: now.get(Calendar.HOUR_OF_DAY)
    val initM2 = initToTime?.minute ?: now.get(Calendar.MINUTE)

    var h1 by remember { mutableIntStateOf(initH1) }
    var m1 by remember { mutableIntStateOf(initM1) }
    var h2 by remember { mutableIntStateOf(initH2) }
    var m2 by remember { mutableIntStateOf(initM2) }
    val valid = h2 * 60 + m2 > h1 * 60 + m1

    val hourEntries = remember { (0..23).map { if (it < 10) "0$it" else "$it" } }
    val minuteEntries = remember { (0..59).map { if (it < 10) "0$it" else "$it" } }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = tokens.spacing.lg,
                    end = tokens.spacing.lg,
                    top = tokens.spacing.lg,
                    bottom = tokens.spacing.sm
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (titleRes != 0) stringResource(titleRes) else "",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Card(
                onClick = onCancel,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Text(
                    text = stringResource(R.string.cancel),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = tokens.spacing.md, vertical = tokens.spacing.xs)
                )
            }
            Card(
                onClick = {
                    onConfirm(TimeInDay(h1, m1), TimeInDay(h2, m2))
                },
                enabled = valid,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.padding(start = tokens.spacing.xs)
            ) {
                Text(
                    text = stringResource(R.string.confirm),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = tokens.spacing.md, vertical = tokens.spacing.xs)
                )
            }
        }
        if (!valid) {
            Text(
                stringResource(R.string.ade_time_invalid),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = tokens.spacing.lg,
                    end = tokens.spacing.lg,
                    bottom = tokens.spacing.xl
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WheelPicker(
                entries = hourEntries,
                initialIndex = initH1,
                modifier = Modifier.weight(1.2f),
                onItemSelected = { h1 = it }
            )
            Text(
                text = stringResource(R.string.pick_single_time_label2),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = tokens.spacing.xs, end = tokens.spacing.xs)
            )
            WheelPicker(
                entries = minuteEntries,
                initialIndex = initM1,
                modifier = Modifier.weight(1.2f),
                onItemSelected = { m1 = it }
            )
            Text(
                text = stringResource(R.string.pick_single_time_label3),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = tokens.spacing.xs)
            )

            Spacer(modifier = Modifier.weight(0.1f))
            Text(
                text = "-",
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(0.1f))

            WheelPicker(
                entries = hourEntries,
                initialIndex = initH2,
                modifier = Modifier.weight(1.2f),
                onItemSelected = { h2 = it }
            )
            Text(
                text = stringResource(R.string.pick_single_time_label2),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = tokens.spacing.xs, end = tokens.spacing.xs)
            )
            WheelPicker(
                entries = minuteEntries,
                initialIndex = initM2,
                modifier = Modifier.weight(1.2f),
                onItemSelected = { m2 = it }
            )
            Text(
                text = stringResource(R.string.pick_single_time_label3),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = tokens.spacing.xs)
            )
        }
    }
}

@Composable
private fun WheelPicker(
    entries: List<String>,
    initialIndex: Int,
    modifier: Modifier = Modifier,
    onItemSelected: (Int) -> Unit
) {
    AndroidView(
        modifier = modifier.height(160.dp),
        factory = { context ->
            MWheel3DView(context).apply {
                setEntries(entries)
                currentIndex = initialIndex
                setOnWheelChangedListener { _, _, newIndex ->
                    onItemSelected(newIndex)
                }
            }
        }
    )
}
