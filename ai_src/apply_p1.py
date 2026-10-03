from pathlib import Path

def copy(src_name, dst_name):
    src = Path(src_name)
    dst = Path(dst_name)
    if not src.exists():
        raise SystemExit("missing overlay: " + src_name)
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")

copy("ai_src/ContextMemoryManager.kt", "app/src/main/java/com/hypernexus/nit/evolution/ContextMemoryManager.kt")
copy("ai_src/LlamaEngine.kt", "app/src/main/java/com/hypernexus/nit/engine/LlamaEngine.kt")
copy("ai_src/ModelManager.kt", "app/src/main/java/com/hypernexus/nit/engine/ModelManager.kt")
copy("ai_src/LoraHotSwapManager.kt", "app/src/main/java/com/hypernexus/nit/engine/LoraHotSwapManager.kt")

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

old_generic = """        // 7. SYSTEM 2: AGENTIC TOOL CALLING QUA LLM JSON SCHEMA HOẶC SUY LUẬN SÂU
        else {
            // Thử gọi công cụ tự hành thông qua LLM JSON Tool Calling
            val toolCallResult = HybridSemanticDispatcher.executeLlmToolCalling(context, rawInput)
            if (toolCallResult != null) {
                finalReply = toolCallResult
            } else {
                val systemPrompt = "Bạn là Nít, trợ lý AI tự hành tối tân trên Samsung Galaxy S21 FE. Hãy phản hồi ngắn gọn, thông minh và súc tích bằng tiếng Việt."
                val conversationHistory = memoryManager.getRecentSlidingWindowContext()
                val historyBuilder = StringBuilder()
                for ((role, content) in conversationHistory) {
                    historyBuilder.append("<|im_start|>$role\n$content<|im_end|>\n")
                }
                val fullPrompt = "<|im_start|>system\n" + systemPrompt + "<|im_end|>\n" + historyBuilder.toString() + "<|im_start|>user\n" + rawInput + "<|im_end|>\n<|im_start|>assistant\n"
                finalReply = LlamaEngine.generateResponse(fullPrompt, maxTokens = 256, temperature = 0.7f)
            }
        }
"""
new_generic = """        // 7. SYSTEM 2: REAL ON-DEVICE AGENT BRAIN
        else {
            if (!ensureRealModelLoaded()) {
                finalReply = "Lõi AI cục bộ chưa sẵn sàng. Hãy tải mô hình GGUF Qwen 2.5 1.5B trước."
            } else {
                val toolCallResult = HybridSemanticDispatcher.executeLlmToolCalling(context, rawInput)
                if (toolCallResult != null) {
                    finalReply = toolCallResult
                } else {
                    val memoryContext = memoryManager.buildMemoryContext(rawInput)
                    val systemPrompt = "Bạn là Nít, trợ lý AI chạy cục bộ. Chỉ dùng ký ức được cung cấp trong CONTEXT; nếu không đủ thì nói rõ. Không tuyên bố đã thực hiện hành động nếu công cụ chưa chạy. Trả lời tiếng Việt tự nhiên.\nCONTEXT:\n" + memoryContext
                    val fullPrompt = systemPrompt + "\n\nUSER:\n" + rawInput + "\nASSISTANT:"
                    finalReply = LlamaEngine.generateResponse(fullPrompt, maxTokens = 320, temperature = 0.65f)
                }
            }
        }
"""
if old_generic not in s:
    raise SystemExit("SystemRouter generic block not found")
s = s.replace(old_generic, new_generic, 1)
p.write_text(s, encoding="utf-8")

d = Path("app/src/main/java/com/hypernexus/nit/router/HybridSemanticDispatcher.kt")
ds = d.read_text(encoding="utf-8")
ds = ds.replace('else -> "Đã nhận diện công cụ $toolName."', 'else -> "Lỗi: công cụ không được đăng ký: $toolName"')
d.write_text(ds, encoding="utf-8")

print("P1_OVERLAY=OK")
