package com.projectcit.layout

import kotlin.math.max
import kotlin.math.min

/** Axis-aligned rectangle in window pixels. Pure Kotlin so the engine is JVM-testable. */
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = max(0f, width) * max(0f, height)
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun inset(all: Float): Rect = inset(all, all, all, all)
    fun inset(l: Float, t: Float, r: Float, b: Float): Rect =
        Rect(left + l, top + t, max(left + l, right - r), max(top + t, bottom - b))

    /** Strict interior overlap (touching edges do not count). */
    fun intersects(o: Rect): Boolean =
        !isEmpty && !o.isEmpty && left < o.right && o.left < right && top < o.bottom && o.top < bottom

    fun contains(o: Rect, epsilon: Float = 0.5f): Boolean =
        o.left >= left - epsilon && o.top >= top - epsilon &&
            o.right <= right + epsilon && o.bottom <= bottom + epsilon

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    fun intersect(o: Rect): Rect =
        Rect(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom))

    /** Horizontal slice [fromFraction, toFraction] of this rect's height. */
    fun rows(fromFraction: Float, toFraction: Float): Rect =
        Rect(left, top + height * fromFraction, right, top + height * toFraction)

    /** Vertical slice [fromFraction, toFraction] of this rect's width. */
    fun cols(fromFraction: Float, toFraction: Float): Rect =
        Rect(left + width * fromFraction, top, left + width * toFraction, bottom)

    override fun toString(): String =
        "[%.0f,%.0f %.0f×%.0f]".format(left, top, width, height)

    companion object {
        val ZERO = Rect(0f, 0f, 0f, 0f)
        fun ofSize(left: Float, top: Float, width: Float, height: Float) =
            Rect(left, top, left + width, top + height)
    }
}

data class Insets(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object { val ZERO = Insets(0f, 0f, 0f, 0f) }
}

enum class Align { START, CENTER, END }
