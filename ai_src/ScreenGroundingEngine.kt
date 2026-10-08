package com.hypernexus.nit.vision

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.hypernexus.nit.accessibility.NitAccessibilityService
import java.util.Locale

/** CAP6.1 semantic grounding; read-only target selection. */
object ScreenGroundingEngine {
    private const val MAX_NODES = 80
    private const val MAX_TEXT = 140
    private const val ACTION_MIN_SCORE = 85

    data class GroundedTarget(val score: Int, val bounds: Rect, val clickable: Boolean, val enabled: Boolean)

    fun findBestTarget(service: NitAccessibilityService, target: String): GroundedTarget? {
        val root = service.rootInActiveWindow ?: return null
        val query = target.trim()
        if (query.isEmpty()) return null
        val candidates = mutableListOf<Match>()
        collect(root, query, candidates)
        val best = candidates.maxByOrNull { it.score }
        val result = best?.takeIf {
            it.score >= ACTION_MIN_SCORE && it.node.isVisibleToUser &&
                it.node.isEnabled && !it.bounds.isEmpty
        }?.let { GroundedTarget(it.score, Rect(it.bounds), it.node.isClickable, it.node.isEnabled) }
        candidates.forEach { it.node.recycle() }
        return result
    }

    fun inspect(service: NitAccessibilityService, target: String): String {
        val root = service.rootInActiveWindow ?: return "SCREEN_GROUNDING\nerror=no_active_window"
        val query = target.trim()
        if (query.isEmpty()) return "SCREEN_GROUNDING\nerror=empty_target"
        val candidates = mutableListOf<Match>()
        collect(root, query, candidates)
        candidates.sortByDescending { it.score }
        val out = StringBuilder("SCREEN_GROUNDING\n")
        out.append("package=").append(service.packageName ?: "").append('\n')
        out.append("target=").append(query.take(MAX_TEXT)).append('\n')
        val best = candidates.firstOrNull()
        if (best == null) out.append("match=none\n") else {
            out.append("match=best\nscore=").append(best.score).append('\n')
            appendNode(out, best.nodeIndex, best.node)
        }
        out.append("candidates=").append(candidates.take(5).size).append('\n')
        candidates.take(5).forEachIndexed { index, match ->
            out.append("candidate[").append(index).append("]=score=").append(match.score).append('|')
            appendNode(out, match.nodeIndex, match.node)
        }
        out.append("node_count=").append(candidates.size).append('\n')
        candidates.forEach { it.node.recycle() }
        return out.toString().take(12000)
    }

    private data class Match(
        val score: Int, val nodeIndex: Int, val node: AccessibilityNodeInfo, val bounds: Rect
    )

    private fun collect(node: AccessibilityNodeInfo, query: String, candidates: MutableList<Match>, counter: IntArray = intArrayOf(0)) {
        if (counter[0] >= MAX_NODES) return
        val index = counter[0]++
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val className = node.className?.toString().orEmpty()
            val q = query.lowercase(Locale.ROOT)
            val score = score(q, text, desc, className, node.isClickable, node.isEnabled)
            if (score > 0) candidates += Match(score, index, AccessibilityNodeInfo.obtain(node), Rect().also { node.getBoundsInScreen(it) })
        }
        for (i in 0 until node.childCount) {
            if (counter[0] >= MAX_NODES) break
            val child = node.getChild(i) ?: continue
            try { collect(child, query, candidates, counter) } finally { child.recycle() }
        }
    }

    private fun score(query: String, text: String, desc: String, className: String, clickable: Boolean, enabled: Boolean): Int {
        val t = text.lowercase(Locale.ROOT)
        val d = desc.lowercase(Locale.ROOT)
        val c = className.lowercase(Locale.ROOT)
        var value = when {
            t == query || d == query -> 100
            t.contains(query) || d.contains(query) -> 80
            query.contains(t) && t.length >= 2 -> 60
            c.contains(query) -> 40
            else -> 0
        }
        if (clickable) value += 10
        if (enabled) value += 5
        return value
    }

    private fun appendNode(out: StringBuilder, index: Int, node: AccessibilityNodeInfo) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        out.append("index=").append(index).append('|')
            .append("text=").append(node.text?.toString()?.trim().orEmpty().take(MAX_TEXT)).append('|')
            .append("desc=").append(node.contentDescription?.toString()?.trim().orEmpty().take(MAX_TEXT)).append('|')
            .append("class=").append(node.className?.toString().orEmpty().take(100)).append('|')
            .append("bounds=").append(bounds.left).append(',').append(bounds.top).append(',').append(bounds.right).append(',').append(bounds.bottom).append('|')
            .append("clickable=").append(node.isClickable).append('|').append("enabled=").append(node.isEnabled).append('|')
            .append("scrollable=").append(node.isScrollable).append('\n')
    }
}
