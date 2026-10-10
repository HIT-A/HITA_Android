package cn.limpu.hita.ui.timetable.friend

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ComposeView
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import dagger.hilt.android.AndroidEntryPoint

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
                FriendTimetableScreen(viewModel, onBack = { finish() })
            }
        }
    }
    companion object {
        fun intent(context: Context, openImport: Boolean = false) = Intent(context, FriendTimetableActivity::class.java)
            .putExtra("openImport", openImport)
    }
}
