package com.projectcit.layout

import kotlin.math.max
import kotlin.math.min

/**
 * Pure function: (window, options) → where every screen quad and control cluster goes.
 * No Android types, so every posture/platform combination is covered by JVM tests.
 */
object LayoutEngine {

    /** Visual gap kept on each side of a fold seam (dp). Zero-width seams still crease. */
    const val SEAM_PADDING_DP = 12f
    const val MARGIN_DP = 8f
    const val DS_SCREEN_GAP_DP = 6f
    const val MIN_RAIL_DP = 132f

    const val TOOLBAR_BUTTON_DP = 44f
    const val TOOLBAR_GAP_DP = 6f

    fun compute(window: WindowSpec, options: LayoutOptions): LayoutSpec {
        val base = layout(window, options)
        if (options.toolbarButtons <= 0) return base
        val d = window.density
        val w = (options.toolbarButtons * TOOLBAR_BUTTON_DP + (options.toolbarButtons - 1) * TOOLBAR_GAP_DP + 8f) * d
        val h = (TOOLBAR_BUTTON_DP + 8f) * d
        // 1) A free corner of some pane, as a row, then as a column (e.g. beside a
        //    centred game on the unfolded Fold): costs the game nothing.
        freeCorner(base, w, h, d)?.let { return base.copy(toolbar = it) }
        freeCorner(base, h, w, d)?.let { return base.copy(toolbar = it) }
        // 2) Otherwise reserve a strip along the top and lay out again below it.
        val reserved = window.copy(insets = window.insets.copy(top = window.insets.top + h))
        val spec = layout(reserved, options)
        val stripPane = spec.panes.maxByOrNull { it.right } ?: spec.content
        val right = stripPane.right
        val top = window.insets.top
        return spec.copy(toolbar = Rect(max(stripPane.left, right - w), top, right, top + h), content = base.content)
    }

    private fun layout(window: WindowSpec, options: LayoutOptions): LayoutSpec {
        val posture = PostureClassifier.classify(window)
        val ctx = Ctx(window, options)
        val spec = when (posture) {
            Posture.PORTRAIT -> ctx.portrait(posture)
            Posture.LANDSCAPE -> ctx.landscape(posture, ctx.content)
            Posture.BOOK_FLAT, Posture.BOOK_HALF_OPENED -> ctx.book(posture)
            Posture.TABLETOP -> ctx.tabletop(posture)
        }
        // Game Boy / Color have no shoulder buttons: don't reserve zones for them.
        if (options.platform == Platform.GB || options.platform == Platform.GBC) {
            return spec.copy(controls = spec.controls.filter { it.cluster != ControlCluster.SHOULDER_L && it.cluster != ControlCluster.SHOULDER_R })
        }
        return spec
    }

    /** Corners of each pane (top-right first: where people look for a menu). */
    private fun freeCorner(spec: LayoutSpec, w: Float, h: Float, d: Float): Rect? {
        val m = 4f * d
        val seamPad = SEAM_PADDING_DP * d
        val seam = spec.hinge?.let { hg ->
            Rect(hg.bounds.left - seamPad, hg.bounds.top - seamPad, hg.bounds.right + seamPad, hg.bounds.bottom + seamPad)
        }
        val blockers = spec.screens.map { it.rect } + spec.controls.map { it.rect } + listOfNotNull(seam)
        val panes = spec.panes.sortedByDescending { it.right }
        for (p in panes) {
            val candidates = listOf(
                Rect(p.right - w - m, p.top + m, p.right - m, p.top + m + h),        // top-right
                Rect(p.left + m, p.top + m, p.left + m + w, p.top + m + h),          // top-left
                Rect(p.right - w - m, p.bottom - h - m, p.right - m, p.bottom - m),  // bottom-right
                Rect(p.left + m, p.bottom - h - m, p.left + m + w, p.bottom - m),    // bottom-left
            )
            candidates.firstOrNull { c -> p.contains(c) && blockers.none { it.intersects(c) } }?.let { return it }
        }
        return null
    }

    private class Ctx(val window: WindowSpec, val o: LayoutOptions) {
        val d = window.density
        val margin = MARGIN_DP * d
        val gap = DS_SCREEN_GAP_DP * d
        val seam = SEAM_PADDING_DP * d
        val touch = !o.physicalController
        val p = o.platform
        val content = Rect(
            window.insets.left, window.insets.top,
            window.widthPx - window.insets.right, window.heightPx - window.insets.bottom,
        )

        val srcW = o.sourceWidth
        val srcH = o.sourceHeight

        fun fit(c: Rect, h: Align = Align.CENTER, v: Align = Align.CENTER) =
            fitAspect(c, srcW, srcH, o.integerScaling, h, v)

        // ---- screen role helpers -------------------------------------------------
        fun main(r: Rect) = ScreenViewport(ScreenRole.MAIN, r, 0f, 1f)
        fun ds(role: ScreenRole, r: Rect) =
            if (role == ScreenRole.TOP) ScreenViewport(role, r, 0f, 0.5f)
            else ScreenViewport(role, r, 0.5f, 1f)

        /** Role shown in the "upper/left/primary" slot. */
        val primary get() = if (o.swapScreens) ScreenRole.BOTTOM else ScreenRole.TOP
        val secondary get() = if (o.swapScreens) ScreenRole.TOP else ScreenRole.BOTTOM

        fun isLarge(role: ScreenRole) = when (o.ndsEmphasis) {
            NdsEmphasis.BALANCED -> null
            NdsEmphasis.TOP_LARGE -> role == ScreenRole.TOP
            NdsEmphasis.BOTTOM_LARGE -> role == ScreenRole.BOTTOM
        }

        // ---- PORTRAIT (candybar / cover screen) ------------------------------------
        fun portrait(posture: Posture): LayoutSpec {
            val area = content.inset(margin, margin, margin, 0f)
            val screens = mutableListOf<ScreenViewport>()
            val screensBottom: Float
            if (!p.isDualScreen) {
                val maxH = if (touch) area.height * 0.55f else area.height
                val r = fit(Rect(area.left, area.top, area.right, area.top + maxH), v = Align.START)
                screens += main(r)
                screensBottom = r.bottom
            } else {
                val maxH = (if (touch) area.height * 0.64f else area.height) - gap
                val unitH = area.width * srcH / srcW
                // weights: large=1.0, small=0.55 when emphasised; equal otherwise.
                val wFirst = when (isLarge(primary)) { null -> 1f; true -> 1f; false -> 0.55f }
                val wSecond = when (isLarge(secondary)) { null -> 1f; true -> 1f; false -> 0.55f }
                val scale = min(1f, maxH / (unitH * (wFirst + wSecond)))
                val h1 = unitH * wFirst * scale
                val h2 = unitH * wSecond * scale
                val r1 = fit(Rect(area.left, area.top, area.right, area.top + h1), v = Align.END)
                val r2 = fit(Rect(area.left, r1.bottom + gap, area.right, r1.bottom + gap + h2), v = Align.START)
                screens += ds(primary, r1)
                screens += ds(secondary, r2)
                screensBottom = r2.bottom
            }
            val controls = if (touch) {
                deck(Rect(content.left, screensBottom + margin, content.right, content.bottom))
            } else emptyList()
            return LayoutSpec(posture, content, listOf(content), null, screens, controls)
        }

        // ---- LANDSCAPE (phone sideways, tablets, flat fold w/ horizontal seam) ------
        fun landscape(posture: Posture, area: Rect, hinge: HingeInfo? = null): LayoutSpec {
            val rail = if (touch) railWidth(area.width) else 0f
            val center = area.inset(rail + margin, margin, rail + margin, margin)
            val screens = if (!p.isDualScreen) {
                listOf(main(fit(center)))
            } else {
                sideBySide(center)
            }
            val controls = if (touch) rails(
                Rect(area.left, area.top, area.left + rail, area.bottom),
                Rect(area.right - rail, area.top, area.right, area.bottom),
            ) else emptyList()
            return LayoutSpec(posture, content, listOf(area), hinge, screens, controls)
        }

        fun sideBySide(center: Rect): List<ScreenViewport> {
            val wFirst = when (isLarge(primary)) { null -> 1f; true -> 2f; false -> 1f }
            val wSecond = when (isLarge(secondary)) { null -> 1f; true -> 2f; false -> 1f }
            val split = center.left + (center.width - gap) * wFirst / (wFirst + wSecond)
            return listOf(
                ds(primary, fit(Rect(center.left, center.top, split, center.bottom), h = Align.END)),
                ds(secondary, fit(Rect(split + gap, center.top, center.right, center.bottom), h = Align.START)),
            )
        }

        // ---- BOOK (vertical seam) --------------------------------------------------
        fun book(posture: Posture): LayoutSpec {
            val hinge = window.hinge!!
            val left = Rect(content.left, content.top, min(hinge.bounds.left - seam, content.right), content.bottom)
            val right = Rect(max(hinge.bounds.right + seam, content.left), content.top, content.right, content.bottom)
            val panes = listOf(left, right)

            if (!p.isDualScreen) {
                return if (posture == Posture.BOOK_FLAT) {
                    // Flat: the crease is cosmetic, keep the game centred on the full width.
                    bookFlatSingle(posture, hinge, left, right)
                } else {
                    // Half-open: never draw across an angled seam. Game left, deck right.
                    val screen = fit(left.inset(margin))
                    val controls = if (touch) deck(right.inset(margin)) else emptyList()
                    LayoutSpec(posture, content, panes, hinge, listOf(main(screen)), controls)
                }
            }

            return when (o.ndsBookMode) {
                NdsBookMode.SIDE_BY_SIDE -> {
                    val screenH = if (touch) 0.52f else 1f
                    val s1 = fit(left.inset(margin).rows(0f, screenH))
                    val s2 = fit(right.inset(margin).rows(0f, screenH))
                    // Align both quads to the same baseline so they read as one device.
                    val top = max(s1.top, s2.top)
                    val a = s1.copy(top = top, bottom = top + s1.height)
                    val b = s2.copy(top = top, bottom = top + s2.height)
                    val controls = if (touch) {
                        val below = max(a.bottom, b.bottom) + margin
                        leftRail(Rect(left.left, below, left.right, left.bottom)) +
                            rightRail(Rect(right.left, below, right.right, right.bottom))
                    } else emptyList()
                    LayoutSpec(posture, content, panes, hinge, listOf(ds(primary, a), ds(secondary, b)), controls)
                }
                NdsBookMode.HYBRID -> {
                    val big = fit(left.inset(margin))
                    val rp = right.inset(margin)
                    val small = if (touch) {
                        fit(Rect(rp.left + rp.width * 0.075f, rp.top, rp.right - rp.width * 0.075f, rp.top + rp.height * 0.38f), v = Align.START)
                    } else fit(rp.cols(0.075f, 0.925f))
                    val controls = if (touch) deck(Rect(right.left, small.bottom + margin, right.right, right.bottom)) else emptyList()
                    LayoutSpec(posture, content, panes, hinge, listOf(ds(primary, big), ds(secondary, small)), controls)
                }
            }
        }

        fun bookFlatSingle(posture: Posture, hinge: HingeInfo, left: Rect, right: Rect): LayoutSpec {
            val panes = listOf(left, right)
            if (!touch) return LayoutSpec(posture, content, panes, hinge, listOf(main(fit(content.inset(margin)))), emptyList())
            if (content.width >= content.height * 1.2f) {
                return landscape(posture, content, hinge).copy(panes = panes)
            }
            // Near-square inner display: full-width game up top, thumb clusters in the
            // lower corners, one per pane so nothing sits on the crease.
            val area = content.inset(margin)
            val screen = fit(area.rows(0f, 0.6f), v = Align.CENTER)
            val below = screen.bottom + margin
            val controls = leftRail(Rect(left.left, below, left.right, left.bottom)) +
                rightRail(Rect(right.left, below, right.right, right.bottom))
            return LayoutSpec(posture, content, panes, hinge, listOf(main(screen)), controls)
        }

        // ---- TABLETOP / Flex mode (horizontal seam, half open) --------------------
        fun tabletop(posture: Posture): LayoutSpec {
            val hinge = window.hinge!!
            val top = Rect(content.left, content.top, content.right, min(hinge.bounds.top - seam, content.bottom))
            val bottom = Rect(content.left, max(hinge.bounds.bottom + seam, content.top), content.right, content.bottom)
            val panes = listOf(top, bottom)
            if (!p.isDualScreen) {
                val screen = fit(top.inset(margin))
                val controls = if (touch) deck(bottom.inset(margin, 0f, margin, margin)) else emptyList()
                return LayoutSpec(posture, content, panes, hinge, listOf(main(screen)), controls)
            }
            // True clamshell: DS top screen above the seam, touchscreen below with
            // controls flanking it like the hardware.
            val upper = fit(top.inset(margin))
            val rail = if (touch) railWidth(bottom.width) else 0f
            val lower = fit(bottom.inset(rail + margin, 0f, rail + margin, margin), v = Align.START)
            val controls = if (touch) rails(
                Rect(bottom.left, bottom.top, bottom.left + rail, bottom.bottom),
                Rect(bottom.right - rail, bottom.top, bottom.right, bottom.bottom),
            ) else emptyList()
            return LayoutSpec(posture, content, panes, hinge, listOf(ds(primary, upper), ds(secondary, lower)), controls)
        }

        // ---- control zone builders ---------------------------------------------
        fun railWidth(available: Float): Float =
            min(max(MIN_RAIL_DP * d, available * 0.18f), available * 0.26f)

        fun rails(left: Rect, right: Rect) = leftRail(left) + rightRail(right)

        fun leftRail(r: Rect) = listOf(
            ControlZone(ControlCluster.SHOULDER_L, r.rows(0f, 0.16f)),
            ControlZone(ControlCluster.DPAD, r.rows(0.2f, 0.8f)),
            ControlZone(ControlCluster.SELECT, r.rows(0.84f, 1f).cols(0.2f, 0.8f)),
        )

        fun rightRail(r: Rect) = listOf(
            ControlZone(ControlCluster.SHOULDER_R, r.rows(0f, 0.16f)),
            ControlZone(ControlCluster.FACE_BUTTONS, r.rows(0.2f, 0.8f)),
            ControlZone(ControlCluster.START, r.rows(0.84f, 1f).cols(0.2f, 0.8f)),
        )

        /** Horizontal control deck (GBA SP style) used below the screen. */
        fun deck(r: Rect): List<ControlZone> {
            if (r.isEmpty) return emptyList()
            return listOf(
                ControlZone(ControlCluster.SHOULDER_L, r.rows(0f, 0.16f).cols(0f, 0.34f)),
                ControlZone(ControlCluster.SHOULDER_R, r.rows(0f, 0.16f).cols(0.66f, 1f)),
                ControlZone(ControlCluster.DPAD, r.rows(0.2f, 0.82f).cols(0f, 0.48f)),
                ControlZone(ControlCluster.FACE_BUTTONS, r.rows(0.2f, 0.82f).cols(0.52f, 1f)),
                ControlZone(ControlCluster.SELECT, r.rows(0.86f, 1f).cols(0.26f, 0.48f)),
                ControlZone(ControlCluster.START, r.rows(0.86f, 1f).cols(0.52f, 0.74f)),
            )
        }
    }
}
