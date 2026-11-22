package com.projectcit.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEngineTest {
    private val gba = LayoutOptions(Platform.GBA)
    private val nds = LayoutOptions(Platform.NDS)

    private fun aspect(r: Rect) = r.width / r.height

    @Test fun portraitGbaPinnedToTopSafeAreaWithControlsBelow() {
        val spec = LayoutEngine.compute(Devices.candybar, gba)
        val s = spec.screen(ScreenRole.MAIN)!!.rect
        assertEquals(1.5f, aspect(s), 0.02f)
        assertTrue("pinned near top inset", s.top - Devices.candybar.insets.top < 40f)
        assertTrue(spec.controls.isNotEmpty())
        assertTrue(spec.controls.all { it.rect.top >= s.bottom })
    }

    @Test fun portraitNdsStacksTopAboveBottom() {
        val spec = LayoutEngine.compute(Devices.foldCover, nds)
        val top = spec.screen(ScreenRole.TOP)!!.rect
        val bottom = spec.screen(ScreenRole.BOTTOM)!!.rect
        assertTrue(top.bottom <= bottom.top)
        assertEquals(top.width, bottom.width, 1f)
        assertTrue(spec.controls.all { it.rect.top >= bottom.bottom })
    }

    @Test fun swapPutsTouchscreenFirst() {
        val spec = LayoutEngine.compute(Devices.foldCover, nds.copy(swapScreens = true))
        assertTrue(spec.screen(ScreenRole.BOTTOM)!!.rect.bottom <= spec.screen(ScreenRole.TOP)!!.rect.top)
    }

    @Test fun emphasisEnlargesChosenScreen() {
        val spec = LayoutEngine.compute(Devices.candybar, nds.copy(ndsEmphasis = NdsEmphasis.BOTTOM_LARGE))
        assertTrue(spec.screen(ScreenRole.BOTTOM)!!.rect.area > spec.screen(ScreenRole.TOP)!!.rect.area * 2.5f)
    }

    @Test fun bookSideBySideSplitsAcrossSeam() {
        val w = Devices.foldInner(HingeState.FLAT)
        val spec = LayoutEngine.compute(w, nds)
        val seam = w.hinge!!.bounds.left
        assertTrue(spec.screen(ScreenRole.TOP)!!.rect.right < seam)
        assertTrue(spec.screen(ScreenRole.BOTTOM)!!.rect.left > seam)
        assertEquals(spec.screen(ScreenRole.TOP)!!.rect.top, spec.screen(ScreenRole.BOTTOM)!!.rect.top, 0.5f)
    }

    @Test fun hybridKeepsTouchscreenSmallerAboveControlsOnRight() {
        val w = Devices.foldInner(HingeState.FLAT)
        val spec = LayoutEngine.compute(w, nds.copy(ndsBookMode = NdsBookMode.HYBRID))
        val big = spec.screen(ScreenRole.TOP)!!.rect
        val small = spec.screen(ScreenRole.BOTTOM)!!.rect
        val seam = w.hinge!!.bounds.left
        assertTrue(big.right < seam && small.left > seam)
        assertTrue(small.area < big.area)
        assertTrue(spec.controls.all { it.rect.left > seam && it.rect.top >= small.bottom })
    }

    @Test fun flatBookGbaIsCenteredAcrossFullWidth() {
        val w = Devices.foldInner(HingeState.FLAT)
        val s = LayoutEngine.compute(w, gba).screen(ScreenRole.MAIN)!!.rect
        assertEquals(w.widthPx / 2f, s.centerX, 2f)
    }

    @Test fun tabletopNdsIsTrueClamshell() {
        val w = Devices.foldTabletop
        val spec = LayoutEngine.compute(w, nds)
        val seam = w.hinge!!.bounds.top
        val top = spec.screen(ScreenRole.TOP)!!.rect
        val bottom = spec.screen(ScreenRole.BOTTOM)!!.rect
        assertTrue(top.bottom < seam && bottom.top > seam)
        assertEquals(w.widthPx / 2f, bottom.centerX, 2f)
        val dpad = spec.controls.first { it.cluster == ControlCluster.DPAD }.rect
        val face = spec.controls.first { it.cluster == ControlCluster.FACE_BUTTONS }.rect
        assertTrue(dpad.right <= bottom.left && face.left >= bottom.right)
        assertTrue(spec.controls.all { it.rect.top > seam })
    }

    @Test fun tabletopGbaIsSpDeck() {
        val w = Devices.foldTabletop
        val spec = LayoutEngine.compute(w, gba)
        val seam = w.hinge!!.bounds.top
        assertTrue(spec.screen(ScreenRole.MAIN)!!.rect.bottom < seam)
        assertTrue(spec.controls.isNotEmpty() && spec.controls.all { it.rect.top > seam })
    }

    @Test fun physicalControllerHidesTouchControlsAndGrowsScreen() {
        val w = Devices.foldInner(HingeState.FLAT)
        val touch = LayoutEngine.compute(w, gba).screen(ScreenRole.MAIN)!!.rect
        val pad = LayoutEngine.compute(w, gba.copy(physicalController = true))
        assertTrue(pad.controls.isEmpty())
        assertTrue(pad.screen(ScreenRole.MAIN)!!.rect.area >= touch.area)
    }

    @Test fun integerScalingProducesWholeMultiples() {
        for (w in Devices.all) {
            val s = LayoutEngine.compute(w, gba.copy(integerScaling = true)).screen(ScreenRole.MAIN)!!.rect
            if (s.width >= 240f) assertEquals("$w", 0f, s.width % 240f, 0.01f)
        }
    }

    @Test fun ndsSourceUvSlicesFramebufferInHalves() {
        val spec = LayoutEngine.compute(Devices.candybar, nds)
        val top = spec.screen(ScreenRole.TOP)!!
        val bottom = spec.screen(ScreenRole.BOTTOM)!!
        assertEquals(0f, top.sourceVTop); assertEquals(0.5f, top.sourceVBottom)
        assertEquals(0.5f, bottom.sourceVTop); assertEquals(1f, bottom.sourceVBottom)
        assertTrue(bottom.acceptsTouch); assertFalse(top.acceptsTouch)
    }

    /** Exhaustive sweep: safety invariants hold for every device × option combination. */
    @Test fun invariantsHoldEverywhere() {
        var checked = 0
        for (w in Devices.all) for (platform in Platform.entries) for (mode in NdsBookMode.entries)
        for (emph in NdsEmphasis.entries) for (swap in listOf(false, true))
        for (pad in listOf(false, true)) for (integer in listOf(false, true)) {
            val o = LayoutOptions(platform, mode, emph, swap, integer, pad)
            val spec = LayoutEngine.compute(w, o)
            val label = "${spec.posture} $o"
            assertEquals(label, platform.screenCount, spec.screens.size)
            for (s in spec.screens) {
                assertFalse("$label empty screen", s.rect.isEmpty)
                assertTrue("$label screen outside safe area ${s.rect}", spec.content.contains(s.rect))
                assertEquals(label, platform.screenWidth.toFloat() / platform.screenHeight, aspect(s.rect), 0.03f)
            }
            if (spec.screens.size == 2) assertFalse(label, spec.screens[0].rect.intersects(spec.screens[1].rect))
            for (c in spec.controls) {
                assertTrue("$label control outside safe area", spec.content.contains(c.rect))
                for (s in spec.screens) assertFalse("$label ${c.cluster} overlaps ${s.role}", c.rect.intersects(s.rect))
            }
            if (pad) assertTrue(label, spec.controls.isEmpty())
            // Nothing may straddle a seam, except a single screen on a fully flat book.
            val hinge = spec.hinge
            if (hinge != null && !(spec.posture == Posture.BOOK_FLAT && !platform.isDualScreen)) {
                val seamZone = when (hinge.orientation) {
                    HingeOrientation.VERTICAL -> Rect(hinge.bounds.left - 1f, -1e6f, hinge.bounds.right + 1f, 1e6f)
                    HingeOrientation.HORIZONTAL -> Rect(-1e6f, hinge.bounds.top - 1f, 1e6f, hinge.bounds.bottom + 1f)
                }
                for (s in spec.screens) assertFalse("$label ${s.role} on seam", s.rect.intersects(seamZone))
                for (c in spec.controls) assertFalse("$label ${c.cluster} on seam", c.rect.intersects(seamZone))
            }
            assertNotNull(spec.posture)
            checked++
        }
        assertTrue(checked > 500)
    }
}
