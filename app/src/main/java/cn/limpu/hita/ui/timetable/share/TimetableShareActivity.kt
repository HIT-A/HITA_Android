package cn.limpu.hita.ui.timetable.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.platform.ComposeView
import cn.limpu.hita.R
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TimetableShareActivity : HiltBaseActivity<ComposeViewBinding>() {
    private val viewModel: TimetableShareViewModel by viewModels()
    override fun initViewBinding() = ComposeViewBinding(ComposeView(this))

    override fun initViews() {
        viewModel.initialize(intent.getStringExtra(EXTRA_TIMETABLE_ID).orEmpty(), getString(R.string.timetable_share_default_nickname))
        viewModel.timetables.observe(this) { viewModel.reconcile(it.orEmpty()) }
        (binding.root as ComposeView).setContent {
            HitaComposeTheme {
                val timetables by viewModel.timetables.observeAsState(emptyList())
                val form = viewModel.form
                if (form != null) TimetableShareScreen(
                    form = form, timetables = timetables,
                    summary = viewModel.summary, loading = viewModel.loading, errorResource = viewModel.errorResource,
                    showSummaryRetry = viewModel.summaryErrorResource != null,
                    onRetrySummary = viewModel::retrySummary,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onSelect = { viewModel.edit(id = it) }, onNickname = { viewModel.edit(name = it) },
                    onMode = { viewModel.edit(mode = it) }, onGenerate = viewModel::generate,
                    onDismiss = viewModel::dismissToken,
                    onCopy = { token ->
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText(getString(R.string.timetable_share_action), token))
                        Toast.makeText(this, R.string.timetable_share_copied, Toast.LENGTH_SHORT).show()
                    },
                    onShare = { token ->
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, token)
                        }
                        startActivity(Intent.createChooser(send, getString(R.string.timetable_share_action)))
                    },
                )
            }
        }
    }

    companion object { const val EXTRA_TIMETABLE_ID = "timetableId" }
}
