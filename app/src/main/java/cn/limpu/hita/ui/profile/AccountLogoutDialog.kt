package cn.limpu.hita.ui.profile

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.source.preference.IdentityVisibilityStore
import cn.limpu.hita.ui.main.MainSession
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.limpu.hitauser.data.model.UserLocal
import com.limpu.hitauser.data.repository.LocalUserRepository

/** The session shown in the confirmation, not whichever account happens to be current later. */
internal sealed class AccountLogoutTarget(@StringRes val actionLabel: Int, val accountName: String?) {
    abstract fun signOut(context: Context, localUsers: LocalUserRepository, eas: EASRepository?): Boolean

    private class Hita(
        name: String?,
        private val id: String?,
        private val username: String?,
        private val token: String,
    ) : AccountLogoutTarget(R.string.account_logout_hita, name) {
        override fun signOut(context: Context, localUsers: LocalUserRepository, eas: EASRepository?): Boolean {
            val current = localUsers.getLoggedInUser()
            if (!current.isValid() || current.id != id || current.username != username || current.token != token) return false
            localUsers.logout(context)
            return true
        }
    }

    private class Academic(name: String?, private val session: MainSession) :
        AccountLogoutTarget(R.string.account_logout_academic, name) {
        override fun signOut(context: Context, localUsers: LocalUserRepository, eas: EASRepository?): Boolean {
            if (eas == null || MainSession.from(eas.getEasToken()) != session) return false
            eas.logout()
            return true
        }
    }

    companion object {
        fun hita(user: UserLocal): AccountLogoutTarget? = if (user.isValid()) {
            Hita(user.nickname?.takeIf(String::isNotBlank) ?: user.username, user.id, user.username, requireNotNull(user.token))
        } else null

        fun academic(token: EASToken): AccountLogoutTarget? = if (token.isLogin()) {
            Academic(token.name?.takeIf(String::isNotBlank) ?: token.username, MainSession.from(token))
        } else null
    }
}

internal object AccountLogoutDialog {
    fun show(context: Context, target: AccountLogoutTarget, confirm: () -> Boolean): AlertDialog {
        val account = target.accountName?.takeUnless { IdentityVisibilityStore(context).isHidden() }
        val message = if (account.isNullOrBlank()) context.getString(R.string.account_logout_keep_local)
            else context.getString(R.string.account_logout_named, account, context.getString(R.string.account_logout_keep_local))
        var handled = false
        return MaterialAlertDialogBuilder(context)
            .setTitle(target.actionLabel)
            .setMessage(message)
            .setNegativeButton(R.string.button_cancel, null)
            .setPositiveButton(target.actionLabel) { _, _ ->
                if (!handled) {
                    handled = true
                    if (!confirm()) Toast.makeText(context, R.string.account_logout_changed, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }
}
