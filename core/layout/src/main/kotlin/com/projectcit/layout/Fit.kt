package com.projectcit.layout

import kotlin.math.floor
import kotlin.math.min

/**
 * Largest rect with the console's aspect ratio that fits [container].
 * With [integer] the scale snaps down to a whole multiple (pixel-perfect), unless
 * that would make the image smaller than 1×, in which case fractional scaling is used.
 * Results are snapped to whole pixels so nearest-neighbor sampling stays crisp.
 */
fun fitAspect(
    container: Rect,
    srcW: Int,
    srcH: Int,
    integer: Boolean,
    hAlign: Align = Align.CENTER,
    vAlign: Align = Align.CENTER,
    maxScale: Float = Float.MAX_VALUE,
): Rect {
    if (container.isEmpty) return Rect.ofSize(container.left, container.top, 0f, 0f)
    var scale = min(min(container.width / srcW, container.height / srcH), maxScale)
    if (integer && scale >= 1f) scale = floor(scale)
    val w = floor(srcW * scale)
    val h = floor(srcH * scale)
    val x = when (hAlign) {
        Align.START -> container.left
        Align.CENTER -> container.left + (container.width - w) / 2f
        Align.END -> container.right - w
    }
    val y = when (vAlign) {
        Align.START -> container.top
        Align.CENTER -> container.top + (container.height - h) / 2f
        Align.END -> container.bottom - h
    }
    val sx = floor(x)
    val sy = floor(y)
    return Rect(sx, sy, sx + w, sy + h)
}
