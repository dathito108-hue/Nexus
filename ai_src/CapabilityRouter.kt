package com.hypernexus.nit.router

import java.util.Locale

/**
 * CAP2: deterministic capability resolver.
 * Converts a user goal into the smallest registered capability set before tool planning.
 */
object CapabilityRouter {
    data class Match(
        val capabilityId: String,
        val confidence: Float,
        val skills: List<String>
    )

    private val hints = mapOf(
        "market_analysis" to listOf("btc", "sol", "eth", "xau", "vàng", "bitcoin", "crypto", "thị trường", "giá"),
        "screen_memory" to listOf("màn hình", "đã xem", "lúc nãy", "trên màn hình", "screen"),
        "automation" to listOf("lên lịch", "đặt lịch", "hẹn", "tự động", "schedule"),
        "creative_3d" to listOf("3d", "stl", "mô hình 3d", "model 3d"),
        "web_creation" to listOf("website", "web app", "web game", "game web", "html"),
        "tool_action" to listOf("bật", "tắt", "điều khiển", "chỉnh sửa", "dựng video", "capcut"),
        "web_research" to listOf("tìm trên mạng", "tìm web", "tra cứu", "nghiên cứu", "nguồn", "latest", "mới nhất"),
        "file_document" to listOf("file", "tài liệu", "pdf", "docx", "xlsx", "đọc tài liệu", "tạo tài liệu"),
        "vision" to listOf("ảnh", "hình ảnh", "screenshot", "nhìn", "phân tích ảnh"),
        "computer_control" to listOf("click", "bấm", "nhấn", "chạm", "mở ứng dụng", "điều khiển máy", "cuộn", "scroll"),
        "screen_grounding" to listOf("định vị nút", "định vị mục", "màn hình hiện tại", "giao diện hiện tại", "grounding", "screen grounding")
    )

    fun resolve(goal: String): List<Match> {
        val n = goal.lowercase(Locale.ROOT)
        return hints.mapNotNull { (id, words) ->
            val hits = words.count { n.contains(it) }
            if (hits == 0) null
            else CapabilityRegistry.find(id)?.let { c ->
                Match(id, (0.55f + hits.coerceAtMost(4) * 0.1f).coerceAtMost(0.95f), c.skills)
            }
        }.sortedByDescending { it.confidence }
    }

    fun primary(goal: String): Match? = resolve(goal).firstOrNull()

    fun status(goal: String): String {
        val matches = resolve(goal)
        if (matches.isEmpty()) return "NO_CAPABILITY_MATCH"
        return matches.joinToString(";") { "${it.capabilityId}:${"%.2f".format(Locale.ROOT, it.confidence)}" }
    }
}
