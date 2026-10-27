package com.puzzlesolver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class OverlayService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.puzzlesolver.STOP"
        private const val CHANNEL = "puzzle_solver"
        private const val TAP_GAP_MS = 110L
        private val ROUND_DELAYS = longArrayOf(600L, 900L, 1400L)
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val auto = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var latest: Image? = null
    private var screenW = 0
    private var screenH = 0

    private var panel: LinearLayout? = null
    private var markerView: MarkerView? = null
    private lateinit var status: TextView
    private lateinit var autoBtn: Button
    private lateinit var speedBtn: Button

    private var plan: List<Arrow> = emptyList()
    private var markers: List<Marker> = emptyList()
    private var autoRunning = false
    private var speedIdx = 1
    private var scanning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        startCapture(proj)
        addMarkerWindow()
        addPanelWindow()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Puzzle solver", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = android.app.PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Puzzle solver is running")
            .setContentText("Tap Stop to close the overlay")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(0, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ------------------------------------------------------------------ capture

    private fun startCapture(proj: MediaProjection) {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                synchronized(lock) {
                    latest?.close()
                    latest = img
                }
            }
        }, main)
        reader = r
        display = proj.createVirtualDisplay(
            "puzzle-solver", screenW, screenH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main,
        )
    }

    private fun grabBitmap(): Bitmap? {
        synchronized(lock) {
            val img = latest ?: return null
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val padded = rowStride / pixelStride
            val full = Bitmap.createBitmap(padded, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            return if (padded == img.width) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        }
    }

    // ------------------------------------------------------------------ windows

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun addMarkerWindow() {
        val v = MarkerView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
        markerView = v
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addPanelWindow() {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(225, 40, 30, 20))
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "Open a level, then press Scan"
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { onClick() }
        }

        val scan = btn("Scan") { scan() }
        autoBtn = btn("Auto") { toggleAuto() }
        speedBtn = btn("Speed 2") { cycleSpeed() }
        val close = btn("X") { stopSelf() }
        for (b in listOf(scan, autoBtn, speedBtn, close)) row.addView(b)

        p.addView(status)
        p.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.x = dp(8)
        lp.y = dp(24)
        wm.addView(p, lp)
        panel = p
    }

    // ------------------------------------------------------------------ actions

    private fun scan() {
        if (scanning) return
        stopAuto()
        scanning = true
        status.text = "Scanning..."
        markerView?.visibility = View.INVISIBLE
        panel?.visibility = View.INVISIBLE
        // give the compositor time to drop our own overlay from the captured frames
        main.postDelayed({
            val bmp = grabBitmap()
            panel?.visibility = View.VISIBLE
            if (bmp == null) {
                scanning = false
                markerView?.visibility = View.VISIBLE
                status.text = "No frame captured yet, try again"
                return@postDelayed
            }
            Thread {
                val parsed = try {
                    BoardParser.parse(bmp)
                } catch (e: Exception) {
                    null
                }
                val solved = parsed?.let { Solver.solve(it) }
                main.post { onScanDone(parsed, solved) }
            }.start()
        }, 400L)
    }

    private fun onScanDone(parsed: ParseResult?, solved: SolveResult?) {
        scanning = false
        markerView?.visibility = View.VISIBLE
        if (parsed == null || solved == null || parsed.arrows.isEmpty()) {
            plan = emptyList()
            markers = emptyList()
            markerView?.items = markers
            status.text = "Could not find a board. Open a level and try again."
            return
        }
        plan = solved.order
        markers = plan.mapIndexed { i, a -> Marker(i + 1, a.headX, a.headY, a.round) }
        markerView?.items = markers
        val warn = buildString {
            if (solved.stuck > 0) append(" | ${solved.stuck} stuck")
            if (parsed.unparsed > 0) append(" | ${parsed.unparsed} unread")
        }
        status.text = "${parsed.arrows.size} arrows, ${solved.rounds} rounds$warn"
    }

    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % ROUND_DELAYS.size
        speedBtn.text = "Speed ${speedIdx + 1}"
    }

    private fun toggleAuto() {
        if (autoRunning) stopAuto() else startAuto()
    }

    private fun startAuto() {
        if (plan.isEmpty()) {
            status.text = "Press Scan first"
            return
        }
        val svc = AutoTapService.instance
        if (svc == null) {
            status.text = "Enable the auto-tap accessibility service first"
            return
        }
        autoRunning = true
        autoBtn.text = "Stop"
        var t = 150L
        val roundDelay = ROUND_DELAYS[speedIdx]
        val byRound = plan.indices.groupBy { plan[it].round }.toSortedMap()
        for ((_, idxs) in byRound) {
            for ((k, i) in idxs.withIndex()) {
                val at = t + k * TAP_GAP_MS
                auto.postDelayed({
                    if (!autoRunning) return@postDelayed
                    val a = plan[i]
                    AutoTapService.instance?.tap(a.tapX, a.tapY)
                    markers.getOrNull(i)?.done = true
                    markerView?.invalidate()
                }, at)
            }
            t += idxs.size * TAP_GAP_MS + roundDelay
        }
        auto.postDelayed({
            autoRunning = false
            autoBtn.text = "Auto"
            status.text = "Done"
        }, t)
        status.text = "Auto-tapping..."
    }

    private fun stopAuto() {
        auto.removeCallbacksAndMessages(null)
        if (autoRunning) status.text = "Stopped"
        autoRunning = false
        if (::autoBtn.isInitialized) autoBtn.text = "Auto"
    }

    // ------------------------------------------------------------------ cleanup

    override fun onDestroy() {
        stopAuto()
        main.removeCallbacksAndMessages(null)
        try {
            markerView?.let { wm.removeView(it) }
            panel?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        markerView = null
        panel = null
        display?.release()
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        reader?.close()
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
