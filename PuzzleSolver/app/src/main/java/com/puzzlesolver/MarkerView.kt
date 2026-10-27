package com.puzzlesolver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

class Marker(val n: Int, val x: Float, val y: Float, val round: Int) {
    var done = false
}

/** Full-screen, touch-transparent layer that draws the tap order on top of the game. */
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
        val r = resources.displayMetrics.density * 9f
        text.textSize = r * 1.05f
        for (m in items) {
            if (m.done) continue
            // green = can be tapped right now, orange = later
            fill.color = if (m.round == 0) Color.argb(230, 30, 160, 70) else Color.argb(215, 220, 90, 20)
            canvas.drawCircle(m.x, m.y, r, fill)
            canvas.drawCircle(m.x, m.y, r, ring)
            canvas.drawText((m.n).toString(), m.x, m.y + text.textSize * 0.35f, text)
        }
    }
}
