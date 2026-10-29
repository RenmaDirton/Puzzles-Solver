package com.puzzlesolver

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Reads the words on a screenshot (on the phone, no internet) and says where a given word or phrase is. */
object TextFinder {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private fun norm(s: String) =
        s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim().split(' ').filter { it.isNotEmpty() }

    /** Calls [cb] on the main thread with the box of the first match (screen pixels), or null. */
    fun find(bmp: Bitmap, target: String, avoid: Rect?, cb: (Rect?) -> Unit) {
        val want = norm(target)
        if (want.isEmpty()) {
            cb(null)
            return
        }
        try {
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { text ->
                    var found: Rect? = null
                    loop@ for (block in text.textBlocks) {
                        for (line in block.lines) {
                            val els = line.elements
                            val words = els.map { norm(it.text).joinToString("") }
                            // consecutive elements that spell the wanted words
                            var i = 0
                            while (i + want.size <= words.size) {
                                var ok = true
                                for (k in want.indices) if (words[i + k] != want[k].replace(" ", "")) { ok = false; break }
                                if (ok) {
                                    val box = Rect()
                                    var any = false
                                    for (k in want.indices) {
                                        val b = els[i + k].boundingBox ?: continue
                                        if (!any) { box.set(b); any = true } else box.union(b)
                                    }
                                    if (any && (avoid == null || !avoid.contains(box.centerX(), box.centerY()))) {
                                        found = box
                                        break@loop
                                    }
                                }
                                i++
                            }
                            // a line that merges the words into one element (e.g. "NextLevel")
                            if (found == null && words.joinToString("").contains(want.joinToString(""))) {
                                val b = line.boundingBox
                                if (b != null && (avoid == null || !avoid.contains(b.centerX(), b.centerY()))) {
                                    found = b
                                    break@loop
                                }
                            }
                        }
                    }
                    cb(found)
                }
                .addOnFailureListener { cb(null) }
        } catch (e: Throwable) {
            cb(null)
        }
    }
}
