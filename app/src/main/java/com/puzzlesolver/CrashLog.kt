package com.puzzlesolver

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** Writes the reason for a crash to a file so the app can show it the next time it opens. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(app).writeText("${Date()}\nthread: ${t.name}\n$sw")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun read(ctx: Context): String? {
        val f = file(ctx)
        return if (f.exists()) f.readText() else null
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
