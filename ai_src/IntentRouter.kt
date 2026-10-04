package com.hypernexus.nit.router

import com.hypernexus.nit.engine.LlamaEngine
import org.json.JSONObject
import java.util.Locale

/**
 * Semantic route gate.
 *
 * It decides only CHAT vs AGENT. It never creates tool names/parameters and
 * never grants permission. P2AgentBrain + SkillRegistry remain authoritative
 * for planning, validation and execution.
 */
object IntentRouter {
    enum class Route { CHAT, AGENT }

    data class Decision(val route: Route, val confidence: Float)

    fun obviousAgent(input: String): Boolean {
        val n = input.lowercase(Locale.ROOT)
        val hints = listOf(
            "tạo game", "làm game", "develop game", "tạo web", "làm web",
            "tạo ứng dụng", "tạo app", "dựng 3d", "tạo mô hình 3d",
            "xuất stl", "chỉnh video", "sửa video", "capcut",
            "bật đèn", "tắt đèn", "bật điều hòa", "tắt điều hòa",
            "bật quạt", "tắt quạt", "lên lịch", "đặt lịch", "hẹn giờ"
        )
        return hints.any(n::contains)
    }

    fun decide(input: String): Decision {
        val text = input.trim().take(1800)
        if (text.isEmpty()) return Decision(Route.CHAT, 1f)
        if (obviousAgent(text)) return Decision(Route.AGENT, 1f)
        if (!LlamaEngine.isModelLoaded()) return Decision(Route.CHAT, 0f)

        val prompt = """
            <|im_start|>system
            Bạn là bộ định tuyến ngữ nghĩa của Nít.
            Chỉ quyết định người dùng muốn CHAT hay thực hiện một HÀNH ĐỘNG.
            CHAT = hỏi, giải thích, tâm sự, viết, dịch, tóm tắt, suy luận hoặc trao đổi.
            AGENT = yêu cầu Nít thực sự thực hiện một tác vụ bằng công cụ.
            Không được tạo tên công cụ, tham số hay hành động cụ thể.
            Trả về DUY NHẤT JSON:
            {"route":"CHAT","confidence":0.0}
            hoặc {"route":"AGENT","confidence":0.0}
            confidence từ 0 đến 1.
            Nếu chỉ đang hỏi về cách làm một việc, chọn CHAT.
            <|im_end|><|im_start|>user
            $text
            <|im_end|><|im_start|>assistant
        """.trimIndent()

        return try {
            val raw = LlamaEngine.generateResponse(prompt, maxTokens = 48, temperature = 0.0f)
            val json = extractJson(raw) ?: return Decision(Route.CHAT, 0f)
            val route = when (json.optString("route").uppercase(Locale.ROOT)) {
                "AGENT" -> Route.AGENT
                else -> Route.CHAT
            }
            val confidence = json.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f)
            // Ambiguous classifier output fails closed to ordinary language.
            if (confidence < 0.62f) Decision(Route.CHAT, confidence)
            else Decision(route, confidence)
        } catch (_: Exception) {
            Decision(Route.CHAT, 0f)
        }
    }

    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(text.substring(start, end + 1)) } catch (_: Exception) { null }
    }
}
