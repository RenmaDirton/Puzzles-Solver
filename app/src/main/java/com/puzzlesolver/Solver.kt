package com.puzzlesolver

class SolveResult(
    /** Arrows in the order they should be tapped. */
    val order: List<Arrow>,
    /** Arrows that could never be freed (blocked by something unreadable, or an invalid level). */
    val stuck: Int,
    val rounds: Int,
)

/** Lattice cells in front of the arrow's head, up to the edge of the lattice. */
fun rayCells(a: Arrow, nc: Int, nr: Int): IntArray {
    val out = ArrayList<Int>()
    var c = a.headC + a.dirC
    var r = a.headR + a.dirR
    while (c in 0 until nc && r in 0 until nr) {
        out.add(r * nc + c)
        c += a.dirC
        r += a.dirR
    }
    return out.toIntArray()
}

/**
 * An arrow can leave when the straight line from its head to the board edge crosses no
 * other arrow that is still on the board. Removing an arrow never blocks another one, so
 * repeatedly removing every free arrow solves any solvable level.
 *
 * Arrow.round == 0 means "can leave right now".
 *
 * Shapes the parser could not read (ParseResult.blockers) are treated as permanent
 * obstacles, so a misread can only make the solver more careful, never reckless.
 */
object Solver {
    fun solve(res: ParseResult): SolveResult {
        val arrows = res.arrows
        val nc = res.cols
        val nr = res.rows
        val owner = IntArray(nc * nr) { -1 }
        for ((i, a) in arrows.withIndex()) for (id in a.cells) owner[id] = i
        for (b in res.blockers) for (id in b) owner[id] = -2

        val alive = BooleanArray(arrows.size) { true }
        var remaining = arrows.size
        val rays = Array(arrows.size) { rayCells(arrows[it], nc, nr) }

        fun isFree(i: Int): Boolean {
            for (id in rays[i]) {
                val o = owner[id]
                if (o == -2) return false
                if (o >= 0 && o != i && alive[o]) return false
            }
            return true
        }

        val order = ArrayList<Arrow>()
        var round = 0
        while (remaining > 0) {
            val free = arrows.indices.filter { alive[it] && isFree(it) }
            if (free.isEmpty()) break
            for (i in free) {
                alive[i] = false
                arrows[i].round = round
                order.add(arrows[i])
            }
            remaining -= free.size
            round++
        }
        return SolveResult(order, remaining, round)
    }
}

/**
 * Chooses which of the currently free arrows may be tapped together.
 *
 * Two free arrows can still collide while sliding: if their exit paths cross, the second
 * one runs into the body of the first while it is still passing through. So a batch only
 * contains arrows whose exit paths (and bodies) do not touch each other. The rest wait
 * for the next round, after the screen has settled.
 */
object Planner {
    fun safeBatch(res: ParseResult): List<Arrow> {
        val nc = res.cols
        val nr = res.rows
        val usedRay = BooleanArray(nc * nr)
        val usedBody = BooleanArray(nc * nr)
        val out = ArrayList<Arrow>()
        val cands = res.arrows.filter { it.round == 0 }
            .map { it to rayCells(it, nc, nr) }
            .sortedBy { it.second.size }
        for ((a, ray) in cands) {
            if (ray.any { usedRay[it] || usedBody[it] }) continue
            if (a.cells.any { usedRay[it] }) continue
            for (id in ray) usedRay[id] = true
            for (id in a.cells) usedBody[id] = true
            out.add(a)
        }
        return out
    }
}
