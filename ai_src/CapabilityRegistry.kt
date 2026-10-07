package com.hypernexus.nit.router

/** Unified capability layer. Execution remains delegated to SkillRegistry. */
object CapabilityRegistry {
    data class Capability(val id: String, val name: String, val description: String, val skills: List<String>, val state: State)
    enum class State { READY, PARTIAL, PLANNED }

    private val capabilities = listOf(
        Capability("tool_action", "Tool & Action", "Lập kế hoạch và thực thi tác vụ có kiểm soát.", listOf("generate_3d_model", "develop_web_game", "develop_web_app", "edit_video_capcut", "control_smart_home"), State.READY),
        Capability("market_analysis", "Market & Trading Analysis", "Phân tích định lượng thị trường theo chế độ chỉ đọc.", listOf("quant_market_analyze"), State.READY),
        Capability("screen_memory", "Screen Memory", "Tìm lại thông tin đã lưu từ dòng thời gian màn hình.", listOf("search_screen_memory"), State.READY),
        Capability("automation", "Automation", "Lập lịch kế hoạch tự động có kiểm soát.", listOf("schedule_autonomous_plan"), State.READY),
        Capability("web_research", "Web & Research", "Thu thập, tổng hợp và kiểm chứng dữ liệu web.", listOf("web_research"), State.READY),
        Capability("file_document", "File & Document", "Đọc, tạo và biến đổi tài liệu văn bản trong vùng dữ liệu cục bộ của Nít.", listOf("file_read", "file_write", "file_append", "file_list", "file_info"), State.READY),
        Capability("vision", "Vision", "Phân tích ảnh cục bộ bằng đặc trưng hình học, màu sắc, độ sáng, tương phản và biên ảnh.", listOf("vision_analyze"), State.READY),
        Capability("computer_control", "Computer Interaction", "Tương tác giao diện Android qua AccessibilityService với kiểm tra quyền, action allowlist và mục tiêu giới hạn.", listOf("computer_control", "screen_grounding"), State.READY),
        Capability("screen_grounding", "Screen Grounding", "Định vị ngữ nghĩa các thành phần UI hiện tại bằng Accessibility tree và bounds.", listOf("screen_grounding"), State.READY),
        Capability("creative_3d", "Creative 3D", "Sinh và xuất tài sản 3D trên thiết bị.", listOf("generate_3d_model"), State.READY),
        Capability("web_creation", "Web & Game Creation", "Sinh ứng dụng/game web cục bộ.", listOf("develop_web_game", "develop_web_app"), State.READY),
        Capability("execution_memory", "Execution Memory", "Ghi nhật ký, độ tin cậy và trạng thái thực thi.", emptyList(), State.READY)
    )

    fun all(): List<Capability> = capabilities
    fun ready(): List<Capability> = capabilities.filter { it.state == State.READY }
    fun find(id: String): Capability? = capabilities.firstOrNull { it.id == id }
    fun promptCatalog(): String = capabilities.joinToString("\n") { c ->
        "${c.id} | ${c.name} | state=${c.state} | skills=${c.skills.joinToString(",").ifEmpty { "none" }} | ${c.description}"
    }
    fun statusText(): String = buildString {
        appendLine("NÍT CAPABILITIES")
        for (c in capabilities) append("• ").append(c.name).append(": ").append(c.state).appendLine()
    }
}