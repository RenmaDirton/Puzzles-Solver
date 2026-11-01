package com.example.ringtapper

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One captured frame (RGBA_8888). Only valid while the Image it came from is open. */
class Frame(val buf: ByteBuffer, val width: Int, val height: Int, val rowStride: Int) {
    /** Returns 0xRRGGBB. Coordinates are clamped to the frame. */
    fun rgb(xi: Int, yi: Int): Int {
        val x = if (xi < 0) 0 else if (xi >= width) width - 1 else xi
        val y = if (yi < 0) 0 else if (yi >= height) height - 1 else yi
        val i = y * rowStride + x * 4
        return ((buf.get(i).toInt() and 0xFF) shl 16) or
            ((buf.get(i + 1).toInt() and 0xFF) shl 8) or
            (buf.get(i + 2).toInt() and 0xFF)
    }
}

/** Where to tap, in frame pixels. */
data class TapRequest(val x: Float, val y: Float)

/** One implementation per game. */
interface GameSolver {
    fun reset()
    fun analyze(frame: Frame, nowNanos: Long): TapRequest?
    val debug: String
}

private fun red(c: Int) = (c shr 16) and 0xFF
private fun green(c: Int) = (c shr 8) and 0xFF
private fun blue(c: Int) = c and 0xFF

/**
 * Solver for the "ball spins on a purple ring, tap when it is on the bar" game.
 *
 * Pipeline per frame:
 *  1. Sample the ring at 360 angles (1 degree each).
 *  2. Find the ball from the dark rod joining the hub to the ball.
 *  3. Every angle outside the ball's own footprint is marked bar / not-bar. A bar is
 *     anything on the ring that is not purple, whatever its colour (the score text, which
 *     is pure white, is ignored). Angles hidden under the ball keep their last seen value.
 *  4. The bars form one cluster. It is split into colour groups and the MIDDLE group is
 *     the target. Estimate angular velocity, add latency compensation, and tap when the
 *     predicted ball angle reaches the middle of that bar.
 *  5. After a tap the bar map is wiped (the game moves the bars) and rebuilt.
 */
class RingSolver : GameSolver {

    companion object {
        const val N = 360
        const val MIN_FRAMES_AFTER_RESET = 4
        const val GAP_FILL = 3          // bridge thin gaps between bar tiles (degrees)
        const val MIN_ARC = 4           // ignore target arcs shorter than this (degrees)
        const val ROD_V_MAX = 80        // rod pixels are darker than this (0..255)
        const val TEXT_WHITE_MIN = 248  // all channels >= this = pure-white score text, not a bar
        val ROD_INNER = floatArrayOf(0.70f, 0.62f, 0.78f)   // fractions of ring radius
        val ROD_OUTER = floatArrayOf(0.88f, 0.84f, 0.92f)
    }

    private val sinT = FloatArray(N) { sin(Math.toRadians(it.toDouble())).toFloat() }
    private val cosT = FloatArray(N) { cos(Math.toRadians(it.toDouble())).toFloat() }

    // Calibration
    private var calibrated = false
    private var cx = 0f
    private var cy = 0f
    private var midR = 0f
    private var ringW = 0f
    private var frameCount = 0L
    private var lowChecks = 0

    // Ring samples for the current frame
    private val ringR = IntArray(N)
    private val ringG = IntArray(N)
    private val ringB = IntArray(N)

    // Bar map: last seen state of every angle
    private val known = BooleanArray(N)
    private val bar = BooleanArray(N)
    private val lastR = IntArray(N)
    private val lastG = IntArray(N)
    private val lastB = IntArray(N)
    private val txt = BooleanArray(N)      // pure-white score text crossing the ring
    private val txtD = BooleanArray(N)
    private var framesSinceReset = 0

    // Ball tracking
    private val tBuf = LongArray(5)
    private val uBuf = DoubleArray(5)
    private var bCount = 0
    private var unwrapped = 0.0
    private var lastRaw = 0.0
    private var rejects = 0
    private var misses = 0
    private var omega = 0.0 // degrees per second, + = clockwise

    private var lastFrameNs = 0L
    private var frameDt = 0.016
    private var lastTapNs = 0L

    @Volatile
    private var dbg = "starting"
    override val debug: String get() = dbg

    override fun reset() {
        calibrated = false
        lowChecks = 0
        clearBars()
        bCount = 0
        misses = 0
        rejects = 0
        lastTapNs = 0L
        lastFrameNs = 0L
        dbg = "reset"
    }

    private fun clearBars() {
        known.fill(false)
        bar.fill(false)
        framesSinceReset = 0
    }

    // ------------------------------------------------------------------ main entry

    override fun analyze(frame: Frame, nowNanos: Long): TapRequest? {
        frameCount++

        if (!calibrated) {
            if (frameCount % 15L == 1L) {
                calibrated = calibrate(frame)
                if (calibrated) {
                    clearBars()
                    bCount = 0
                    dbg = "ring found at (${cx.toInt()},${cy.toInt()}) r=${midR.toInt()}"
                } else {
                    dbg = "looking for the purple ring..."
                }
            }
            return null
        }

        if (lastFrameNs != 0L) {
            val dt = (nowNanos - lastFrameNs) / 1e9
            if (dt > 0.001 && dt < 0.5) frameDt = frameDt * 0.9 + dt * 0.1
        }
        lastFrameNs = nowNanos

        sampleRing(frame)

        // Every ~30 frames check that the ring is still on screen.
        if (frameCount % 30L == 0L) {
            var pc = 0
            for (a in 0 until N) if (isPurple(ringR[a], ringG[a], ringB[a])) pc++
            if (pc < 0.35 * N) {
                lowChecks++
                if (lowChecks >= 3) {
                    calibrated = false
                    lowChecks = 0
                    dbg = "ring lost, searching again"
                    return null
                }
            } else {
                lowChecks = 0
            }
        }

        val ball = locateBall(frame)
        if (ball == null) {
            misses++
            if (misses > 20) bCount = 0
            dbg = "ball not found"
            return null
        }
        misses = 0

        if (!trackBall(ball.angle, nowNanos)) {
            dbg = "ball jump rejected"
            return null
        }

        updateBarMap(ball.angle)
        framesSinceReset++

        // ---------------------------------------------------------- decide
        if (framesSinceReset < MIN_FRAMES_AFTER_RESET) {
            dbg = "rebuilding bar map"
            return null
        }
        var knownCnt = 0
        for (a in 0 until N) if (known[a]) knownCnt++
        if (knownCnt < N) {
            dbg = "rebuilding bar map ($knownCnt/$N)"
            return null
        }
        if (bCount < 3 || abs(omega) < 15.0) {
            dbg = "measuring ball speed"
            return null
        }

        val target = findTarget()
        val arcs = if (target != null) listOf(target) else emptyList()

        val lead = omega * Prefs.latencyMs / 1000.0
        val pred = wrap360(ball.angle + lead)
        val stepDeg = abs(omega) * frameDt

        val sb = StringBuilder()
        sb.append("ball=${ball.angle.toInt()} w=${omega.toInt()}/s target=")
        if (target != null) sb.append("[${target[0]}+${target[1]}] groups=$lastGroups") else sb.append("none")

        val cooldownOk = (nowNanos - lastTapNs) > Prefs.cooldownMs * 1_000_000L
        if (cooldownOk) {
            for (arc in arcs) {
                val start = arc[0] - 0.5
                val len = arc[1].toDouble()
                // distance travelled inside the arc, measured from the edge the ball enters at
                val dist = if (omega > 0) wrap360(pred - start) else wrap360(start + len - pred)
                if (dist <= len) {
                    val pos = dist / len
                    val stepFrac = stepDeg / len
                    // Tap on the frame nearest to the arc centre.
                    if (pos + 0.5 * stepFrac >= 0.5) {
                        lastTapNs = nowNanos
                        clearBars()
                        dbg = "TAP! $sb"
                        return TapRequest(cx, cy)
                    }
                }
            }
        }
        dbg = sb.toString()
        return null
    }

    // ------------------------------------------------------------------ calibration

    /** Finds ring centre and radius from the purple pixels. Needs the game on screen. */
    private fun calibrate(f: Frame): Boolean {
        val w = f.width
        val h = f.height
        val xs = IntArray(w)
        val ys = IntArray(h)
        var cnt = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = f.rgb(x, y)
                if (isPurple(red(c), green(c), blue(c))) {
                    xs[x]++
                    ys[y]++
                    cnt++
                }
                x += 2
            }
            y += 2
        }
        val samples = (w / 2) * (h / 2)
        if (cnt < samples * 0.03) return false

        val x1 = percentile(xs, cnt, 0.01)
        val x2 = percentile(xs, cnt, 0.99)
        val y1 = percentile(ys, cnt, 0.01)
        val y2 = percentile(ys, cnt, 0.99)
        cx = (x1 + x2) / 2f
        cy = (y1 + y2) / 2f

        val rh = IntArray(max(w, h) + 1)
        var rc = 0
        y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = f.rgb(x, y)
                if (isPurple(red(c), green(c), blue(c))) {
                    val r = hypot(x - cx, y - cy).toInt()
                    if (r < rh.size) {
                        rh[r]++
                        rc++
                    }
                }
                x += 2
            }
            y += 2
        }
        if (rc == 0) return false
        val rIn = percentile(rh, rc, 0.03)
        val rOut = percentile(rh, rc, 0.97)
        midR = (rIn + rOut) / 2f
        ringW = (rOut - rIn).toFloat()
        if (ringW < 6f || midR < 30f) return false

        // Validate: most of the circle at mid radius must be purple.
        var pc = 0
        for (a in 0 until N) {
            val c = f.rgb((cx + midR * sinT[a]).roundToInt(), (cy - midR * cosT[a]).roundToInt())
            if (isPurple(red(c), green(c), blue(c))) pc++
        }
        return pc >= 0.6 * N
    }

    // ------------------------------------------------------------------ per-frame sampling

    private fun sampleRing(f: Frame) {
        val off = ringW * 0.15f
        for (a in 0 until N) {
            var sr = 0
            var sg = 0
            var sb = 0
            var text = false
            for (k in -1..1) {
                val r = midR + k * off
                val c = f.rgb((cx + r * sinT[a]).roundToInt(), (cy - r * cosT[a]).roundToInt())
                sr += red(c)
                sg += green(c)
                sb += blue(c)
                if (min(red(c), min(green(c), blue(c))) >= TEXT_WHITE_MIN) text = true
            }
            ringR[a] = sr / 3
            ringG[a] = sg / 3
            ringB[a] = sb / 3
            txt[a] = text
        }
    }

    // ------------------------------------------------------------------ ball

    private class Ball(val x: Float, val y: Float, val angle: Double)

    /** Centre of the dark rod where it crosses the circle of radius r, or null. */
    private fun rodPoint(f: Frame, r: Float): FloatArray? {
        val dark = BooleanArray(N)
        for (a in 0 until N) {
            val c = f.rgb((cx + r * sinT[a]).roundToInt(), (cy - r * cosT[a]).roundToInt())
            dark[a] = max(red(c), max(green(c), blue(c))) < ROD_V_MAX
        }
        var best: IntArray? = null
        for (run in runs(dark, true)) {
            if (best == null || run[1] > best[1]) best = run
        }
        if (best == null || best[1] > 30) return null
        val ang = Math.toRadians(wrap360(best[0] + (best[1] - 1) / 2.0))
        return floatArrayOf(cx + r * sin(ang).toFloat(), cy - r * cos(ang).toFloat())
    }

    /**
     * The rod runs from the hub to the ball. Take two points on it, extend the line
     * to the ring radius and that is the ball centre. This stays accurate even if the
     * hub is a few pixels off the ring centre.
     */
    private fun locateBall(f: Frame): Ball? {
        var p1: FloatArray? = null
        for (k in ROD_INNER) {
            p1 = rodPoint(f, midR * k)
            if (p1 != null) break
        }
        var p2: FloatArray? = null
        for (k in ROD_OUTER) {
            p2 = rodPoint(f, midR * k)
            if (p2 != null) break
        }
        val ref = p2 ?: p1 ?: return null
        val angRef = angleOf(ref[0] - cx, ref[1] - cy)

        var bx = cx + midR * sin(Math.toRadians(angRef)).toFloat()
        var by = cy - midR * cos(Math.toRadians(angRef)).toFloat()

        if (p1 != null && p2 != null) {
            var dx = p2[0] - p1[0]
            var dy = p2[1] - p1[1]
            val len = hypot(dx, dy)
            if (len > 0.08f * midR) {
                dx /= len
                dy /= len
                val px = p2[0] - cx
                val py = p2[1] - cy
                val b = px * dx + py * dy
                val disc = b * b - (px * px + py * py - midR * midR)
                if (disc >= 0f) {
                    val t = -b + sqrt(disc)
                    if (t >= 0f) {
                        val ex = p2[0] + t * dx
                        val ey = p2[1] + t * dy
                        val angE = angleOf(ex - cx, ey - cy)
                        if (abs(angDiff(angE, angRef)) < 15.0) {
                            bx = ex
                            by = ey
                        }
                    }
                }
            }
        }
        return Ball(bx, by, angleOf(bx - cx, by - cy))
    }

    /** Keeps an unwrapped angle history and estimates angular velocity. */
    private fun trackBall(angle: Double, now: Long): Boolean {
        if (bCount == 0) {
            unwrapped = angle
        } else {
            val d = angDiff(angle, lastRaw)
            if (abs(d) > 90.0) {
                rejects++
                if (rejects > 5) {
                    bCount = 0
                    rejects = 0
                }
                return false
            }
            unwrapped += d
        }
        rejects = 0
        lastRaw = angle

        if (bCount == 5) {
            for (i in 0 until 4) {
                tBuf[i] = tBuf[i + 1]
                uBuf[i] = uBuf[i + 1]
            }
        } else {
            bCount++
        }
        tBuf[bCount - 1] = now
        uBuf[bCount - 1] = unwrapped
        if (bCount >= 3) {
            val dt = (tBuf[bCount - 1] - tBuf[0]) / 1e9
            if (dt > 0.0) omega = (uBuf[bCount - 1] - uBuf[0]) / dt
        }
        return true
    }

    // ------------------------------------------------------------------ bar map

    private fun updateBarMap(ballAngle: Double) {
        // Angular half-width of the ball, so we never read the ball itself as a bar.
        val fp = Math.toDegrees(atan(((ringW / 2f) + 4f) / midR).toDouble()) + 2.0
        // Grow the score-text mask by 2 degrees to cover its anti-aliased edges.
        for (a in 0 until N) {
            var t = false
            for (k in -2..2) if (txt[(a + k + N) % N]) t = true
            txtD[a] = t
        }
        for (a in 0 until N) {
            if (abs(angDiff(a.toDouble(), ballAngle)) <= fp) continue
            lastR[a] = ringR[a]
            lastG[a] = ringG[a]
            lastB[a] = ringB[a]
            bar[a] = !txtD[a] && !isPurple(ringR[a], ringG[a], ringB[a])
            known[a] = true
        }
    }

    @Volatile
    private var lastGroups = 0

    /**
     * Finds the bar cluster (largest run of non-purple angles), splits it into colour
     * groups and returns [start, length] in degrees of the MIDDLE group (the one with a
     * different-coloured neighbour on each side), or null if there is no clear middle.
     */
    private fun findTarget(): IntArray? {
        val work = bar.copyOf()
        fillGaps(work, GAP_FILL)
        var cluster: IntArray? = null
        for (run in runs(work, true)) {
            if (run[1] < MIN_ARC || run[1] > N - 20) continue
            if (cluster == null || run[1] > cluster[1]) cluster = run
        }
        if (cluster == null) return null

        val tol = Prefs.tolerance
        val tol2 = tol * tol
        val groups = ArrayList<IntArray>() // [startOffset, endOffset] inside the cluster
        var refR = 0
        var refG = 0
        var refB = 0
        var have = false
        var gStart = 0
        var lastGood = 0
        var firstDiff = 0
        var diffCount = 0
        for (k in 0 until cluster[1]) {
            val a = (cluster[0] + k) % N
            val r = lastR[a]
            val g = lastG[a]
            val b = lastB[a]
            if (isPurple(r, g, b)) continue // thin gap line between two tiles
            if (!have) {
                refR = r; refG = g; refB = b
                gStart = k
                lastGood = k
                have = true
                diffCount = 0
                continue
            }
            val dr = r - refR
            val dg = g - refG
            val db = b - refB
            if (dr * dr + dg * dg + db * db > tol2) {
                if (diffCount == 0) firstDiff = k
                diffCount++
                // 3 samples in a row differ: a new colour group starts. (The dark divider
                // lines between tiles are only 1-2 degrees wide, so they never trigger this.)
                if (diffCount >= 3) {
                    groups.add(intArrayOf(gStart, lastGood))
                    gStart = firstDiff
                    lastGood = k
                    refR = r; refG = g; refB = b
                    diffCount = 0
                }
            } else {
                diffCount = 0
                lastGood = k
                refR = (refR * 3 + r) / 4
                refG = (refG * 3 + g) / 4
                refB = (refB * 3 + b) / 4
            }
        }
        if (!have) return null
        groups.add(intArrayOf(gStart, lastGood))
        lastGroups = groups.size

        // The target has a different-coloured group on BOTH sides (e.g. white between two
        // pinks). A wrong tap loses the game, so if we do not see a clear middle group
        // (3, 5, ... groups) we return null and do not tap.
        val n = groups.size
        if (n < 3 || n % 2 == 0) return null
        val startOff = groups[n / 2][0]
        val endOff = groups[n / 2][1]
        val len = endOff - startOff + 1
        if (len < 2) return null
        return intArrayOf((cluster[0] + startOff) % N, len)
    }

    // ------------------------------------------------------------------ helpers

    /** Maximal circular runs of `value` in mask, as [start, length]. */
    private fun runs(mask: BooleanArray, value: Boolean): List<IntArray> {
        val out = ArrayList<IntArray>()
        var p = -1
        for (i in 0 until N) {
            if (mask[i] != value) {
                p = i
                break
            }
        }
        if (p < 0) return out
        var curStart = -1
        var len = 0
        for (k in 1..N) {
            val idx = (p + k) % N
            if (mask[idx] == value) {
                if (curStart < 0) {
                    curStart = idx
                    len = 0
                }
                len++
            } else if (curStart >= 0) {
                out.add(intArrayOf(curStart, len))
                curStart = -1
            }
        }
        return out
    }

    private fun fillGaps(mask: BooleanArray, maxGap: Int) {
        for (g in runs(mask, false)) {
            if (g[1] <= maxGap) {
                for (k in 0 until g[1]) mask[(g[0] + k) % N] = true
            }
        }
    }

    private fun percentile(hist: IntArray, total: Int, q: Double): Int {
        val target = total * q
        var acc = 0
        for (i in hist.indices) {
            acc += hist[i]
            if (acc >= target) return i
        }
        return hist.size - 1
    }

    /** Angle in degrees, 0 = up, increasing clockwise (screen coordinates). */
    private fun angleOf(dx: Float, dy: Float): Double =
        wrap360(Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())))

    private fun wrap360(a: Double): Double {
        var r = a % 360.0
        if (r < 0) r += 360.0
        return r
    }

    /** Signed difference a - b wrapped to (-180, 180]. */
    private fun angDiff(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /**
     * Ring colour test (HSV: hue 245..315, saturation >= 0.38, value 0.25..0.9).
     * The lilac decoy bars are far less saturated, so they are not "purple".
     */
    private fun isPurple(r: Int, g: Int, b: Int): Boolean {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        if (mx < 64 || mx > 230) return false
        val d = mx - mn
        if (d * 100 < 38 * mx) return false
        val h: Float = when (mx) {
            r -> 60f * (((g - b).toFloat() / d) % 6f)
            g -> 60f * ((b - r).toFloat() / d + 2f)
            else -> 60f * ((r - g).toFloat() / d + 4f)
        }
        val hh = if (h < 0f) h + 360f else h
        return hh >= 245f && hh <= 315f
    }
}
