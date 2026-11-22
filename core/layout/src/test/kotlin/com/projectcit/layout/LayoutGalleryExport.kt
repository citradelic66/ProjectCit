package com.projectcit.layout

import org.junit.Test
import java.io.File

/**
 * Renders the engine's real output for the key device/posture cases to
 * build/layout-gallery/index.html — a quick visual review without a device.
 */
class LayoutGalleryExport {
    private data class Case(val title: String, val window: WindowSpec, val options: LayoutOptions)

    @Test fun export() {
        val nds = LayoutOptions(Platform.NDS)
        val gba = LayoutOptions(Platform.GBA)
        val cases = listOf(
            Case("Candybar · GBA", Devices.candybar, gba),
            Case("Fold cover · NDS stacked", Devices.foldCover, nds),
            Case("Fold cover · NDS touch-large", Devices.foldCover, nds.copy(ndsEmphasis = NdsEmphasis.BOTTOM_LARGE)),
            Case("Phone landscape · NDS", Devices.candybarLandscape, nds),
            Case("Fold flat · GBA", Devices.foldInner(HingeState.FLAT), gba),
            Case("Fold flat · GBA + gamepad", Devices.foldInner(HingeState.FLAT), gba.copy(physicalController = true)),
            Case("Fold flat · NDS book", Devices.foldInner(HingeState.FLAT), nds),
            Case("Fold flat · NDS hybrid", Devices.foldInner(HingeState.FLAT), nds.copy(ndsBookMode = NdsBookMode.HYBRID)),
            Case("Flex / tabletop · GBA SP", Devices.foldTabletop, gba),
            Case("Flex / tabletop · NDS clamshell", Devices.foldTabletop, nds),
        )
        val out = File("build/layout-gallery").apply { mkdirs() }
        val cards = cases.joinToString("\n") { c -> "<figure><svg viewBox=\"0 0 ${c.window.widthPx.toInt()} ${c.window.heightPx.toInt()}\">${svg(c)}</svg><figcaption>${c.title}<br><small>${LayoutEngine.compute(c.window, c.options).posture}</small></figcaption></figure>" }
        File(out, "index.html").writeText(
            """<!doctype html><meta charset="utf-8"><style>
            body{margin:0;padding:16px;background:#111;color:#ddd;font:13px system-ui;box-sizing:border-box}
            main{display:grid;grid-template-columns:repeat(5,1fr);gap:14px;max-width:980px}
            figure{margin:0} svg{width:100%;height:auto;max-height:340px;background:#000;border:1px solid #333}
            figcaption{text-align:center;margin-top:4px}</style><main>$cards</main>""",
        )
    }

    private fun svg(c: Case): String {
        val spec = LayoutEngine.compute(c.window, c.options)
        val sb = StringBuilder()
        fun rect(r: Rect, fill: String, stroke: String, extra: String = "") =
            sb.append("<rect x='${r.left}' y='${r.top}' width='${r.width}' height='${r.height}' fill='$fill' stroke='$stroke' stroke-width='6' $extra/>")
        fun text(r: Rect, t: String) =
            sb.append("<text x='${r.centerX}' y='${r.centerY}' fill='#fff' font-size='${(r.height / 4).coerceIn(28f, 64f)}' text-anchor='middle' dominant-baseline='middle' font-family='sans-serif'>$t</text>")
        val i = c.window.insets
        rect(Rect(0f, 0f, c.window.widthPx, i.top), "#222", "none")
        rect(Rect(0f, c.window.heightPx - i.bottom, c.window.widthPx, c.window.heightPx), "#222", "none")
        spec.hinge?.let { h ->
            val pad = LayoutEngine.SEAM_PADDING_DP * c.window.density
            val band = if (h.orientation == HingeOrientation.VERTICAL) Rect(h.bounds.left - pad, 0f, h.bounds.right + pad, c.window.heightPx)
                       else Rect(0f, h.bounds.top - pad, c.window.widthPx, h.bounds.bottom + pad)
            rect(band, "rgba(255,77,77,.35)", "none")
        }
        spec.screens.forEach { s ->
            val col = when (s.role) { ScreenRole.TOP -> "#7FD1FF"; ScreenRole.BOTTOM -> "#FF7FC8"; ScreenRole.MAIN -> "#9BE58B" }
            rect(s.rect, "${col}33", col); text(s.rect, s.role.name)
        }
        spec.controls.forEach { z -> rect(z.rect, "rgba(255,255,255,.18)", "rgba(255,255,255,.5)", "rx='30'"); text(z.rect, z.cluster.name.take(5)) }
        return sb.toString()
    }
}
