package com.hypernexus.nit.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import com.hypernexus.nit.vision.ScreenGroundingEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object NitAccessibilityController {
    private val lock = Mutex()
    @Volatile private var service: NitAccessibilityService? = null
    fun attach(value: NitAccessibilityService) { service = value }
    fun detach(value: NitAccessibilityService) { if (service === value) service = null }

    suspend fun execute(context: android.content.Context, action: String, target: String): String =
        lock.withLock {
            val s = service ?: return@withLock "LỖI: Computer Control chưa được người dùng cấp quyền Accessibility."
            when (action) {
                "back" -> if (s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) "Đã quay lại." else "LỖI: không thực hiện được BACK."
                "home" -> if (s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) "Đã về màn hình chính." else "LỖI: không thực hiện được HOME."
                "tap_text" -> tapText(s, target)
                "tap" -> tapCoordinate(s, target)
                "scroll_forward" -> scroll(s, true)
                "scroll_backward" -> scroll(s, false)
                else -> "LỖI: action Computer Control không được phép."
            }
        }

    private fun tapText(s: NitAccessibilityService, target: String): String {
        val root = s.rootInActiveWindow ?: return "LỖI: không đọc được cửa sổ hiện tại."
        val nodes = root.findAccessibilityNodeInfosByText(target).orEmpty()
        val node = nodes.firstOrNull { it.isVisibleToUser && it.isClickable && it.isEnabled }
            ?: nodes.firstOrNull { it.isVisibleToUser && it.isEnabled }
        if (node != null) {
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            return if (ok) "ACTION_RESULT\naction=tap_text\nmethod=accessibility_node\ntarget=" + target + "\nsuccess=true\nverify=dispatched"
            else "LỖI: không click được: " + target
        }

        val grounded = ScreenGroundingEngine.findBestTarget(s, target)
            ?: return "LỖI: không tìm thấy mục hoặc grounding confidence thấp: " + target
        val bounds = grounded.bounds
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        if (x !in 0f..4000f || y !in 0f..4000f) return "LỖI: grounding cho tọa độ ngoài giới hạn."

        if (grounded.clickable) {
            val verified = findNodeByBounds(s.rootInActiveWindow, bounds)
            if (verified != null) {
                val ok = verified.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                verified.recycle()
                if (ok) return "ACTION_RESULT\naction=tap_text\nmethod=grounded_node\ntarget=" + target + "\nscore=" + grounded.score + "\nsuccess=true\nverify=dispatched"
            }
        }

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return if (s.dispatchGesture(gesture, null, null))
            "ACTION_RESULT\naction=tap_text\nmethod=grounded_bounds\ntarget=" + target + "\nscore=" + grounded.score +
                "\nbounds=" + bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom +
                "\nsuccess=true\nverify=dispatched"
        else "LỖI: grounded gesture thất bại: " + target
    }

    private fun findNodeByBounds(node: AccessibilityNodeInfo?, target: android.graphics.Rect): AccessibilityNodeInfo? {
        if (node == null) return null
        val own = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        if (node.isVisibleToUser && node.isEnabled && own == target) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = try { findNodeByBounds(child, target) } finally { child.recycle() }
            if (found != null) return found
        }
        return null
    }

    private fun tapCoordinate(s: NitAccessibilityService, target: String): String {
        val parts = target.split(",").map { it.trim() }
        if (parts.size != 2) return "LỖI: tap cần target dạng x,y."
        val x = parts[0].toFloatOrNull() ?: return "LỖI: x không hợp lệ."
        val y = parts[1].toFloatOrNull() ?: return "LỖI: y không hợp lệ."
        if (x !in 0f..4000f || y !in 0f..4000f) return "LỖI: tọa độ ngoài giới hạn."
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return if (s.dispatchGesture(gesture, null, null)) "Đã chạm tọa độ (" + x + "," + y + ")." else "LỖI: dispatch gesture thất bại."
    }

    private fun scroll(s: NitAccessibilityService, forward: Boolean): String {
        val root = s.rootInActiveWindow ?: return "LỖI: không đọc được cửa sổ hiện tại."
        val scrollable = findScrollable(root) ?: return "LỖI: không tìm thấy vùng có thể cuộn."
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val ok = scrollable.performAction(action)
        scrollable.recycle()
        return if (ok) "Đã cuộn " + if (forward) "xuống." else "lên." else "LỖI: không cuộn được."
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }
}
