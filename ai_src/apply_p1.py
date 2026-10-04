from pathlib import Path

def copy(src_name, dst_name):
    src = Path(src_name)
    dst = Path(dst_name)
    if not src.exists():
        raise SystemExit("missing overlay: " + src_name)
    dst = Path(dst_name)
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")

copy("ai_src/ContextMemoryManager.kt", "app/src/main/java/com/hypernexus/nit/evolution/ContextMemoryManager.kt")
copy("ai_src/LlamaEngine.kt", "app/src/main/java/com/hypernexus/nit/engine/LlamaEngine.kt")
copy("ai_src/ModelManager.kt", "app/src/main/java/com/hypernexus/nit/engine/ModelManager.kt")
copy("ai_src/LoraHotSwapManager.kt", "app/src/main/java/com/hypernexus/nit/engine/LoraHotSwapManager.kt")
copy("ai_src/P2AgentBrain.kt", "app/src/main/java/com/hypernexus/nit/router/P2AgentBrain.kt")
copy("ai_src/AgentExecutionJournal.kt", "app/src/main/java/com/hypernexus/nit/router/AgentExecutionJournal.kt")
copy("ai_src/SkillRegistry.kt", "app/src/main/java/com/hypernexus/nit/router/SkillRegistry.kt")
copy("ai_src/AgentLifecycle.kt", "app/src/main/java/com/hypernexus/nit/router/AgentLifecycle.kt")
copy("ai_src/AgentContracts.kt", "app/src/main/java/com/hypernexus/nit/router/AgentContracts.kt")
copy("ai_src/AgentScheduler.kt", "app/src/main/java/com/hypernexus/nit/router/AgentScheduler.kt")
copy("ai_src/AgentBudget.kt", "app/src/main/java/com/hypernexus/nit/router/AgentBudget.kt")
copy("ai_src/AgentHealth.kt", "app/src/main/java/com/hypernexus/nit/router/AgentHealth.kt")
copy("ai_src/LanguageModelCore.kt", "app/src/main/java/com/hypernexus/nit/router/LanguageModelCore.kt")

p = Path("app/src/main/java/com/hypernexus/nit/router/SystemRouter.kt")
s = p.read_text(encoding="utf-8")
s = s.replace(
    "import kotlinx.coroutines.runBlocking\nimport kotlinx.coroutines.withContext\n",
    "import kotlinx.coroutines.runBlocking\nimport kotlinx.coroutines.sync.Mutex\nimport kotlinx.coroutines.sync.withLock\nimport kotlinx.coroutines.withContext\n"
)

old_init = """    init {
        // Tự động nạp mô hình vào GPU Mali-G78 nếu file GGUF đã có sẵn
        if (ModelManager.isModelReady(context)) {
            val modelFile = ModelManager.getModelFile(context)
            LlamaEngine.initModel(modelFile.absolutePath)
        }
    }
"""
new_init = """    private val modelLoadMutex = Mutex()

    private suspend fun ensureRealModelLoaded(): Boolean = modelLoadMutex.withLock {
        if (LlamaEngine.isModelLoaded()) return@withLock true
        if (!ModelManager.isModelReady(context)) return@withLock false
        withContext(Dispatchers.IO) {
            LlamaEngine.initModel(ModelManager.getModelFile(context).absolutePath)
        }
    }
"""
if old_init not in s:
    raise SystemExit("SystemRouter init block not found")
s = s.replace(old_init, new_init, 1)

start_marker = "        // 7. SYSTEM 2: AGENTIC TOOL CALLING QUA LLM JSON SCHEMA HOẶC SUY LUẬN SÂU"
return_marker = "        return finalReply"
start = s.find(start_marker)
end = s.find(return_marker, start)
if start < 0 or end < 0:
    raise SystemExit("SystemRouter generic region not found")

new_generic = """        // 7. PRIMARY LANGUAGE MODEL: natural conversation goes directly to the LLM.
        // Explicit action requests are delegated to the structured agent layer.
        else {
            if (!ensureRealModelLoaded()) {
                finalReply = "Lõi mô hình ngôn ngữ cục bộ chưa sẵn sàng. Hãy tải mô hình GGUF Qwen 2.5 1.5B trước."
            } else if (com.hypernexus.nit.router.LanguageModelCore.shouldUseAgent(rawInput)) {
                val memoryContext = memoryManager.buildMemoryContext(rawInput, maxChars = 3600)
                val agentResult = com.hypernexus.nit.router.P2AgentBrain.run(context, rawInput, memoryContext)
                finalReply = agentResult ?: com.hypernexus.nit.router.LanguageModelCore.respond(context, rawInput)
            } else {
                finalReply = com.hypernexus.nit.router.LanguageModelCore.respond(context, rawInput)
            }
        }

"""
s = s[:start] + new_generic + s[end:]
p.write_text(s, encoding="utf-8")

d = Path("app/src/main/java/com/hypernexus/nit/router/HybridSemanticDispatcher.kt")
ds = d.read_text(encoding="utf-8")
ds = ds.replace('else -> "Đã nhận diện công cụ $toolName."', 'else -> "Lỗi: công cụ không được đăng ký: $toolName"')
d.write_text(ds, encoding="utf-8")

print("P1_OVERLAY=OK")
