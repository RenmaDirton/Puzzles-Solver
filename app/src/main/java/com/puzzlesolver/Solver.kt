package com.puzzlesolver

class SolveResult(
    /** Arrows in the order they should be tapped. */
    val order: List<Arrow>,
    /** Arrows that could never be freed (blocked by something unreadable, or an invalid level). */
    val stuck: Int,
    val rounds: Int,
)

/**
 * An arrow can leave when the straight line from its head to the board edge crosses no
 * other arrow that is still on the board. Removing an arrow never blocks another one, so
 * repeatedly removing every free arrow solves any solvable level.
 *
 * Arrows freed in the same round do not depend on each other, so they can be tapped
 * back to back. Arrow.round == 0 means "can leave right now".
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

        fun isFree(i: Int): Boolean {
            val a = arrows[i]
            var c = a.headC + a.dirC
            var r = a.headR + a.dirR
            while (c in 0 until nc && r in 0 until nr) {
                val o = owner[r * nc + c]
                if (o == -2) return false
                if (o >= 0 && o != i && alive[o]) return false
                c += a.dirC
                r += a.dirR
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
