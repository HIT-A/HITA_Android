package cn.limpu.hita.ui.main

import androidx.appcompat.app.AppCompatActivity
import cn.limpu.hita.utils.ActivityUtils
import com.limpu.hitauser.data.model.CheckUpdateResult
import com.limpu.style.widgets.PopUpUpdate

/** Rebuild field-backed update dialogs with the current host; lifecycle teardown is not dismissal. */
internal class MainUpdatePresenter(
    private val activity: AppCompatActivity,
    private val finished: (CheckUpdateResult) -> Unit,
) : AutoCloseable {
    private var popup: PopUpUpdate? = null
    private var displayed: CheckUpdateResult? = null
    private var closed = false

    fun show(result: CheckUpdateResult) {
        val manager = activity.supportFragmentManager
        if (closed || activity.isFinishing || activity.isDestroyed || manager.isStateSaved) return
        if (displayed === result && popup?.isAdded == true) return
        // PopUpUpdate restores as a Fragment, but its text and action listener are not saved fields.
        (manager.findFragmentByTag("update") as? PopUpUpdate)?.let {
            it.onActionListener = null
            it.dismissNow()
        }
        ActivityUtils.showUpdateNotification(result, activity)
        manager.executePendingTransactions()
        val current = manager.findFragmentByTag("update") as? PopUpUpdate
        if (current == null) {
            // The existing skip/cooldown policy intentionally suppressed this version.
            finished(result)
            return
        }
        val delegate = current.onActionListener
        displayed = result
        popup = current
        current.onActionListener = object : PopUpUpdate.OnActionListener {
            private var handled = false
            private fun complete(action: () -> Unit) {
                if (closed || handled) return
                handled = true
                displayed = null
                finished(result)
                action()
            }
            override fun onConfirm() = complete { delegate?.onConfirm() }
            override fun onCancel() = complete { delegate?.onCancel() }
            override fun onSkip() = complete { delegate?.onSkip() }
        }
    }

    override fun close() {
        closed = true
        popup?.onActionListener = null
        popup = null
        displayed = null
    }
}
