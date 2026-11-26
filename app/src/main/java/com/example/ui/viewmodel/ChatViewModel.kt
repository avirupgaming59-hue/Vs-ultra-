package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiChatService
import com.example.data.local.AppDatabase
import com.example.data.model.ChatMessageEntity
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ChatViewModel"
private const val MAX_HISTORY_MESSAGES = 10

enum class ChatRolePreset(
    val modelId: String,
    val title: String,
    val shortBadge: String,
    val description: String,
    val thinkingLevel: ThinkingLevel,
    val systemInstructionText: String,
) {
    GENERAL_FLASH(
        modelId = GeminiChatService.TARGET_MODEL_ID,
        title = "General Assistant",
        shortBadge = "3.8 Flash",
        description = "Full-depth coding help, explanations & VS Code workflows",
        thinkingLevel = ThinkingLevel.LOW,
        systemInstructionText = "You are VS Shell Assistant, an experienced full-stack and mobile software engineer embedded inside a VS Code Android workspace. Provide thorough, accurate technical guidance and clean, complete code.",
    ),
    DEEP_ARCHITECT(
        modelId = GeminiChatService.TARGET_MODEL_ID,
        title = "Deep Debugging",
        shortBadge = "3.8 Deep",
        description = "Architecture design, complex bugs & root-cause analysis",
        thinkingLevel = ThinkingLevel.HIGH,
        systemInstructionText = "You are a Principal Systems Architect & Debugger. Analyze root causes, edge cases, concurrency, and architecture deeply and provide complete, verified solutions.",
    ),
    INTERACTIVE_PAIR(
        modelId = GeminiChatService.TARGET_MODEL_ID,
        title = "Pair Programmer",
        shortBadge = "3.8 Pair",
        description = "Multi-file refactoring & complete interactive coding",
        thinkingLevel = ThinkingLevel.MEDIUM,
        systemInstructionText = "You are a Senior Pair Programmer. Propose thorough refactorings and complete, ready-to-paste implementations.",
    ),
    FAST_FLASH_LITE(
        modelId = GeminiChatService.FALLBACK_MODEL_ID,
        title = "CLI & Snippets",
        shortBadge = "3.5 Lite",
        description = "Ultra-fast terminal commands, scripts, regex & git workflows",
        thinkingLevel = ThinkingLevel.LOW,
        systemInstructionText = "You are a CLI & Systems Scripting Specialist. Provide accurate terminal commands, shell scripts, regex patterns, and git workflows.",
    ),
}

sealed interface ChatStreamState {
    data object Idle : ChatStreamState
    data class Loading(val statusNote: String = "Gemini is thinking...") : ChatStreamState
    data class Streaming(val partialText: String) : ChatStreamState
    data class Error(val message: String) : ChatStreamState
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val chatDao = AppDatabase.getInstance(application.applicationContext).chatMessageDao()
    private val geminiService = GeminiChatService()

    val messages: StateFlow<List<ChatMessageEntity>> = chatDao.observeAllMessages()
        .catch { err ->
            Log.w(TAG, "Chat messages flow warning", err)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedRole = MutableStateFlow(ChatRolePreset.GENERAL_FLASH)
    val selectedRole: StateFlow<ChatRolePreset> = _selectedRole.asStateFlow()

    private val _streamState = MutableStateFlow<ChatStreamState>(ChatStreamState.Idle)
    val streamState: StateFlow<ChatStreamState> = _streamState.asStateFlow()

    private val isSendInFlight = AtomicBoolean(false)
    private var activeGenerationJob: Job? = null
    private var lastFailedPrompt: String? = null
    private var lastServerContext: String? = null

    private val generalModel: GenerativeModel by lazy { buildPresetModel(ChatRolePreset.GENERAL_FLASH) }
    private val deepModel: GenerativeModel by lazy { buildPresetModel(ChatRolePreset.DEEP_ARCHITECT) }
    private val pairModel: GenerativeModel by lazy { buildPresetModel(ChatRolePreset.INTERACTIVE_PAIR) }
    private val fastModel: GenerativeModel by lazy { buildPresetModel(ChatRolePreset.FAST_FLASH_LITE) }

    init {
        // Pre-warm App Check token & default model in background only if FirebaseApp is initialized
        viewModelScope.launch(Dispatchers.IO) {
            if (!GeminiChatService.isFirebaseConfigured(getApplication())) return@launch
            runCatching {
                if (FirebaseApp.getApps(getApplication()).isNotEmpty()) {
                    generalModel
                    withTimeoutOrNull(2_000L) {
                        FirebaseAppCheck.getInstance().getAppCheckToken(false).await()
                    }
                }
            }.onFailure { err ->
                Log.w(TAG, "ChatViewModel background pre-warm skipped", err)
            }
        }
    }

    private fun buildPresetModel(
        preset: ChatRolePreset,
        modelId: String = preset.modelId,
        includeThinkingConfig: Boolean = false,
    ): GenerativeModel =
        geminiService.createModel(
            systemInstructionText = preset.systemInstructionText,
            thinkingLevel = preset.thinkingLevel,
            modelId = modelId,
            includeThinkingConfig = includeThinkingConfig,
        )

    private fun modelFor(preset: ChatRolePreset): GenerativeModel = when (preset) {
        ChatRolePreset.GENERAL_FLASH -> generalModel
        ChatRolePreset.DEEP_ARCHITECT -> deepModel
        ChatRolePreset.INTERACTIVE_PAIR -> pairModel
        ChatRolePreset.FAST_FLASH_LITE -> fastModel
    }

    fun selectRole(preset: ChatRolePreset) {
        _selectedRole.value = preset
    }

    fun sendMessage(rawPrompt: String, activeServerContext: String? = null) {
        val prompt = rawPrompt.trim()
        if (prompt.isBlank()) return
        if (!isSendInFlight.compareAndSet(false, true)) {
            return
        }
        if (_streamState.value is ChatStreamState.Loading || _streamState.value is ChatStreamState.Streaming) {
            isSendInFlight.set(false)
            return
        }
        if (!GeminiChatService.isFirebaseConfigured(getApplication())) {
            _streamState.value = ChatStreamState.Error(
                "Firebase is not configured for this app. Add the project's google-services.json and rebuild to enable Gemini.",
            )
            return
        }

        val role = _selectedRole.value
        lastFailedPrompt = prompt
        lastServerContext = activeServerContext

        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            _streamState.value = ChatStreamState.Loading("Connecting to ${role.shortBadge}...")
            try {
                val (historyContents, effectivePrompt) = withContext(Dispatchers.IO) {
                    val priorMessages = chatDao.getAllMessages()
                    val history = buildValidAlternatingHistory(priorMessages.takeLast(MAX_HISTORY_MESSAGES))

                    val shouldInsertUserBubble = priorMessages.lastOrNull()?.let { last ->
                        !(last.role == "user" && last.text == prompt)
                    } ?: true

                    if (shouldInsertUserBubble) {
                        chatDao.insert(
                            ChatMessageEntity(
                                role = "user",
                                text = prompt,
                                modelName = role.modelId,
                                rolePresetName = role.title,
                            )
                        )
                    }

                    val contextualPrompt = if (!activeServerContext.isNullOrBlank() && history.isEmpty()) {
                        "[Workspace: $activeServerContext]\n\n$prompt"
                    } else {
                        prompt
                    }
                    history to contextualPrompt
                }

                if (FirebaseApp.getApps(getApplication()).isEmpty()) {
                    _streamState.value = ChatStreamState.Error(
                        "Firebase AI Logic is not initialized yet. Please restart the app.",
                    )
                    return@launch
                }

                val replyResult = geminiService.generateReplyDetailed(
                    model = modelFor(role),
                    primaryModelId = role.modelId,
                    historyContents = historyContents,
                    prompt = effectivePrompt,
                    onPartialText = { partial ->
                        _streamState.value = ChatStreamState.Streaming(partial)
                    },
                    onRetryStatus = { note ->
                        _streamState.value = ChatStreamState.Loading(note)
                    },
                    fallbackModelProvider = {
                        buildPresetModel(
                            preset = role,
                            modelId = GeminiChatService.FALLBACK_MODEL_ID,
                            includeThinkingConfig = false,
                        )
                    },
                )

                if (replyResult.text.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        chatDao.insert(
                            ChatMessageEntity(
                                role = "model",
                                text = replyResult.text,
                                modelName = replyResult.modelUsed,
                                rolePresetName = role.title,
                            )
                        )
                    }
                    lastFailedPrompt = null
                    _streamState.value = ChatStreamState.Idle
                } else {
                    _streamState.value = ChatStreamState.Error("Gemini returned an empty response. Your message history is preserved.")
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini timed out after retries", e)
                _streamState.value = ChatStreamState.Error(
                    "Gemini timed out after retries. Check network connectivity and tap Retry — chat history is preserved.",
                )
            } catch (e: CancellationException) {
                if (_streamState.value is ChatStreamState.Loading || _streamState.value is ChatStreamState.Streaming) {
                    _streamState.value = ChatStreamState.Idle
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gemini chat failed", e)
                _streamState.value = ChatStreamState.Error(formatGeminiError(e))
            } finally {
                isSendInFlight.set(false)
            }
        }
    }

    fun cancelGeneration() {
        activeGenerationJob?.cancel()
        activeGenerationJob = null
        isSendInFlight.set(false)
        _streamState.value = ChatStreamState.Idle
    }

    private fun formatGeminiError(err: Throwable): String {
        val raw = err.localizedMessage.orEmpty()
        return when {
            raw.contains("403") || raw.contains("AppCheck", ignoreCase = true) ->
                "Firebase App Check rejected the request ($raw). Restart the app from launcher or rebuild so debug token refreshes."
            raw.contains("401") || raw.contains("UNAUTHENTICATED", ignoreCase = true) ->
                "Authentication with Firebase AI Logic failed ($raw)."
            raw.contains("404") || raw.contains("NOT_FOUND", ignoreCase = true) ->
                "Requested Gemini model was not found ($raw). Try switching role preset or retrying."
            raw.contains("429") || raw.contains("RESOURCE_EXHAUSTED", ignoreCase = true) || raw.contains("quota", ignoreCase = true) ->
                "Gemini rate limit or quota exceeded ($raw). Wait briefly and tap Retry."
            raw.contains("400") || raw.contains("INVALID_ARGUMENT", ignoreCase = true) ->
                "Gemini rejected the request arguments ($raw). Your chat history is preserved."
            raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
                "Gemini timed out after automatic retries. Verify connectivity and tap Retry."
            raw.isNotBlank() -> raw
            else -> "Failed to generate Gemini response. Chat history is preserved."
        }
    }

    fun retryLastMessage() {
        val prompt = lastFailedPrompt ?: return
        _streamState.value = ChatStreamState.Idle
        sendMessage(prompt, lastServerContext)
    }

    fun dismissError() {
        _streamState.update {
            if (it is ChatStreamState.Error) ChatStreamState.Idle else it
        }
    }

    fun clearHistory() {
        cancelGeneration()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                chatDao.clearAll()
            }
            _streamState.value = ChatStreamState.Idle
            lastFailedPrompt = null
        }
    }

    companion object {
        /**
         * Gemini startChat(history) requires strictly alternating user -> model pairs and must not end
         * on an unanswered user turn (e.g. if a previous attempt failed before receiving a model reply).
         */
        internal fun buildValidAlternatingHistory(messages: List<ChatMessageEntity>): List<Content> {
            val paired = mutableListOf<Pair<String, String>>()
            for (msg in messages) {
                if (msg.text.isBlank()) continue
                val role = if (msg.role == "model") "model" else "user"
                if (paired.isEmpty()) {
                    if (role == "user") paired.add(role to msg.text)
                } else {
                    val prevRole = paired.last().first
                    if (role != prevRole) {
                        paired.add(role to msg.text)
                    }
                }
            }
            if (paired.lastOrNull()?.first == "user") {
                paired.removeAt(paired.lastIndex)
            }
            return paired.map { (role, text) ->
                content(role = role) { text(text) }
            }
        }
    }
}
