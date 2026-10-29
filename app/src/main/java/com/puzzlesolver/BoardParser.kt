package com.puzzlesolver

import android.graphics.Bitmap

/** Android wrapper: pulls the pixels out of a screenshot and hands them to the pure-Kotlin core. */
object BoardParser {

    /**
     * One captured screen.
     * sig: fingerprint of the ink (same for two frames of a still screen).
     * red: red pixels on the board (an arrow that hit something is drawn red).
     */
    class Frame(val pix: IntArray, val w: Int, val h: Int, val sig: LongArray, val red: Int, val thumb: IntArray, val inkFrac: Float)

    fun frameOf(bmp: Bitmap): Frame {
        val w = bmp.width
        val h = bmp.height
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)
        return Frame(pix, w, h, BoardCore.inkSignature(pix, w, h), BoardCore.motionCounts(pix, w, h)[1], BoardCore.thumb(pix, w, h), BoardCore.inkFraction(pix, w, h))
    }

    fun parse(f: Frame, cached: Calibration?): ParseResult? = BoardCore.parse(f.pix, f.w, f.h, cached)

    /** Number of fingerprint cells that differ between two frames; small = the screen did not change. */
    fun diff(a: Frame, b: Frame): Int = BoardCore.signatureDiff(a.sig, b.sig)
}
