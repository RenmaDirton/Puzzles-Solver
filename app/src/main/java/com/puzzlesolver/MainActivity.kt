package com.puzzlesolver

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val REQ_CAPTURE = 100
        const val REQ_NOTIF = 101
    }

    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "Puzzle solver 0.1"
            textSize = 26f
            setTextColor(Color.BLACK)
        })
        info = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad / 2, 0, pad)
        }
        col.addView(info)

        fun button(label: String, onClick: () -> Unit) {
            col.addView(Button(this).apply {
                text = label
                isAllCaps = false
                gravity = Gravity.CENTER
                setOnClickListener { onClick() }
            })
        }

        button("1. Allow drawing over other apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        }
        button("2. Enable auto-tap (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find \"Puzzle solver auto-tap\" and turn it on", Toast.LENGTH_LONG).show()
        }
        button("3. Start Puzzle solver") { start() }
        button("Stop") {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        }

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        val acc = AutoTapService.instance != null
        info.text = "Overlay permission: ${if (overlay) "OK" else "missing"}\n" +
            "Auto-tap service: ${if (acc) "on" else "off (optional: you can tap by hand using the numbers)"}\n\n" +
            "How to use: press Start, allow screen capture, open Amaze Go! and a level, " +
            "then press Scan on the floating panel. Numbers show the tap order " +
            "(green = can go now). Press Auto to tap them for you."
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow drawing over other apps first", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_NOTIF) start() // proceed whether or not it was granted
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra(OverlayService.EXTRA_CODE, resultCode)
                .putExtra(OverlayService.EXTRA_DATA, data)
            startForegroundService(i)
            moveTaskToBack(true)
        } else if (requestCode == REQ_CAPTURE) {
            Toast.makeText(this, "Screen capture was not allowed", Toast.LENGTH_LONG).show()
        }
    }
}
