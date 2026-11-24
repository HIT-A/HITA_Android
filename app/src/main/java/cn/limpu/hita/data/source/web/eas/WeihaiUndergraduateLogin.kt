package cn.limpu.hita.data.source.web.eas

import com.google.gson.Gson
import com.google.gson.JsonParseException
import org.jsoup.Connection
import org.jsoup.Jsoup

/** HTTP protocol for the legacy Weihai undergraduate system (jwts), not yjsgl. */
internal object WeihaiUndergraduateLogin {
    const val TIMETABLE_PATH = "/kbcx/queryGrkb"
    private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15"
    private val exchangeCookieNames = setOf("JSESSIONID", "HIT", "TWFID")
    private val gson = Gson()

    fun exchangeVpnCookies(cookies: Map<String, String>): Map<String, String> {
        val endpoint = WeihaiEasEntryPolicy.VPN_LOGIN + "wengine-vpn/cookie"
        val response = request(endpoint, cookies, 10_000)
            .header("Accept", "*/*")
            .data("method", "get")
            .data("host", "jwts.hitwh.edu.cn")
            .data("scheme", "http")
            .data("path", "/loginCAS")
            .data("vpn_timestamp", System.currentTimeMillis().toString())
            .execute()
        return if (response.statusCode() == 200) parseExchangedCookies(response.body()) else emptyMap()
    }

    internal fun parseExchangedCookies(body: String): Map<String, String> {
        val raw = body.trim()
        val header = if (raw.startsWith('"')) {
            try {
                gson.fromJson(raw, String::class.java).orEmpty()
            } catch (_: JsonParseException) {
                return emptyMap()
            }
        } else raw
        return buildMap {
            for (part in header.split(';')) {
                val separator = part.indexOf('=')
                if (separator < 1) continue
                val name = part.substring(0, separator).trim().uppercase()
                val value = part.substring(separator + 1).trim()
                if (name in exchangeCookieNames && value.isNotEmpty()) put(name, value)
            }
        }
    }

    fun mergeExchangedCookies(cookies: MutableMap<String, String>, exchanged: Map<String, String>) {
        for ((name, value) in exchanged) {
            cookies.keys.removeAll { it.equals(name, ignoreCase = true) }
            cookies[name] = value
        }
    }

    fun requestTimetable(base: String, cookies: Map<String, String>, timeout: Int): Connection.Response =
        request(base + TIMETABLE_PATH, cookies, timeout)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .execute()

    fun isVerifiedTimetable(response: Connection.Response): Boolean {
        if (response.statusCode() != 200) return false
        val doc = response.parse()
        if (doc.selectFirst("input[type=password]") != null) return false
        return doc.select("select[name=xnxq] option").any { option ->
            WeihaiTermPolicy.parseTermValue(option.attr("value"), option.text()) != null
        }
    }

    private fun request(url: String, cookies: Map<String, String>, timeout: Int): Connection =
        Jsoup.connect(url)
            .cookies(cookies)
            .userAgent(USER_AGENT)
            .timeout(timeout)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .followRedirects(false)
}
