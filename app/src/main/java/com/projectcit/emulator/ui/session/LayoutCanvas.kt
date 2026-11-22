package com.projectcit.emulator.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.projectcit.layout.ControlCluster
import com.projectcit.layout.HingeOrientation
import com.projectcit.layout.LayoutEngine
import com.projectcit.layout.LayoutSpec
import com.projectcit.layout.Platform
import com.projectcit.layout.Rect
import com.projectcit.layout.ScreenRole

private val TopColor = Color(0xFF7FD1FF)
private val BottomColor = Color(0xFFFF7FC8)
private val MainColor = Color(0xFF9BE58B)
private val HingeColor = Color(0xFFFF4D4D)

@Composable
fun LayoutCanvas(spec: LayoutSpec, platform: Platform, overlay: Boolean, drawScreens: Boolean, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        if (overlay) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
            spec.panes.forEach { drawRect(Color(0x66FFFFFF), it.offset(), it.size(), style = Stroke(2f, pathEffect = dash)) }
            spec.hinge?.let { h ->
                val pad = LayoutEngine.SEAM_PADDING_DP * density
                val band = when (h.orientation) {
                    HingeOrientation.VERTICAL -> Rect(h.bounds.left - pad, h.bounds.top, h.bounds.right + pad, h.bounds.bottom)
                    HingeOrientation.HORIZONTAL -> Rect(h.bounds.left, h.bounds.top - pad, h.bounds.right, h.bounds.bottom + pad)
                }
                drawRect(HingeColor.copy(alpha = 0.18f), band.offset(), band.size())
                // Zero-width seams (Galaxy Z Fold) still get a visible 3px line.
                if (h.bounds.width < 1f || h.bounds.height < 1f) {
                    val (a, b) = if (h.orientation == HingeOrientation.VERTICAL)
                        Offset(h.bounds.left, h.bounds.top) to Offset(h.bounds.left, h.bounds.bottom)
                    else Offset(h.bounds.left, h.bounds.top) to Offset(h.bounds.right, h.bounds.top)
                    drawLine(HingeColor, a, b, strokeWidth = 3f)
                } else drawRect(HingeColor.copy(alpha = 0.6f), h.bounds.offset(), h.bounds.size())
                label(tm, "HINGE ${h.state} ${h.bounds}", Offset(band.left + 6f, band.top + 6f), HingeColor)
            }
        }
        spec.screens.forEach { s ->
            val color = when (s.role) { ScreenRole.TOP -> TopColor; ScreenRole.BOTTOM -> BottomColor; ScreenRole.MAIN -> MainColor }
            // While a game runs, the GL surface underneath shows the real screens;
            // only outline them (debug overlay) so the picture stays visible.
            if (!drawScreens) {
                if (overlay) drawRect(color, s.rect.offset(), s.rect.size(), style = Stroke(2f))
                return@forEach
            }
            drawRect(color.copy(alpha = 0.16f), s.rect.offset(), s.rect.size())
            drawPixelGrid(s.rect, platform, color)
            drawRect(color, s.rect.offset(), s.rect.size(), style = Stroke(3f))
            val scale = s.rect.width / platform.screenWidth
            val text = buildString {
                append(s.role.name).append(" ").append(platform.screenWidth).append('×').append(platform.screenHeight)
                append(" ×").append("%.2f".format(scale))
                if (s.acceptsTouch) append(" · touch")
            }
            label(tm, text, Offset(s.rect.left + 8f, s.rect.top + 8f), color)
        }
        spec.controls.forEach { c ->
            drawRoundRect(Color(0x33FFFFFF), c.rect.offset(), c.rect.size(), CornerRadius(24f, 24f))
            if (overlay) drawRoundRect(Color(0x88FFFFFF), c.rect.offset(), c.rect.size(), CornerRadius(24f, 24f), style = Stroke(2f))
            val name = when (c.cluster) {
                ControlCluster.DPAD -> "D-PAD"
                ControlCluster.FACE_BUTTONS -> if (platform == Platform.NDS) "A B X Y" else "A B"
                ControlCluster.SHOULDER_L -> "L"
                ControlCluster.SHOULDER_R -> "R"
                ControlCluster.SELECT -> "SELECT"
                ControlCluster.START -> "START"
            }
            val layout = tm.measure(name, TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            if (layout.size.width < c.rect.width && layout.size.height < c.rect.height) {
                drawText(layout, topLeft = Offset(c.rect.centerX - layout.size.width / 2f, c.rect.centerY - layout.size.height / 2f))
            }
        }
    }
}

/** One faint line every 16 native pixels, so the effective scale factor is visible at a glance. */
private fun DrawScope.drawPixelGrid(r: Rect, platform: Platform, color: Color) {
    val step = r.width / platform.screenWidth * 16f
    if (step < 6f) return
    val c = color.copy(alpha = 0.18f)
    var x = r.left + step
    while (x < r.right - 0.5f) { drawLine(c, Offset(x, r.top), Offset(x, r.bottom), 1f); x += step }
    var y = r.top + step
    while (y < r.bottom - 0.5f) { drawLine(c, Offset(r.left, y), Offset(r.right, y), 1f); y += step }
}

private fun DrawScope.label(tm: TextMeasurer, text: String, at: Offset, color: Color) {
    val layout = tm.measure(text, TextStyle(color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace))
    drawRect(Color(0xAA000000), at, Size(layout.size.width + 8f, layout.size.height + 4f))
    drawText(layout, topLeft = Offset(at.x + 4f, at.y + 2f))
}

private fun Rect.offset() = Offset(left, top)
private fun Rect.size() = Size(width, height)
