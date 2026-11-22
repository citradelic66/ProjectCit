package com.projectcit.layout

/** Representative window metrics (px). Values approximate real devices; the tests
 *  assert invariants, not pixel-exact output, so ±few % doesn't matter. */
object Devices {
    const val D = 2.625f

    val candybar = WindowSpec(1080f, 2400f, D, Insets(0f, 128f, 0f, 63f))
    val candybarLandscape = WindowSpec(2400f, 1080f, D, Insets(128f, 0f, 63f, 0f))
    val foldCover = WindowSpec(968f, 2376f, D, Insets(0f, 110f, 0f, 63f))

    fun foldInner(state: HingeState) = WindowSpec(
        1856f, 2160f, D, Insets(0f, 96f, 0f, 63f),
        HingeInfo(Rect(928f, 0f, 928f, 2160f), HingeOrientation.VERTICAL, state,
            isSeparating = state == HingeState.HALF_OPENED, occludes = false),
    )

    val foldTabletop = WindowSpec(
        2160f, 1856f, D, Insets(0f, 96f, 0f, 63f),
        HingeInfo(Rect(0f, 928f, 2160f, 928f), HingeOrientation.HORIZONTAL, HingeState.HALF_OPENED,
            isSeparating = true, occludes = false),
    )

    val foldFlatLandscape = foldTabletop.copy(hinge = foldTabletop.hinge!!.copy(state = HingeState.FLAT))

    /** Dual-panel device with a physical, occluding gap. */
    val dualPanelBook = WindowSpec(
        2784f, 1800f, 2.5f, Insets.ZERO,
        HingeInfo(Rect(1350f, 0f, 1434f, 1800f), HingeOrientation.VERTICAL, HingeState.HALF_OPENED,
            isSeparating = true, occludes = true),
    )

    val all = listOf(candybar, candybarLandscape, foldCover, foldInner(HingeState.FLAT),
        foldInner(HingeState.HALF_OPENED), foldTabletop, foldFlatLandscape, dualPanelBook)
}
