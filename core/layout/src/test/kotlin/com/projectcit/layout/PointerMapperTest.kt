package com.projectcit.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerMapperTest {
    private fun near(expected: Int, actual: Int, tol: Int = 1) =
        assertTrue("expected $expected±$tol, got $actual", kotlin.math.abs(expected - actual) <= tol)

    private val bottom = ScreenViewport(ScreenRole.BOTTOM, Rect(100f, 500f, 612f, 884f), 0.5f, 1f) // 512×384 = 2×
    private val main = ScreenViewport(ScreenRole.MAIN, Rect(0f, 0f, 480f, 320f), 0f, 1f)

    @Test fun cornersOfFullFrameHitExtremes() {
        assertEquals(PointerSample(-0x7fff, -0x7fff, true), PointerMapper.map(0f, 0f, main))
        val br = PointerMapper.map(479.999f, 319.999f, main)!!
        near(0x7fff, br.x); near(0x7fff, br.y)
        val c = PointerMapper.map(240f, 160f, main)!!
        assertEquals(0, c.x); assertEquals(0, c.y)
    }

    @Test fun touchscreenIsLowerHalfOfDsFrame() {
        // Top-left of the touchscreen = frame (0, 192) of 256×384 -> y = 0.
        assertEquals(PointerSample(-0x7fff, 0, true), PointerMapper.map(100f, 500f, bottom))
        // Bottom edge of the touchscreen = bottom edge of the frame.
        near(0x7fff, PointerMapper.map(356f, 883.999f, bottom)!!.y)
    }

    @Test fun nativePixelRoundTrip() {
        // Tap the centre of DS touch pixel (200, 150) at 2× scale; core converts back.
        val s = PointerMapper.map(100f + 200.5f * 2f, 500f + 150.5f * 2f, bottom)!!
        val fx = (s.x + 0x7fff) / (2.0 * 0x7fff) * 256
        val fy = (s.y + 0x7fff) / (2.0 * 0x7fff) * 384
        assertEquals(200, fx.toInt()); assertEquals(192 + 150, fy.toInt())
    }

    @Test fun outsideIsIgnoredUnlessDragging() {
        assertNull(PointerMapper.map(50f, 600f, bottom))
        val clamped = PointerMapper.map(50f, 2000f, bottom, clampToEdge = true)
        assertEquals(PointerSample(-0x7fff, 0x7fff, true), clamped)
    }

    @Test fun findsTouchScreenInRealLayouts() {
        for (w in Devices.all) for (swap in listOf(false, true)) {
            val spec = LayoutEngine.compute(w, LayoutOptions(Platform.NDS, swapScreens = swap))
            val touch = spec.screen(ScreenRole.BOTTOM)!!.rect
            val s = PointerMapper.map(touch.centerX, touch.centerY, spec)
            assertNotNull(s); near(0, s!!.x)
            assertTrue("centre of touchscreen is 3/4 down the DS frame", s.y in 0x3fff - 2..0x3fff + 2)
            val top = spec.screen(ScreenRole.TOP)!!.rect
            assertNull(PointerMapper.map(top.centerX, top.centerY, spec))
        }
        assertNull(PointerMapper.map(10f, 10f, LayoutEngine.compute(Devices.candybar, LayoutOptions(Platform.GBA))))
    }
}
