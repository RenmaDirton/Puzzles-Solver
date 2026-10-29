package com.puzzlesolver

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Short notes about what Auto did, shown on the start screen so they can be sent for debugging. */
object DebugLog {
    private const val MAX = 20000
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "auto_log.txt")

    @Synchronized
    fun add(ctx: Context, msg: String) {
        try {
            val f = file(ctx)
            var text = if (f.exists()) f.readText() else ""
            text += fmt.format(Date()) + " " + msg + "\n"
            if (text.length > MAX) text = text.substring(text.length - MAX / 2)
            f.writeText(text)
        } catch (_: Throwable) {
        }
    }

    fun tail(ctx: Context, chars: Int): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val t = f.readText()
        return if (t.length > chars) t.substring(t.length - chars) else t
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
