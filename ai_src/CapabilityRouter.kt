package com.hypernexus.nit.router
import java.util.Locale
object CapabilityRouter {
    data class Match(val capabilityId: String, val confidence: Float, val skills: List<String>)
    private data class Hint(val phrase: String, val weight: Float)
    private val hints = mapOf(
        "market_analysis" to listOf(Hint("phân tích btc",.40f),Hint("phân tích bitcoin",.40f),Hint("phân tích sol",.40f),Hint("phân tích eth",.40f),Hint("phân tích xau",.40f),Hint("thị trường",.25f),Hint("crypto",.25f),Hint("bitcoin",.25f),Hint("btc",.25f),Hint("sol",.25f),Hint("eth",.25f),Hint("xau",.25f),Hint("vàng",.25f)),
        "screen_grounding" to listOf(Hint("screen grounding",.50f),Hint("grounding",.50f),Hint("định vị nút",.45f),Hint("định vị mục",.45f),Hint("màn hình hiện tại",.45f),Hint("giao diện hiện tại",.45f)),
        "screen_memory" to listOf(Hint("đã xem",.25f),Hint("lúc nãy",.25f),Hint("trên màn hình",.25f),Hint("lịch sử màn hình",.40f),Hint("screen memory",.40f)),
        "automation" to listOf(Hint("lên lịch",.35f),Hint("đặt lịch",.35f),Hint("hẹn giờ",.35f),Hint("tự động",.20f),Hint("schedule",.30f)),
        "creative_3d" to listOf(Hint("mô hình 3d",.40f),Hint("dựng 3d",.40f),Hint("xuất stl",.40f),Hint("3d",.25f),Hint("stl",.25f)),
        "web_creation" to listOf(Hint("web app",.35f),Hint("web game",.35f),Hint("website",.25f),Hint("game web",.35f),Hint("html",.20f)),
        "tool_action" to listOf(Hint("điều khiển",.30f),Hint("bật",.20f),Hint("tắt",.20f),Hint("chỉnh sửa",.20f),Hint("dựng video",.35f),Hint("capcut",.35f)),
        "web_research" to listOf(Hint("tìm trên mạng",.35f),Hint("tìm web",.35f),Hint("tra cứu",.25f),Hint("nghiên cứu",.25f),Hint("nguồn",.20f),Hint("latest",.30f),Hint("mới nhất",.30f)),
        "file_document" to listOf(Hint("tài liệu",.25f),Hint("pdf",.25f),Hint("docx",.25f),Hint("xlsx",.25f),Hint("đọc tài liệu",.40f),Hint("tạo tài liệu",.40f)),
        "vision" to listOf(Hint("phân tích ảnh",.40f),Hint("hình ảnh",.25f),Hint("screenshot",.30f),Hint("ảnh",.20f),Hint("nhìn",.20f)),
        "computer_control" to listOf(Hint("mở ứng dụng",.40f),Hint("điều khiển máy",.40f),Hint("bấm",.25f),Hint("nhấn",.25f),Hint("chạm",.25f),Hint("cuộn",.25f),Hint("scroll",.25f),Hint("click",.25f))
    )
    fun resolve(goal: String): List<Match> {
        val n = goal.lowercase(Locale.ROOT)
        return hints.mapNotNull { (id, words) ->
            val hits = words.filter { n.contains(it.phrase) }
            if (hits.isEmpty()) null else CapabilityRegistry.find(id)?.let { c ->
                val score = (0.50f + hits.sumOf { h -> h.weight.toDouble() }.toFloat()).coerceAtMost(0.98f)
                Match(id, score, c.skills)
            }
        }.sortedWith(compareByDescending<Match> { it.confidence }.thenBy { it.capabilityId })
    }
    fun primary(goal: String): Match? = resolve(goal).firstOrNull()
    fun status(goal: String): String {
        val matches = resolve(goal)
        if (matches.isEmpty()) return "NO_CAPABILITY_MATCH"
        return matches.joinToString(";") { "${it.capabilityId}:${"%.2f".format(Locale.ROOT, it.confidence)}" }
    }
}