package com.puzzlesolver

import android.content.Context

/**
 * One step of the routine the player builds in the gear menu.
 * T = tap a spot (x, y as fractions of the screen), W = wait, B = Back button, X = find a word on screen and press it.
 */
class Step(val type: Char, val x: Float = 0f, val y: Float = 0f, val text: String = "", val secs: Int = 0)

object Routine {
    private const val PREFS = "puzzle"

    /** Words offered in the gear menu (custom words typed on the start screen are added to these). */
    val PRESET_WORDS = listOf(
        "Continue", "Next Level", "Next", "Skip", "Close", "Play", "OK", "Collect", "Claim", "No thanks", "Not now", "Done",
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Step> {
        val out = ArrayList<Step>()
        val raw = prefs(ctx).getString("routine", "") ?: ""
        for (line in raw.split('\n')) {
            val p = line.split('|')
            try {
                when (p[0]) {
                    "T" -> out.add(Step('T', x = p[1].toFloat(), y = p[2].toFloat()))
                    "W" -> out.add(Step('W', secs = p[1].toInt()))
                    "B" -> out.add(Step('B'))
                    "X" -> out.add(Step('X', text = p[1]))
                }
            } catch (_: Throwable) {
            }
        }
        return out
    }

    fun save(ctx: Context, steps: List<Step>) {
        val raw = steps.joinToString("\n") {
            when (it.type) {
                'T' -> "T|${it.x}|${it.y}"
                'W' -> "W|${it.secs}"
                'B' -> "B"
                else -> "X|${it.text.replace('|', ' ').replace('\n', ' ')}"
            }
        }
        prefs(ctx).edit().putString("routine", raw).apply()
    }

    fun describe(s: Step): String = when (s.type) {
        'T' -> "Tap spot (%d%%, %d%%)".format((s.x * 100).toInt(), (s.y * 100).toInt())
        'W' -> "Wait ${s.secs} s"
        'B' -> "Press Back"
        else -> "Press the word \"${s.text}\""
    }

    fun customWords(ctx: Context): List<String> =
        (prefs(ctx).getString("words", "") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun saveCustomWords(ctx: Context, raw: String) {
        prefs(ctx).edit().putString("words", raw).apply()
    }

    fun levelLimit(ctx: Context): Int = prefs(ctx).getInt("limit", 0)

    fun saveLevelLimit(ctx: Context, n: Int) {
        prefs(ctx).edit().putInt("limit", n).apply()
    }

    fun autoSnap(ctx: Context): Boolean = prefs(ctx).getBoolean("autosnap", true)

    fun setAutoSnap(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("autosnap", on).apply()
    }
}
