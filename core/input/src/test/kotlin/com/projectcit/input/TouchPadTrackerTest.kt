package com.projectcit.input

import com.projectcit.layout.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchPadTrackerTest {
    private val pad = listOf(
        PadElement.DPad(100f, 500f, 80f),
        PadElement.Face(listOf(RoundButton(PadButton.B, 567f, 516.5f, 30f), RoundButton(PadButton.A, 633f, 483.5f, 30f)), Rect(500f, 420f, 700f, 580f)),
        PadElement.Key(PadButton.R, Rect(560f, 300f, 700f, 340f)),
    )
    private val t = TouchPadTracker().apply { setElements(pad) }

    @Test fun threeFingersCombine() {
        assertEquals(setOf(PadButton.RIGHT), t.down(1, 160f, 500f))
        assertEquals(setOf(PadButton.A), t.down(2, 640f, 480f))
        assertEquals(setOf(PadButton.R), t.down(3, 600f, 320f))
        assertEquals(PadButton.mask(listOf(PadButton.RIGHT, PadButton.A, PadButton.R)), t.mask)
        t.up(2)
        assertEquals(PadButton.mask(listOf(PadButton.RIGHT, PadButton.R)), t.mask)
    }

    @Test fun thumbRollsAroundDpadAndSlidesAcrossFaceButtons() {
        t.down(1, 160f, 500f)
        assertEquals(setOf(PadButton.DOWN), t.move(1, 100f, 560f))     // only the new button is reported (haptics)
        assertEquals(PadButton.DOWN.bit, t.mask)
        t.down(2, 560f, 520f)
        assertEquals(setOf(PadButton.A), t.move(2, 600f, 500f))        // slide onto the A+B chord
        assertEquals(setOf<PadButton>(), t.move(2, 640f, 480f))        // onto A only: nothing new pressed
        assertEquals(PadButton.DOWN.bit or PadButton.A.bit, t.mask)
    }

    @Test fun touchesOffThePadAreNotClaimed() {
        assertNull(t.down(7, 350f, 100f))     // e.g. the DS touchscreen: left for the stylus path
        assertFalse(t.isTracking(7))
        assertEquals(0, t.mask)
    }

    @Test fun relayoutReleasesEverything() {
        t.down(1, 160f, 500f)
        t.setElements(pad.take(1))
        assertEquals(0, t.mask)
        assertTrue(t.up(1).isEmpty())
    }
}
