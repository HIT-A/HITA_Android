package cn.limpu.hita.ui.timetable.friend

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ComposeView
import cn.limpu.hita.R
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.main.timetable.compare.TimetableComparisonActivity
import cn.limpu.hita.ui.timetable.export.TimetableExportItem
import cn.limpu.hita.ui.timetable.export.TimetableExportSharer
import cn.limpu.hita.ui.timetable.export.TimetableExportSheet
import cn.limpu.hita.ui.timetable.export.TimetableExportTokensDialog
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FriendTimetableActivity : HiltBaseActivity<ComposeViewBinding>() {
    private val viewModel: FriendTimetableViewModel by viewModels()
    private var importIntentConsumed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        importIntentConsumed = savedInstanceState?.getBoolean("importIntentConsumed") ?: false
        super.onCreate(savedInstanceState)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("importIntentConsumed", importIntentConsumed)
        super.onSaveInstanceState(outState)
    }
    override fun initViewBinding() = ComposeViewBinding(ComposeView(this))
    override fun initViews() {
        if (!importIntentConsumed && intent.getBooleanExtra("openImport", false)) {
            importIntentConsumed = true
            viewModel.showImport()
        }
        (binding.root as ComposeView).setContent {
            HitaComposeTheme {
                LaunchedEffect(viewModel.openShareId) {
                    viewModel.openShareId?.let { id ->
                        viewModel.consumedNavigation()
                        startActivity(FriendTimetableDetailActivity.intent(this@FriendTimetableActivity, id))
                    }
                }
                LaunchedEffect(viewModel.exportedIcs) {
                    viewModel.consumeExportedIcs()?.let { files ->
                        TimetableExportSharer.shareIcsFiles(this@FriendTimetableActivity, files)
                    }
                }
                LaunchedEffect(viewModel.exportFailed) {
                    if (viewModel.exportFailed) {
                        viewModel.dismissExportFailed()
                        Toast.makeText(this@FriendTimetableActivity,
                            R.string.timetable_export_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                FriendTimetableScreen(viewModel, onBack = { finish() }, onCompare = { row ->
                    startActivity(TimetableComparisonActivity.intent(this@FriendTimetableActivity, row.shareId))
                })

                if (viewModel.exportOpen) {
                    TimetableExportSheet(
                        items = viewModel.friends.map {
                            TimetableExportItem(it.shareId,
                                it.remark?.takeIf { remark -> remark.isNotBlank() } ?: it.nickname,
                                it.termName)
                        },
                        initiallySelected = emptySet(),
                        busy = viewModel.exportBusy,
                        onDismiss = viewModel::dismissExport,
                        onExportToken = { items, _ -> viewModel.exportTokens(items.map { it.key }) },
                        onExportIcs = { items -> viewModel.exportIcs(items.map { it.key }) },
                        forwarding = true,
                    )
                }
                viewModel.exportedTokens?.let { tokens ->
                    TimetableExportTokensDialog(
                        tokens = tokens,
                        onCopy = { TimetableExportSharer.copyToken(this@FriendTimetableActivity, it) },
                        onShareAll = {
                            lifecycleScope.launch {
                                try {
                                    TimetableExportSharer.shareTokens(this@FriendTimetableActivity, tokens)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    Toast.makeText(this@FriendTimetableActivity,
                                        R.string.timetable_export_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onDismiss = viewModel::dismissExportedTokens,
                    )
                }
            }
        }
    }
    companion object {
        fun intent(context: Context, openImport: Boolean = false) = Intent(context, FriendTimetableActivity::class.java)
            .putExtra("openImport", openImport)
    }
}
