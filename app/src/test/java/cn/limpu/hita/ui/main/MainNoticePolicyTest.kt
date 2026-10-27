package cn.limpu.hita.ui.main

import cn.limpu.hita.data.notice.AppNotice
import org.junit.Assert.assertEquals
import org.junit.Test

class MainNoticePolicyTest {
    @Test fun firstCriticalWinsEvenWhenAlreadySeenOrAfterAnUpgradeNotice() {
        val first = notice("critical-first", severity = "critical")
        val next = notice("critical-next", severity = "critical")
        val selected = MainNoticePolicy.select(listOf(notice("upgrade", kind = "version", minVersion = 99), first, next), setOf(first.id), 10)
        assertEquals(MainNoticeSelection.Critical(first), selected)
    }

    @Test fun firstRequiredVersionWinsOverUnseenAndDoesNotChooseTheLargestVersion() {
        val first = notice("first", kind = "version", minVersion = 11)
        val later = notice("later", kind = "version", minVersion = 20)
        val selected = MainNoticePolicy.select(listOf(notice("ordinary"), first, later), setOf(first.id), 10)
        assertEquals(MainNoticeSelection.Version(first), selected)
    }

    @Test fun sameOlderAndUnspecifiedVersionNoticesRemainOrdinaryUnreadItems() {
        val values = listOf(
            notice("same", kind = "version", minVersion = 10),
            notice("older", kind = "version", minVersion = 9),
            notice("no-min", kind = "version"),
            notice("service-with-min", minVersion = 100),
        )
        assertEquals(MainNoticeSelection.Unseen(values), MainNoticePolicy.select(values, emptySet(), 10))
    }

    @Test fun unseenSelectionPreservesFetchOrderWithoutChangingReadState() {
        val first = notice("first")
        val seen = notice("seen")
        val last = notice("last", kind = "incident")
        val seenIds = mutableSetOf(seen.id)
        val fetched = mutableListOf(first, seen, last)
        val selected = MainNoticePolicy.select(fetched, seenIds, 10)
        fetched.clear()
        assertEquals(MainNoticeSelection.Unseen(listOf(first, last)), selected)
        assertEquals(setOf("seen"), seenIds)
    }

    @Test fun noUnreadItemsMeansNoPopupAndPolicyDoesNotReapplyActiveFiltering() {
        assertEquals(MainNoticeSelection.None, MainNoticePolicy.select(emptyList(), emptySet(), 10))
        assertEquals(MainNoticeSelection.None, MainNoticePolicy.select(listOf(notice("seen")), setOf("seen"), 10))
        val prefiltered = notice("already-active").copy(startsAt = Long.MAX_VALUE, endsAt = 0, affectedMinVersion = Long.MAX_VALUE)
        assertEquals(MainNoticeSelection.Unseen(listOf(prefiltered)), MainNoticePolicy.select(listOf(prefiltered), emptySet(), 10))
    }

    private fun notice(id: String, kind: String = "service", severity: String = "info", minVersion: Long? = null) =
        AppNotice(id, kind, severity, "title $id", "body $id", minVersion, null, null, null)
}
