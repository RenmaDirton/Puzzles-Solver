package com.puzzlesolver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

        /** The screen (activity) that is in front right now, e.g. the game itself or an ad. Null until Android tells us. */
        @Volatile
        var curClass: String? = null
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || cls.startsWith("android.") || cls.startsWith("androidx.")) return
        curClass = "$pkg/$cls"
    }

    override fun onInterrupt() {}

    /** Queue a tap; [gap] is the least time between the start of the previous tap and this one. */
    fun tap(x: Float, y: Float, gap: Long = 0L, done: (Boolean) -> Unit = {}) {
        main.post {
            queue.addLast(Job(x, y, gap, done))
            pump()
        }
    }

    /**
     * Presses a button or text on screen whose accessibility label contains [word] (case-insensitive).
     * Games that draw their own text (no labels) are handled by reading the screenshot instead.
     */
    fun pressText(word: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            if (root.packageName?.toString() == packageName) return false
            val want = word.trim().lowercase()
            if (want.isEmpty()) return false
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            var seen = 0
            while (stack.isNotEmpty() && seen < 800) {
                val n = stack.removeLast()
                seen++
                val l = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim().lowercase()
                if (l.isNotEmpty() && l.length <= want.length + 12 && l.contains(want)) {
                    var c: AccessibilityNodeInfo? = n
                    var hops = 0
                    while (c != null && hops < 4) {
                        if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                        c = c.parent
                        hops++
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /** The phone's Back button (leaves the Google Play page an ad opened). */
    fun back(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_BACK)
    } catch (_: Exception) {
        false
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
