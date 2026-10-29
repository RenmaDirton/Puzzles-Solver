package com.puzzlesolver

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Position of the dot lattice on the screen, in pixels: point (c, r) is at (x0 + c*px, y0 + r*py). */
class Calibration(val x0: Float, val y0: Float, val px: Float, val py: Float, val exp: Float = 1f)

/** One arrow (snake). Cells are lattice point ids (row * cols + col). */
class Arrow(
    val headC: Int,
    val headR: Int,
    val dirC: Int,
    val dirR: Int,
    val cells: IntArray,
    val headX: Float,
    val headY: Float,
    /** A point on the line just behind the head, in screen pixels: where to tap. */
    val tapX: Float,
    val tapY: Float,
) {
    /** Removal round assigned by the solver (0 = can leave right now). */
    var round: Int = -1
    val cellSet: Set<Int> by lazy { cells.toHashSet() }
}

class ParseResult(
    val arrows: List<Arrow>,
    /** Shapes that could not be read as an arrow. They still block other arrows. */
    val blockers: List<IntArray>,
    val cols: Int,
    val rows: Int,
    val cal: Calibration,
)

/**
 * Reads an "arrow maze" board from raw screen pixels (ARGB ints, row by row).
 *
 * The board is a lattice of points. Every arrow is a polyline along lattice lines, and
 * different arrows never share a lattice point, so each connected component of ink edges
 * is one arrow. The head is the end whose neighbourhood is filled by the arrowhead triangle.
 *
 * "Ink" is anything clearly different from the background, plus the faded yellow of arrows
 * that are sliding off the board (those fade towards their tail), but not the faint dots
 * that the game leaves on cleared cells.
 *
 * This file has no Android dependencies so it can be compiled and tested on a normal JVM.
 */
object BoardCore {

    const val BAND_TOP = 0.19f
    const val BAND_BOT = 0.87f

    private const val RIGHT = 1
    private const val DOWN = 2
    private const val LEFT = 4
    private const val UP = 8

    class Masks(val ink: BooleanArray, val struct: BooleanArray, val win: Int = 25)

    const val PMIN = 10f
    const val PMAX = 170f

    /** counters for the test harness: how many arrows were decided by the old / the size-independent head test */
    @JvmField var dbgOld = 0
    @JvmField var dbgAdaptive = 0
    @JvmField var dbgDisagree = 0
    @JvmField var forceAdaptive = false
    @JvmField var forceOld = false
    @JvmField var dbgCands = false

    fun parse(pix: IntArray, w: Int, h: Int, cached: Calibration?): ParseResult? {
        val m = masks(pix, w, h)
        var cal: Calibration? = cached
        // a cached lattice stays valid while it explains the ink about as well as when it was fitted
        // (sparse boards with big arrowheads never reach 0.9, so a fixed 0.9 refitted on every scan)
        if (cal != null && explained(m.ink, w, h, cal) < min(0.90f, cal.exp - 0.08f)) cal = null
        val fresh = cal == null
        val first = cal ?: pickCalibration(m, w, h) ?: return null
        var best = parseWith(m.ink, w, h, first)
        if (fresh && quality(best) < 0) {
            // suspicious result: try the neighbouring lattice sizes before giving up
            for (p in floatArrayOf(first.px * 2f, first.px / 2f, first.px * 3f)) {
                if (p < PMIN || p > PMAX) continue
                val c = calibrateAt(m.struct, w, h, p, m.win) ?: continue
                val r = parseWith(m.ink, w, h, c)
                if (quality(r) > quality(best)) best = r
            }
        }
        return best
    }

    /**
     * The best-scoring pitch can be a multiple of the real one (a small board with thin lines scores the
     * same at 2x or 4x). A multiple leaves much of the drawing off the lattice, while the real pitch
     * explains it almost completely, so: if halving (or a third) explains the ink much better, use that.
     */
    private fun pickCalibration(m: Masks, w: Int, h: Int): Calibration? {
        var cur = calibrate(m.struct, w, h, m.win) ?: return null
        var curExp = explained(m.ink, w, h, cur)
        var guard = 0
        var moved = true
        while (moved && guard++ < 3) {
            moved = false
            for (div in intArrayOf(2, 3)) {
                val q = cur.px / div
                if (q < PMIN) continue
                val c = calibrateAt(m.struct, w, h, q, m.win) ?: continue
                val e = explained(m.ink, w, h, c)
                if (dbgCands) println("   pitch %.2f explained %.3f -> /%d %.2f explained %.3f".format(cur.px, curExp, div, c.px, e))
                val need = if (c.px < 18f) 0.97f else 0.93f
                if (e - curExp > 0.2f && e >= need) {
                    cur = c
                    curExp = e
                    moved = true
                    break
                }
            }
        }
        return Calibration(cur.x0, cur.y0, cur.px, cur.py, curExp)
    }

    private fun quality(r: ParseResult) = r.arrows.size - 2 * r.blockers.size

    // ------------------------------------------------------------------ pixels

    fun masks(pix: IntArray, w: Int, h: Int): Masks {
        val bg = pix[(h / 2) * w + 8]
        val bgR = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bgB = bg and 0xFF
        val ink = BooleanArray(w * h)
        val struct = BooleanArray(w * h)
        for (i in pix.indices) {
            val p = pix[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val d = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)
            val rb = r - b
            ink[i] = d > 165 || (rb >= 78 && d >= 60)
            struct[i] = d >= 60 || (rb >= 78 && d >= 45)
        }
        return Masks(ink, struct, strokeWindow(ink, w, h))
    }

    /**
     * Width of the running-median window used to remove the background from the line projections.
     * It must be clearly wider than a line, so boards with big cells (thick lines) need a wider one.
     * Thin-line boards keep the original 25.
     */
    private fun strokeWindow(ink: BooleanArray, w: Int, h: Int): Int {
        val y0 = (h * BAND_TOP).toInt()
        val y1 = (h * BAND_BOT).toInt()
        val runs = ArrayList<Int>()
        var y = y0
        while (y < y1) {
            var x = 0
            while (x < w) {
                if (ink[y * w + x]) {
                    var e = x
                    while (e < w && ink[y * w + e]) e++
                    val len = e - x
                    if (len in 2..110) runs.add(len)
                    x = e
                } else x++
            }
            y += 6
        }
        var x2 = 0
        while (x2 < w) {
            var yy = y0
            while (yy < y1) {
                if (ink[yy * w + x2]) {
                    var e = yy
                    while (e < y1 && ink[e * w + x2]) e++
                    val len = e - yy
                    if (len in 2..110) runs.add(len)
                    yy = e
                } else yy++
            }
            x2 += 6
        }
        if (runs.size < 20) return 25
        runs.sort()
        val stroke = runs[runs.size / 3]
        var win = max(25, (4.5f * stroke).toInt())
        if (win % 2 == 0) win++
        return min(win, 151)
    }

    /**
     * A compact fingerprint of the dark ink in the board band (one bit per 3x3 pixels).
     * Two frames of a still screen give identical fingerprints; if any arrow is sliding the
     * fingerprints differ a lot. This does not depend on any particular colour.
     */
    fun inkSignature(pix: IntArray, w: Int, h: Int): LongArray {
        val y0 = (h * BAND_TOP).toInt()
        val y1 = (h * BAND_BOT).toInt()
        val bg = pix[(h / 2) * w + 8]
        val bgR = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bgB = bg and 0xFF
        val nx = w / 3
        val ny = (y1 - y0) / 3
        val out = LongArray((nx * ny + 63) / 64)
        var k = 0
        for (j in 0 until ny) {
            val base = (y0 + j * 3) * w
            for (i in 0 until nx) {
                val p = pix[base + i * 3]
                val d = abs(((p shr 16) and 0xFF) - bgR) + abs(((p shr 8) and 0xFF) - bgG) + abs((p and 0xFF) - bgB)
                if (d > 165) out[k ushr 6] = out[k ushr 6] or (1L shl (k and 63))
                k++
            }
        }
        return out
    }

    /** Number of fingerprint cells that differ (a huge number if the sizes do not match). */
    fun signatureDiff(a: LongArray, b: LongArray): Int {
        if (a.size != b.size) return Int.MAX_VALUE
        var n = 0
        for (i in a.indices) n += java.lang.Long.bitCount(a[i] xor b[i])
        return n
    }

    /** Counts gold (arrow is sliding) and red (arrow hit something) pixels inside the board band. */
    fun motionCounts(pix: IntArray, w: Int, h: Int): IntArray {
        val y0 = (h * BAND_TOP).toInt()
        val y1 = (h * BAND_BOT).toInt()
        var gold = 0
        var red = 0
        var y = y0
        while (y < y1) {
            val base = y * w
            var x = 0
            while (x < w) {
                val p = pix[base + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r >= 225 && r - b >= 85 && g >= 140 && b <= 175) gold++
                else if (r >= 150 && g <= 110 && b <= 100 && r - g >= 70) red++
                x += 2
            }
            y += 2
        }
        return intArrayOf(gold * 4, red * 4)
    }

    // ------------------------------------------------------------------ calibration

    fun explained(ink: BooleanArray, w: Int, h: Int, cal: Calibration): Float {
        val y0 = (h * BAND_TOP).toInt()
        val y1 = (h * BAND_BOT).toInt()
        val nearX = BooleanArray(w) { x ->
            val t = (x - cal.x0) / cal.px
            abs(t - t.roundToInt()) * cal.px <= 3.2f
        }
        val nearY = BooleanArray(h) { y ->
            val t = (y - cal.y0) / cal.py
            abs(t - t.roundToInt()) * cal.py <= 3.2f
        }
        var total = 0
        var ok = 0
        for (y in y0 until y1) {
            val base = y * w
            val ny = nearY[y]
            for (x in 0 until w) {
                if (ink[base + x]) {
                    total++
                    if (ny || nearX[x]) ok++
                }
            }
        }
        return if (total == 0) 1f else ok.toFloat() / total
    }

    /** Subtract a running median so only the lattice peaks remain (a half-size lattice can no longer win). */
    private fun highpass(v: FloatArray, win: Int = 25): FloatArray {
        val n = v.size
        val half = win / 2
        val out = FloatArray(n)
        val buf = FloatArray(win)
        for (i in 0 until n) {
            for (k in 0 until win) buf[k] = v[(i - half + k).coerceIn(0, n - 1)]
            buf.sort()
            out[i] = max(v[i] - buf[half], 0f)
        }
        return out
    }

    /** Box-blur by [sw], then keep only local maxima within +-sw/2 (a flat-topped line becomes one sharp peak). */
    private fun ridge(v: FloatArray, sw: Int): FloatArray {
        val n = v.size
        val pre = FloatArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + v[i]
        val half = sw / 2
        val bl = FloatArray(n) { i ->
            val a = max(0, i - half)
            val b = min(n, i + half + 1)
            (pre[b] - pre[a]) / (b - a)
        }
        val out = FloatArray(n)
        for (i in 0 until n) {
            var isMax = bl[i] > 0f
            var k = 1
            while (isMax && k <= half) {
                val l = bl[max(0, i - k)]
                val rr = bl[min(n - 1, i + k)]
                if (l > bl[i] || rr > bl[i] || (l == bl[i] && k == 1 && i > 0 && out[i - 1] > 0f)) isMax = false
                k++
            }
            if (isMax) out[i] = bl[i]
        }
        return out
    }

    private class Proj(val col: FloatArray, val row: FloatArray)

    private fun projections(struct: BooleanArray, w: Int, h: Int, win: Int = 25): Proj {
        val y0 = (h * BAND_TOP).toInt()
        val y1 = (h * BAND_BOT).toInt()
        val col = FloatArray(w)
        val row = FloatArray(h)
        for (y in y0 until y1) {
            val base = y * w
            for (x in 0 until w) {
                if (struct[base + x]) {
                    col[x] += 1f
                    row[y] += 1f
                }
            }
        }
        var c = highpass(col, win)
        var r = highpass(row, win)
        if (win >= 45) {
            // thick lines: reduce every line to a sharp ridge at its centre so a wrong pitch cannot hit it by chance
            val sw = max(6, (win / 4.5f).toInt())
            c = ridge(c, sw)
            r = ridge(r, sw)
        }
        for (y in 0 until y0) r[y] = 0f
        for (y in y1 until h) r[y] = 0f
        return Proj(c, r)
    }

    private fun scoreAxis(proj: FloatArray, p: Float, o: Float): Float {
        val n = proj.size
        var s = 0f
        var k = 0
        while (true) {
            val pos = o + p * k
            if (pos >= n - 1) break
            val i = pos.roundToInt().coerceIn(1, n - 2)
            s += max(proj[i - 1], max(proj[i], proj[i + 1]))
            k++
        }
        return s
    }

    /** returns (score, offset) of the best offset for pitch p */
    private fun bestOffset(proj: FloatArray, p: Float): Pair<Float, Float> {
        var best = -1f
        var bo = 0f
        var o = 0f
        while (o < p) {
            val s = scoreAxis(proj, p, o)
            if (s > best) {
                best = s
                bo = o
            }
            o += 0.25f
        }
        return best to bo
    }

    /** Least-squares fit of the line centres: position = o + k * p. Returns (p, o). */
    private fun refineAxis(proj: FloatArray, p0: Float, o0: Float): Pair<Float, Float>? {
        val n = proj.size
        var cnt = 0
        var sk = 0.0
        var sc = 0.0
        var skk = 0.0
        var skc = 0.0
        var k = 0
        while (o0 + k * p0 < n - 6) {
            val xc = o0 + k * p0
            if (xc >= 6f) {
                val lo = (xc - 5f).toInt()
                val hi = min((xc + 5f).toInt() + 1, n)
                var mx = 0f
                var mn = Float.MAX_VALUE
                for (i in lo until hi) {
                    mx = max(mx, proj[i])
                    mn = min(mn, proj[i])
                }
                if (mx > 8f) {
                    var sw = 0.0
                    var sx = 0.0
                    for (i in lo until hi) {
                        val v = (proj[i] - mn).toDouble()
                        sw += v
                        sx += v * i
                    }
                    if (sw > 0.0) {
                        val c = sx / sw
                        cnt++
                        sk += k.toDouble()
                        sc += c
                        skk += k.toDouble() * k
                        skc += k * c
                    }
                }
            }
            k++
        }
        if (cnt < 4) return null
        val den = cnt * skk - sk * sk
        if (abs(den) < 1e-9) return null
        val p = (cnt * skc - sk * sc) / den
        val o = (sc - p * sk) / cnt
        if (abs(p - p0) > p0 * 0.05) return null
        return p.toFloat() to o.toFloat()
    }

    fun calibrate(struct: BooleanArray, w: Int, h: Int, win: Int = 25): Calibration? = calibrationCandidates(struct, w, h, 18f, PMAX, win).firstOrNull()

    /** Peaks of the pitch score that come close to the best one, largest pitch first (at most 6). */
    fun calibrationCandidates(struct: BooleanArray, w: Int, h: Int, lo: Float = 18f, hi: Float = PMAX, win: Int = 25): List<Calibration> {
        val pr = projections(struct, w, h, win)
        val count = ((hi - lo) / 0.1f).toInt() + 1
        val scores = FloatArray(count)
        var maxScore = 0f
        for (i in 0 until count) {
            val p = lo + i * 0.1f
            scores[i] = bestOffset(pr.col, p).first + bestOffset(pr.row, p).first
            if (scores[i] > maxScore) maxScore = scores[i]
        }
        if (maxScore <= 0f) return emptyList()
        val thr = 0.93f * maxScore
        val peaks = ArrayList<Float>()
        var i = count - 1
        while (i >= 0 && peaks.size < 6) {
            if (scores[i] < thr) { i--; continue }
            // one cluster of neighbouring pitches: keep its best one (the largest wins ties)
            var j = i
            var bi = i
            while (j >= 0 && (scores[j] >= thr || (j > 0 && scores[j - 1] >= thr) || (j > 1 && scores[j - 2] >= thr))) {
                if (scores[j] > scores[bi] + 1e-6f) bi = j
                j--
            }
            peaks.add(lo + bi * 0.1f)
            i = j
        }
        if (peaks.isEmpty()) return emptyList()
        // the original rule (largest pitch within 93% of the best) stays the first candidate
        var largest = lo
        for (k in 0 until count) if (scores[k] >= thr) largest = lo + k * 0.1f
        val out = ArrayList<Calibration>()
        out.add(fit(pr, largest))
        for (pk in peaks) if (abs(pk - largest) > 0.6f) out.add(fit(pr, pk))
        return out
    }

    private fun calibrateAt(struct: BooleanArray, w: Int, h: Int, pitch: Float, win: Int = 25): Calibration? =
        fit(projections(struct, w, h, win), pitch)

    private fun fit(pr: Proj, pc: Float): Calibration {
        var bestTotal = -1f
        var pitch = pc
        var ox = 0f
        var oy = 0f
        for (k in -30..29) {
            val p = pc + k * 0.02f
            val sx = bestOffset(pr.col, p)
            val sy = bestOffset(pr.row, p)
            if (sx.first + sy.first > bestTotal) {
                bestTotal = sx.first + sy.first
                pitch = p
                ox = sx.second
                oy = sy.second
            }
        }
        val rx = refineAxis(pr.col, pitch, ox)
        val ry = refineAxis(pr.row, pitch, oy)
        return Calibration(
            x0 = rx?.second ?: ox,
            y0 = ry?.second ?: oy,
            px = rx?.first ?: pitch,
            py = ry?.first ?: pitch,
        )
    }

    // ------------------------------------------------------------------ arrows

    private fun parseWith(ink: BooleanArray, w: Int, h: Int, cal: Calibration): ParseResult {
        val y0 = (h * BAND_TOP).toInt()
        val y1 = (h * BAND_BOT).toInt()
        val cmin = ceil((0.03f * w - cal.x0) / cal.px).toInt()
        val cmax = floor((0.97f * w - cal.x0) / cal.px).toInt()
        val rmin = ceil((y0 - cal.y0) / cal.py).toInt()
        val rmax = floor((y1 - cal.y0) / cal.py).toInt()
        val nc = cmax - cmin + 1
        val nr = rmax - rmin + 1

        fun px(c: Int) = cal.x0 + (cmin + c) * cal.px
        fun py(r: Int) = cal.y0 + (rmin + r) * cal.py

        fun inkNear(x: Int, y: Int): Boolean {
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h && ink[yy * w + xx]) return true
                }
            }
            return false
        }

        fun cover(xa: Float, ya: Float, xb: Float, yb: Float): Int {
            var hit = 0
            for (i in 0 until 9) {
                val t = 0.25f + 0.5f * i / 8f
                if (inkNear((xa + (xb - xa) * t).roundToInt(), (ya + (yb - ya) * t).roundToInt())) hit++
            }
            return hit
        }

        // ---- edges between neighbouring lattice points ----
        val conn = ByteArray(nc * nr)
        for (r in 0 until nr) {
            for (c in 0 until nc) {
                val id = r * nc + c
                if (c + 1 < nc && cover(px(c), py(r), px(c + 1), py(r)) >= 8) {
                    conn[id] = (conn[id].toInt() or RIGHT).toByte()
                    conn[id + 1] = (conn[id + 1].toInt() or LEFT).toByte()
                }
                if (r + 1 < nr && cover(px(c), py(r), px(c), py(r + 1)) >= 8) {
                    conn[id] = (conn[id].toInt() or DOWN).toByte()
                    conn[id + nc] = (conn[id + nc].toInt() or UP).toByte()
                }
            }
        }

        fun neighbours(id: Int): IntArray {
            val m = conn[id].toInt()
            val out = IntArray(Integer.bitCount(m))
            var n = 0
            if (m and RIGHT != 0) out[n++] = id + 1
            if (m and LEFT != 0) out[n++] = id - 1
            if (m and DOWN != 0) out[n++] = id + nc
            if (m and UP != 0) out[n++] = id - nc
            return out
        }

        // Ink in a tight window around an endpoint. The arrowhead is a filled triangle spanning
        // about -12..+11 px around the lattice point, so it fills the side area; a tail end has
        // nothing there. The window stays inside one lattice step so the other end of a
        // one-segment arrow cannot leak in.
        // On boards with bigger cells (easy levels) the whole drawing is bigger, so the window grows too.
        val k = max(1f, (cal.px + cal.py) / 2f / 42f)
        fun headScore(id: Int, dx: Int, dy: Int): Int {
            val x = px(id % nc)
            val y = py(id / nc)
            val qx = -dy
            val qy = dx
            var cnt = 0
            for (u in -9..9) {
                for (s in SIDES) {
                    val sx = (x + dx * u * k + qx * s * k).roundToInt()
                    val sy = (y + dy * u * k + qy * s * k).roundToInt()
                    if (sx in 0 until w && sy in 0 until h && ink[sy * w + sx]) cnt++
                }
            }
            return cnt
        }

        // ---- size-independent head test ----
        // A head is a filled triangle, so across the line it is much thicker than the line itself.
        // Everything is measured against the line thickness of this very board, in its own pixels.
        val pAvg = (cal.px + cal.py) / 2f
        val extLim = max(3, (0.5f * pAvg).toInt())

        fun inkAt(a: Int, b: Int) = a in 0 until w && b in 0 until h && ink[b * w + a]

        /** Thickness of the ink across the line at (x,y); qx,qy is the unit vector across the line. */
        fun extent(x: Float, y: Float, qx: Float, qy: Float): Int {
            var off0 = Int.MIN_VALUE
            for (o in intArrayOf(0, 1, -1, 2, -2)) {
                if (inkAt((x + qx * o).roundToInt(), (y + qy * o).roundToInt())) { off0 = o; break }
            }
            if (off0 == Int.MIN_VALUE) return 0
            var a = 0
            while (a < extLim && inkAt((x + qx * (off0 + a + 1)).roundToInt(), (y + qy * (off0 + a + 1)).roundToInt())) a++
            var b = 0
            while (b < extLim && inkAt((x + qx * (off0 - b - 1)).roundToInt(), (y + qy * (off0 - b - 1)).roundToInt())) b++
            return a + b + 1
        }

        val strokeSamples = ArrayList<Int>()
        for (r in 0 until nr) {
            for (c in 0 until nc) {
                val m0 = conn[r * nc + c].toInt()
                if (m0 and RIGHT != 0) strokeSamples.add(extent((px(c) + px(c + 1)) / 2f, py(r), 0f, 1f))
                if (m0 and DOWN != 0) strokeSamples.add(extent(px(c), (py(r) + py(r + 1)) / 2f, 1f, 0f))
            }
        }
        strokeSamples.removeAll { it <= 0 }
        strokeSamples.sort()
        val stroke = if (strokeSamples.isEmpty()) 3f else max(1f, strokeSamples[strokeSamples.size / 2].toFloat())

        /** How many times thicker than the line the drawing gets near this end (about 1 = plain tail, 2.5+ = head). */
        fun headRatio(id: Int, dx: Int, dy: Int): Float {
            val x = px(id % nc)
            val y = py(id / nc)
            val qx = -dy.toFloat()
            val qy = dx.toFloat()
            var mx = 0
            var t = -0.42f * pAvg
            val tEnd = 0.3f * pAvg
            while (t <= tEnd) {
                val e = extent(x + dx * t, y + dy * t, qx, qy)
                if (e > mx) mx = e
                t += 1f
            }
            return mx / stroke
        }

        // ---- components => arrows (or blockers when unreadable) ----
        val seen = BooleanArray(nc * nr)
        val arrows = ArrayList<Arrow>()
        val blockers = ArrayList<IntArray>()
        val stack = IntArray(nc * nr)
        for (start in 0 until nc * nr) {
            if (seen[start] || conn[start].toInt() == 0) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            val comp = ArrayList<Int>()
            while (sp > 0) {
                val q = stack[--sp]
                comp.add(q)
                for (n in neighbours(q)) {
                    if (!seen[n]) {
                        seen[n] = true
                        stack[sp++] = n
                    }
                }
            }
            val ends = comp.filter { Integer.bitCount(conn[it].toInt()) == 1 }
            val branched = comp.any { Integer.bitCount(conn[it].toInt()) > 2 }
            if (comp.size < 2 || ends.size != 2 || branched) {
                blockers.add(comp.toIntArray())
                continue
            }
            val scores = IntArray(2)
            val dirs = Array(2) { IntArray(2) }
            for (i in 0..1) {
                val e = ends[i]
                val nb = neighbours(e)[0]
                dirs[i][0] = (e % nc) - (nb % nc)
                dirs[i][1] = (e / nc) - (nb / nc)
                scores[i] = headScore(e, dirs[i][0], dirs[i][1])
            }
            val hiScore = max(scores[0], scores[1])
            val loScore = min(scores[0], scores[1])
            val oldOk = !(hiScore < 8 || hiScore < 2.5f * loScore + 6f)
            val ra0 = headRatio(ends[0], dirs[0][0], dirs[0][1])
            val ra1 = headRatio(ends[1], dirs[1][0], dirs[1][1])
            val raHi = max(ra0, ra1)
            val raLo = min(ra0, ra1)
            val adOk = raHi >= 1.7f && raHi >= 1.35f * raLo
            val adaptiveOnly = forceAdaptive || (!forceOld && (pAvg < 22f || pAvg > 75f))
            val hiOld = if (scores[0] > scores[1]) 0 else 1
            val hiAd = if (ra0 > ra1) 0 else 1
            val hi: Int
            if (adaptiveOnly) {
                if (!adOk) { blockers.add(comp.toIntArray()); continue }
                hi = hiAd
                dbgAdaptive++
            } else if (oldOk) {
                hi = hiOld
                dbgOld++
                if (adOk && hiAd != hiOld) dbgDisagree++
            } else if (adOk) {
                hi = hiAd
                dbgAdaptive++
            } else {
                // not clearly one head and one tail: keep it as an obstacle instead of guessing
                blockers.add(comp.toIntArray())
                continue
            }
            val head = ends[hi]
            val nb = neighbours(head)[0]
            val hc = head % nc
            val hr = head / nc
            arrows.add(
                Arrow(
                    headC = hc, headR = hr,
                    dirC = dirs[hi][0], dirR = dirs[hi][1],
                    cells = comp.toIntArray(),
                    headX = px(hc), headY = py(hr),
                    tapX = (px(hc) + px(nb % nc)) / 2f,
                    tapY = (py(hr) + py(nb / nc)) / 2f,
                ),
            )
        }
        return ParseResult(arrows, blockers, nc, nr, cal)
    }

    private val SIDES = intArrayOf(-12, -11, -10, -9, -8, -7, -6, -5, 5, 6, 7, 8, 9, 10, 11, 12)

    // ------------------------------------------------------------------ screens with a big button

    /** A wide rounded button in the lower part of a menu screen: its centre row and how strongly it stands out. */
    class Button(val cy: Int, val contrast: Int)

    /**
     * Finds big centred buttons (Next Level, Continue, Home...) without relying on their colours:
     * a row is part of a button when the fill 20% and 80% across is the same colour, and going
     * outwards from there the colour changes at about 8-19% / 81-92% of the width.
     */
    fun findButtons(pix: IntArray, w: Int, h: Int, yMaxFrac: Float = 0.94f): List<Button> {
        fun d(a: Int, b: Int) =
            abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))
        val ya = (h * 0.50f).toInt()
        val yb = (h * min(0.94f, yMaxFrac)).toInt()
        val xl = (w * 0.20f).toInt()
        val xr = (w * 0.80f).toInt()
        val rowContrast = IntArray(h)
        for (y in ya..yb) {
            val base = y * w
            val fill = pix[base + xl]
            if (d(fill, pix[base + xr]) > 45) continue
            var le = -1
            var x = xl
            while (x > (w * 0.04f).toInt()) {
                if (d(pix[base + x], fill) > 60) { le = x; break }
                x -= 2
            }
            var re = -1
            x = xr
            while (x < (w * 0.96f).toInt()) {
                if (d(pix[base + x], fill) > 60) { re = x; break }
                x += 2
            }
            if (le < 0 || re < 0) continue
            if (le < w * 0.08f || le > w * 0.19f || re < w * 0.81f || re > w * 0.92f) continue
            if (abs(le - (w - re)) > w * 0.04f) continue
            val out = d(fill, pix[base + (w * 0.03f).toInt()])
            if (out < 60) continue
            rowContrast[y] = out
        }
        val res = ArrayList<Button>()
        var y = ya
        while (y <= yb) {
            if (rowContrast[y] == 0) { y++; continue }
            var top = y
            var last = y
            var sum = 0
            var cnt = 0
            while (y <= yb && y - last <= 3) {
                if (rowContrast[y] > 0) { last = y; sum += rowContrast[y]; cnt++ }
                y++
            }
            val hh = last - top + 1
            if (hh >= h * 0.04f && hh <= h * 0.10f && cnt >= hh * 0.7f) res.add(Button((top + last) / 2, sum / cnt))
        }
        return res
    }

    // ------------------------------------------------------------------ ad skip control

    /**
     * True when the top-right corner holds a small bright symbol on a flat patch (the usual ">>" / X of an ad).
     * Only used while an ad is showing.
     */
    fun cornerGlyph(pix: IntArray, w: Int, h: Int): Boolean {
        val xa = (w * 0.80f).toInt()
        val xb = (w * 0.99f).toInt()
        val ya = (h * 0.015f).toInt()
        val yb = (h * 0.075f).toInt()
        val rs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val bs = ArrayList<Int>()
        var y = ya
        while (y < yb) {
            var x = xa
            while (x < xb) {
                val p = pix[y * w + x]
                rs.add((p shr 16) and 255); gs.add((p shr 8) and 255); bs.add(p and 255)
                x += 4
            }
            y += 4
        }
        if (rs.isEmpty()) return false
        rs.sort(); gs.sort(); bs.sort()
        val mr = rs[rs.size / 2]; val mg = gs[gs.size / 2]; val mb = bs[bs.size / 2]
        var diff = 0
        var tot = 0
        for (yy in ya until yb) {
            for (xx in xa until xb) {
                val p = pix[yy * w + xx]
                val d = abs(((p shr 16) and 255) - mr) + abs(((p shr 8) and 255) - mg) + abs((p and 255) - mb)
                if (d > 120) diff++
                tot++
            }
        }
        val f = diff.toFloat() / tot
        return f in 0.006f..0.5f
    }

    // ------------------------------------------------------------------ whole-screen thumbnail

    const val THUMB_W = 16
    const val THUMB_H = 32

    /** Tiny brightness picture of the whole screen: tells whether the screen changed at all. */
    fun thumb(pix: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(THUMB_W * THUMB_H)
        for (ty in 0 until THUMB_H) {
            for (tx in 0 until THUMB_W) {
                var sum = 0
                var n = 0
                for (dy in 0..2) {
                    for (dx in 0..2) {
                        val x = ((tx + (dx + 0.5f) / 3f) / THUMB_W * w).toInt().coerceIn(0, w - 1)
                        val y = ((ty + (dy + 0.5f) / 3f) / THUMB_H * h).toInt().coerceIn(0, h - 1)
                        val p = pix[y * w + x]
                        sum += (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10
                        n++
                    }
                }
                out[ty * THUMB_W + tx] = sum / n
            }
        }
        return out
    }

    /** Mean brightness difference of two thumbnails (0 = same screen; > 5 = something changed). */
    fun thumbDiff(a: IntArray, b: IntArray): Float {
        var d = 0L
        for (i in a.indices) d += abs(a[i] - b[i])
        return d.toFloat() / a.size
    }
}
