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

    fun performClick(x: Float, y: Float): Boolean {
        if (x !in 0f..4000f || y !in 0f..4000f) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }
}
