package cn.limpu.hita.data.repository

import android.app.Application
import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.data.source.web.eas.AcademicCalendarPeriod
import cn.limpu.hita.data.source.web.eas.PublicAcademicCalendarParser
import com.google.gson.Gson
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Official calendars are cached separately by campus; a missing source never borrows another campus. */
@Singleton
class AcademicCalendarRepository @Inject constructor(application: Application) {
    private val prefs by lazy { application.getSharedPreferences("academic_calendar_v1", Context.MODE_PRIVATE) }
    private val gson = Gson()
    private val state = MutableLiveData<List<AcademicCalendarPeriod>>(emptyList())
    val periods: LiveData<List<AcademicCalendarPeriod>> = state
    private val refreshing = AtomicBoolean(false)
    private val refreshPolicy = AcademicCalendarRefreshPolicy()

    private fun readCache(): List<AcademicCalendarPeriod> = runCatching {
        gson.fromJson(prefs.getString("periods", "[]"), Array<AcademicCalendarPeriod>::class.java)
            .filter { it.valid() }
    }.getOrDefault(emptyList())

    /** Called on IO; duplicate refreshes return immediately and never wait behind network IO. */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        try {
            var cached = readCache()
            state.postValue(cached)
            for (campus in listOf("BENBU", "SHENZHEN", "WEIHAI")) {
                val now = System.currentTimeMillis()
                if (!refreshPolicy.begin(campus, now, cached.any { it.campus == campus }, prefs.getLong("updated_$campus", 0L))) continue
                val parsed = when (campus) {
                    "BENBU" -> fetchBenbu()
                    "SHENZHEN" -> fetchShenzhen()
                    else -> fetchWeihai()
                }.filter { it.valid() && it.campus == campus }.distinct()
                if (parsed.isEmpty()) continue
                // Retain other campuses and historical periods when this source fails or has no update.
                val retained = cached.filter { old -> parsed.none {
                    it.campus == old.campus && it.vacation == old.vacation &&
                        it.startDay <= old.endDay && old.startDay <= it.endDay
                } }
                cached = (retained + parsed).sortedBy { it.startDay }.takeLast(40)
                prefs.edit().putString("periods", gson.toJson(cached)).putLong("updated_$campus", now).apply()
                state.postValue(cached)
            }
        } finally {
            refreshing.set(false)
        }
    }

    private fun fetchBenbu(): List<AcademicCalendarPeriod> = runCatching {
        val index = Jsoup.connect("https://en.hit.edu.cn/Links/list.htm").timeout(10_000).maxBodySize(1_000_000).get()
        val links = index.select("a[href]").filter { it.text().contains("Academic Year", true) }
            .map { it.absUrl("href") }.distinct().filter { url ->
                runCatching { val u = URI(url); u.scheme == "https" && u.host == "en.hit.edu.cn" && u.path.endsWith("/page.htm") }.getOrDefault(false)
            }.take(8)
        links.flatMap { url ->
            runCatching {
                PublicAcademicCalendarParser.parse(Jsoup.connect(url).timeout(10_000).maxBodySize(1_000_000).get().outerHtml())
            }.getOrDefault(emptyList())
        }.distinct()
    }.getOrDefault(emptyList())

    private fun fetchShenzhen(): List<AcademicCalendarPeriod> = runCatching {
        val home = Jsoup.connect("https://www.hitsz.edu.cn/").timeout(10_000).maxBodySize(1_000_000).get()
        val url = home.select("a[href]").firstOrNull { it.text().trim() == "校历周历" }?.absUrl("href")
        if (url != null && URI(url).host == "www.hitsz.edu.cn" && URI(url).scheme == "https") {
            PublicAcademicCalendarParser.parseShenzhen(Jsoup.connect(url).timeout(10_000).maxBodySize(1_000_000).get().outerHtml())
        } else emptyList()
    }.getOrDefault(emptyList())

    private fun fetchWeihai(): List<AcademicCalendarPeriod> = runCatching {
        val list = Jsoup.connect("https://jwc.hitwh.edu.cn/xlxz/list.htm").timeout(10_000).maxBodySize(1_000_000).get()
        list.select("a[href]").filter { it.text().contains("学期校历") }.map { it.absUrl("href") }.distinct().take(3).flatMap { url ->
            runCatching attachment@{
                if (URI(url).host != "jwc.hitwh.edu.cn" || URI(url).scheme != "https") return@attachment emptyList<AcademicCalendarPeriod>()
                val page = Jsoup.connect(url).timeout(10_000).maxBodySize(1_000_000).get()
                val pdf = page.selectFirst("[pdfsrc]")?.absUrl("pdfsrc") ?: return@attachment emptyList<AcademicCalendarPeriod>()
                if (URI(pdf).host != "jwc.hitwh.edu.cn" || URI(pdf).scheme != "https") return@attachment emptyList<AcademicCalendarPeriod>()
                val bytes = Jsoup.connect(pdf).ignoreContentType(true).timeout(10_000).maxBodySize(2_000_000).execute().bodyAsBytes()
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes).use { document ->
                    if (document.numberOfPages > 4) emptyList() else PublicAcademicCalendarParser.parseWeihaiText(com.tom_roush.pdfbox.text.PDFTextStripper().getText(document))
                }
            }.getOrDefault(emptyList())
        }
    }.getOrDefault(emptyList())
}
