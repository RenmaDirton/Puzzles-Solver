package com.puzzlesolver

import android.content.Context
import java.io.File
import kotlin.math.abs

/**
 * What the app has learned about the screens that are not the board (win screen, Continue, ad end cards...).
 * Each entry is a tiny picture of a screen plus a press that worked there. A press that is followed by the
 * board coming back scores up; one that changes nothing, or throws the game out of the foreground, scores down.
 * A screen that has been seen with a proven press gets that press straight away next time.
 */
object ScreenMemory {
    class Entry(val thumb: IntArray, val x: Float, val y: Float, var score: Int)

    private const val MAX_ENTRIES = 60
    private var loaded = false
    private val entries = ArrayList<Entry>()

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "screens.txt")

    @Synchronized
    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (!f.exists()) return
            for (line in f.readLines()) {
                val parts = line.split(' ')
                if (parts.size < 4) continue
                val t = parts[3].split(',').mapNotNull { it.toIntOrNull() }.toIntArray()
                if (t.size != BoardCore.THUMB_W * BoardCore.THUMB_H) continue
                entries.add(Entry(t, parts[1].toFloat(), parts[2].toFloat(), parts[0].toInt()))
            }
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    private fun save(ctx: Context) {
        try {
            file(ctx).writeText(entries.joinToString("\n") { "${it.score} ${it.x} ${it.y} ${it.thumb.joinToString(",")}" })
        } catch (_: Throwable) {
        }
    }

    /** A proven press (score >= 2) for a screen that looks like this one, or null. */
    @Synchronized
    fun best(ctx: Context, thumb: IntArray): Entry? {
        load(ctx)
        var pick: Entry? = null
        for (e in entries) {
            if (e.score < 2) continue
            if (BoardCore.thumbDiff(e.thumb, thumb) >= 4f) continue
            if (pick == null || e.score > pick.score) pick = e
        }
        return pick
    }

    @Synchronized
    fun learn(ctx: Context, thumb: IntArray, x: Float, y: Float, delta: Int) {
        load(ctx)
        val e = entries.firstOrNull {
            abs(it.x - x) < 0.05f && abs(it.y - y) < 0.04f && BoardCore.thumbDiff(it.thumb, thumb) < 3f
        }
        if (e != null) {
            e.score = (e.score + delta).coerceIn(-5, 20)
        } else if (delta > 0) {
            entries.add(Entry(thumb.copyOf(), x, y, delta))
            if (entries.size > MAX_ENTRIES) {
                entries.sortBy { it.score }
                entries.removeAt(0)
            }
        }
        entries.removeAll { it.score <= -3 }
        save(ctx)
    }

    @Synchronized
    fun count(ctx: Context): Int {
        load(ctx)
        return entries.count { it.score >= 2 }
    }
}
