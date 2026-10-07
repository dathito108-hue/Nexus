package com.hypernexus.nit.router

import org.json.JSONObject
import java.util.Locale

/**
 * P8: declarative skill registry.
 * Skills remain explicit, validated and deterministic; there is no reflection-based execution.
 */
object SkillRegistry {
    enum class Risk { READ_ONLY, LOCAL_MUTATION, EXTERNAL_ACTION, SCHEDULED_AUTONOMY }

    data class SkillSpec(
        val id: String,
        val description: String,
        val requiredParams: Set<String>,
        val risk: Risk,
        val repairable: Boolean,
        val outputType: String,
        val directIntentHints: List<String> = emptyList()
    )

    private val specs = linkedMapOf(
        "generate_3d_model" to SkillSpec(
            "generate_3d_model", "Tạo mô hình 3D cục bộ và xuất STL",
            setOf("prompt"), Risk.LOCAL_MUTATION, true, "file"
        ),
        "develop_web_game" to SkillSpec(
            "develop_web_game", "Tạo game web cục bộ và chạy trong sandbox",
            setOf("prompt"), Risk.LOCAL_MUTATION, true, "local_web_project"
        ),
        "develop_web_app" to SkillSpec(
            "develop_web_app", "Tạo ứng dụng web cục bộ và chạy trong sandbox",
            setOf("prompt"), Risk.LOCAL_MUTATION, true, "local_web_project"
        ),
        "edit_video_capcut" to SkillSpec(
            "edit_video_capcut", "Điều khiển quy trình chỉnh sửa video CapCut",
            setOf("prompt"), Risk.EXTERNAL_ACTION, false, "action_result",
            listOf("chỉnh", "sửa", "dựng", "edit", "capcut", "video")
        ),
        "quant_market_analyze" to SkillSpec(
            "quant_market_analyze", "Phân tích định lượng thị trường ở chế độ chỉ đọc",
            setOf("asset"), Risk.READ_ONLY, false, "analysis"
        ),
        "control_smart_home" to SkillSpec(
            "control_smart_home", "Điều khiển thiết bị nhà thông minh",
            setOf("device", "action"), Risk.EXTERNAL_ACTION, false, "action_result",
            listOf("bật", "tắt", "mở", "đóng", "turn on", "turn off")
        ),
        "web_research" to SkillSpec(
            "web_research", "Tìm kiếm và tổng hợp nguồn web ở chế độ chỉ đọc",
            setOf("query"), Risk.READ_ONLY, true, "web_evidence",
            listOf("tìm trên mạng", "tìm web", "tra cứu", "nghiên cứu", "nguồn", "latest", "mới nhất")
        ),
        "file_read" to SkillSpec(
            "file_read", "Đọc tài liệu văn bản cục bộ trong vùng dữ liệu của Nít",
            setOf("path"), Risk.READ_ONLY, true, "text",
            listOf("đọc file", "đọc tệp", "đọc tài liệu", "mở file")
        ),
        "file_write" to SkillSpec(
            "file_write", "Tạo hoặc ghi đè tài liệu văn bản cục bộ",
            setOf("path", "content"), Risk.LOCAL_MUTATION, true, "file",
            listOf("tạo file", "tạo tệp", "ghi file", "lưu file", "tạo tài liệu")
        ),
        "file_append" to SkillSpec(
            "file_append", "Nối nội dung vào tài liệu văn bản cục bộ",
            setOf("path", "content"), Risk.LOCAL_MUTATION, true, "file",
            listOf("thêm vào file", "ghi thêm", "append file")
        ),
        "file_list" to SkillSpec(
            "file_list", "Liệt kê tài liệu và thư mục cục bộ",
            setOf("path"), Risk.READ_ONLY, true, "file_list",
            listOf("liệt kê file", "danh sách file", "xem thư mục")
        ),
        "file_info" to SkillSpec(
            "file_info", "Đọc thông tin kích thước và loại tài liệu cục bộ",
            setOf("path"), Risk.READ_ONLY, true, "file_info",
            listOf("thông tin file", "thông tin tệp", "dung lượng file")
        ),
        "search_screen_memory" to SkillSpec(
            "search_screen_memory", "Tìm kiếm bộ nhớ màn hình cục bộ",
            setOf("query"), Risk.READ_ONLY, true, "search_result"
        ),
        "schedule_autonomous_plan" to SkillSpec(
            "schedule_autonomous_plan", "Lập lịch kế hoạch tự động",
            setOf("goal"), Risk.SCHEDULED_AUTONOMY, false, "scheduled_plan",
            listOf("lên lịch", "đặt lịch", "schedule", "hẹn")
        )
    )

    fun spec(id: String): SkillSpec? = specs[id]
    fun all(): Collection<SkillSpec> = specs.values
    fun isRepairable(id: String): Boolean = specs[id]?.repairable == true

    fun promptCatalog(): String = specs.values.joinToString(", ") { spec ->
        val params = when (spec.id) {
            "quant_market_analyze" -> "asset=BTC|SOL|XAU|ETH"
            "control_smart_home" -> "device=LIGHT|AC|FAN,action=turn_on|turn_off"
            else -> spec.requiredParams.joinToString(",")
        }
        "${spec.id}($params)"
    }

    fun validateParams(spec: SkillSpec, params: JSONObject): String? {
        val keys = params.keys().asSequence().toSet()
        if (!keys.containsAll(spec.requiredParams) || keys.any { it !in spec.requiredParams }) {
            return "Params không đúng schema của ${spec.id}"
        }
        for (key in keys) {
            val value = params.opt(key)
            if (value !is String) return "Param phải là chuỗi: $key"
            if (value.length > 1200) return "Param quá dài: $key"
        }
        return try {
            when (spec.id) {
                "quant_market_analyze" ->
                    require(params.getString("asset").uppercase(Locale.ROOT) in setOf("BTC", "SOL", "XAU", "ETH"))
                "web_research" -> require(params.getString("query").isNotBlank())\n            "control_smart_home" -> {
                    require(params.getString("device").uppercase(Locale.ROOT) in setOf("LIGHT", "AC", "FAN"))
                    require(params.getString("action") in setOf("turn_on", "turn_off"))
                }
            }
            null
        } catch (_: IllegalArgumentException) {
            "Tham số enum không hợp lệ của ${spec.id}"
        }
    }

    /**
     * Authorization is derived only from the current user goal, never from memory/tool output.
     */
    fun isAuthorized(goal: String, spec: SkillSpec): Boolean {
        if (spec.risk == Risk.READ_ONLY || spec.risk == Risk.LOCAL_MUTATION) return true
        val normalized = goal.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        return when (spec.id) {
            "control_smart_home" -> Regex("\\b(bật|tắt|mở|đóng|turn\\s+on|turn\\s+off)\\b").containsMatchIn(normalized)
            "edit_video_capcut" -> Regex("\\b(chỉnh|sửa|dựng|edit|capcut|video)\\b").containsMatchIn(normalized)
            "schedule_autonomous_plan" -> Regex("(lên\\s+lịch|đặt\\s+lịch|schedule|hẹn)").containsMatchIn(normalized)
            else -> spec.directIntentHints.any { normalized.contains(it) }
        }
    }
}
