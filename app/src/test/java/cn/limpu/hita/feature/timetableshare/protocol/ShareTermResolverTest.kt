package cn.limpu.hita.feature.timetableshare.protocol

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ShareTermResolverTest {
    private fun date(value: String) = LocalDate.parse(value).atStartOfDay(ShareLimits.ZONE).toInstant().toEpochMilli()
    @Test fun campusAndLegacyCodesNormalizeToTheSameIdentity() {
        val expected = ShareTermIdentity("2026-2027-1", "2026-2027学年秋季")
        listOf("2026-20271", "2026-2027-1", "BENBU:2026-20271", "WEIHAI:2026-20271", "SHENZHEN:2026-20271").forEach {
            assertEquals(expected, ShareTermResolver.resolve(it, date("2026-09-01")))
        }
    }
    @Test fun springAndSummerAreRecognized() {
        assertEquals("2026-2027学年春季", ShareTermResolver.resolve("2026-20272", date("2027-02-01")).displayName)
        assertEquals("2026-2027学年夏季", ShareTermResolver.resolve("2026-20273", date("2027-07-01")).displayName)
    }
    @Test fun invalidCodesFallBackToShanghaiSeason() {
        listOf(null, "secret", "2026-20281", "2026-20270", "X:2026-20271").forEach {
            assertEquals(ShareTermIdentity("2026-autumn", "2026年秋季"), ShareTermResolver.resolve(it, date("2026-08-01")))
        }
        assertEquals(ShareTermIdentity("2026-spring", "2026年春季"), ShareTermResolver.resolve(null, date("2026-07-31")))
    }
    @Test fun sameSeasonAdjustmentsKeepKeyAndCrossSeasonChangesKey() {
        assertEquals(ShareTermResolver.resolve(null, date("2026-08-01")).key, ShareTermResolver.resolve(null, date("2026-10-01")).key)
        assertNotEquals(ShareTermResolver.resolve(null, date("2026-07-31")).key, ShareTermResolver.resolve(null, date("2026-08-01")).key)
        assertEquals(ShareTermResolver.resolve("2026-20271", date("2026-08-01")).key, ShareTermResolver.resolve("2026-20271", date("2026-10-01")).key)
    }
}
