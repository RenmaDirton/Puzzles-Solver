package com.puzzlesolver

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Position of the dot lattice on the screen, in pixels: point (c, r) is at (x0 + c*px, y0 + r*py). */
class Calibration(val x0: Float, val y0: Float, val px: Float, val py: Float)

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

    class Masks(val ink: BooleanArray, val struct: BooleanArray)

    fun parse(pix: IntArray, w: Int, h: Int, cached: Calibration?): ParseResult? {
        val m = masks(pix, w, h)
        var cal: Calibration? = cached
        if (cal != null && explained(m.ink, w, h, cal) < 0.90f) cal = null
        val fresh = cal == null
        val first = cal ?: calibrate(m.struct, w, h) ?: return null
        var best = parseWith(m.ink, w, h, first)
        if (fresh && quality(best) < 0) {
            // suspicious result: try the neighbouring lattice sizes before giving up
            for (p in floatArrayOf(first.px * 2f, first.px / 2f)) {
                if (p < 18f || p > 100f) continue
                val c = calibrateAt(m.struct, w, h, p) ?: continue
                val r = parseWith(m.ink, w, h, c)
                if (quality(r) > quality(best)) best = r
            }
        }
        return best
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
        return Masks(ink, struct)
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

    private fun explained(ink: BooleanArray, w: Int, h: Int, cal: Calibration): Float {
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

    private class Proj(val col: FloatArray, val row: FloatArray)

    private fun projections(struct: BooleanArray, w: Int, h: Int): Proj {
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
        val c = highpass(col)
        val r = highpass(row)
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

    fun calibrate(struct: BooleanArray, w: Int, h: Int): Calibration? {
        val pr = projections(struct, w, h)
        val count = 820
        val scores = FloatArray(count)
        var maxScore = 0f
        for (i in 0 until count) {
            val p = 18f + i * 0.1f
            scores[i] = bestOffset(pr.col, p).first + bestOffset(pr.row, p).first
            if (scores[i] > maxScore) maxScore = scores[i]
        }
        if (maxScore <= 0f) return null
        var pc = 18f
        for (i in 0 until count) if (scores[i] >= 0.93f * maxScore) pc = 18f + i * 0.1f
        return fit(pr, pc)
    }

    private fun calibrateAt(struct: BooleanArray, w: Int, h: Int, pitch: Float): Calibration? =
        fit(projections(struct, w, h), pitch)

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
        fun headScore(id: Int, dx: Int, dy: Int): Int {
            val x = px(id % nc)
            val y = py(id / nc)
            val qx = -dy
            val qy = dx
            var cnt = 0
            for (u in -9..9) {
                for (s in SIDES) {
                    val sx = (x + dx * u + qx * s).roundToInt()
                    val sy = (y + dy * u + qy * s).roundToInt()
                    if (sx in 0 until w && sy in 0 until h && ink[sy * w + sx]) cnt++
                }
            }
            return cnt
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
            if (hiScore < 8 || hiScore < 2.5f * loScore + 6f) {
                // not clearly one head and one tail: keep it as an obstacle instead of guessing
                blockers.add(comp.toIntArray())
                continue
            }
            val hi = if (scores[0] > scores[1]) 0 else 1
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
}
