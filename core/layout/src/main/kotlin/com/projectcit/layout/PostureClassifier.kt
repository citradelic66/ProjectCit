package com.projectcit.layout

object PostureClassifier {
    /**
     * The hinge decides the posture, not the size class: an unfolded Galaxy Z Fold
     * inner display is ~690–830dp wide, i.e. MEDIUM, not EXPANDED.
     */
    fun classify(window: WindowSpec): Posture {
        val hinge = window.hinge?.takeIf { it.isUsable(window) }
        val landscape = window.widthPx >= window.heightPx
        return when {
            hinge == null -> if (landscape) Posture.LANDSCAPE else Posture.PORTRAIT
            hinge.orientation == HingeOrientation.VERTICAL ->
                if (hinge.state == HingeState.FLAT) Posture.BOOK_FLAT else Posture.BOOK_HALF_OPENED
            hinge.state == HingeState.HALF_OPENED -> Posture.TABLETOP
            // Flat with a horizontal seam: crease is cosmetic, use the regular layouts.
            else -> if (landscape) Posture.LANDSCAPE else Posture.PORTRAIT
        }
    }

    /** Ignore hinges that are outside this window (e.g. app not spanning the fold). */
    private fun HingeInfo.isUsable(window: WindowSpec): Boolean {
        val b = bounds
        return when (orientation) {
            HingeOrientation.VERTICAL -> b.left > 0f && b.right < window.widthPx
            HingeOrientation.HORIZONTAL -> b.top > 0f && b.bottom < window.heightPx
        }
    }
}
