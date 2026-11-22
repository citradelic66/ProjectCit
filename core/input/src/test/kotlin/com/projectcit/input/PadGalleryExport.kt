package com.projectcit.input

import com.projectcit.layout.HingeInfo
import com.projectcit.layout.HingeOrientation
import com.projectcit.layout.HingeState
import com.projectcit.layout.Insets
import com.projectcit.layout.LayoutEngine
import com.projectcit.layout.LayoutOptions
import com.projectcit.layout.NdsBookMode
import com.projectcit.layout.Platform
import com.projectcit.layout.Rect
import com.projectcit.layout.WindowSpec
import org.junit.Test
import java.io.File

/** Renders the real virtual pad over the real layout to build/pad-gallery/index.html for visual review. */
class PadGalleryExport {
    @Test fun export() {
        val d = 2.625f
        val inner = WindowSpec(1856f, 2160f, d, Insets(0f, 96f, 0f, 63f),
            HingeInfo(Rect(928f, 0f, 928f, 2160f), HingeOrientation.VERTICAL, HingeState.FLAT, false, false))
        val flex = WindowSpec(2160f, 1856f, d, Insets(0f, 96f, 0f, 63f),
            HingeInfo(Rect(0f, 928f, 2160f, 928f), HingeOrientation.HORIZONTAL, HingeState.HALF_OPENED, true, false))
        val cases = listOf(
            Triple("Phone · GBA", WindowSpec(1080f, 2400f, d, Insets(0f, 128f, 0f, 63f)), LayoutOptions(Platform.GBA)),
            Triple("Cover · NDS", WindowSpec(968f, 2376f, d, Insets(0f, 110f, 0f, 63f)), LayoutOptions(Platform.NDS)),
            Triple("Landscape · GBA", WindowSpec(2400f, 1080f, d, Insets(128f, 0f, 63f, 0f)), LayoutOptions(Platform.GBA)),
            Triple("Fold flat · GBA", inner, LayoutOptions(Platform.GBA)),
            Triple("Fold flat · NDS book", inner, LayoutOptions(Platform.NDS)),
            Triple("Fold flat · NDS hybrid", inner, LayoutOptions(Platform.NDS, NdsBookMode.HYBRID)),
            Triple("Flex · GBA SP", flex, LayoutOptions(Platform.GBA)),
            Triple("Flex · NDS clamshell", flex, LayoutOptions(Platform.NDS)),
        )
        val figs = cases.joinToString("") { (title, w, o) ->
            val spec = LayoutEngine.compute(w, o)
            val pad = VirtualPadBuilder.build(spec, o.platform, d)
            val sb = StringBuilder()
            spec.screens.forEach { s -> sb.append("<rect x='${s.rect.left}' y='${s.rect.top}' width='${s.rect.width}' height='${s.rect.height}' fill='#234' stroke='#7fd1ff' stroke-width='4'/>") }
            spec.hinge?.let { h -> sb.append("<rect x='${h.bounds.left - 3}' y='${h.bounds.top - 3}' width='${h.bounds.width + 6}' height='${h.bounds.height + 6}' fill='#f44'/>") }
            fun txt(x: Float, y: Float, t: String, size: Float) = sb.append("<text x='$x' y='$y' fill='#fff' font-size='$size' font-family='sans-serif' text-anchor='middle' dominant-baseline='middle'>$t</text>")
            pad.forEach { e ->
                when (e) {
                    is PadElement.DPad -> {
                        val a = e.r * 0.34f
                        sb.append("<path d='M${e.cx - a},${e.cy - e.r}h${2 * a}v${e.r - a}h${e.r - a}v${2 * a}h${-(e.r - a)}v${e.r - a}h${-2 * a}v${-(e.r - a)}h${-(e.r - a)}v${-2 * a}h${e.r - a}z' fill='rgba(255,255,255,.25)' stroke='#fff' stroke-width='3'/>")
                    }
                    is PadElement.Face -> e.buttons.forEach { b ->
                        sb.append("<circle cx='${b.cx}' cy='${b.cy}' r='${b.r}' fill='rgba(255,255,255,.25)' stroke='#fff' stroke-width='3'/>"); txt(b.cx, b.cy, b.button.label, b.r)
                    }
                    is PadElement.Key -> {
                        val r = e.bounds
                        sb.append("<rect x='${r.left}' y='${r.top}' width='${r.width}' height='${r.height}' rx='${r.height / 2}' fill='rgba(255,255,255,.25)' stroke='#fff' stroke-width='3'/>"); txt(r.centerX, r.centerY, e.button.label, r.height * 0.45f)
                    }
                }
            }
            "<figure><svg viewBox='0 0 ${w.widthPx.toInt()} ${w.heightPx.toInt()}'>$sb</svg><figcaption>$title</figcaption></figure>"
        }
        File("build/pad-gallery").apply { mkdirs() }.resolve("index.html").writeText(
            """<!doctype html><meta charset="utf-8"><style>body{margin:0;padding:16px;background:#111;color:#ddd;font:13px system-ui;box-sizing:border-box}
            main{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;max-width:980px}figure{margin:0}
            svg{width:100%;height:auto;max-height:360px;background:#000;border:1px solid #333}figcaption{text-align:center}</style><main>$figs</main>""",
        )
    }
}
