package com.projectcit.input

import com.projectcit.layout.LayoutSpec
import com.projectcit.layout.PointerMapper
import com.projectcit.layout.PointerSample

/**
 * Routes every finger, independently, to either the DS stylus or the virtual pad.
 * The decision is made once per finger, on touch-down:
 *  - first finger landing on the touchscreen quad becomes the stylus, tracked until
 *    lift and clamped to the screen edge if it slides off (like a real stylus);
 *  - any other finger goes to the pad if it hit a control, otherwise it is ignored.
 *
 * Output goes through [sink]; Compose only forwards raw pointer events.
 */
class TouchRouter(private val sink: Sink) {

    interface Sink {
        fun pointer(sample: PointerSample)
        /** @param newlyPressed buttons that just went down (for haptics). */
        fun buttons(mask: Int, newlyPressed: Set<PadButton>)
    }

    val pad = TouchPadTracker()
    private var spec: LayoutSpec? = null
    private var stylus: Long? = null

    fun setLayout(spec: LayoutSpec, elements: List<PadElement>) {
        releaseAll()
        this.spec = spec
        pad.setElements(elements)
    }

    fun down(id: Long, x: Float, y: Float) {
        val s = spec ?: return
        if (stylus == null) {
            PointerMapper.map(x, y, s)?.let { sample ->
                stylus = id
                sink.pointer(sample)
                return
            }
        }
        pad.down(id, x, y)?.let { sink.buttons(pad.mask, it) }
    }

    fun move(id: Long, x: Float, y: Float) {
        val s = spec ?: return
        when {
            id == stylus -> PointerMapper.map(x, y, s, clampToEdge = true)?.let(sink::pointer)
            pad.isTracking(id) -> {
                val before = pad.mask
                val newly = pad.move(id, x, y)
                if (pad.mask != before) sink.buttons(pad.mask, newly)
            }
        }
    }

    fun up(id: Long) {
        when {
            id == stylus -> { stylus = null; sink.pointer(PointerSample.RELEASED) }
            pad.isTracking(id) -> { pad.up(id); sink.buttons(pad.mask, emptySet()) }
        }
    }

    /** Gesture cancelled / layout changed / screen left: nothing may stay held. */
    fun releaseAll() {
        if (stylus != null) sink.pointer(PointerSample.RELEASED)
        stylus = null
        val hadButtons = pad.mask != 0
        pad.cancelAll()
        if (hadButtons) sink.buttons(0, emptySet())
    }
}
