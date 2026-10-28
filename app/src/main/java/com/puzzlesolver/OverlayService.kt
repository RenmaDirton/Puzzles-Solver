package com.puzzlesolver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
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
import android.provider.MediaStore
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

        /** Speed levels: gap between two taps (ms) and the most arrows tapped before the screen is checked again. */
        private val TAP_GAPS = longArrayOf(110L, 80L, 55L)
        private val MAX_CHUNKS = intArrayOf(20, 40, 80)
        private const val START_CHUNK = 10
        private const val MIN_CHUNK = 4

        /** Red pixels on the board mean an arrow hit something. */
        private const val RED_HIT = 150

        /** Two frames whose ink fingerprints differ by at most this many cells count as "the screen did not change". */
        private const val SETTLE_MS = 700L
        private const val RECHECKS = 3
        private const val SMART_MS = 1500L
        private const val MAX_ATTEMPTS = 3
    }

    private class Scan(val res: ParseResult?, val solved: SolveResult?, val sig: LongArray?, val red: Int)

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
    private lateinit var speedBtn: Button

    private var cal: Calibration? = null
    private var captureBusy = false

    /** Bumped whenever a mode starts or stops, so late callbacks from an old run can be ignored. */
    private var session = 0
    private var smartOn = false
    private var autoOn = false
    private var smartBusy = false
    private var followUps = 0
    private var lastSmartSig: LongArray? = null
    private var redSeen = false
    private var unsure = 0
    private var stillRetries = 0
    private var recheck = 0
    private var speedIdx = 1
    private var chunkSize = START_CHUNK
    private val tapped = HashSet<String>()
    private val attempts = HashMap<String, Int>()

    private val smartTick = Runnable { smartStep() }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }

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

    /**
     * Copies the newest captured frame into a bitmap.
     * The buffer is rewound first: when the screen is still, the same frame is read again and
     * again, and a buffer that was already read once would otherwise be at its end and throw.
     */
    private fun grabBitmap(): Bitmap? {
        synchronized(lock) {
            val img = latest ?: return null
            return try {
                val plane = img.planes[0]
                val buf = plane.buffer
                buf.rewind()
                val padded = plane.rowStride / plane.pixelStride
                val full = Bitmap.createBitmap(padded, img.height, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(buf)
                if (padded == img.width) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height)
            } catch (e: Throwable) {
                null
            }
        }
    }

    private fun frameOrNull(bmp: Bitmap): BoardParser.Frame? =
        try {
            BoardParser.frameOf(bmp)
        } catch (e: Throwable) {
            null
        }

    /** Latest frame as raw pixels (converted off the main thread). Markers are left as they are. */
    private fun grabFrame(cb: (BoardParser.Frame?) -> Unit) {
        val bmp = grabBitmap()
        if (bmp == null) {
            cb(null)
            return
        }
        Thread {
            val f = frameOrNull(bmp)
            main.post { cb(f) }
        }.start()
    }

    /** Like grabFrame, but hides our own markers first so they cannot end up in the picture. */
    private fun captureClean(cb: (Bitmap?, BoardParser.Frame?) -> Unit) {
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
                cb(null, null)
                return
            }
            Thread {
                val f = frameOrNull(bmp)
                main.post {
                    captureBusy = false
                    cb(bmp, f)
                }
            }.start()
        }

        fun waitFrame() {
            val fresh = !hide || frameSeq.get() != seq0 || SystemClock.uptimeMillis() - started > 500L
            if (fresh) main.postDelayed({ grab() }, if (hide) 60L else 0L) else main.postDelayed({ waitFrame() }, 20L)
        }
        waitFrame()
    }

    /** Clean capture, then parse + solve off the main thread. Never throws. */
    private fun scanBoard(cb: (Scan) -> Unit) {
        captureClean { _, f ->
            if (f == null) {
                cb(Scan(null, null, null, 0))
                return@captureClean
            }
            val calUsed = cal
            Thread {
                var res: ParseResult? = null
                var solved: SolveResult? = null
                try {
                    res = BoardParser.parse(f, calUsed)
                    solved = res?.let { Solver.solve(it) }
                } catch (e: Throwable) {
                    res = null
                    solved = null
                }
                val r = res
                main.post {
                    if (r != null) cal = r.cal
                    cb(Scan(r, solved, f.sig, f.red))
                }
            }.start()
        }
    }

    // ------------------------------------------------------------------ snapshot

    /** Saves what the app sees (without its own markers) to Pictures/PuzzleSolver, so it can be sent for debugging. */
    private fun snap() {
        status.text = "Saving picture..."
        captureClean { bmp, _ ->
            if (bmp == null) {
                status.text = "No picture yet, try again"
                return@captureClean
            }
            Thread {
                val msg = try {
                    if (Build.VERSION.SDK_INT < 29) {
                        "Snap needs Android 10 or newer"
                    } else {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "puzzle_${System.currentTimeMillis()}.png")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PuzzleSolver")
                        }
                        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri == null) {
                            "Could not save the picture"
                        } else {
                            contentResolver.openOutputStream(uri)?.use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            "Saved: Pictures/PuzzleSolver (see Gallery)"
                        }
                    }
                } catch (e: Throwable) {
                    "Save failed: ${e.javaClass.simpleName}"
                }
                main.post { status.text = msg }
            }.start()
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
            setPadding(dp(9), 0, dp(9), 0)
            setOnClickListener { onClick() }
        }

        smartBtn = btn("Smart") { toggleSmart() }
        autoBtn = btn("Auto") { toggleAuto() }
        speedBtn = btn("Speed 2") { cycleSpeed() }
        val snap = btn("Snap") { snap() }
        val close = btn("X") { stopSelf() }
        for (b in listOf(smartBtn, autoBtn, speedBtn, snap, close)) row.addView(b)

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
            lastSmartSig = null
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
            var next = SMART_MS
            if (res == null || solved == null) {
                clearMarkers()
                status.text = "No board found (press Snap and send me the picture)"
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
        stillRetries = 0
        chunkSize = START_CHUNK
        redSeen = false
        clearMarkers()
        recheck = 0
        status.text = "Auto: starting..."
        autoStep(id)
    }

    private fun stopAuto(msg: String?) {
        if (autoOn) ++session
        autoOn = false
        AutoTapService.instance?.clearQueue()
        tapped.clear()
        if (::autoBtn.isInitialized) autoBtn.text = "Auto"
        if (msg != null && ::status.isInitialized) status.text = msg
    }

    private fun autoFail(msg: String, at: Arrow? = null) {
        stopAuto(msg)
        clearMarkers()
        if (at != null) markerView?.items = listOf(Marker(0, at.headX, at.headY, -2))
    }

    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % TAP_GAPS.size
        speedBtn.text = "Speed ${speedIdx + 1}"
    }

    /**
     * One step of Auto. It never waits for "a still screen": the live picture is not reliably still.
     *
     * An arrow that was already tapped does not hurt the arrows behind it, even while it is
     * still sliding, so the solution is tapped in order (arrows with an open path first), in chunks.
     *   1. scan; check that everything tapped in the last chunk has left. Arrows that are still
     *      there get a few more looks first (they may just be sliding); only then are they
     *      counted as a miss (smaller chunk, tapped again) or, if red shows, Auto stops.
     *   2. scan again shortly after; only the start of the plan that both scans agree on is used
     *   3. tap that chunk, one tap at a time (the service queues them), counting only taps that went through
     *   4. a moment after the last tap, repeat.
     */
    private fun autoStep(id: Int) {
        if (id != session || !autoOn) return
        scanBoard { s1 ->
            if (id != session || !autoOn) return@scanBoard
            val res1 = s1.res
            val solved1 = s1.solved
            if (res1 == null || solved1 == null) {
                autoFail("Auto stopped: cannot read the board (press Snap and send me the picture)")
                return@scanBoard
            }
            if (res1.arrows.isEmpty()) {
                stopAuto(if (res1.blockers.isEmpty()) "Board clear" else "Done, ${res1.blockers.size} unread shapes left")
                clearMarkers()
                return@scanBoard
            }

            // did the arrows we tapped last time leave?
            val still = res1.arrows.filter { key(it) in tapped }
            if (still.isNotEmpty() && recheck < RECHECKS) {
                recheck++
                status.text = "Auto: waiting for ${still.size} arrows to leave..."
                main.postDelayed({ autoStep(id) }, SETTLE_MS)
                return@scanBoard
            }
            recheck = 0
            if (still.isNotEmpty()) {
                if (s1.red >= RED_HIT) {
                    autoFail("Auto stopped: a tapped arrow hit something (red)", still[0])
                    return@scanBoard
                }
                val worst = still.maxByOrNull { attempts[key(it)] ?: 0 }!!
                if ((attempts[key(worst)] ?: 0) >= MAX_ATTEMPTS) {
                    autoFail("Auto stopped: an arrow will not move", worst)
                    return@scanBoard
                }
                chunkSize = maxOf(MIN_CHUNK, chunkSize / 2)
            } else if (tapped.isNotEmpty()) {
                chunkSize = minOf(MAX_CHUNKS[speedIdx], chunkSize * 2)
            }
            tapped.clear()

            if (res1.blockers.size > res1.arrows.size / 2 + 3) {
                // probably something is still sliding: look again a few times before giving up
                if (unsure++ > 4) autoFail("Auto stopped: board unclear (${res1.blockers.size} unread shapes)")
                else main.postDelayed({ autoStep(id) }, SETTLE_MS)
                return@scanBoard
            }
            if (solved1.order.none { it.round == 0 }) {
                if (unsure++ > 2) autoFail("Auto stopped: ${res1.arrows.size} arrows cannot move${unreadText(res1)}")
                else main.postDelayed({ autoStep(id) }, SETTLE_MS)
                return@scanBoard
            }
            val round1 = HashMap<String, Int>()
            for (a in res1.arrows) round1[key(a)] = a.round

            main.postDelayed({
                if (id != session || !autoOn) return@postDelayed
                scanBoard { s2 ->
                    if (id != session || !autoOn) return@scanBoard
                    val res2 = s2.res
                    val solved2 = s2.solved
                    if (res2 == null || solved2 == null) {
                        main.postDelayed({ autoStep(id) }, 300L)
                        return@scanBoard
                    }

                    // the start of the plan (in solving order) that both scans agree on
                    val plan = ArrayList<Arrow>()
                    for (a in solved2.order) {
                        if (round1[key(a)] != a.round) break
                        plan.add(a)
                        if (plan.size >= chunkSize) break
                    }
                    if (plan.isEmpty()) {
                        unsure++
                        if (unsure > 4) {
                            autoFail("Auto stopped: scans keep disagreeing")
                        } else {
                            main.postDelayed({ autoStep(id) }, 300L)
                        }
                        return@scanBoard
                    }
                    unsure = 0
                    val svc = AutoTapService.instance
                    if (svc == null) {
                        autoFail("Auto stopped: accessibility service is off")
                        return@scanBoard
                    }
                    val gap = TAP_GAPS[speedIdx]
                    status.text = "Auto: tapping ${plan.size} | ${res2.arrows.size} left"
                    var pending = plan.size
                    plan.forEach { a ->
                        svc.tap(a.tapX, a.tapY, gap) { ok ->
                            if (id != session || !autoOn) return@tap
                            if (ok) {
                                val k = key(a)
                                tapped.add(k)
                                attempts[k] = (attempts[k] ?: 0) + 1
                            }
                            if (--pending == 0) main.postDelayed({ autoStep(id) }, SETTLE_MS)
                        }
                    }
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
