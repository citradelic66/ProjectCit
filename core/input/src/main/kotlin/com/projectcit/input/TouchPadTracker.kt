package com.projectcit.input

/**
 * Multi-touch state for the virtual pad: each finger is captured by the element
 * it landed on and contributes that element's buttons. The emitted mask is the
 * union over fingers, so D-pad + A + R with three fingers just works.
 */
class TouchPadTracker {
    private var elements: List<PadElement> = emptyList()
    private val fingers = LinkedHashMap<Long, Pair<PadElement, Set<PadButton>>>()

    var mask: Int = 0
        private set

    /** New layout (fold, rotate, option change): fingers on old geometry are released. */
    fun setElements(newElements: List<PadElement>) {
        if (newElements == elements) return
        elements = newElements
        fingers.clear()
        mask = 0
    }

    /** @return the buttons newly pressed by this change (for haptics), or null if the touch isn't on the pad. */
    fun down(pointerId: Long, x: Float, y: Float): Set<PadButton>? {
        // Topmost (last added) element wins where bounds overlap.
        for (e in elements.asReversed()) {
            val pressed = e.press(x, y, captured = false) ?: continue
            fingers[pointerId] = e to pressed
            return update()
        }
        return null
    }

    fun move(pointerId: Long, x: Float, y: Float): Set<PadButton> {
        val (element, _) = fingers[pointerId] ?: return emptySet()
        fingers[pointerId] = element to (element.press(x, y, captured = true) ?: emptySet())
        return update()
    }

    fun up(pointerId: Long): Set<PadButton> {
        fingers.remove(pointerId) ?: return emptySet()
        return update()
    }

    fun isTracking(pointerId: Long): Boolean = pointerId in fingers

    fun cancelAll() {
        fingers.clear()
        mask = 0
    }

    private fun update(): Set<PadButton> {
        val newMask = fingers.values.fold(0) { m, (_, b) -> m or PadButton.mask(b) }
        val newlyPressed = PadButton.fromMask(newMask and mask.inv())
        mask = newMask
        return newlyPressed
    }
}
