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
                ),
        )
    }
}
