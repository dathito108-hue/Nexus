package com.hypernexus.nit.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo

class NitAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: NitAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        NitAccessibilityController.attach(this)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        NitAccessibilityController.detach(this)
        super.onDestroy()
    }

    /** Compatibility facade for existing automation engines. */
    fun clickNodeByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text).orEmpty()
        val node = nodes.firstOrNull { it.isVisibleToUser && it.isClickable }
            ?: nodes.firstOrNull { it.isVisibleToUser }
        if (node == null) return false
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        return ok
    }

    fun performClick(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun performClick(text: String): Boolean = clickNodeByText(text)

    /** Compatibility overload for legacy callers using Int/Double coordinates. */
    fun performClick(x: Number, y: Number): Boolean = performClick(x.toFloat(), y.toFloat())

    /** Compatibility facade for legacy automation modules. */
    fun performSwipe(startX: Number, startY: Number, endX: Number, endY: Number): Boolean =
        performSwipe(startX, startY, endX, endY, 350L)

    fun performSwipe(startX: Number, startY: Number, endX: Number, endY: Number, durationMs: Number): Boolean {
        val sx = startX.toFloat()
        val sy = startY.toFloat()
        val ex = endX.toFloat()
        val ey = endY.toFloat()
        val duration = durationMs.toLong().coerceIn(50L, 5000L)
        if (sx !in 0f..4000f || sy !in 0f..4000f || ex !in 0f..4000f || ey !in 0f..4000f) return false
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Legacy callers use this as an automation/timeline capability provider.
     * Returning the service preserves the existing call contract without adding
     * a second automation core.
     */
    fun getTimelineManager(): com.hypernexus.nit.evolution.ScreenTimelineMemoryManager =
           com.hypernexus.nit.evolution.ScreenTimelineMemoryManager(this)

    fun performClick(x: Float, y: Float): Boolean {
        if (x !in 0f..4000f || y !in 0f..4000f) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }
}
