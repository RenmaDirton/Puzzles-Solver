// JVM test harness for the pure-Kotlin core. Not part of the Android app.
// Usage: java -jar coretest.jar shot1.jpg shot2.jpg ...
import com.puzzlesolver.BoardCore
import com.puzzlesolver.Planner
import com.puzzlesolver.Solver
import java.io.File
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    val strip = System.getenv("NOSTRIP") == null
    for (path in args) {
        val img = ImageIO.read(File(path))
        val w = img.width
        val h = img.height
        val pix = IntArray(w * h)
        img.getRGB(0, 0, w, h, pix, 0, w)
        val bg = pix[(h / 2) * w + 8]
        if (strip) {
            // test only: remove the green marker dots that the overlay drew into user screenshots
            val disc = BooleanArray(w * h)
            for (i in pix.indices) {
                val r = (pix[i] shr 16) and 0xFF
                val g = (pix[i] shr 8) and 0xFF
                val b = pix[i] and 0xFF
                disc[i] = g > r + 35 && g > b + 20
            }
            val gone = BooleanArray(w * h)
            for (y in 0 until h) for (x in 0 until w) if (disc[y * w + x]) {
                for (dy in -4..4) for (dx in -4..4) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h) gone[yy * w + xx] = true
                }
            }
            for (i in pix.indices) if (gone[i]) pix[i] = bg
        }
        if (System.getenv("EXTRA") != null) extraChecks(pix, w, h)
        BoardCore.forceAdaptive = System.getenv("FORCE_ADAPTIVE") != null
        BoardCore.dbgCands = System.getenv("CANDS") != null
        BoardCore.forceOld = System.getenv("FORCE_OLD") != null
        BoardCore.dbgOld = 0; BoardCore.dbgAdaptive = 0; BoardCore.dbgDisagree = 0
        val t0 = System.nanoTime()
        val res = BoardCore.parse(pix, w, h, null)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val name = File(path).name
        if (res == null) {
            println("$name: PARSE FAILED")
            continue
        }
        val solved = Solver.solve(res)
        val batch = Planner.safeBatch(res)
        val mo = BoardCore.motionCounts(pix, w, h)
        println(
            "$name: pitch %.2f/%.2f x0 %.1f y0 %.1f lattice %dx%d arrows %d blockers %d rounds %d stuck %d free_now %d safe_batch %d gold %d red %d (%d ms)"
                .format(
                    res.cal.px, res.cal.py, res.cal.x0, res.cal.y0, res.cols, res.rows, res.arrows.size,
                    res.blockers.size, solved.rounds, solved.stuck, res.arrows.count { it.round == 0 },
                    batch.size, mo[0], mo[1], ms,
                ) + " [old %d adaptive %d disagree %d] cubes %d lives %d".format(BoardCore.dbgOld, BoardCore.dbgAdaptive, BoardCore.dbgDisagree, res.sinks.count { it }, BoardCore.lives(pix, w, h)),
        )
        if (System.getenv("CACHED") != null) {
            val t1 = System.nanoTime()
            val again = BoardCore.parse(pix, w, h, res.cal)
            println("   cached parse: %d ms, pitch %.2f, arrows %d".format((System.nanoTime() - t1) / 1_000_000, again?.cal?.px ?: -1f, again?.arrows?.size ?: -1))
        }
        if (System.getenv("CROSS") != null) {
            val img2 = ImageIO.read(File(System.getenv("CROSS")))
            val pix2 = IntArray(img2.width * img2.height)
            img2.getRGB(0, 0, img2.width, img2.height, pix2, 0, img2.width)
            val other = BoardCore.parse(pix2, img2.width, img2.height, null)
            if (other != null) {
                val r2 = BoardCore.parse(pix, w, h, other.cal)
                println("   with the calibration of ${File(System.getenv("CROSS")).name} (pitch %.1f): pitch %.2f arrows %d".format(other.cal.px, r2?.cal?.px ?: -1f, r2?.arrows?.size ?: -1))
            }
        }
        if (System.getenv("BLK") != null) for (b in res.blockers) println("BLOCKER cells=%d first at x=%.0f y=%.0f last x=%.0f y=%.0f".format(b.size, res.cal.x0 + (b[0] % res.cols) * res.cal.px, res.cal.y0 + (b[0] / res.cols) * res.cal.py, res.cal.x0 + (b.last() % res.cols) * res.cal.px, res.cal.y0 + (b.last() / res.cols) * res.cal.py))
        if (System.getenv("DUMP") != null) for (a in res.arrows) println("ARROW %.1f %.1f %d %d".format(a.headX, a.headY, a.dirC, a.dirR))
    }
}

/** Motion fingerprint and colour-shift robustness checks. */
fun extraChecks(pix: IntArray, w: Int, h: Int) {
    val sig = BoardCore.inkSignature(pix, w, h)
    // 1) identical frame
    println("   fingerprint: same frame diff = ${BoardCore.signatureDiff(sig, BoardCore.inkSignature(pix, w, h))}")
    // 2) sensor-like noise on 2% of pixels
    val rnd = java.util.Random(1)
    val noisy = pix.copyOf()
    for (i in noisy.indices) if (rnd.nextInt(50) == 0) {
        val d = rnd.nextInt(9) - 4
        val r = (((noisy[i] shr 16) and 0xFF) + d).coerceIn(0, 255)
        val g = (((noisy[i] shr 8) and 0xFF) + d).coerceIn(0, 255)
        val b = ((noisy[i] and 0xFF) + d).coerceIn(0, 255)
        noisy[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    println("   fingerprint: noisy frame diff = ${BoardCore.signatureDiff(sig, BoardCore.inkSignature(noisy, w, h))}")
    // 3) the whole picture moved 6 px (a slow slide)
    val moved = IntArray(pix.size)
    for (y in 0 until h) for (x in 0 until w) moved[y * w + x] = pix[y * w + maxOf(0, x - 6)]
    println("   fingerprint: shifted 6px diff = ${BoardCore.signatureDiff(sig, BoardCore.inkSignature(moved, w, h))}")
    // 4) colour shifts: does the reading survive?
    fun shift(name: String, f: (Int, Int, Int) -> IntArray) {
        val out = IntArray(pix.size)
        for (i in pix.indices) {
            val c = f((pix[i] shr 16) and 0xFF, (pix[i] shr 8) and 0xFF, pix[i] and 0xFF)
            out[i] = (0xFF shl 24) or (c[0].coerceIn(0, 255) shl 16) or (c[1].coerceIn(0, 255) shl 8) or c[2].coerceIn(0, 255)
        }
        val res = BoardCore.parse(out, w, h, null)
        println("   colour $name: " + (res?.let { "arrows ${it.arrows.size} blockers ${it.blockers.size}" } ?: "PARSE FAILED"))
    }
    shift("warm") { r, g, b -> intArrayOf(r + 8, g, b - 14) }
    shift("vivid") { r, g, b -> val m = (r + g + b) / 3; intArrayOf(m + ((r - m) * 1.25f).toInt(), m + ((g - m) * 1.25f).toInt(), m + ((b - m) * 1.25f).toInt()) }
    shift("dim") { r, g, b -> intArrayOf((r * 0.9f).toInt(), (g * 0.9f).toInt(), (b * 0.9f).toInt()) }
    shift("cool") { r, g, b -> intArrayOf(r - 8, g, b + 10) }
}

object ButtonCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        for (path in args) {
            val img = ImageIO.read(File(path))
            val w = img.width
            val h = img.height
            val pix = IntArray(w * h)
            img.getRGB(0, 0, w, h, pix, 0, w)
            val b = BoardCore.findButtons(pix, w, h)
            println(File(path).name + ": " + b.joinToString { "y=%.3f c=%d".format(it.cy.toFloat() / h, it.contrast) })
        }
    }
}
