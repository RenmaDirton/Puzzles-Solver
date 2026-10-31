package com.taptimer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var runsText: TextView
    private lateinit var info: TextView
    private lateinit var gapBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad)

        fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).also {
            it.text = s
            it.textSize = size
            if (bold) it.setTypeface(null, android.graphics.Typeface.BOLD)
            col.addView(it)
        }
        fun button(s: String, f: () -> Unit) = Button(this).also {
            it.text = s
            it.isAllCaps = false
            it.setOnClickListener { f() }
            col.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        text("Tap timer", 26f, true)
        text("Shows the time between YOUR taps on top of any game, so you can learn the rhythm. It never taps for you.\n")
        info = text("")
        button("1. Allow drawing over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("2. Start") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            if (!Settings.canDrawOverlays(this)) {
                refresh("Allow drawing over other apps first (button 1).")
            } else {
                startForegroundService(Intent(this, TimerService::class.java))
                refresh("On. Open your game and tap.")
            }
        }
        button("Stop") {
            startService(Intent(this, TimerService::class.java).setAction(TimerService.ACTION_STOP))
            refresh("Stopped.")
        }

        text("\nWhere the numbers show", 16f, true)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        listOf("Top", "Middle", "Bottom").forEachIndexed { i, n ->
            row.addView(Button(this).also {
                it.text = n
                it.isAllCaps = false
                it.setOnClickListener {
                    Prefs.setPosition(this, i)
                    refresh("Position: $n. Press Stop, then Start to apply.")
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        col.addView(row)

        text("\nA new run starts after this many seconds without a tap", 14f)
        gapBox = EditText(this)
        gapBox.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        gapBox.setText((Prefs.gapMs(this) / 1000.0).toString())
        col.addView(gapBox)
        button("Save seconds") {
            val s = gapBox.text.toString().toDoubleOrNull()
            if (s != null && s in 0.5..60.0) {
                Prefs.setGapMs(this, (s * 1000).toLong())
                refresh("Saved. Press Stop, then Start to apply.")
            } else refresh("Type a number between 0.5 and 60.")
        }

        text("\nLast runs (ms between taps, oldest first)", 16f, true)
        runsText = text("", 14f)
        runsText.typeface = android.graphics.Typeface.MONOSPACE
        button("Clear runs") { Prefs.clearRuns(this); refresh("Cleared.") }

        setContentView(ScrollView(this).also { it.addView(col) })
        refresh("")
    }

    override fun onResume() {
        super.onResume()
        refresh("")
    }

    private fun refresh(msg: String) {
        val perm = if (Settings.canDrawOverlays(this)) "Overlay permission: yes" else "Overlay permission: NO"
        val on = if (TimerService.running) "Timer: ON" else "Timer: off"
        info.text = (if (msg.isEmpty()) "" else "$msg\n") + "$perm · $on"
        val runs = Prefs.runs(this)
        runsText.text = if (runs.isEmpty()) "(none yet)" else runs.joinToString("\n\n")
    }
}
