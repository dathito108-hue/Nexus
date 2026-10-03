package com.hypernexus.nit.router

import android.content.Context
import android.util.Log
import com.hypernexus.nit.engine.LlamaEngine
import com.hypernexus.nit.planner.CognitiveTaskReasoner
import com.hypernexus.nit.planner.TaskPlanningManager
import org.json.JSONArray
import org.json.JSONObject

/** One-inference structured agent: chat OR validated tool graph. */
object P2AgentBrain {
    private const val TAG = "P2AgentBrain"
    private val allowed = mapOf(
        "generate_3d_model" to setOf("prompt"),
        "develop_web_game" to setOf("prompt"),
        "develop_web_app" to setOf("prompt"),
        "edit_video_capcut" to setOf("prompt"),
        "quant_market_analyze" to setOf("asset"),
        "control_smart_home" to setOf("device", "action"),
        "search_screen_memory" to setOf("query"),
        "schedule_autonomous_plan" to setOf("goal")
    )

    suspend fun run(context: Context, input: String): String? {
        val prompt = """
            <|im_start|>system
            Bạn là Nít. Hãy trả về DUY NHẤT JSON, không markdown.
            Chat: {"mode":"chat","answer":"..."}
            Hành động: {"mode":"tools","tools":[{"id":"s1","tool":"...","params":{},"depends_on":[],"condition":"always"}]}
            Tool hợp lệ: generate_3d_model(prompt), develop_web_game(prompt), develop_web_app(prompt), edit_video_capcut(prompt),
            quant_market_analyze(asset=BTC|SOL|XAU|ETH), control_smart_home(device=LIGHT|AC|FAN,action=turn_on|turn_off),
            search_screen_memory(query), schedule_autonomous_plan(goal).
            Không tạo tool/param khác. Tối đa 8 bước.
            <|im_end|><|im_start|>user
            $input<|im_end|><|im_start|>assistant
        """.trimIndent()
        val raw = LlamaEngine.generateResponse(prompt, maxTokens = 320, temperature = 0.2f)
        val json = extractJson(raw) ?: return null
        return when (json.optString("mode")) {
            "chat" -> json.optString("answer").trim().ifEmpty { null }
            "tools" -> executeGraph(context, input, json.optJSONArray("tools") ?: JSONArray())
            else -> null
        }
    }

    private suspend fun executeGraph(context: Context, goal: String, steps: JSONArray): String {
        if (steps.length() == 0 || steps.length() > 8) return "Kế hoạch hành động không hợp lệ: số bước phải từ 1 đến 8."
        val seen = mutableSetOf<String>()
        val ok = mutableMapOf<String, Boolean>()
        val out = StringBuilder("⚡ [AGENT PLAN]\n")
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: return "Bước " + (i + 1) + " không hợp lệ."
            val id = step.optString("id", "s" + (i + 1))
            val tool = step.optString("tool")
            if (!seen.add(id) || !id.matches(Regex("[A-Za-z0-9_-]{1,32}"))) return "ID bước không hợp lệ hoặc trùng: " + id
            val schema = allowed[tool] ?: return "Tool không được đăng ký: " + tool
            val p = step.optJSONObject("params") ?: JSONObject()
            val keys = p.keys().asSequence().toSet()
            if (!keys.containsAll(schema) || keys.any { it !in schema }) return "Params không đúng schema của " + tool
            for (k in keys) if (p.opt(k) is String && p.getString(k).length > 1200) return "Param quá dài: " + k
            validateEnums(tool, p)
            val deps = step.optJSONArray("depends_on") ?: JSONArray()
            for (j in 0 until deps.length()) {
                val d = deps.optString(j)
                if (d == id || !seen.contains(d) || ok[d] != true) return "Dependency chưa thành công: " + d
            }
            val condition = step.optString("condition", "always")
            if (condition != "always") {
                val d = condition.removePrefix("after:")
                if (ok[d] != true) { ok[id] = false; out.append("• ").append(id).append(": SKIPPED\n"); continue }
            }
            val result = execute(context, tool, p)
            ok[id] = !result.startsWith("LỖI:")
            out.append("• ").append(id).append('/').append(tool).append(": ").append(result).append('\n')
            if (ok[id] != true) return out.append("• Dừng kế hoạch do bước lỗi.").toString().trim()

            // P3: observe -> evaluate -> optionally repair only safe/idempotent tools.
            val verdict = evaluateResult(input = goal, tool = tool, result = result)
            out.append("• ").append(id).append("/VERIFY: ").append(verdict).append('\n')
            if (verdict == "STOP") return out.toString().trim()
            if (verdict == "RETRY" && tool in RETRYABLE_TOOLS) {
                val repaired = execute(context, tool, p)
                ok[id] = !repaired.startsWith("LỖI:")
                out.append("• ").append(id).append("/RETRY: ").append(repaired).append('\n')
                if (ok[id] != true) return out.append("• Dừng sau retry lỗi.").toString().trim()
            }
        }
        return out.toString().trim()
    }

    private val RETRYABLE_TOOLS = setOf("generate_3d_model", "develop_web_game", "develop_web_app", "quant_market_analyze", "search_screen_memory")

    private fun evaluateResult(input: String, tool: String, result: String): String {
        val prompt = """
            <|im_start|>system
            Bạn là bộ kiểm định của Nít. Chỉ trả về một từ: OK, RETRY hoặc STOP.
            OK = kết quả phù hợp mục tiêu; RETRY = có thể thử lại an toàn; STOP = không nên tiếp tục.
            Không yêu cầu retry cho hành động điều khiển thiết bị, tài chính, video hoặc hành động bên ngoài.
            <|im_end|><|im_start|>user
            TOOL=$tool
            PARAMS=$input
            RESULT=$result
            <|im_end|><|im_start|>assistant
        """.trimIndent()
        return when (LlamaEngine.generateResponse(prompt, maxTokens = 8, temperature = 0.0f).trim().uppercase()) {
            "RETRY" -> "RETRY"
            "STOP" -> "STOP"
            else -> "OK"
        }
    }

    private fun validateEnums(tool: String, p: JSONObject) {
        when (tool) {
            "quant_market_analyze" -> require(p.getString("asset").uppercase() in setOf("BTC","SOL","XAU","ETH"))
            "control_smart_home" -> {
                require(p.getString("device").uppercase() in setOf("LIGHT","AC","FAN"))
                require(p.getString("action") in setOf("turn_on","turn_off"))
            }
        }
    }

    private fun extractJson(text: String): JSONObject? {
        var start = -1; var depth = 0; var quoted = false; var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) {
                '"' -> quoted = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { if (depth > 0) depth--; if (depth == 0 && start >= 0) return JSONObject(text.substring(start, i + 1)) }
            }
        }
        return null
    }

    private suspend fun execute(c: Context, tool: String, p: JSONObject): String = try {
        when (tool) {
            "generate_3d_model" -> {
                val m = com.hypernexus.nit.engine.Dynamic3DSynthesisEngine.synthesize3DMeshFromPrompt(p.getString("prompt"))
                val f = com.hypernexus.nit.engine.Procedural3DGenerator.exportToBinaryStlFile(c, m, "nit_tool_" + (System.currentTimeMillis() % 1000) + ".stl")
                "Đã dựng 3D và xuất " + f.name
            }
            "develop_web_game" -> { val x = com.hypernexus.nit.web.DynamicGameSynthesisEngine.synthesizeGameFromPrompt(c,p.getString("prompt")); com.hypernexus.nit.web.LocalWebSandboxServer.startServer(x); "Đã tạo game " + x.title }
            "develop_web_app" -> { val t = com.hypernexus.nit.web.WebDevelopmentEngine.matchTemplateFromCommand(p.getString("prompt")); val x = com.hypernexus.nit.web.WebDevelopmentEngine.generateProject(t); com.hypernexus.nit.web.LocalWebSandboxServer.startServer(x); "Đã tạo web " + x.title }
            "edit_video_capcut" -> com.hypernexus.nit.video.DynamicCapCutSynthesisEngine.executeFromPrompt(c,p.getString("prompt"))
            "quant_market_analyze" -> com.hypernexus.nit.finance.InstitutionalMarketAnalyzer.analyzeInstitutionalMarket(p.getString("asset").uppercase()).formatExecutiveMemo()
            "control_smart_home" -> { val d = when(p.getString("device").uppercase()) { "AC" -> com.hypernexus.nit.smarthome.SmartHomeLocalBridge.DeviceType.AIR_CONDITIONER; "FAN" -> com.hypernexus.nit.smarthome.SmartHomeLocalBridge.DeviceType.FAN; else -> com.hypernexus.nit.smarthome.SmartHomeLocalBridge.DeviceType.LIGHT }; com.hypernexus.nit.smarthome.SmartHomeLocalBridge.controlDevice(d,p.getString("action")) }
            "search_screen_memory" -> { val x = com.hypernexus.nit.evolution.ScreenTimelineMemoryManager(c).searchTimelineMemory(p.getString("query"),2); if(x.isEmpty()) "Không tìm thấy." else "Tìm thấy: " + x[0].textSnippet }
            "schedule_autonomous_plan" -> { val plan=CognitiveTaskReasoner.reasonAndCreatePlan(p.getString("goal")); TaskPlanningManager.schedulePlan(c,plan); "Đã lên lịch: " + plan.title }
            else -> "LỖI: tool không được đăng ký"
        }
    } catch (e: Exception) { Log.e(TAG, "tool failed", e); "LỖI: " + (e.message ?: e.javaClass.simpleName) }
}