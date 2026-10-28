package com.puzzlesolver

import android.graphics.Bitmap

/** Android wrapper: pulls the pixels out of a screenshot and hands them to the pure-Kotlin core. */
object BoardParser {

    class Frame(val pix: IntArray, val w: Int, val h: Int)

    fun frameOf(bmp: Bitmap): Frame {
        val w = bmp.width
        val h = bmp.height
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)
        return Frame(pix, w, h)
    }

    fun parse(f: Frame, cached: Calibration?): ParseResult? = BoardCore.parse(f.pix, f.w, f.h, cached)

    /** [gold, red] pixel counts: gold = arrows are still sliding, red = an arrow hit something. */
    fun motion(f: Frame): IntArray = BoardCore.motionCounts(f.pix, f.w, f.h)
}
