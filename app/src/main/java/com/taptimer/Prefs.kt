package com.taptimer

import android.content.Context

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("taptimer", Context.MODE_PRIVATE)

    /** 0 = top, 1 = middle, 2 = bottom */
    fun position(c: Context) = p(c).getInt("pos", 0)
    fun setPosition(c: Context, v: Int) = p(c).edit().putInt("pos", v).apply()

    /** A pause longer than this (ms) ends a run: the next tap starts a new one. */
    fun gapMs(c: Context) = p(c).getLong("gap", 3000L)
    fun setGapMs(c: Context, v: Long) = p(c).edit().putLong("gap", v).apply()

    fun runs(c: Context): List<String> =
        p(c).getString("runs", "")!!.split("\n").filter { it.isNotBlank() }

    fun addRun(c: Context, line: String) {
        val all = (listOf(line) + runs(c)).take(10)
        p(c).edit().putString("runs", all.joinToString("\n")).apply()
    }

    fun clearRuns(c: Context) = p(c).edit().remove("runs").apply()
}
