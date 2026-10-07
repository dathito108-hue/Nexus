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
        Capability("web_research", "Web & Research", "Thu thập, tổng hợp và kiểm chứng dữ liệu web.", emptyList(), State.PARTIAL),
        Capability("file_document", "File & Document", "Đọc, tạo và biến đổi tài liệu cục bộ.", emptyList(), State.PARTIAL),
        Capability("vision", "Vision", "Hiểu ảnh/màn hình và nối kết quả vào kế hoạch hành động.", emptyList(), State.PARTIAL),
        Capability("computer_control", "Computer Interaction", "Tương tác giao diện thông qua lớp accessibility.", emptyList(), State.PARTIAL),
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