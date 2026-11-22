package com.projectcit.input

import com.projectcit.layout.Insets
import com.projectcit.layout.LayoutEngine
import com.projectcit.layout.LayoutOptions
import com.projectcit.layout.Platform
import com.projectcit.layout.PointerSample
import com.projectcit.layout.ScreenRole
import com.projectcit.layout.WindowSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchRouterTest {
    private val pointers = mutableListOf<PointerSample>()
    private var mask = 0
    private val haptics = mutableListOf<Set<PadButton>>()
    private val router = TouchRouter(object : TouchRouter.Sink {
        override fun pointer(sample: PointerSample) { pointers += sample }
        override fun buttons(mask: Int, newlyPressed: Set<PadButton>) { this@TouchRouterTest.mask = mask; if (newlyPressed.isNotEmpty()) haptics += newlyPressed }
    })

    private val window = WindowSpec(1080f, 2400f, 2.625f, Insets(0f, 128f, 0f, 63f))
    private val spec = LayoutEngine.compute(window, LayoutOptions(Platform.NDS))
    private val elements = VirtualPadBuilder.build(spec, Platform.NDS, 2.625f).also { router.setLayout(spec, it) }
    private val touch = spec.screen(ScreenRole.BOTTOM)!!.rect
    private val top = spec.screen(ScreenRole.TOP)!!.rect
    private val dpad = elements.filterIsInstance<PadElement.DPad>().single()
    private val aButton = elements.filterIsInstance<PadElement.Face>().single().buttons.first { it.button == PadButton.A }

    @Test fun stylusAndButtonsAtTheSameTime() {
        router.down(1, touch.centerX, touch.centerY)          // stylus on the touchscreen
        router.down(2, dpad.cx + dpad.r * 0.7f, dpad.cy)      // thumb holds RIGHT
        router.down(3, aButton.cx, aButton.cy)                // other thumb taps A
        assertTrue(pointers.last().pressed)
        assertEquals(PadButton.RIGHT.bit or PadButton.A.bit, mask)
        assertEquals(listOf(setOf(PadButton.RIGHT), setOf(PadButton.A)), haptics)

        router.move(1, touch.right + 500f, touch.centerY)     // stylus slides off the right edge
        assertEquals(PointerSample.MAX, pointers.last().x)    // pinned to the edge, still pressed
        assertTrue(pointers.last().pressed)

        router.up(3)
        assertEquals(PadButton.RIGHT.bit, mask)
        router.up(1)
        assertFalse(pointers.last().pressed)
        assertEquals(PadButton.RIGHT.bit, mask)                // releasing the stylus doesn't touch the pad
    }

    @Test fun onlyOneStylusAndTopScreenIsInert() {
        router.down(1, touch.centerX, touch.centerY)
        val n = pointers.size
        router.down(2, touch.left + 10f, touch.top + 10f)     // second finger on the touchscreen: ignored
        router.move(2, touch.left + 50f, touch.top + 50f)
        assertEquals(n, pointers.size)
        router.down(3, top.centerX, top.centerY)              // top screen isn't touchable
        assertEquals(n, pointers.size); assertEquals(0, mask)
    }

    @Test fun relayoutMidTouchReleasesEverything() {
        router.down(1, touch.centerX, touch.centerY)
        router.down(2, aButton.cx, aButton.cy)
        router.setLayout(spec, elements)                       // fold / rotate while holding
        assertFalse(pointers.last().pressed)
        assertEquals(0, mask)
        router.up(1); router.up(2)                             // late lifts are harmless
        assertEquals(0, mask)
    }

    @Test fun gbaHasNoStylus() {
        val gba = LayoutEngine.compute(window, LayoutOptions(Platform.GBA))
        router.setLayout(gba, VirtualPadBuilder.build(gba, Platform.GBA, 2.625f))
        pointers.clear()
        val screen = gba.screen(ScreenRole.MAIN)!!.rect
        router.down(1, screen.centerX, screen.centerY)
        assertTrue(pointers.isEmpty()); assertEquals(0, mask)
    }
}
