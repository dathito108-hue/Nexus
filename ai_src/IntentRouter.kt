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

        // Fast-path ordinary conversation so normal chat does not pay for
        // a classifier generation before the actual answer generation.
        if (obviousChat(text)) return Decision(Route.CHAT, 0.99f)

        // Deterministic capability matches are stronger than a second LLM
        // classifier for explicitly tool-oriented requests. This keeps
        // web/file/vision/market/screen capabilities reachable while leaving
        // planning, parameter validation and authorization to the agent layer.
        val capabilityMatch = CapabilityRouter.resolve(text).firstOrNull()
        if (capabilityMatch != null && capabilityMatch.confidence >= 0.65f) {
            return Decision(Route.AGENT, capabilityMatch.confidence)
        }

        if (!containsActionSignal(text)) return Decision(Route.CHAT, 0.94f)
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

    private fun obviousChat(input: String): Boolean {
        val n = input.lowercase(Locale.ROOT).trim()
        val prefixes = listOf(
            "giải thích", "cho tôi biết", "hãy giải thích", "tại sao",
            "vì sao", "là gì", "nghĩa là gì", "so sánh", "phân tích",
            "tóm tắt", "dịch ", "viết ", "viết lại", "sửa câu",
            "đặt câu", "cho ví dụ", "hướng dẫn", "có nghĩa gì",
            "bạn nghĩ", "bạn thấy", "hãy kể", "kể cho tôi",
            "nói về", "mô tả", "giúp tôi hiểu"
        )
        return prefixes.any(n::startsWith)
    }

    private fun containsActionSignal(input: String): Boolean {
        val n = input.lowercase(Locale.ROOT)
        val signals = listOf(
            "thực hiện", "hãy làm", "làm giúp", "tạo giúp", "tạo cho tôi",
            "chạy giúp", "mở giúp", "đóng giúp", "bật giúp", "tắt giúp",
            "gửi giúp", "đặt giúp", "lên lịch", "hẹn giờ", "điều khiển",
            "triển khai", "build", "deploy", "develop", "generate",
            "tạo game", "tạo web", "tạo app", "tạo ứng dụng",
            "dựng 3d", "xuất stl", "chỉnh video", "capcut",
            "phân tích btc", "phân tích sol", "phân tích eth", "phân tích xau"
        )
        return signals.any(n::contains)
    }

    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(text.substring(start, end + 1)) } catch (_: Exception) { null }
    }
}