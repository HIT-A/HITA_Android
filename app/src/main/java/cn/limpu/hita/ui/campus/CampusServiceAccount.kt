package cn.limpu.hita.ui.campus

import android.content.Context
import cn.limpu.hita.data.repository.CampusCardLedgerStore
import java.io.File
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import cn.limpu.hita.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Service-local sign-out. Shared CAS and academic-system sessions are deliberately retained. */
internal enum class CampusServiceAccount(
    private val hosts: List<String>,
    private val paths: List<String>,
    private val cookieName: String? = null,
) {
    CARD(listOf("dxcdp.hitsz.edu.cn"), listOf("/", "/portal", "/portal/", "/portal-api", "/portal-api/")),
    POD(
        listOf("www.utszlib.edu.cn", "www-utszlib-edu-cn-s.hitsz.edu.cn"),
        listOf("/", "/space-reservation-fg", "/space-reservation-fg/", "/space-reservation-fg/x-space-reservation-api", "/space-reservation-fg/x-space-reservation-api/", "/space-reservation-fg/x-space-reservation-api/web/reservations"),
        "pima.space-reservation-fg.sid",
    );

    fun isSignedOut(context: Context): Boolean = preferences(context).getBoolean(name, false)

    fun beginSignIn(context: Context) {
        preferences(context).edit().putBoolean(name, false).apply()
    }

    suspend fun signOut(context: Context) {
        if (this == CARD) CampusCardSyncController.get(context.applicationContext as android.app.Application).cancel()
        // Persist before touching cookies so a retained SSO ticket cannot silently sign the service back in.
        preferences(context).edit().putBoolean(name, true).apply()
        val manager = CookieManager.getInstance()
        for (host in hosts) {
            val origin = "https://$host"
            val names = paths.flatMap { path ->
                CampusCookieJar.header(origin + path).split(';')
                    .map { it.substringBefore('=').trim() }
            }.filter { it.matches(Regex("[A-Za-z0-9_.-]+")) }
                .filter { cookieName == null || it == cookieName }
                .toSet()
            for (name in names) for (path in paths) {
                for (domain in listOf("", "; Domain=$host", "; Domain=.$host")) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        manager.setCookie(origin + path,
                            "$name=; Path=$path; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly$domain") {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
            }
            // Portal tokens may also be kept in browser storage. The library origin is shared,
            // so for POD only its named business cookie is removed.
            if (this == CARD) WebStorage.getInstance().deleteOrigin(origin)
        }
        manager.flush()
        if (this == CARD) CampusCardLedgerStore(File(context.noBackupFilesDir, "campus-card-history")).clear()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences("campus_service_accounts", Context.MODE_PRIVATE)
}

@Composable
internal fun CampusSignOutButton(enabled: Boolean = true, onSignOut: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    TextButton(onClick = { confirming = true }, enabled = enabled) {
        Text(stringResource(R.string.logout))
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text(stringResource(R.string.logout)) },
        text = { Text(stringResource(R.string.campus_service_sign_out_message)) },
        confirmButton = {
            TextButton(onClick = { confirming = false; onSignOut() }) {
                Text(stringResource(R.string.logout))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirming = false }) {
                Text(stringResource(R.string.campus_card_login_cancel))
            }
        },
    )
}
