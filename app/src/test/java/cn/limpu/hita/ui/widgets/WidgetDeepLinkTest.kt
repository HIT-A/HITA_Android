package cn.limpu.hita.ui.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetDeepLinkTest {

    @Test
    fun `valid event id and date parse`() {
        val link = WidgetDeepLink.parse("evt-1", "2026-11-15")

        assertEquals("evt-1", link?.eventId)
        assertEquals("2026-11-15", link?.date)
    }

    @Test
    fun `missing event id is rejected`() {
        assertNull(WidgetDeepLink.parse(null, "2026-11-15"))
        assertNull(WidgetDeepLink.parse("   ", "2026-11-15"))
    }

    @Test
    fun `malformed date is rejected`() {
        assertNull(WidgetDeepLink.parse("evt-1", null))
        assertNull(WidgetDeepLink.parse("evt-1", "2026/11/15"))
        assertNull(WidgetDeepLink.parse("evt-1", "2026-11-15T08:00"))
        assertNull(WidgetDeepLink.parse("evt-1", "2026-1-5"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val link = WidgetDeepLink.parse("  evt-9  ", " 2026-11-15 ")

        assertEquals("evt-9", link?.eventId)
        assertEquals("2026-11-15", link?.date)
    }
}
