from pathlib import Path

def copy(src_name, dst_name):
    src = Path(src_name)
    if not src.exists():
        raise SystemExit("missing overlay: " + src_name)
    dst = Path(dst_name)
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")

for src, dst in [
    ("ai_src/ContextMemoryManager.kt", "app/src/main/java/com/hypernexus/nit/evolution/ContextMemoryManager.kt"),
    ("ai_src/LlamaEngine.kt", "app/src/main/java/com/hypernexus/nit/engine/LlamaEngine.kt"),
    ("ai_src/ModelManager.kt", "app/src/main/java/com/hypernexus/nit/engine/ModelManager.kt"),
    ("ai_src/LoraHotSwapManager.kt", "app/src/main/java/com/hypernexus/nit/engine/LoraHotSwapManager.kt"),
    ("ai_src/P2AgentBrain.kt", "app/src/main/java/com/hypernexus/nit/router/P2AgentBrain.kt"),
    ("ai_src/AgentExecutionJournal.kt", "app/src/main/java/com/hypernexus/nit/router/AgentExecutionJournal.kt"),
    ("ai_src/SkillRegistry.kt", "app/src/main/java/com/hypernexus/nit/router/SkillRegistry.kt"),
    ("ai_src/AgentLifecycle.kt", "app/src/main/java/com/hypernexus/nit/router/AgentLifecycle.kt"),
    ("ai_src/AgentContracts.kt", "app/src/main/java/com/hypernexus/nit/router/AgentContracts.kt"),
    ("ai_src/AgentScheduler.kt", "app/src/main/java/com/hypernexus/nit/router/AgentScheduler.kt"),
    ("ai_src/AgentBudget.kt", "app/src/main/java/com/hypernexus/nit/router/AgentBudget.kt"),
    ("ai_src/AgentHealth.kt", "app/src/main/java/com/hypernexus/nit/router/AgentHealth.kt"),
    ("ai_src/LanguageModelCore.kt", "app/src/main/java/com/hypernexus/nit/router/LanguageModelCore.kt"),
    ("ai_src/IntentRouter.kt", "app/src/main/java/com/hypernexus/nit/router/IntentRouter.kt"),
]:
    copy(src, dst)

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
if old_init in s:
    s = s.replace(old_init, new_init, 1)

old_gate = "LanguageModelCore.shouldUseAgent(rawInput)"
new_gate = "IntentRouter.decide(rawInput).route == IntentRouter.Route.AGENT"
s = s.replace(old_gate, new_gate)

# Route generic conversation through the real language-model core; reserve agent tools for explicit AGENT intent.
generic_old = """        else {
            // Thử gọi công cụ tự hành thông qua LLM JSON Tool Calling
            val toolCallResult = HybridSemanticDispatcher.executeLlmToolCalling(context, rawInput)
            if (toolCallResult != null) {
                finalReply = toolCallResult
            } else {
                val systemPrompt = "Bạn là Nít, trợ lý AI tự hành tối tân trên Samsung Galaxy S21 FE. Hãy phản hồi ngắn gọn, thông minh và súc tích bằng tiếng Việt."
                val conversationHistory = memoryManager.getRecentSlidingWindowContext()
                val historyBuilder = StringBuilder()
                for ((role, content) in conversationHistory) {
                    historyBuilder.append("<|im_start|>$role\\n$content<|im_end|>\\n")
                }

                val fullPrompt = "<|im_start|>system\\n$systemPrompt<|im_end|>\\n$\{historyBuilder}<|im_start|>user\\n$rawInput<|im_end|>\\n<|im_start|>assistant\\n"
                finalReply = LlamaEngine.generateResponse(fullPrompt, maxTokens = 256, temperature = 0.7f)
            }
        }"""
generic_new = """        else {
            val decision = IntentRouter.decide(rawInput)
            if (decision.route == IntentRouter.Route.AGENT) {
                val toolCallResult = HybridSemanticDispatcher.executeLlmToolCalling(context, rawInput)
                finalReply = toolCallResult ?: "Nít nhận diện đây là tác vụ nhưng chưa có công cụ phù hợp để thực hiện."
            } else {
                finalReply = LanguageModelCore.respond(context, rawInput)
            }
        }"""
if generic_old in s:
    s = s.replace(generic_old, generic_new, 1)
else:
    raise SystemExit("generic SystemRouter branch not found")
p.write_text(s, encoding="utf-8")

d = Path("app/src/main/java/com/hypernexus/nit/router/HybridSemanticDispatcher.kt")
if d.exists():
    ds = d.read_text(encoding="utf-8")
    ds = ds.replace('else -> "Đã nhận diện công cụ $toolName."', 'else -> "Lỗi: công cụ không được đăng ký: $toolName"')
    d.write_text(ds, encoding="utf-8")
