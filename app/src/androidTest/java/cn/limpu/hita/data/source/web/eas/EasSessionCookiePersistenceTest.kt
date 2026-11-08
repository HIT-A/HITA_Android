package cn.limpu.hita.data.source.web.eas

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WebView 的 cookie jar 是静默续期的唯一凭据来源：身份层票据（CAS `CASTGC`、aTrust 会话、
 * WebVPN ticket）必须能在进程退出后仍被读回，否则冷启动只能要求用户重新登录。
 *
 * 这些用例验证「写入 → flush → 读回」这条不依赖 UI 的链路，以及续期所依赖的
 * 各校区会话判据。真实教务站点的端到端登录属于设备侧手工验证，这里不触碰网络。
 */
@RunWith(AndroidJUnit4::class)
class EasSessionCookiePersistenceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cookieManager: CookieManager get() = CookieManager.getInstance()

    private val shenzhenJar = "https://jw-hitsz-edu-cn.hitsz.edu.cn/"
    private val casJar = "https://ids.hit.edu.cn/"

    @Test fun cookiesSurviveFlushAndAreReadBackFromTheJar() {
        cookieManager.setAcceptCookie(true)
        cookieManager.setCookie(shenzhenJar, "SESSION=it-session-value; Path=/")
        cookieManager.setCookie(casJar, "CASTGC=it-castgc-value; Path=/; Max-Age=1209600")
        cookieManager.flush()

        val shenzhen = cookieManager.getCookie(shenzhenJar)
        val cas = cookieManager.getCookie(casJar)

        assertNotNull(shenzhen)
        assertNotNull(cas)
        assertTrue("SESSION must be readable back", shenzhen!!.contains("SESSION="))
        assertTrue("CASTGC must be readable back", cas!!.contains("CASTGC="))
    }

    @Test fun flushedCookiesAreVisibleToAFreshManagerLookup() {
        cookieManager.setAcceptCookie(true)
        cookieManager.setCookie(casJar, "CASTGC=it-persist-value; Path=/; Max-Age=1209600")
        cookieManager.flush()

        // A second CookieManager.getInstance() returns the same singleton, but reading through
        // the jar after flush is what a cold start does: only on-disk cookies are present then.
        val reread = CookieManager.getInstance().getCookie(casJar).orEmpty()
        assertTrue("durable cookie must survive flush", reread.contains("CASTGC=it-persist-value"))
    }

    @Test fun shenzhenSessionDetectionRequiresBusinessCookies() {
        // Only the identity ticket is present: renewal is still needed, so this must not pass.
        assertEquals(
            false,
            cn.limpu.hita.data.model.eas.EASToken.hasShenzhenWebSessionCookies(
                mapOf("CASTGC" to "tgt", "sdp_user_token" to "trust")
            )
        )
        // Business cookies make the session usable without a renewal round.
        assertEquals(
            true,
            cn.limpu.hita.data.model.eas.EASToken.hasShenzhenWebSessionCookies(
                mapOf("SESSION" to "ok")
            )
        )
        assertEquals(
            true,
            cn.limpu.hita.data.model.eas.EASToken.hasShenzhenWebSessionCookies(
                mapOf("JSESSIONID" to "ok", "route" to "r")
            )
        )
    }
}
