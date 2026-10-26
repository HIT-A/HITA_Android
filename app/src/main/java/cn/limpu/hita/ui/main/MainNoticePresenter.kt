package cn.limpu.hita.ui.main

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.R
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsDimensions
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.notice.AppNoticeCenter
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Platform presentation only. Priority and selection have already been decided by the policy. */
internal class MainNoticePresenter(
    private val activity: Activity,
    private val finished: (MainNoticeSelection) -> Unit,
) : AutoCloseable {
    private var dialog: AlertDialog? = null
    private var closed = false

    fun show(selection: MainNoticeSelection) {
        if (closed || activity.isFinishing || activity.isDestroyed || selection == MainNoticeSelection.None) return
        closeDialog()
        val notices = when (selection) {
            is MainNoticeSelection.Critical -> listOf(selection.notice)
            is MainNoticeSelection.Version -> listOf(selection.notice)
            is MainNoticeSelection.Unseen -> selection.notices
            MainNoticeSelection.None -> return
        }
        val single = notices.singleOrNull()
        val presentation = when (selection) {
            is MainNoticeSelection.Critical -> "fullscreen"
            is MainNoticeSelection.Version -> "version_dialog"
            else -> "open_popup"
        }
        val dimensions = mapOf(
            UsageAnalyticsDimensions.PRESENTATION to presentation,
            UsageAnalyticsDimensions.KIND to (single?.kind ?: "batch"),
        )
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(single?.title ?: activity.getString(R.string.notice_popup_title))
            .setMessage(single?.body ?: notices.joinToString("\n\n") { "${it.title}\n${it.body}" })
        when (selection) {
            is MainNoticeSelection.Critical -> builder
                .setCancelable(false)
                .setPositiveButton(R.string.main_notice_ack) { _, _ ->
                    UsageAnalyticsClient.record(UsageAnalyticsEvent.NOTICE_ACTION_TAPPED, dimensions)
                }
            is MainNoticeSelection.Version -> builder
                .setPositiveButton(R.string.main_notice_update) { _, _ ->
                    UsageAnalyticsClient.record(UsageAnalyticsEvent.NOTICE_ACTION_TAPPED, dimensions)
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.UPDATE_URL))) }
                }
                .setNegativeButton(R.string.main_notice_later, null)
            is MainNoticeSelection.Unseen -> builder
                .setPositiveButton(R.string.notice_popup_ack, null)
            MainNoticeSelection.None -> Unit
        }
        builder.setOnDismissListener {
            if (!closed) {
                if (selection is MainNoticeSelection.Unseen) {
                    AppNoticeCenter.markNoticesSeen(activity.applicationContext, notices.map { it.id })
                    UsageAnalyticsClient.record(UsageAnalyticsEvent.NOTICE_DISMISSED, dimensions)
                }
                finished(selection)
            }
        }
        dialog = builder.show()
        UsageAnalyticsClient.record(UsageAnalyticsEvent.NOTICE_SHOWN, dimensions)
        if (selection !is MainNoticeSelection.Unseen) {
            AppNoticeCenter.markNoticesSeen(activity.applicationContext, notices.map { it.id })
        }
    }

    private fun closeDialog() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
    }

    override fun close() { closed = true; closeDialog() }
}
