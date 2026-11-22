package com.projectcit.input

import com.projectcit.layout.ControlCluster
import com.projectcit.layout.LayoutSpec
import com.projectcit.layout.Platform
import com.projectcit.layout.Rect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** A round on-screen button (face buttons). */
data class RoundButton(val button: PadButton, val cx: Float, val cy: Float, val r: Float)

/**
 * One touch target on screen. A finger that lands on an element is *captured* by it
 * until lifted, and keeps being evaluated by it with a larger tolerance. That lets
 * the thumb roll across the D-pad or slide from B to A without lifting, like real
 * hardware, and stops a slightly drifting thumb from "falling off" a button.
 */
sealed interface PadElement {
    /** Area drawn and used for "does a new touch start here". */
    val bounds: Rect

    /**
     * Buttons pressed by a finger at (x, y).
     * @param captured the finger started on this element (looser tolerance).
     * @return null when a *new* touch misses this element entirely.
     */
    fun press(x: Float, y: Float, captured: Boolean): Set<PadButton>?

    data class DPad(val cx: Float, val cy: Float, val r: Float) : PadElement {
        override val bounds = Rect(cx - r, cy - r, cx + r, cy + r)

        override fun press(x: Float, y: Float, captured: Boolean): Set<PadButton>? {
            val dx = x - cx
            val dy = y - cy
            val d = hypot(dx, dy)
            if (!captured && d > r * 1.15f) return null
            if (d < r * DEADZONE) return emptySet()          // resting thumb in the middle
            if (captured && d > r * 2.2f) return emptySet()  // slid far away: release, stay captured
            // Angle in degrees, 0 = right, 90 = down (screen y grows downward).
            val a = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            val out = mutableSetOf<PadButton>()
            // Cardinals own ±CARDINAL_HALF degrees; the rest is diagonal. Wider
            // cardinals prevent accidental diagonals in menus.
            if (abs(angleDelta(a, 0f)) < 90f - DIAG_START) out += PadButton.RIGHT
            if (abs(angleDelta(a, 180f)) < 90f - DIAG_START) out += PadButton.LEFT
            if (abs(angleDelta(a, 90f)) < 90f - DIAG_START) out += PadButton.DOWN
            if (abs(angleDelta(a, -90f)) < 90f - DIAG_START) out += PadButton.UP
            return out
        }

        companion object {
            const val DEADZONE = 0.18f
            /** Cardinal-only within ±30° of an axis; diagonal between 30° and 60°. */
            const val DIAG_START = 30f
        }
    }

    data class Face(val buttons: List<RoundButton>, override val bounds: Rect) : PadElement {
        override fun press(x: Float, y: Float, captured: Boolean): Set<PadButton>? {
            if (!captured && !bounds.contains(x, y)) return null
            val hits = buttons.filter { hypot(x - it.cx, y - it.cy) <= it.r * 1.1f }
            if (hits.isNotEmpty()) return hits.mapTo(mutableSetOf()) { it.button }
            // Between two neighbouring buttons: press both (A+B is a real GBA input).
            for (i in buttons.indices) for (j in i + 1 until buttons.size) {
                val p = buttons[i]
                val q = buttons[j]
                val gap = hypot(p.cx - q.cx, p.cy - q.cy)
                if (gap > (p.r + q.r) * 1.35f) continue  // not neighbours (e.g. X and B)
                val mx = (p.cx + q.cx) / 2
                val my = (p.cy + q.cy) / 2
                if (hypot(x - mx, y - my) <= min(p.r, q.r) * 0.55f) return setOf(p.button, q.button)
            }
            val nearest = buttons.minByOrNull { hypot(x - it.cx, y - it.cy) } ?: return emptySet()
            val reach = if (captured) 1.8f else 1.35f
            return if (hypot(x - nearest.cx, y - nearest.cy) <= nearest.r * reach) setOf(nearest.button) else emptySet()
        }
    }

    data class Key(val button: PadButton, override val bounds: Rect) : PadElement {
        override fun press(x: Float, y: Float, captured: Boolean): Set<PadButton>? {
            val growX = bounds.width * (if (captured) 0.4f else 0.12f)
            val growY = bounds.height * (if (captured) 0.6f else 0.2f)
            val hit = x >= bounds.left - growX && x <= bounds.right + growX && y >= bounds.top - growY && y <= bounds.bottom + growY
            return when {
                hit -> setOf(button)
                captured -> emptySet()
                else -> null
            }
        }
    }
}

private fun angleDelta(a: Float, b: Float): Float {
    var d = (a - b) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

/** Turns the layout engine's control zones into concrete, sized touch elements. */
object VirtualPadBuilder {

    fun build(spec: LayoutSpec, platform: Platform, density: Float): List<PadElement> {
        val dp = density
        val out = mutableListOf<PadElement>()
        for (zone in spec.controls) {
            val z = zone.rect
            if (z.isEmpty) continue
            when (zone.cluster) {
                ControlCluster.DPAD -> {
                    val r = min(min(z.width, z.height) * 0.46f, 84f * dp)
                    out += PadElement.DPad(z.centerX, z.centerY, r)
                }
                ControlCluster.FACE_BUTTONS -> out += face(z, platform, dp)
                ControlCluster.SHOULDER_L, ControlCluster.SHOULDER_R -> {
                    if (platform == Platform.GB || platform == Platform.GBC) continue  // no shoulders on GB
                    val w = min(z.width * 0.9f, 128f * dp)
                    val h = min(z.height * 0.8f, 46f * dp)
                    val left = if (zone.cluster == ControlCluster.SHOULDER_L) z.left + (z.width - w) * 0.15f else z.right - w - (z.width - w) * 0.15f
                    val top = z.top + (z.height - h) / 2f
                    val b = if (zone.cluster == ControlCluster.SHOULDER_L) PadButton.L else PadButton.R
                    out += PadElement.Key(b, Rect(left, top, left + w, top + h))
                }
                ControlCluster.SELECT, ControlCluster.START -> {
                    val w = min(z.width * 0.9f, 92f * dp)
                    val h = min(z.height * 0.8f, 36f * dp)
                    val left = z.centerX - w / 2
                    val top = z.centerY - h / 2
                    val b = if (zone.cluster == ControlCluster.START) PadButton.START else PadButton.SELECT
                    out += PadElement.Key(b, Rect(left, top, left + w, top + h))
                }
            }
        }
        return out
    }

    private fun face(z: Rect, platform: Platform, dp: Float): PadElement.Face {
        val cx = z.centerX
        val cy = z.centerY
        val buttons = if (platform == Platform.NDS) {
            // DS diamond: X top, A right, B bottom, Y left.
            val r = min(min(z.width, z.height) / 5.2f, 34f * dp)
            val s = r * 1.5f
            listOf(
                RoundButton(PadButton.X, cx, cy - s, r), RoundButton(PadButton.A, cx + s, cy, r),
                RoundButton(PadButton.B, cx, cy + s, r), RoundButton(PadButton.Y, cx - s, cy, r),
            )
        } else {
            // GBA/GB: B lower-left, A upper-right, tilted like the hardware.
            val r = min(min(z.width / 4.6f, z.height / 3.3f), 40f * dp)
            listOf(RoundButton(PadButton.B, cx - 1.1f * r, cy + 0.55f * r, r), RoundButton(PadButton.A, cx + 1.1f * r, cy - 0.55f * r, r))
        }
        return PadElement.Face(buttons, z)
    }
}
