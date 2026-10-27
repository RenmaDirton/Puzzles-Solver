package com.puzzlesolver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * n > 0 draws that number inside the dot, n == 0 draws a plain dot.
 * round: 0 = can leave now (green), > 0 = later (orange), -2 = failed arrow (red).
 */
class Marker(val n: Int, val x: Float, val y: Float, val round: Int)

/** Full-screen, touch-transparent layer that draws markers on top of the game. */
class MarkerView(context: Context) : View(context) {

    var items: List<Marker> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        text.textSize = density * 9.5f
        for (m in items) {
            fill.color = when {
                m.round == -2 -> Color.argb(240, 210, 30, 30)
                m.round == 0 -> Color.argb(230, 30, 160, 70)
                else -> Color.argb(215, 220, 90, 20)
            }
            val r = if (m.n > 0) density * 9f else density * 6f
            canvas.drawCircle(m.x, m.y, r, fill)
            canvas.drawCircle(m.x, m.y, r, ring)
            if (m.n > 0) canvas.drawText(m.n.toString(), m.x, m.y + text.textSize * 0.35f, text)
        }
    }
}
