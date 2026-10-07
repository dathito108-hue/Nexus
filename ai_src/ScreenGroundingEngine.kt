package com.hypernexus.nit.vision

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.hypernexus.nit.accessibility.NitAccessibilityService

/**
 * CAP6.1: deterministic screen grounding from the live Accessibility tree.
 */
object ScreenGroundingEngine {
    private const val MAX_NODES = 80
    private const val MAX_TEXT = 140

    fun inspect(service: NitAccessibilityService): String {
        val root = service.rootInActiveWindow ?: return "SCREEN_GROUNDING\nerror=no_active_window"
        val out = StringBuilder("SCREEN_GROUNDING\n")
        out.append("package=").append(service.packageName ?: "").append('\n')
        out.append("nodes=\n")
        val counter = intArrayOf(0)
        walk(root, out, counter)
        out.append("node_count=").append(counter[0]).append('\n')
        return out.toString().take(12000)
    }

    private fun walk(node: AccessibilityNodeInfo, out: StringBuilder, counter: IntArray) {
        if (counter[0] >= MAX_NODES) return
        if (node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isScrollable) {
                val index = counter[0]++
                out.append(index).append('|')
                    .append("text=").append(text.take(MAX_TEXT)).append('|')
                    .append("desc=").append(desc.take(MAX_TEXT)).append('|')
                    .append("class=").append(node.className?.toString().orEmpty().take(100)).append('|')
                    .append("bounds=").append(bounds.left).append(',').append(bounds.top)
                    .append(',').append(bounds.right).append(',').append(bounds.bottom).append('|')
                    .append("clickable=").append(node.isClickable).append('|')
                    .append("enabled=").append(node.isEnabled).append('|')
                    .append("scrollable=").append(node.isScrollable).append('\n')
            }
        }
        for (i in 0 until node.childCount) {
            if (counter[0] >= MAX_NODES) break
            val child = node.getChild(i) ?: continue
            try {
                walk(child, out, counter)
            } finally {
                child.recycle()
            }
        }
    }
}
