package com.puzzlesolver

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

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
    /** Removal round assigned by the solver (0 = free right now). */
    var round: Int = -1
}

class ParseResult(
    val arrows: List<Arrow>,
    val cols: Int,
    val rows: Int,
    val pitch: Float,
    val unparsed: Int,
)

/**
 * Reads an "arrow maze" board from a screenshot.
 *
 * The board is a lattice of points. Every arrow is a polyline along lattice lines, and
 * different arrows never share a lattice point, so each connected component of ink edges
 * is one arrow. The head is the endpoint with arrowhead wings (extra ink on both sides).
 */
object BoardParser {

    private const val RIGHT = 1
    private const val DOWN = 2
    private const val LEFT = 4
    private const val UP = 8

    fun parse(bmp: Bitmap): ParseResult? {
        val w = bmp.width
        val h = bmp.height
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)

        // ---- 1. ink mask: anything strongly different from the background colour ----
        val bg = pix[(h * 0.5f).toInt() * w + 8]
        val bgR = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bgB = bg and 0xFF
        val mask = BooleanArray(w * h)
        for (i in pix.indices) {
            val p = pix[i]
            val d = abs(((p shr 16) and 0xFF) - bgR) +
                abs(((p shr 8) and 0xFF) - bgG) +
                abs((p and 0xFF) - bgB)
            mask[i] = d > 250
        }

        // Board lives in the middle band of the screen (header and bottom buttons excluded).
        val y0 = (h * 0.18f).toInt()
        val y1 = (h * 0.86f).toInt()
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

        // ---- 2. calibrate the lattice (pitch and offsets) ----
        fun scoreAxis(proj: FloatArray, p: Float, o: Float): Float {
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

        fun bestOffset(proj: FloatArray, p: Float): Pair<Float, Float> {
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

        // coarse pass: largest pitch that still explains (almost) all the ink
        val coarse = ArrayList<Pair<Float, Float>>() // (pitch, score)
        for (k in 0..420) {
            val p = 18f + k * 0.1f
            coarse.add(p to (bestOffset(col, p).first + bestOffset(row, p).first))
        }
        val maxScore = coarse.maxOf { it.second }
        if (maxScore <= 0f) return null
        val pc = coarse.filter { it.second >= 0.93f * maxScore }.maxOf { it.first }

        // fine pass
        var bestTot = -1f
        var pitch = pc
        var x0 = 0f
        var yy0 = 0f
        for (k in -30..30) {
            val p = pc + k * 0.02f
            val sx = bestOffset(col, p)
            val sy = bestOffset(row, p)
            val tot = sx.first + sy.first
            if (tot > bestTot) {
                bestTot = tot
                pitch = p
                x0 = sx.second
                yy0 = sy.second
            }
        }

        val thr = pitch * 1.2f
        var kx0 = Int.MAX_VALUE
        var kx1 = Int.MIN_VALUE
        var k = 0
        while (x0 + k * pitch < w - 1) {
            val xi = (x0 + k * pitch).roundToInt().coerceIn(1, w - 2)
            if (max(col[xi - 1], max(col[xi], col[xi + 1])) > thr) {
                if (k < kx0) kx0 = k
                if (k > kx1) kx1 = k
            }
            k++
        }
        var ky0 = Int.MAX_VALUE
        var ky1 = Int.MIN_VALUE
        k = 0
        while (yy0 + k * pitch < h - 1) {
            val yi = (yy0 + k * pitch).roundToInt().coerceIn(1, h - 2)
            if (max(row[yi - 1], max(row[yi], row[yi + 1])) > thr) {
                if (k < ky0) ky0 = k
                if (k > ky1) ky1 = k
            }
            k++
        }
        if (kx0 > kx1 || ky0 > ky1) return null
        val nc = kx1 - kx0 + 1
        val nr = ky1 - ky0 + 1
        if (nc < 3 || nr < 3) return null

        fun px(c: Int) = x0 + (kx0 + c) * pitch
        fun py(r: Int) = yy0 + (ky0 + r) * pitch

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

        // ---- 3. edges between neighbouring lattice points ----
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
            for (back in intArrayOf(4, 8, 12)) {
                for (side in intArrayOf(-9, -7, 7, 9)) {
                    val sx = (x - dx * back + perpX * side).roundToInt()
                    val sy = (y - dy * back + perpY * side).roundToInt()
                    if (sx in 0 until w && sy in 0 until h && mask[sy * w + sx]) cnt++
                }
            }
            return cnt
        }

        // ---- 4. components => arrows ----
        val seen = BooleanArray(nc * nr)
        val arrows = ArrayList<Arrow>()
        var unparsed = 0
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
                unparsed++
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
                unparsed++
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
        return ParseResult(arrows, nc, nr, pitch, unparsed)
    }
}
