package com.taptimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Measures the time between the person's own taps and shows it on a small overlay.
 * It never taps anything itself. A 1-pixel window watches for touches outside itself
 * (ACTION_OUTSIDE); that gives the time of each tap in any app, without any accessibility permission.
 */
class TimerService : Service() {

    companion object {
        const val ACTION_STOP = "com.taptimer.STOP"
        @Volatile
        var running = false
        private const val CHANNEL = "taptimer"
        private const val SHOWN = 8
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var watcher: View? = null
    private var panel: LinearLayout? = null
    private lateinit var big: TextView
    private lateinit var small: TextView

    private val gaps = ArrayList<Long>() // ms between taps of the current run
    private var taps = 0
    private var lastTime = 0L
    private val closeRun = Runnable { finishRun() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, notification())
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) addViews()
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(closeRun)
        finishRun()
        try { watcher?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}
        watcher = null
        panel = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tap timer", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, TimerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Tap timer is on")
            .setContentText("Tap this to open, or press Stop")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addViews() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // 1) the watcher: tiny, never takes touches, hears every touch outside itself
        val w = View(this)
        w.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onTap(e.eventTime)
            false
        }
        val wlp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        )
        wlp.gravity = Gravity.TOP or Gravity.START
        wm.addView(w, wlp)
        watcher = w

        // 2) the panel: display only, touches go straight through to the game
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14), dp(8), dp(14), dp(10))
        box.background = GradientDrawable().apply {
            setColor(Color.argb(190, 20, 20, 30))
            cornerRadius = dp(14).toFloat()
        }
        big = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            text = "Tap to start"
        }
        small = TextView(this).apply {
            setTextColor(Color.rgb(200, 210, 230))
            textSize = 15f
            typeface = Typeface.MONOSPACE
        }
        box.addView(big)
        box.addView(small)
        val plp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        when (Prefs.position(this)) {
            0 -> { plp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; plp.y = dp(70) }
            1 -> plp.gravity = Gravity.CENTER
            else -> { plp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; plp.y = dp(120) }
        }
        wm.addView(box, plp)
        panel = box
    }

    private fun onTap(t: Long) {
        main.post {
            main.removeCallbacks(closeRun)
            if (taps > 0 && t - lastTime > Prefs.gapMs(this)) finishRun()
            if (taps > 0) gaps.add(t - lastTime)
            taps++
            lastTime = t
            render()
            main.postDelayed(closeRun, Prefs.gapMs(this) + 50)
        }
    }

    private fun render() {
        if (gaps.isEmpty()) {
            big.text = "Tap 1"
            small.text = "Tap again to see the time between taps"
            return
        }
        val last = gaps.last()
        val diff = if (gaps.size >= 2) {
            val d = last - gaps[gaps.size - 2]
            "  (" + (if (d >= 0) "+" else "") + d + ")"
        } else ""
        big.text = "$last ms$diff"
        val sb = StringBuilder()
        val from = maxOf(0, gaps.size - SHOWN)
        for (i in gaps.size - 1 downTo from) {
            sb.append(String.format("%2d→%2d  %5d ms\n", i + 1, i + 2, gaps[i]))
        }
        small.text = sb.toString().trimEnd()
    }

    /** Saves the finished run (2+ taps) so it can be read again in the app. */
    private fun finishRun() {
        if (gaps.isNotEmpty()) Prefs.addRun(this, "${taps} taps: " + gaps.joinToString(" "))
        gaps.clear()
        taps = 0
        lastTime = 0L
        if (panel != null) {
            big.text = "Tap to start"
            small.text = ""
        }
    }
}
