package com.projectcit.emulator.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.projectcit.input.PadButton
import com.projectcit.input.PadElement

/** Draws the virtual pad; pressed buttons light up. Purely visual: touches are handled in SessionScreen. */
@Composable
fun VirtualPadOverlay(elements: List<PadElement>, pressedMask: Int, opacity: Float, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val idle = Color.White.copy(alpha = 0.22f * opacity / 0.55f)
        val lit = Color.White.copy(alpha = (0.6f * opacity / 0.55f).coerceAtMost(0.85f))
        val outline = Color.White.copy(alpha = (0.7f * opacity / 0.55f).coerceAtMost(0.9f))
        fun on(b: PadButton) = pressedMask and b.bit != 0
        for (e in elements) when (e) {
            is PadElement.DPad -> drawDpad(e, ::on, idle, lit, outline)
            is PadElement.Face -> e.buttons.forEach { b ->
                val c = Offset(b.cx, b.cy)
                drawCircle(if (on(b.button)) lit else idle, b.r, c)
                drawCircle(outline, b.r, c, style = Stroke(2.5f))
                label(tm, b.button.label, c, b.r * 0.9f)
            }
            is PadElement.Key -> {
                val r = e.bounds
                val radius = CornerRadius(r.height / 2, r.height / 2)
                drawRoundRect(if (on(e.button)) lit else idle, Offset(r.left, r.top), Size(r.width, r.height), radius)
                drawRoundRect(outline, Offset(r.left, r.top), Size(r.width, r.height), radius, style = Stroke(2.5f))
                label(tm, e.button.label, Offset(r.centerX, r.centerY), r.height * 0.42f)
            }
        }
    }
}

private fun DrawScope.drawDpad(d: PadElement.DPad, on: (PadButton) -> Boolean, idle: Color, lit: Color, outline: Color) {
    val a = d.r * 0.34f
    val cross = Path().apply {
        moveTo(d.cx - a, d.cy - d.r); lineTo(d.cx + a, d.cy - d.r); lineTo(d.cx + a, d.cy - a)
        lineTo(d.cx + d.r, d.cy - a); lineTo(d.cx + d.r, d.cy + a); lineTo(d.cx + a, d.cy + a)
        lineTo(d.cx + a, d.cy + d.r); lineTo(d.cx - a, d.cy + d.r); lineTo(d.cx - a, d.cy + a)
        lineTo(d.cx - d.r, d.cy + a); lineTo(d.cx - d.r, d.cy - a); lineTo(d.cx - a, d.cy - a); close()
    }
    drawPath(cross, idle)
    // Light the pressed arm(s).
    val arm = d.r - a
    if (on(PadButton.UP)) drawRect(lit, Offset(d.cx - a, d.cy - d.r), Size(2 * a, arm))
    if (on(PadButton.DOWN)) drawRect(lit, Offset(d.cx - a, d.cy + a), Size(2 * a, arm))
    if (on(PadButton.LEFT)) drawRect(lit, Offset(d.cx - d.r, d.cy - a), Size(arm, 2 * a))
    if (on(PadButton.RIGHT)) drawRect(lit, Offset(d.cx + a, d.cy - a), Size(arm, 2 * a))
    drawPath(cross, outline, style = Stroke(2.5f))
}

private fun DrawScope.label(tm: TextMeasurer, text: String, center: Offset, heightPx: Float) {
    val style = TextStyle(color = Color.White.copy(alpha = 0.9f), fontSize = (heightPx / density / 1.5f).coerceIn(8f, 22f).sp, fontWeight = FontWeight.Bold)
    val l = tm.measure(text, style)
    drawText(l, topLeft = Offset(center.x - l.size.width / 2f, center.y - l.size.height / 2f))
}
