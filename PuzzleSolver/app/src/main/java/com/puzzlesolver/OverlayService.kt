package com.puzzlesolver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicInteger

class OverlayService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.puzzlesolver.STOP"
        private const val CHANNEL = "puzzle_solver"

        /** Gap between two taps inside one batch. */
        private const val TAP_GAP_MS = 90L

        /** How long Auto waits for a batch to slide off before it rescans (Speed 1..3). */
        private val SETTLE_MS = longArrayOf(500L, 800L, 1200L)

        /** A tapped arrow that is still on the board after this long counts as a failure. */
        private const val FAIL_AFTER_MS = 3000L
    }

    private class Pending(val cells: Set<Int>, val time: Long)

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val auto = Handler(Looper.getMainLooper())
    private val smart = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val frameSeq = AtomicInteger(0)

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var latest: Image? = null

    private var panel: LinearLayout? = null
    private var markerView: MarkerView? = null
    private var watcher: View? = null
    private lateinit var status: TextView
    private lateinit var smartBtn: Button
    private lateinit var autoBtn: Button
    private lateinit var speedBtn: Button

    private var cal: Calibration? = null
    private var scanBusy = false
    private var smartOn = false
    private var autoOn = false
    private var followUps = 0
    private var speedIdx = 1
    private val pending = ArrayList<Pending>()
    private var lastCal: Calibration? = null

    private val tick = Runnable { smartScan() }

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
        addWatcherWindow()
        addPanelWindow()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Puzzle solver", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
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
        val r = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 3)
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
                frameSeq.incrementAndGet()
            }
        }, main)
        reader = r
        display = proj.createVirtualDisplay(
            "puzzle-solver", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main,
        )
    }

    private fun grabBitmap(): Bitmap? {
        synchronized(lock) {
            val img = latest ?: return null
            val plane = img.planes[0]
            val padded = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(padded, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            return if (padded == img.width) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        }
    }

    /**
     * Captures a clean frame (our own markers hidden), reads the board and solves it.
     * The callback runs on the main thread. Only one scan runs at a time.
     */
    private fun requestScan(fullRecalibrate: Boolean, cb: (ParseResult?, SolveResult?) -> Unit) {
        if (scanBusy) {
            main.postDelayed({ requestScan(fullRecalibrate, cb) }, 150L)
            return
        }
        scanBusy = true
        if (fullRecalibrate) cal = null

        val mv = markerView
        val hadMarkers = mv != null && mv.visibility == View.VISIBLE && mv.items.isNotEmpty()
        val seq0 = frameSeq.get()
        if (hadMarkers) mv!!.visibility = View.INVISIBLE
        val started = SystemClock.uptimeMillis()

        fun grab() {
            val bmp = grabBitmap()
            markerView?.visibility = View.VISIBLE
            if (bmp == null) {
                scanBusy = false
                cb(null, null)
                return
            }
            val calUsed = cal
            Thread {
                val parsed = try {
                    BoardParser.parse(bmp, calUsed)
                } catch (e: Exception) {
                    null
                }
                val solved = parsed?.let { Solver.solve(it) }
                main.post {
                    scanBusy = false
                    if (parsed != null) cal = parsed.cal
                    cb(parsed, solved)
                }
            }.start()
        }

        fun waitFrame() {
            val fresh = !hadMarkers ||
                frameSeq.get() != seq0 ||
                SystemClock.uptimeMillis() - started > 500L
            if (!fresh) {
                main.postDelayed({ waitFrame() }, 20L)
            } else {
                main.postDelayed({ grab() }, if (hadMarkers) 60L else 0L)
            }
        }
        waitFrame()
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

    /** A 1-pixel window that is told when the person touches anything else (to trigger a rescan). */
    private fun addWatcherWindow() {
        val v = View(this)
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) onUserTouch()
            false
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(v, lp)
        watcher = v
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
            text = "Open a level, then press Smart"
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
        }

        val scan = btn("Scan") { scanFull() }
        smartBtn = btn("Smart") { toggleSmart() }
        autoBtn = btn("Auto") { toggleAuto() }
        speedBtn = btn("Speed 2") { cycleSpeed() }
        val close = btn("X") { stopSelf() }
        for (b in listOf(scan, smartBtn, autoBtn, speedBtn, close)) row.addView(b)

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

    // ------------------------------------------------------------------ display helpers

    private fun showLive(res: ParseResult, failed: Arrow? = null) {
        val list = ArrayList<Marker>()
        for (a in res.arrows) if (a.round == 0) list.add(Marker(0, a.headX, a.headY, 0))
        if (failed != null) list.add(Marker(0, failed.headX, failed.headY, -2))
        markerView?.items = list
    }

    private fun liveStatus(res: ParseResult, solved: SolveResult): String {
        val now = res.arrows.count { it.round == 0 }
        val unread = if (res.blockers.isNotEmpty()) " | ${res.blockers.size} unread" else ""
        return when {
            res.arrows.isEmpty() -> "No arrows left$unread"
            now == 0 -> "None can move, ${solved.stuck} stuck$unread"
            else -> "$now can move now | ${res.arrows.size} left$unread"
        }
    }

    // ------------------------------------------------------------------ Scan (numbered plan)

    private fun scanFull() {
        stopAuto(null)
        stopSmart()
        status.text = "Scanning..."
        requestScan(true) { res, solved ->
            if (res == null || solved == null || res.arrows.isEmpty()) {
                markerView?.items = emptyList()
                status.text = "Could not find a board. Open a level and try again."
                return@requestScan
            }
            markerView?.items = solved.order.mapIndexed { i, a -> Marker(i + 1, a.headX, a.headY, a.round) }
            val unread = if (res.blockers.isNotEmpty()) " | ${res.blockers.size} unread" else ""
            status.text = "${res.arrows.size} arrows, ${solved.rounds} rounds$unread"
        }
    }

    // ------------------------------------------------------------------ Smart scan

    private fun schedule(ms: Long) {
        smart.removeCallbacks(tick)
        smart.postDelayed(tick, ms)
    }

    private fun toggleSmart() {
        if (smartOn) {
            stopSmart()
            markerView?.items = emptyList()
            status.text = "Smart off"
        } else {
            stopAuto(null)
            smartOn = true
            smartBtn.text = "Smart ON"
            status.text = "Smart scanning..."
            schedule(0L)
        }
    }

    private fun stopSmart() {
        smartOn = false
        smart.removeCallbacksAndMessages(null)
        if (::smartBtn.isInitialized) smartBtn.text = "Smart"
    }

    private fun onUserTouch() {
        if (!smartOn || autoOn) return
        followUps = 1
        schedule(650L)
    }

    private fun smartScan() {
        if (!smartOn || autoOn) return
        requestScan(false) { res, solved ->
            if (!smartOn) return@requestScan
            if (res == null || solved == null) {
                markerView?.items = emptyList()
                status.text = "No board found"
            } else {
                showLive(res)
                status.text = liveStatus(res, solved)
            }
            if (followUps > 0) {
                followUps--
                schedule(900L)
            } else {
                schedule(3000L)
            }
        }
    }

    // ------------------------------------------------------------------ Auto (closed loop)

    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % SETTLE_MS.size
        speedBtn.text = "Speed ${speedIdx + 1}"
    }

    private fun toggleAuto() {
        if (autoOn) stopAuto("Stopped") else startAuto()
    }

    private fun startAuto() {
        if (AutoTapService.instance == null) {
            status.text = "Enable the auto-tap accessibility service first"
            return
        }
        stopSmart()
        autoOn = true
        autoBtn.text = "Stop"
        pending.clear()
        lastCal = null
        status.text = "Auto: scanning..."
        autoStep()
    }

    private fun stopAuto(msg: String?) {
        auto.removeCallbacksAndMessages(null)
        autoOn = false
        if (::autoBtn.isInitialized) autoBtn.text = "Auto"
        if (msg != null && ::status.isInitialized) status.text = msg
    }

    /**
     * One round of the loop: scan, tap every arrow that can leave right now, wait, repeat.
     * It never taps an arrow that the latest scan says is blocked, and it stops (instead
     * of moving on to the next arrow) if an arrow it tapped does not leave.
     */
    private fun autoStep() {
        if (!autoOn) return
        requestScan(false) { res, solved ->
            if (!autoOn) return@requestScan
            if (res == null || solved == null) {
                stopAuto("Auto stopped: cannot read the board")
                return@requestScan
            }
            if (lastCal !== res.cal) pending.clear() // lattice changed, old ids are meaningless
            lastCal = res.cal

            val now = SystemClock.uptimeMillis()
            var failed: Arrow? = null
            val still = ArrayList<Pending>()
            for (p in pending) {
                val a = res.arrows.firstOrNull { ar -> ar.cells.any { it in p.cells } } ?: continue
                if (now - p.time > FAIL_AFTER_MS) failed = a else still.add(p)
            }
            pending.clear()
            pending.addAll(still)

            showLive(res, failed)
            if (failed != null) {
                stopAuto("Auto stopped: a tapped arrow (red) did not leave")
                return@requestScan
            }
            if (res.arrows.isEmpty()) {
                stopAuto(if (res.blockers.isEmpty()) "Board clear" else "Done, ${res.blockers.size} unread shapes left")
                return@requestScan
            }

            val free = res.arrows.filter { a ->
                a.round == 0 && pending.none { p -> a.cells.any { it in p.cells } }
            }
            if (free.isEmpty()) {
                if (pending.isNotEmpty()) {
                    auto.postDelayed({ autoStep() }, 400L)
                } else {
                    stopAuto("Auto stopped: ${res.arrows.size} arrows cannot move (${res.blockers.size} unread)")
                }
                return@requestScan
            }

            status.text = "Auto: tapping ${free.size} | ${res.arrows.size} left"
            free.forEachIndexed { i, a ->
                auto.postDelayed({
                    if (!autoOn) return@postDelayed
                    AutoTapService.instance?.tap(a.tapX, a.tapY)
                    pending.add(Pending(a.cellSet, SystemClock.uptimeMillis()))
                }, i * TAP_GAP_MS)
            }
            auto.postDelayed({ autoStep() }, free.size * TAP_GAP_MS + SETTLE_MS[speedIdx])
        }
    }

    // ------------------------------------------------------------------ cleanup

    override fun onDestroy() {
        stopAuto(null)
        stopSmart()
        main.removeCallbacksAndMessages(null)
        try {
            markerView?.let { wm.removeView(it) }
            watcher?.let { wm.removeView(it) }
            panel?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        markerView = null
        watcher = null
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
