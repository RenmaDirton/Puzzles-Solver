package com.example.ringtapper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Small control screen: enable accessibility, start/stop, tune latency and tolerance. */
class MainActivity : Activity() {

    private val REQ_CAPTURE = 100
    private val REQ_NOTIF = 101

    private lateinit var statusView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            statusView.text = buildStatus()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.load(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val scroll = ScrollView(this)
        scroll.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Ring Tapper"
            textSize = 26f
            gravity = Gravity.START
        })

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, pad)
        }
        root.addView(statusView)

        root.addView(Button(this).apply {
            text = "1. Enable accessibility service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2. Start"
            setOnClickListener { onStartClicked() }
        })
        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            }
        })

        root.addView(TextView(this).apply {
            text = "\nTuning (applies immediately)"
            textSize = 16f
        })
        addSlider(root, "Latency compensation", 0, 300, Prefs.latencyMs, "ms") { Prefs.latencyMs = it }
        addSlider(root, "Bar colour split", 15, 90, Prefs.tolerance, "") { Prefs.tolerance = it }
        addSlider(root, "Cooldown after tap", 100, 1500, Prefs.cooldownMs, "ms") { Prefs.cooldownMs = it }

        root.addView(TextView(this).apply {
            text = "\nTips: if taps come too late raise latency, too early lower it. " +
                "The app taps only the colour group that has a different colour on both " +
                "sides. The status line shows groups=N; it should be 3 (or 5, 7...). If " +
                "it is wrong, change \"Bar colour split\" (lower = more groups). When the " +
                "system asks what to share, choose \"Entire screen\"."
            textSize = 13f
        })
    }

    override fun onResume() {
        super.onResume()
        ui.post(poll)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        super.onPause()
    }

    private fun buildStatus(): String {
        val acc = if (TapAccessibilityService.instance != null) "enabled" else "NOT enabled"
        return "Accessibility: $acc\n" +
            "Service: ${if (CaptureService.running) "running" else "stopped"}\n" +
            CaptureService.status
    }

    private fun addSlider(
        root: LinearLayout, title: String, min: Int, max: Int,
        initial: Int, unit: String, onChange: (Int) -> Unit
    ) {
        val label = TextView(this).apply { text = "$title: $initial $unit" }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = initial - min
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + min
                label.text = "$title: $v $unit"
                onChange(v)
                Prefs.save(this@MainActivity)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(label)
        root.addView(bar)
    }

    private fun onStartClicked() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        } else {
            launchCapturePrompt()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) launchCapturePrompt()
    }

    private fun launchCapturePrompt() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
            startForegroundService(i)
        }
    }
}
