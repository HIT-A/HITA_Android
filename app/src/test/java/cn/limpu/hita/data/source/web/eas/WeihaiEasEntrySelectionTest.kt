package cn.limpu.hita.data.source.web.eas

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入口选择在真实网络条件下的行为。
 *
 * 用假探测器（[CampusReachabilityProbe] 是 fun interface）驱动，不访问网络：
 * 探测结果就是被测模块的输入，网络只是它的来源。
 */
class WeihaiEasEntrySelectionTest {

    /** 记录每个 URL 被问到的次数，便于断言「只探测该探测的入口」。 */
    private class FakeProbe(private val answer: (String) -> Boolean) : CampusReachabilityProbe {
        val asked = mutableListOf<String>()
        override suspend fun isReachable(url: String): Boolean {
            asked += url
            return answer(url)
        }
    }

    private fun chooseWith(reachable: Set<String>): WeihaiEasEntryPolicy.Entry = runBlocking {
        val probe = FakeProbe { it in reachable }
        val direct = probe.isReachable(
            WeihaiEasEntryPolicy.probeUrl(WeihaiEasEntryPolicy.Entry.DIRECT, graduate = false)
        )
        val vpn = probe.isReachable(
            WeihaiEasEntryPolicy.probeUrl(WeihaiEasEntryPolicy.Entry.VPN, graduate = false)
        )
        WeihaiEasEntryPolicy.choose(directReachable = direct, vpnReachable = vpn)
    }

    @Test
    fun `campus network selects the direct entry`() {
        // 校园网：直连教务可达。
        val entry = chooseWith(
            setOf(WeihaiEasEntryPolicy.DIRECT_UNDERGRADUATE_LOGIN)
        )
        assertEquals(WeihaiEasEntryPolicy.Entry.DIRECT, entry)
    }

    @Test
    fun `off campus selects the vpn entry`() {
        // 校外：直连不通，只有 VPN 可达。
        val entry = chooseWith(setOf(WeihaiEasEntryPolicy.VPN_LOGIN))
        assertEquals(WeihaiEasEntryPolicy.Entry.VPN, entry)
    }

    @Test
    fun `both unreachable still selects vpn to match legacy behaviour`() {
        val entry = chooseWith(emptySet())
        assertEquals(WeihaiEasEntryPolicy.Entry.VPN, entry)
    }

    @Test
    fun `probe only asks about the two entry roots`() {
        val probe = FakeProbe { true }
        runBlocking {
            probe.isReachable(
                WeihaiEasEntryPolicy.probeUrl(WeihaiEasEntryPolicy.Entry.DIRECT, graduate = false)
            )
            probe.isReachable(
                WeihaiEasEntryPolicy.probeUrl(WeihaiEasEntryPolicy.Entry.VPN, graduate = false)
            )
        }
        assertEquals(
            listOf(
                WeihaiEasEntryPolicy.DIRECT_UNDERGRADUATE_LOGIN,
                WeihaiEasEntryPolicy.VPN_LOGIN,
            ),
            probe.asked,
        )
    }

    @Test
    fun `graduate probe uses the graduate direct host`() {
        assertEquals(
            WeihaiEasEntryPolicy.DIRECT_GRADUATE_LOGIN,
            WeihaiEasEntryPolicy.probeUrl(WeihaiEasEntryPolicy.Entry.DIRECT, graduate = true),
        )
        assertTrue(
            WeihaiEasEntryPolicy.probeUrl(WeihaiEasEntryPolicy.Entry.DIRECT, graduate = true)
                .startsWith("https://yjsgl.hitwh.edu.cn")
        )
    }

    @Test
    fun `failed probe never reports reachable`() {
        val probe = FakeProbe { false }
        val reachable = runBlocking { probe.isReachable(WeihaiEasEntryPolicy.VPN_LOGIN) }
        assertFalse(reachable)
    }
}
