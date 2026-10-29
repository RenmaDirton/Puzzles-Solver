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
     * Looks for a clearly labelled Close / Skip button on the screen (ads) and presses it.
     * Ads that draw their X as an unlabelled picture cannot be found this way.
     */
    fun tryCloseAd(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            if (root.packageName?.toString() == packageName) return false
            val dm = resources.displayMetrics
            val sw = dm.widthPixels
            val sh = dm.heightPixels
            val exact = setOf("close", "x", "\u00d7", "\u2715", "\u2716", "\u2573", "no thanks", "no, thanks", "dismiss",
                "continue", "done", "not now", "maybe later", "skip", "close ad", "skip ad", "skip ads", "skip video")
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            val nodes = ArrayList<AccessibilityNodeInfo>()
            while (stack.isNotEmpty() && nodes.size < 800) {
                val n = stack.removeLast()
                nodes.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
            }
            fun press(n: AccessibilityNodeInfo): Boolean {
                var c: AccessibilityNodeInfo? = n
                var hops = 0
                while (c != null && hops < 4) {
                    if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    c = c.parent
                    hops++
                }
                return false
            }
            fun label(n: AccessibilityNodeInfo) =
                (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim().lowercase()

            // 1. clearly labelled: Close, X, Skip, "Skip >>", "Close ad (3)" ...
            for (n in nodes) {
                val l = label(n)
                if (l.isEmpty() || l.length > 24) continue
                val hit = l in exact || l.startsWith("skip") || l.startsWith("close") ||
                    l.startsWith("\u2715") || l.startsWith("\u00d7") || l.endsWith(">>") && l.length <= 8
                if (hit && press(n)) return true
            }
            // 2. an unlabelled (or "close"-labelled) small button in a top corner: the usual X
            val r = android.graphics.Rect()
            var best: AccessibilityNodeInfo? = null
            var bestScore = -1
            for (n in nodes) {
                if (!n.isClickable || !n.isVisibleToUser) continue
                n.getBoundsInScreen(r)
                val bw = r.width()
                val bh = r.height()
                if (bw < 24 || bh < 24 || bw > 190 || bh > 190) continue
                if (r.centerY() > sh * 0.14f) continue
                val inRight = r.centerX() > sw * 0.78f
                val inLeft = r.centerX() < sw * 0.22f
                if (!inRight && !inLeft) continue
                val l = label(n)
                if (l.isNotEmpty() && !l.contains("close") && !l.contains("skip")) continue
                val score = (if (inRight) 2 else 1) + (if (l.isNotEmpty()) 3 else 0)
                if (score > bestScore) { bestScore = score; best = n }
            }
            val pick = best
            pick != null && press(pick)
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
