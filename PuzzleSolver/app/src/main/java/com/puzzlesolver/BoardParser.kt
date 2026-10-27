package com.puzzlesolver

import android.graphics.Bitmap
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
 * Reads an "arrow maze" board from a screenshot.
 *
 * The board is a lattice of points. Every arrow is a polyline along lattice lines, and
 * different arrows never share a lattice point, so each connected component of ink edges
 * is one arrow. The head is the endpoint with arrowhead wings (extra ink on both sides).
 *
 * "Ink" is anything clearly different from the background, so brown, gold (highlighted)
 * and red (blocked) arrows are all seen, while the faint lattice dots left behind on
 * cleared areas are ignored.
 */
object BoardParser {

    private const val RIGHT = 1
    private const val DOWN = 2
    private const val LEFT = 4
    private const val UP = 8

    private const val INK_THRESHOLD = 165

    fun parse(bmp: Bitmap, cached: Calibration?): ParseResult? {
        val w = bmp.width
        val h = bmp.height
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)

        // ---- 1. ink mask: anything clearly different from the background colour ----
        val bg = pix[(h / 2) * w + 8]
        val bgR = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bgB = bg and 0xFF
        val mask = BooleanArray(w * h)
        for (i in pix.indices) {
            val p = pix[i]
            val d = abs(((p shr 16) and 0xFF) - bgR) +
                abs(((p shr 8) and 0xFF) - bgG) +
                abs((p and 0xFF) - bgB)
            mask[i] = d > INK_THRESHOLD
        }

        // Board lives in the middle band of the screen (header and bottom buttons excluded).
        val y0 = (h * 0.20f).toInt()
        val y1 = (h * 0.86f).toInt()

        // Reuse the previous lattice if it still explains the ink (cheap and stable when
        // the board is mostly cleared); otherwise fit a new one.
        var cal: Calibration? = cached
        if (cal != null && explained(mask, w, h, y0, y1, cal) < 0.90f) cal = null
        val usedCal = cal ?: calibrate(mask, w, h, y0, y1) ?: return null
        return parseWith(mask, w, h, y0, y1, usedCal)
    }

    // ------------------------------------------------------------------ calibration

    private fun explained(mask: BooleanArray, w: Int, h: Int, y0: Int, y1: Int, cal: Calibration): Float {
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
                if (mask[base + x]) {
                    total++
                    if (ny || nearX[x]) ok++
                }
            }
        }
        return if (total == 0) 1f else ok.toFloat() / total
    }

    private fun scoreAxis(proj: FloatArray, p: Float, o: Float): Float {
        val n = proj.size
        var s = 0f
        var x = o
        while (x < n - 1) {
            val i = x.roundToInt().coerceIn(1, n - 2)
            s += max(proj[i - 1], max(proj[i], proj[i + 1]))
            x += p
        }
        return s
    }

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
                if (mx > p0 * 1.5f) {
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
        if (p < p0 * 0.9 || p > p0 * 1.1) return null
        return p.toFloat() to o.toFloat()
    }

    private fun calibrate(mask: BooleanArray, w: Int, h: Int, y0: Int, y1: Int): Calibration? {
        val col = FloatArray(w)
        val row = FloatArray(h)
        for (y in y0 until y1) {
            val base = y * w
            for (x in 0 until w) {
                if (mask[base + x]) {
                    col[x] += 1f
                    row[y] += 1f
                }
            }
        }

        // coarse pass: largest pitch that still explains (almost) all the ink
        val pitches = FloatArray(421) { 18f + it * 0.1f }
        val scores = FloatArray(421)
        var maxScore = 0f
        for (i in pitches.indices) {
            scores[i] = bestOffset(col, pitches[i]).first + bestOffset(row, pitches[i]).first
            if (scores[i] > maxScore) maxScore = scores[i]
        }
        if (maxScore <= 0f) return null
        var pc = pitches[0]
        for (i in pitches.indices) {
            if (scores[i] >= 0.93f * maxScore && pitches[i] > pc) pc = pitches[i]
        }

        // fine pass
        var bestTotal = -1f
        var pitch = pc
        var ox = 0f
        var oy = 0f
        for (k in -30..30) {
            val p = pc + k * 0.02f
            val sx = bestOffset(col, p)
            val sy = bestOffset(row, p)
            if (sx.first + sy.first > bestTotal) {
                bestTotal = sx.first + sy.first
                pitch = p
                ox = sx.second
                oy = sy.second
            }
        }

        val rx = refineAxis(col, pitch, ox)
        val ry = refineAxis(row, pitch, oy)
        return Calibration(
            x0 = rx?.second ?: ox,
            y0 = ry?.second ?: oy,
            px = rx?.first ?: pitch,
            py = ry?.first ?: pitch,
        )
    }

    // ------------------------------------------------------------------ arrows

    private fun parseWith(mask: BooleanArray, w: Int, h: Int, y0: Int, y1: Int, cal: Calibration): ParseResult {
        val cmin = ceil((0.04f * w - cal.x0) / cal.px).toInt()
        val cmax = floor((0.96f * w - cal.x0) / cal.px).toInt()
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
                    if (xx in 0 until w && yy in 0 until h && mask[yy * w + xx]) return true
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

        // wing pixels beside the tip => arrowhead
        fun headScore(id: Int, dx: Int, dy: Int): Int {
            val x = px(id % nc)
            val y = py(id / nc)
            val perpX = -dy
            val perpY = dx
            var cnt = 0
            var back = 3
            while (back < 22) {
                for (side in intArrayOf(-10, -8, -6, 6, 8, 10)) {
                    val sx = (x - dx * back + perpX * side).roundToInt()
                    val sy = (y - dy * back + perpY * side).roundToInt()
                    if (sx in 0 until w && sy in 0 until h && mask[sy * w + sx]) cnt++
                }
                back += 3
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
            if (scores[0] == scores[1]) {
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
}
