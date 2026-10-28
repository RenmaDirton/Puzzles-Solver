package com.puzzlesolver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Only used to inject taps when the person presses Auto. It does not read the screen.
 *
 * Android cancels a gesture when a new one starts before it ends, so taps are queued and sent
 * strictly one after the other. Each tap reports whether it really went through.
 */
class AutoTapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoTapService? = null
        private const val TAP_MS = 30L
        private const val WATCHDOG_MS = 500L
        private const val MAX_TRIES = 3
    }

    private class Job(val x: Float, val y: Float, val gap: Long, val done: (Boolean) -> Unit) {
        var tries = 0
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Job>()
    private var busy = false
    private var lastStart = 0L
    private var token = 0

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** Queue a tap; [gap] is the least time between the start of the previous tap and this one. */
    fun tap(x: Float, y: Float, gap: Long = 0L, done: (Boolean) -> Unit = {}) {
        main.post {
            queue.addLast(Job(x, y, gap, done))
            pump()
        }
    }

    fun clearQueue() {
        main.post {
            val jobs = ArrayList(queue)
            queue.clear()
            token++
            busy = false
            jobs.forEach { it.done(false) }
        }
    }

    private fun pump() {
        if (busy || queue.isEmpty()) return
        busy = true
        val job = queue.first()
        val wait = maxOf(0L, lastStart + job.gap - SystemClock.uptimeMillis())
        val my = token
        main.postDelayed({ if (my == token) dispatch(job, my) }, wait)
    }

    private fun finish(job: Job, ok: Boolean) {
        queue.remove(job)
        busy = false
        job.done(ok)
        pump()
    }

    private fun dispatch(job: Job, my: Int) {
        job.tries++
        lastStart = SystemClock.uptimeMillis()
        var answered = false
        fun answer(ok: Boolean) {
            if (answered || my != token) return
            answered = true
            if (ok) {
                finish(job, true)
            } else if (job.tries < MAX_TRIES) {
                main.postDelayed({ if (my == token) dispatch(job, my) }, 30L)
            } else {
                finish(job, false)
            }
        }
        try {
            val path = Path().apply { moveTo(job.x, job.y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
                .build()
            val sent = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) = answer(true)
                override fun onCancelled(g: GestureDescription?) = answer(false)
            }, main)
            if (!sent) answer(false)
        } catch (_: Exception) {
            answer(false)
        }
        main.postDelayed({ answer(false) }, WATCHDOG_MS)
    }
}
