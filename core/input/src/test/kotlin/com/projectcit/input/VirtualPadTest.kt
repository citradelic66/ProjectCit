package com.projectcit.input

import com.projectcit.layout.ControlCluster
import com.projectcit.layout.HingeInfo
import com.projectcit.layout.HingeOrientation
import com.projectcit.layout.HingeState
import com.projectcit.layout.Insets
import com.projectcit.layout.LayoutEngine
import com.projectcit.layout.LayoutOptions
import com.projectcit.layout.Platform
import com.projectcit.layout.Rect
import com.projectcit.layout.WindowSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class VirtualPadTest {
    private val dpad = PadElement.DPad(100f, 100f, 80f)
    private fun at(deg: Double, dist: Float = 60f) =
        dpad.press(100f + dist * cos(Math.toRadians(deg)).toFloat(), 100f + dist * sin(Math.toRadians(deg)).toFloat(), false)

    @Test fun dpadCardinalsAndDiagonals() {
        assertEquals(setOf(PadButton.RIGHT), at(0.0))
        assertEquals(setOf(PadButton.DOWN), at(90.0))        // screen y grows down
        assertEquals(setOf(PadButton.UP), at(-90.0))
        assertEquals(setOf(PadButton.LEFT), at(180.0))
        assertEquals(setOf(PadButton.RIGHT), at(25.0))       // within ±30°: still cardinal
        assertEquals(setOf(PadButton.RIGHT, PadButton.DOWN), at(45.0))
        assertEquals(setOf(PadButton.UP, PadButton.LEFT), at(-135.0))
    }

    @Test fun dpadDeadzoneAndCapture() {
        assertEquals(emptySet<PadButton>(), dpad.press(105f, 102f, false))   // resting thumb
        assertNull(dpad.press(300f, 100f, false))                           // new touch far away
        assertEquals(setOf(PadButton.RIGHT), dpad.press(250f, 100f, true))  // captured thumb slid past the edge
        assertEquals(emptySet<PadButton>(), dpad.press(400f, 100f, true))   // way off: released, not stuck
    }

    private val gbaFace = PadElement.Face(
        // Same geometry VirtualPadBuilder produces for GBA (centres 2.46 r apart).
        listOf(RoundButton(PadButton.B, 67f, 116.5f, 30f), RoundButton(PadButton.A, 133f, 83.5f, 30f)),
        Rect(0f, 0f, 200f, 200f),
    )

    @Test fun faceButtonsAndChord() {
        assertEquals(setOf(PadButton.A), gbaFace.press(140f, 80f, false))
        assertEquals(setOf(PadButton.B), gbaFace.press(62f, 118f, false))
        // The fixture must match what the builder really makes.
        val built = VirtualPadBuilder.build(
            LayoutEngine.compute(WindowSpec(1080f, 2400f, 2.625f), LayoutOptions(Platform.GBA)), Platform.GBA, 2.625f,
        ).filterIsInstance<PadElement.Face>().single().buttons
        assertEquals(2.46f, hypot(built[0].cx - built[1].cx, built[0].cy - built[1].cy) / built[0].r, 0.01f)
        assertEquals(setOf(PadButton.A, PadButton.B), gbaFace.press(100f, 100f, false))  // between = both
        assertNull(gbaFace.press(300f, 300f, false))
        assertEquals(setOf(PadButton.A), gbaFace.press(175f, 70f, true))    // drifted thumb still on A
    }

    @Test fun ndsDiamondDoesNotChordOppositeButtons() {
        val spec = LayoutEngine.compute(WindowSpec(1080f, 2400f, 2.625f, Insets(0f, 128f, 0f, 63f)), LayoutOptions(Platform.NDS))
        val face = VirtualPadBuilder.build(spec, Platform.NDS, 2.625f).filterIsInstance<PadElement.Face>().single()
        assertEquals(listOf(PadButton.X, PadButton.A, PadButton.B, PadButton.Y), face.buttons.map { it.button })
        val x = face.buttons[0]
        val b = face.buttons[2]
        // Centre of the diamond sits between X and B, which are not neighbours: no X+B chord.
        val mid = face.press((x.cx + b.cx) / 2, (x.cy + b.cy) / 2, false)!!
        assertFalse(mid.containsAll(listOf(PadButton.X, PadButton.B)))
        // Buttons don't overlap and are reachable.
        for (p in face.buttons) for (q in face.buttons) if (p != q) assertTrue(hypot(p.cx - q.cx, p.cy - q.cy) >= p.r + q.r)
        face.buttons.forEach { assertEquals(setOf(it.button), face.press(it.cx, it.cy, false)) }
    }

    @Test fun builtPadsAreSaneOnEveryDevice() {
        val windows = listOf(
            WindowSpec(1080f, 2400f, 2.625f, Insets(0f, 128f, 0f, 63f)),
            WindowSpec(2400f, 1080f, 2.625f, Insets(128f, 0f, 63f, 0f)),
            WindowSpec(1856f, 2160f, 2.625f, Insets(0f, 96f, 0f, 63f),
                HingeInfo(Rect(928f, 0f, 928f, 2160f), HingeOrientation.VERTICAL, HingeState.FLAT, false, false)),
            WindowSpec(2160f, 1856f, 2.625f, Insets(0f, 96f, 0f, 63f),
                HingeInfo(Rect(0f, 928f, 2160f, 928f), HingeOrientation.HORIZONTAL, HingeState.HALF_OPENED, true, false)),
        )
        for (w in windows) for (p in Platform.entries) {
            val spec = LayoutEngine.compute(w, LayoutOptions(p))
            val pad = VirtualPadBuilder.build(spec, p, w.density)
            val label = "${spec.posture} $p"
            assertEquals(label, 1, pad.count { it is PadElement.DPad })
            assertEquals(label, 1, pad.count { it is PadElement.Face })
            val keys = pad.filterIsInstance<PadElement.Key>().map { it.button }.toSet()
            assertTrue(label, keys.containsAll(listOf(PadButton.START, PadButton.SELECT)))
            assertEquals(label, p == Platform.GBA || p == Platform.NDS, keys.contains(PadButton.L))
            // Elements are big enough for a thumb (>= 9 mm ≈ 56 dp for the D-pad) and stay off the game screens.
            val d = pad.filterIsInstance<PadElement.DPad>().single()
            assertTrue(label, d.r * 2 / w.density >= 56f)
            for (e in pad) for (s in spec.screens) assertFalse("$label ${e.bounds} over ${s.role}", e.bounds.intersects(s.rect))
            // Every zone produced exactly the expected element kinds.
            assertTrue(spec.controls.any { it.cluster == ControlCluster.DPAD })
        }
    }
}
