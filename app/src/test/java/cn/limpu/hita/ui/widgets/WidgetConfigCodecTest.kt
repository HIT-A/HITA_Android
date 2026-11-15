package cn.limpu.hita.ui.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetConfigCodecTest {

    @Test
    fun `field names are isolated per widget instance`() {
        assertNotEquals(
            WidgetConfigCodec.scopeKeyField(1),
            WidgetConfigCodec.scopeKeyField(2),
        )
        assertEquals("scope_7", WidgetConfigCodec.scopeKeyField(7))
        assertEquals("background_7", WidgetConfigCodec.backgroundKeyField(7))
    }

    @Test
    fun `unknown background falls back to plain`() {
        assertEquals(WidgetConfigCodec.Background.PLAIN, WidgetConfigCodec.decodeBackground(null))
        assertEquals(WidgetConfigCodec.Background.PLAIN, WidgetConfigCodec.decodeBackground("holographic"))
        assertEquals(WidgetConfigCodec.Background.CARD, WidgetConfigCodec.decodeBackground("card"))
    }

    @Test
    fun `blank scope means follow the main screen`() {
        assertNull(WidgetConfigCodec.decode("   ", "card").scopeKey)
        assertEquals("term:2025-2026-1", WidgetConfigCodec.decode("term:2025-2026-1", "card").scopeKey)
    }

    @Test
    fun `round trip preserves both fields`() {
        val config = WidgetConfigCodec.Config(
            scopeKey = "group:1",
            background = WidgetConfigCodec.Background.CARD,
        )

        val encoded = WidgetConfigCodec.encode(config)
        val decoded = WidgetConfigCodec.decode(
            encoded[WidgetConfigCodec.SCOPE_VALUE],
            encoded[WidgetConfigCodec.BACKGROUND_VALUE],
        )

        assertEquals(config, decoded)
    }
}
