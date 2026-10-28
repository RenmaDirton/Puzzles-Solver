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
import kotlin.math.roundToInt

class OverlayService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.puzzlesolver.STOP"
        private const val CHANNEL = "puzzle_solver"

        /** Gap between two taps inside one batch. */
        private const val TAP_GAP_MS = 90L

        /** Gold pixels on the board mean some arrow is still sliding off. */
        private const val GOLD_MOVING = 120

        /** Red pixels mean an arrow hit another one. */
        private const val RED_HIT = 150

        private const val POLL_MS = 120L
        private const val SETTLE_MAX_MS = 8000L
        private const val AFTER_TAP_MS = 400L
        private const val MAX_ATTEMPTS = 3
    }

    private class Scan(val res: ParseResult?, val solved: SolveResult?, val gold: Int, val red: Int)

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
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

    private var cal: Calibration? = null
    private var captureBusy = false

    /** Bumped whenever a mode starts or stops, so late callbacks from an old run can be ignored. */
    private var session = 0
    private var smartOn = false
    private var autoOn = false
    private var smartBusy = false
    private var followUps = 0
    private var redSeen = false
    private var unsure = 0
    private val tapped = HashSet<String>()
    private val attempts = HashMap<String, Int>()

    private val smartTick = Runnable { smartStep() }

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

    /** Latest frame as raw pixels (converted off the main thread). Markers are left as they are. */
    private fun grabFrame(cb: (BoardParser.Frame?) -> Unit) {
        val bmp = grabBitmap()
        if (bmp == null) {
            cb(null)
            return
        }
        Thread {
            val f = try {
                BoardParser.frameOf(bmp)
            } catch (e: Exception) {
                null
            }
            main.post { cb(f) }
        }.start()
    }

    /** Like grabFrame, but hides our own markers first so they cannot end up in the picture. */
    private fun captureClean(cb: (BoardParser.Frame?) -> Unit) {
        if (captureBusy) {
            main.postDelayed({ captureClean(cb) }, 100L)
            return
        }
        captureBusy = true
        val mv = markerView
        val hide = mv != null && mv.visibility == View.VISIBLE && mv.items.isNotEmpty()
        val seq0 = frameSeq.get()
        val started = SystemClock.uptimeMillis()
        if (hide) mv!!.visibility = View.INVISIBLE

        fun grab() {
            val bmp = grabBitmap()
            if (hide) markerView?.visibility = View.VISIBLE
            if (bmp == null) {
                captureBusy = false
                cb(null)
                return
            }
            Thread {
                val f = try {
                    BoardParser.frameOf(bmp)
                } catch (e: Exception) {
                    null
                }
                main.post {
                    captureBusy = false
                    cb(f)
                }
            }.start()
        }

        fun waitFrame() {
            val fresh = !hide || frameSeq.get() != seq0 || SystemClock.uptimeMillis() - started > 500L
            if (fresh) main.postDelayed({ grab() }, if (hide) 60L else 0L) else main.postDelayed({ waitFrame() }, 20L)
        }
        waitFrame()
    }

    /** Clean capture, motion check and (when nothing is sliding) parse + solve. */
    private fun scanBoard(cb: (Scan) -> Unit) {
        captureClean { f ->
            if (f == null) {
                cb(Scan(null, null, 0, 0))
                return@captureClean
            }
            val calUsed = cal
            Thread {
                val m = BoardParser.motion(f)
                var res: ParseResult? = null
                var solved: SolveResult? = null
                if (m[0] < GOLD_MOVING) {
                    res = try {
                        BoardParser.parse(f, calUsed)
                    } catch (e: Exception) {
                        null
                    }
                    solved = res?.let { Solver.solve(it) }
                }
                main.post {
                    if (res != null) cal = res.cal
                    cb(Scan(res, solved, m[0], m[1]))
                }
            }.start()
        }
    }

    /**
     * Waits until no arrow is sliding any more (no gold on the board for two checks in a row).
     * Gives up after SETTLE_MAX_MS. Remembers whether red (a collision) showed up meanwhile.
     */
    private fun waitSettled(id: Int, cb: () -> Unit) {
        val start = SystemClock.uptimeMillis()
        var calm = 0
        fun poll() {
            if (id != session) return
            grabFrame { f ->
                if (id != session) return@grabFrame
                if (f != null) {
                    val m = BoardParser.motion(f)
                    if (m[1] >= RED_HIT) redSeen = true
                    if (m[0] < GOLD_MOVING) calm++ else calm = 0
                }
                if (calm >= 2 || SystemClock.uptimeMillis() - start > SETTLE_MAX_MS) {
                    cb()
                } else {
                    main.postDelayed({ poll() }, POLL_MS)
                }
            }
        }
        poll()
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
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { onClick() }
        }

        val scan = btn("Scan") { scanFull() }
        smartBtn = btn("Smart") { toggleSmart() }
        autoBtn = btn("Auto") { toggleAuto() }
        val close = btn("X") { stopSelf() }
        for (b in listOf(scan, smartBtn, autoBtn, close)) row.addView(b)

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

    // ------------------------------------------------------------------ helpers

    private fun key(a: Arrow): String =
        "${(a.headX / 3f).roundToInt()},${(a.headY / 3f).roundToInt()},${a.dirC},${a.dirR}"

    private fun clearMarkers() {
        markerView?.items = emptyList()
    }

    private fun unreadText(res: ParseResult) =
        if (res.blockers.isNotEmpty()) " | ${res.blockers.size} unread" else ""

    // ------------------------------------------------------------------ Scan (numbered plan)

    private fun scanFull() {
        stopAuto(null)
        stopSmart()
        val id = ++session
        cal = null
        clearMarkers()
        status.text = "Scanning..."
        waitSettled(id) {
            scanBoard { s ->
                if (id != session) return@scanBoard
                val res = s.res
                val solved = s.solved
                if (res == null || solved == null || res.arrows.isEmpty()) {
                    status.text = if (s.gold >= GOLD_MOVING) "Arrows are still moving, try again" else "Could not find a board. Open a level and try again."
                    return@scanBoard
                }
                markerView?.items = solved.order.mapIndexed { i, a -> Marker(i + 1, a.headX, a.headY, a.round) }
                status.text = "${res.arrows.size} arrows, ${solved.rounds} rounds${unreadText(res)}"
            }
        }
    }

    // ------------------------------------------------------------------ Smart scan

    private fun toggleSmart() {
        if (smartOn) {
            stopSmart()
            clearMarkers()
            status.text = "Smart off"
        } else {
            stopAuto(null)
            ++session
            smartOn = true
            smartBtn.text = "Smart ON"
            clearMarkers()
            status.text = "Smart scanning..."
            scheduleSmart(0L)
        }
    }

    private fun stopSmart() {
        smartOn = false
        smartBusy = false
        main.removeCallbacks(smartTick)
        if (::smartBtn.isInitialized) smartBtn.text = "Smart"
    }

    private fun scheduleSmart(ms: Long) {
        main.removeCallbacks(smartTick)
        main.postDelayed(smartTick, ms)
    }

    private fun onUserTouch() {
        if (!smartOn || autoOn) return
        followUps = 3
        scheduleSmart(500L)
    }

    private fun smartStep() {
        if (!smartOn || smartBusy) return
        smartBusy = true
        val id = session
        scanBoard { s ->
            smartBusy = false
            if (id != session || !smartOn) return@scanBoard
            val res = s.res
            val solved = s.solved
            var next = 3000L
            if (res == null || solved == null) {
                clearMarkers()
                if (s.gold >= GOLD_MOVING) {
                    status.text = "Arrows moving..."
                    next = 350L
                } else {
                    status.text = "No board found"
                }
            } else {
                markerView?.items = res.arrows.filter { it.round == 0 }.map { Marker(0, it.headX, it.headY, 0) }
                val now = res.arrows.count { it.round == 0 }
                status.text = when {
                    res.arrows.isEmpty() -> "No arrows left${unreadText(res)}"
                    now == 0 -> "None can move, ${solved.stuck} stuck${unreadText(res)}"
                    else -> "$now can move now | ${res.arrows.size} left${unreadText(res)}"
                }
                if (followUps > 0) {
                    followUps--
                    next = 900L
                }
            }
            scheduleSmart(next)
        }
    }

    // ------------------------------------------------------------------ Auto (closed loop)

    private fun toggleAuto() {
        if (autoOn) stopAuto("Stopped") else startAuto()
    }

    private fun startAuto() {
        if (AutoTapService.instance == null) {
            status.text = "Enable the auto-tap accessibility service first"
            return
        }
        stopSmart()
        val id = ++session
        autoOn = true
        autoBtn.text = "Stop"
        tapped.clear()
        attempts.clear()
        unsure = 0
        redSeen = false
        clearMarkers()
        status.text = "Auto: waiting for a still screen..."
        waitSettled(id) {
            redSeen = false // a red arrow that was already there before we started is not our doing
            autoStep(id)
        }
    }

    private fun stopAuto(msg: String?) {
        if (autoOn) ++session
        autoOn = false
        tapped.clear()
        if (::autoBtn.isInitialized) autoBtn.text = "Auto"
        if (msg != null && ::status.isInitialized) status.text = msg
    }

    private fun autoFail(msg: String, at: Arrow? = null) {
        stopAuto(msg)
        clearMarkers()
        if (at != null) markerView?.items = listOf(Marker(0, at.headX, at.headY, -2))
    }

    /**
     * One round of the loop. The screen is already still when this runs.
     *   1. scan; if an arrow we tapped is still there, stop (red = it hit something)
     *   2. scan again 160 ms later and only keep arrows that both scans agree are free
     *   3. tap a batch whose exit paths do not touch each other
     *   4. wait for the sliding to finish, then repeat
     * It never moves on to "the next arrow" after a problem: it stops and shows what went wrong.
     */
    private fun autoStep(id: Int) {
        if (id != session || !autoOn) return
        scanBoard { s1 ->
            if (id != session || !autoOn) return@scanBoard
            val res1 = s1.res
            if (res1 == null) {
                if (s1.gold >= GOLD_MOVING) {
                    waitSettled(id) { autoStep(id) }
                } else {
                    autoFail("Auto stopped: cannot read the board")
                }
                return@scanBoard
            }
            if (res1.arrows.isEmpty()) {
                stopAuto(if (res1.blockers.isEmpty()) "Board clear" else "Done, ${res1.blockers.size} unread shapes left")
                clearMarkers()
                return@scanBoard
            }
            if (res1.blockers.size > res1.arrows.size / 2 + 3) {
                autoFail("Auto stopped: board unclear (${res1.blockers.size} unread shapes)")
                return@scanBoard
            }

            // did the arrows we tapped last time actually leave?
            val still = res1.arrows.filter { key(it) in tapped }
            if (still.isNotEmpty()) {
                if (redSeen || s1.red >= RED_HIT) {
                    autoFail("Auto stopped: a tapped arrow hit something (red)", still[0])
                    return@scanBoard
                }
                val worst = still.maxByOrNull { attempts[key(it)] ?: 0 }!!
                if ((attempts[key(worst)] ?: 0) >= MAX_ATTEMPTS) {
                    autoFail("Auto stopped: an arrow will not move", worst)
                    return@scanBoard
                }
            }
            tapped.clear()
            redSeen = false

            val free1 = res1.arrows.filter { it.round == 0 }.map { key(it) }.toHashSet()
            if (free1.isEmpty()) {
                autoFail("Auto stopped: ${res1.arrows.size} arrows cannot move${unreadText(res1)}")
                return@scanBoard
            }

            main.postDelayed({
                if (id != session || !autoOn) return@postDelayed
                scanBoard { s2 ->
                    if (id != session || !autoOn) return@scanBoard
                    val res2 = s2.res
                    if (res2 == null) {
                        // something moved between the two scans: start over from a still screen
                        waitSettled(id) { autoStep(id) }
                        return@scanBoard
                    }
                    val batch = Planner.safeBatch(res2).filter { key(it) in free1 }
                    if (batch.isEmpty()) {
                        unsure++
                        if (unsure > 3) {
                            autoFail("Auto stopped: scans keep disagreeing")
                        } else {
                            main.postDelayed({ autoStep(id) }, 300L)
                        }
                        return@scanBoard
                    }
                    unsure = 0
                    status.text = "Auto: tapping ${batch.size} | ${res2.arrows.size} left"
                    val svc = AutoTapService.instance
                    if (svc == null) {
                        autoFail("Auto stopped: accessibility service is off")
                        return@scanBoard
                    }
                    batch.forEachIndexed { i, a ->
                        main.postDelayed({
                            if (id != session || !autoOn) return@postDelayed
                            AutoTapService.instance?.tap(a.tapX, a.tapY)
                        }, i * TAP_GAP_MS)
                        val k = key(a)
                        tapped.add(k)
                        attempts[k] = (attempts[k] ?: 0) + 1
                    }
                    main.postDelayed({
                        if (id != session || !autoOn) return@postDelayed
                        waitSettled(id) { autoStep(id) }
                    }, batch.size * TAP_GAP_MS + AFTER_TAP_MS)
                }
            }, 160L)
        }
    }

    // ------------------------------------------------------------------ cleanup

    override fun onDestroy() {
        ++session
        autoOn = false
        smartOn = false
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
