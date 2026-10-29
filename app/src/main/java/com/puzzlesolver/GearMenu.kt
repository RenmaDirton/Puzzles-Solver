package com.puzzlesolver

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The gear menu: the routine the player builds (what to do whenever the board is gone while Auto is on)
 * and a few utilities. Drawn as an overlay window.
 */
class GearMenu(private val ctx: Context, private val wm: WindowManager, private val host: Host) {

    interface Host {
        fun testRoutine()
        fun snapNow()
        fun logText(): String
    }

    private var root: View? = null
    private var picker: View? = null
    private var steps: MutableList<Step> = ArrayList()
    private var mode = 0 // 0 main, 1 word list, 2 log
    private val limits = intArrayOf(0, 3, 5, 10, 25, 50, 100)

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    val isOpen: Boolean get() = root != null

    fun toggle() {
        if (root != null) hide() else show()
    }

    fun show() {
        hide()
        steps = Routine.load(ctx)
        mode = 0
        val sv = ScrollView(ctx)
        sv.setBackgroundColor(Color.argb(240, 28, 28, 36))
        val dm = ctx.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.94f).toInt(),
            (dm.heightPixels * 0.78f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.CENTER
        wm.addView(sv, lp)
        root = sv
        render()
    }

    fun hide() {
        picker?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        picker = null
        root?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        root = null
    }

    // ------------------------------------------------------------------ drawing

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
    }

    private fun btn(label: String, color: Int, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(color)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float = 13f, color: Int = Color.WHITE) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            p.setMargins(dp(3), dp(3), dp(3), dp(3))
            r.addView(v, p)
        }
        return r
    }

    private fun render() {
        val sv = root as? ScrollView ?: return
        sv.removeAllViews()
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(10), dp(12), dp(12))
        sv.addView(col)
        when (mode) {
            1 -> renderWords(col)
            2 -> renderLog(col)
            else -> renderMain(col)
        }
    }

    private val BLUE = Color.parseColor("#1565C0")
    private val GREEN = Color.parseColor("#2E7D32")
    private val ORANGE = Color.parseColor("#EF6C00")
    private val GREY = Color.parseColor("#455A64")
    private val RED = Color.parseColor("#C62828")
    private val PURPLE = Color.parseColor("#6A1B9A")
    private val TEAL = Color.parseColor("#00838F")

    private fun renderMain(col: LinearLayout) {
        col.addView(label("Gear menu", 18f))
        col.addView(label("Routine: these steps run, in order, again and again whenever the game board is gone " +
            "(level finished, ads...) while Auto is on. When the board comes back, Auto carries on.", 12f, Color.LTGRAY))

        if (steps.isEmpty()) col.addView(label("(no steps yet)", 13f, Color.GRAY))
        steps.forEachIndexed { i, s ->
            val t = label("${i + 1}. ${Routine.describe(s)}", 13f)
            val up = btn("▲", GREY) { if (i > 0) { steps.add(i - 1, steps.removeAt(i)); save(); render() } }
            val down = btn("▼", GREY) { if (i < steps.size - 1) { steps.add(i + 1, steps.removeAt(i)); save(); render() } }
            val del = btn("✕", RED) { steps.removeAt(i); save(); render() }
            val r = LinearLayout(ctx)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            for (b in listOf(up, down, del)) {
                val p = LinearLayout.LayoutParams(dp(44), dp(38))
                p.setMargins(dp(2), 0, dp(2), 0)
                r.addView(b, p)
            }
            col.addView(r)
        }

        col.addView(label("Add a step", 12f, Color.LTGRAY))
        col.addView(row(
            btn("+ Press a word", BLUE) { mode = 1; render() },
            btn("+ Tap a spot", BLUE) { pickSpot() },
        ))
        col.addView(row(
            btn("+ Wait 1 s", PURPLE) { add(Step('W', secs = 1)) },
            btn("+ Wait 3 s", PURPLE) { add(Step('W', secs = 3)) },
            btn("+ Wait 5 s", PURPLE) { add(Step('W', secs = 5)) },
            btn("+ Back", PURPLE) { add(Step('B')) },
        ))
        col.addView(row(
            btn("Suggested routine", GREEN) { steps.clear(); suggested().forEach { steps.add(it) }; save(); render() },
            btn("Clear all", RED) { steps.clear(); save(); render() },
        ))

        col.addView(label("Utilities", 12f, Color.LTGRAY))
        val lim = Routine.levelLimit(ctx)
        col.addView(row(
            btn("Test routine once", ORANGE) { hide(); host.testRoutine() },
            btn("Stop after: " + (if (lim == 0) "never" else "$lim levels"), TEAL) {
                val next = limits[(limits.indexOf(lim).coerceAtLeast(0) + 1) % limits.size]
                Routine.saveLevelLimit(ctx, next)
                render()
            },
        ))
        col.addView(row(
            btn("Snap picture", TEAL) { host.snapNow() },
            btn("Show log", TEAL) { mode = 2; render() },
            btn("Stuck pictures: " + (if (Routine.autoSnap(ctx)) "ON" else "OFF"), GREY) { Routine.setAutoSnap(ctx, !Routine.autoSnap(ctx)); render() },
        ))
        col.addView(row(btn("Close menu", GREY) { hide() }))
    }

    private fun suggested() = listOf(
        Step('X', text = "Continue"),
        Step('X', text = "Next Level"),
        Step('X', text = "Skip"),
        Step('X', text = "Close"),
        Step('W', secs = 2),
    )

    private fun renderWords(col: LinearLayout) {
        col.addView(label("Press which word?", 18f))
        col.addView(label("Auto looks for this word on the screen and presses it. If the word is not there it just moves on. " +
            "Add your own words on the app's start screen.", 12f, Color.LTGRAY))
        val words = Routine.PRESET_WORDS + Routine.customWords(ctx)
        var i = 0
        while (i < words.size) {
            val chunk = words.subList(i, minOf(i + 3, words.size))
            val views = chunk.map { w -> btn(w, BLUE) { add(Step('X', text = w)); mode = 0; render() } }.toMutableList<View>()
            while (views.size < 3) views.add(View(ctx))
            col.addView(row(*views.toTypedArray()))
            i += 3
        }
        col.addView(row(btn("Back", GREY) { mode = 0; render() }))
    }

    private fun renderLog(col: LinearLayout) {
        col.addView(label("Auto log", 18f))
        col.addView(label(host.logText(), 11f, Color.LTGRAY))
        col.addView(row(btn("Back", GREY) { mode = 0; render() }))
    }

    private fun add(s: Step) {
        steps.add(s)
        save()
        render()
    }

    private fun save() = Routine.save(ctx, steps)

    // ------------------------------------------------------------------ spot picker

    private fun pickSpot() {
        root?.visibility = View.GONE
        val real = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(real)
        val v = FrameLayout(ctx)
        v.setBackgroundColor(Color.argb(110, 0, 0, 0))
        val tv = label("Tap exactly where the button is.\n(The game does not get this tap.)", 16f)
        tv.gravity = Gravity.CENTER
        v.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).also {
            it.topMargin = dp(80)
        })
        val cancel = btn("Cancel", RED) { closePicker() }
        v.addView(cancel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also {
            it.bottomMargin = dp(60)
        })
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val fx = (e.rawX / real.widthPixels).coerceIn(0f, 1f)
                val fy = (e.rawY / real.heightPixels).coerceIn(0f, 1f)
                closePicker()
                add(Step('T', x = fx, y = fy))
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
        picker = v
    }

    private fun closePicker() {
        picker?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        picker = null
        root?.visibility = View.VISIBLE
    }
}
