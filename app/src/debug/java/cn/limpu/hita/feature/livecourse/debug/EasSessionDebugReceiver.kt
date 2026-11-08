package cn.limpu.hita.feature.livecourse.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import cn.limpu.hita.utils.LogUtils

/**
 * Debug-only ADB hook that reports which session cookies the shared WebView jar still holds.
 *
 * Silent session renewal depends on identity cookies (CAS `CASTGC` ≈14 days, aTrust session
 * tokens, WebVPN tickets) surviving process death, so verifying the fix needs a way to read the
 * jar back. WebView exposes no expiry API, hence this dumps cookie names only — never values —
 * and is excluded from release APKs.
 */
class EasSessionDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DUMP_EAS_SESSION) return
        val jar = CookieManager.getInstance()
        // Force the in-memory jar to disk first so the dump reflects what survives a fresh start.
        jar.flush()

        probeUrls.forEach { url ->
            val names = jar.getCookie(url)
                ?.split(';')
                ?.map { it.substringBefore('=').trim() }
                ?.filter { it.isNotEmpty() }
                ?.sorted()
                .orEmpty()
            LogUtils.success("eas-cookie-jar url=$url names=${names.joinToString(",")}")
        }
        LogUtils.success("eas-cookie-jar dump complete probes=${probeUrls.size}")
    }

    companion object {
        const val ACTION_DUMP_EAS_SESSION = "cn.limpu.hita.action.DUMP_EAS_SESSION"

        /** Mirrors WebViewLoginActivity.CampusUrls, which is private to the login activity. */
        private val probeUrls = listOf(
            // Benbu zero-trust gateway (application domain moved to vpn.hit.edu.cn in 2026-10).
            "http://jwts-hit-edu-cn.vpn.hit.edu.cn:1080/loginCAS",
            "http://i-hit-edu-cn.vpn.hit.edu.cn:1080/portal/home/",
            // Weihai WebVPN ticket + EAS session.
            "https://webvpn.hitwh.edu.cn/",
            "https://webvpn.hitwh.edu.cn/http/77726476706e69737468656265737421fae0558f693861446900c7a99c406d3667/loginCAS",
            // Shenzhen aTrust proxy and direct jw host.
            "https://jw-hitsz-edu-cn.hitsz.edu.cn/authentication/main",
            "https://jw.hitsz.edu.cn/authentication/main",
            // Shared CAS of the unified identity platform.
            "https://ids.hit.edu.cn/",
        )
    }
}
