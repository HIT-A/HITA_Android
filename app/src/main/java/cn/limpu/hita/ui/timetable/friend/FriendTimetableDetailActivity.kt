package cn.limpu.hita.ui.timetable.friend

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.ui.platform.ComposeView
import cn.limpu.hita.R
import cn.limpu.hita.ui.base.ComposeViewBinding
import cn.limpu.hita.ui.base.HiltBaseActivity
import cn.limpu.hita.ui.design.HitaComposeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FriendTimetableDetailActivity : HiltBaseActivity<ComposeViewBinding>() {
    private val viewModel: FriendTimetableDetailViewModel by viewModels()
    override fun initViewBinding() = ComposeViewBinding(ComposeView(this))
    override fun initViews() {
        viewModel.friend.observe(this) { row ->
            viewModel.accept(row)
            if (row == null) {
                Toast.makeText(this, R.string.friend_timetable_deleted, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        (binding.root as ComposeView).setContent {
            HitaComposeTheme {
                FriendTimetableDetailScreen(viewModel.state, onBack = { finish() })
            }
        }
    }
    companion object {
        const val EXTRA_SHARE_ID = "shareId"
        fun intent(context: Context, shareId: String) = Intent(context, FriendTimetableDetailActivity::class.java)
            .putExtra(EXTRA_SHARE_ID, shareId)
    }
}
