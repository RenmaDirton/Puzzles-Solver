package com.example.ringtapper

import android.content.Context

/** Live-tunable settings. Read by the solver on every frame, so sliders apply immediately. */
object Prefs {
    /** Capture + gesture delay we compensate for by tapping "early". */
    @Volatile var latencyMs = 90

    /** RGB distance above which two neighbouring tiles count as different bars (colour groups). */
    @Volatile var tolerance = 32

    /** Pause after each tap while the game rearranges the bars. */
    @Volatile var cooldownMs = 450

    fun load(c: Context) {
        val p = c.getSharedPreferences("ringtapper", Context.MODE_PRIVATE)
        latencyMs = p.getInt("latencyMs", latencyMs)
        tolerance = p.getInt("tolerance", tolerance)
        cooldownMs = p.getInt("cooldownMs", cooldownMs)
    }

    fun save(c: Context) {
        c.getSharedPreferences("ringtapper", Context.MODE_PRIVATE).edit()
            .putInt("latencyMs", latencyMs)
            .putInt("tolerance", tolerance)
            .putInt("cooldownMs", cooldownMs)
            .apply()
    }
}
