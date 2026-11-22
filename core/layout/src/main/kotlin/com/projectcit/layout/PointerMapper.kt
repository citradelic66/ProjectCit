package com.projectcit.layout

import kotlin.math.roundToInt

/** One libretro RETRO_DEVICE_POINTER sample. x/y are in [-0x7fff, 0x7fff]. */
data class PointerSample(val x: Int, val y: Int, val pressed: Boolean) {
    companion object {
        const val MAX = 0x7fff
        val RELEASED = PointerSample(0, 0, false)
    }
}

/**
 * Maps a touch in window pixels onto libretro's pointer space.
 *
 * libretro pointer coordinates span the core's *whole* video frame, not one DS
 * screen: with melonDS DS outputting a stacked 256×384 frame, the touchscreen is
 * the lower half, so a tap at the touchscreen's top-left is (-0x7fff, 0), not
 * (-0x7fff, -0x7fff). The viewport's source UV region encodes exactly that.
 */
object PointerMapper {

    /**
     * @param clampToEdge true while a drag that started on the screen wanders off it
     *   (keeps the stylus pinned to the edge, like real hardware); false for new touches.
     * @return null if the touch is outside the viewport and not clamped.
     */
    fun map(x: Float, y: Float, viewport: ScreenViewport, clampToEdge: Boolean = false): PointerSample? {
        val r = viewport.rect
        if (r.isEmpty) return null
        var u = (x - r.left) / r.width
        var v = (y - r.top) / r.height
        val inside = u >= 0f && u < 1f && v >= 0f && v < 1f
        if (!inside && !clampToEdge) return null
        u = u.coerceIn(0f, 1f)
        v = v.coerceIn(0f, 1f)
        val frameU = viewport.sourceULeft + u * (viewport.sourceURight - viewport.sourceULeft)
        val frameV = viewport.sourceVTop + v * (viewport.sourceVBottom - viewport.sourceVTop)
        return PointerSample(toAxis(frameU), toAxis(frameV), pressed = true)
    }

    /** Finds the touch-enabled screen in [spec] and maps onto it. */
    fun map(x: Float, y: Float, spec: LayoutSpec, clampToEdge: Boolean = false): PointerSample? {
        val touch = spec.screens.firstOrNull { it.acceptsTouch } ?: return null
        return map(x, y, touch, clampToEdge)
    }

    /** [0,1] -> [-0x7fff, 0x7fff] */
    internal fun toAxis(f: Float): Int = (f * 2f * PointerSample.MAX).roundToInt() - PointerSample.MAX
}
