package cn.limpu.hita.ui.campus

import android.content.Context
import android.webkit.CookieManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.source.web.campus.SilentPodSessionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/** Use a test emulator without real portal accounts: these tests modify the service's business session. */
@RunWith(AndroidJUnit4::class)
class CampusSessionCookiesInstrumentedTest {
    @Test
    fun pathScopedSessionIsReadableAndSignOutRetainsUnrelatedCookie() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val origin = SilentPodSessionPolicy.DIRECT_ORIGIN
            val session = SilentPodSessionPolicy.SESSION_COOKIE
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            try {
                setCookie(origin, "$session=hita-test; Path=/space-reservation-fg; Secure; HttpOnly")
                setCookie(origin, "hita_unrelated=keep; Path=/; Secure; HttpOnly")
                assertFalse(CampusCookieJar.header(origin).contains("$session="))
                assertNotNull(readSilentPodSession())

                CampusServiceAccount.POD.signOut(context)
                assertNull(SilentPodSessionPolicy.sessionCookie(
                    CampusCookieJar.header(SilentPodSessionPolicy.API_BASE_URL + "/web/reservations")))
                assertTrue(CampusCookieJar.header(origin).contains("hita_unrelated=keep"))
                assertTrue(CampusServiceAccount.POD.isSignedOut(context))
                CampusServiceAccount.POD.beginSignIn(context)
                assertFalse(CampusServiceAccount.POD.isSignedOut(context))
            } finally {
                setCookie(origin, "$session=; Path=/space-reservation-fg; Max-Age=0; Secure; HttpOnly")
                setCookie(origin, "hita_unrelated=; Path=/; Max-Age=0; Secure; HttpOnly")
                CampusServiceAccount.POD.beginSignIn(context)
            }
        }
    }

    private suspend fun setCookie(url: String, value: String) = suspendCancellableCoroutine<Unit> { continuation ->
        CookieManager.getInstance().setCookie(url, value) {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
}
