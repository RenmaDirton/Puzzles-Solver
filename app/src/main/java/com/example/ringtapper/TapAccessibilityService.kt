package com.example.ringtapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** Performs the actual screen tap. Must be enabled by the user in Accessibility settings. */
class TapAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapAccessibilityService? = null

        /** Returns false if the service is not enabled. Coordinates are real screen pixels. */
        fun tap(x: Float, y: Float): Boolean {
            val s = instance ?: return false
            return s.doTap(x, y)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun doTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 20)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }
}
