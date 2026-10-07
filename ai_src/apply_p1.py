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
    ("ai_src/CapabilityRegistry.kt", "app/src/main/java/com/hypernexus/nit/router/CapabilityRegistry.kt"),
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

                val fullPrompt = "<|im_start|>system\\n$systemPrompt<|im_end|>\\n${historyBuilder}<|im_start|>user\\n$rawInput<|im_end|>\\n<|im_start|>assistant\\n"
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
stream_method = '''
    /**
     * Central streaming gateway: CHAT streams from the real LLM; AGENT stays behind routing.
     */
    suspend fun routeCommandStreaming(rawInput: String, onDelta: (String) -> Unit): String = withContext(Dispatchers.Default) {
        val decision = IntentRouter.decide(rawInput)
        if (decision.route == IntentRouter.Route.CHAT) {
            LanguageModelCore.respondStreaming(context, rawInput, onDelta)
        } else {
            val reply = routeCommandAsync(rawInput)
            onDelta(reply)
            reply
        }
    }
'''
route_marker = "    fun routeCommand(rawInput: String): String"
if "suspend fun routeCommandStreaming(" not in s and route_marker in s:
    s = s.replace(route_marker, stream_method + "\n" + route_marker, 1)

p.write_text(s, encoding="utf-8")

# Wire the real local language model into the visible chat terminal.
# Streaming is used only for ordinary conversation; agent/tool routing remains
# behind SystemRouter + IntentRouter and is therefore not bypassed by the UI.
main_activity = Path("app/src/main/java/com/hypernexus/nit/MainActivity.kt")
if main_activity.exists():
    ms = main_activity.read_text(encoding="utf-8")
    ms = ms.replace(
        "import com.hypernexus.nit.engine.ModelManager\n",
        "import com.hypernexus.nit.engine.ModelManager\nimport com.hypernexus.nit.router.LanguageModelCore\nimport com.hypernexus.nit.router.SystemRouter\n"
    )
    ms = ms.replace(
        "    private lateinit var tvTerminalOutput: TextView\n",
        "    private lateinit var tvTerminalOutput: TextView\n    private lateinit var etChatInput: EditText\n    private lateinit var btnChatSend: Button\n    private lateinit var tvGenerationStats: TextView\n    private lateinit var systemRouter: SystemRouter\n"
    )
    ms = ms.replace(
        "        tvTerminalOutput = findViewById(R.id.tv_terminal_output)\n",
        "        tvTerminalOutput = findViewById(R.id.tv_terminal_output)\n        etChatInput = findViewById(R.id.et_chat_input)\n        btnChatSend = findViewById(R.id.btn_chat_send)\n        tvGenerationStats = findViewById(R.id.tv_generation_stats)\n        systemRouter = SystemRouter(this)\n"
    )
    listener_anchor = "        btnOpenAccessibility.setOnClickListener {"
    chat_block = """        // 4.5. CHAT LLM ON-DEVICE: stream only ordinary language responses.
        // Agent/tool requests still go through SystemRouter/IntentRouter.
        btnChatSend.setOnClickListener {
            val prompt = etChatInput.text.toString().trim()
            if (prompt.isEmpty()) return@setOnClickListener
            etChatInput.text?.clear()
            btnChatSend.isEnabled = false
            tvTerminalOutput.text = "🤖 [NÍT LLM]: "
            tvGenerationStats.text = "LLM • đang suy luận..."
            lifecycleScope.launch {
                try {
                    val reply = systemRouter.routeCommandStreaming(prompt) { delta ->
                        runOnUiThread {
                            tvTerminalOutput.append(delta)
                        }
                    }
                    tvGenerationStats.text = "LLM • " + LanguageModelCore.lastGenerationStats()
                } catch (t: Throwable) {
                    tvTerminalOutput.text = "❌ [NÍT LLM]: " + (t.message ?: "Lỗi suy luận cục bộ")
                } finally {
                    btnChatSend.isEnabled = true
                }
            }
        }

        etChatInput.setOnEditorActionListener { _, _, _ ->
            btnChatSend.performClick()
            true
        }

"""
    if listener_anchor in ms and "btnChatSend.setOnClickListener" not in ms:
        ms = ms.replace(listener_anchor, chat_block + listener_anchor, 1)
    main_activity.write_text(ms, encoding="utf-8")

layout = Path("app/src/main/res/layout/activity_main.xml")
if layout.exists():
    xs = layout.read_text(encoding="utf-8")
    anchor = """            <TextView
                android:id="@+id/tv_terminal_output"
"""
    chat_xml = """            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="10dp"
                android:orientation="horizontal">

                <EditText
                    android:id="@+id/et_chat_input"
                    android:layout_width="0dp"
                    android:layout_height="44dp"
                    android:layout_weight="1"
                    android:hint="Nói chuyện trực tiếp với Nít..."
                    android:imeOptions="actionSend"
                    android:inputType="text|textCapSentences|textMultiLine"
                    android:maxLines="3"
                    android:paddingHorizontal="12dp"
                    android:textColor="#E2E8F0"
                    android:textColorHint="#64748B"
                    android:textSize="12sp" />

                <Button
                    android:id="@+id/btn_chat_send"
                    android:layout_width="92dp"
                    android:layout_height="44dp"
                    android:layout_marginStart="8dp"
                    android:text="GỬI"
                    android:textSize="11sp" />
            </LinearLayout>

            <TextView
                android:id="@+id/tv_generation_stats"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:text="LLM • chưa có dữ liệu suy luận"
                android:textColor="#64748B"
                android:textSize="10sp" />

"""
    if "android:id=\"@+id/et_chat_input\"" not in xs and anchor in xs:
        xs = xs.replace(anchor, chat_xml + anchor, 1)
    layout.write_text(xs, encoding="utf-8")

d = Path("app/src/main/java/com/hypernexus/nit/router/HybridSemanticDispatcher.kt")
if d.exists():
    ds = d.read_text(encoding="utf-8")
    ds = ds.replace('else -> "Đã nhận diện công cụ $toolName."', 'else -> "Lỗi: công cụ không được đăng ký: $toolName"')
    d.write_text(ds, encoding="utf-8")
