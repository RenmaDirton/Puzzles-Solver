package com.example.ringtapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager

/**
 * Foreground service: captures the screen at half resolution, runs the solver on every
 * frame and asks the accessibility service to tap when the solver says so.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.ringtapper.START"
        const val ACTION_STOP = "com.example.ringtapper.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        @Volatile var running = false
        @Volatile var status = "Stopped"

        private const val CHANNEL = "ringtapper"
        private const val NOTIF_ID = 1
        private const val SCALE = 0.5f
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var stopped = false

    private val solver: GameSolver = RingSolver()
    private var screenW = 0
    private var screenH = 0
    private var taps = 0
    private var lastStatusNs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) startCapture(intent)
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        stopped = false
        createChannel()
        startForeground(
            NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            status = "Missing screen-capture permission"
            stopAll()
            return
        }

        Prefs.load(this)
        solver.reset()
        taps = 0

        val t = HandlerThread("ringtapper-capture")
        t.start()
        thread = t
        val handler = Handler(t.looper)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        // Android 14+ requires a callback to be registered before creating the display.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopAll()
            }
        }, handler)

        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        screenW = bounds.width()
        screenH = bounds.height()
        val capW = ((screenW * SCALE).toInt()) and 1.inv()
        val capH = ((screenH * SCALE).toInt()) and 1.inv()
        val dpi = resources.displayMetrics.densityDpi

        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage()
            if (img != null) {
                try {
                    val plane = img.planes[0]
                    val frame = Frame(plane.buffer, img.width, img.height, plane.rowStride)
                    val now = System.nanoTime()
                    val req = solver.analyze(frame, now)
                    if (req != null) {
                        val sx = screenW.toFloat() / img.width
                        val sy = screenH.toFloat() / img.height
                        val ok = TapAccessibilityService.tap(req.x * sx, req.y * sy)
                        if (ok) taps++
                        else status = "Taps blocked: enable the Ring Tapper accessibility service"
                    }
                    if (now - lastStatusNs > 250_000_000L) {
                        lastStatusNs = now
                        if (TapAccessibilityService.instance != null) {
                            status = "${solver.debug}  |  taps: $taps"
                        }
                    }
                } finally {
                    img.close()
                }
            }
        }, handler)

        display = proj.createVirtualDisplay(
            "ringtapper", capW, capH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
        running = true
        status = "Running. Open your game."
    }

    private fun stopAll() {
        if (stopped) return
        stopped = true
        running = false
        status = "Stopped"
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        display = null
        projection = null
        reader = null
        thread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ring Tapper", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop", stopPi
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Ring Tapper is running")
            .setContentText("Open your game. Tap Stop here to quit.")
            .setContentIntent(openPi)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }
}
