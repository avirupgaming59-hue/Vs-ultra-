package com.example.ai

import android.content.Context
import android.util.Log
import com.example.util.SimpleJson
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerationConfig
import com.google.firebase.ai.type.RequestOptions
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

private const val TAG = "GeminiChatService"

enum class GeminiFailureReason {
    FIREBASE_NOT_INITIALIZED,
    API_PERMISSION_DENIED,
    TIMEOUT,
    INVALID_RESPONSE,
    EMPTY_GENERATED_FILES,
    FILE_WRITE_FAILURE,
    MISSING_TERMUX,
    MISSING_RUNTIME_DEPENDENCIES,
    NETWORK_UNAVAILABLE,
    UNKNOWN,
}

class GeminiBootstrapException(
    val reason: GeminiFailureReason,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 400L,
    val backoffMultiplier: Double = 2.0,
    val maxDelayMs: Long = 3_000L,
    val jitterRatio: Double = 0.15,
    val streamFirstTokenTimeoutMs: Long = 50_000L,
    val streamChunkIdleTimeoutMs: Long = 35_000L,
    val unaryTimeoutMs: Long = 60_000L,
)

data class LocalhostBootstrapPlan(
    val summary: String,
    val termuxCommand: String,
    val starterFiles: Map<String, String>,
    val setupCommand: String = "",
    val generatedByAi: Boolean = true,
)

data class AiWorkspaceEditResult(
    val summary: String,
    val targetFileName: String,
    val updatedContent: String,
    val extraFiles: Map<String, String> = emptyMap(),
)

data class GeminiReplyResult(
    val text: String,
    val modelUsed: String,
)

/**
 * Firebase AI Logic service targeting `gemini-3.8-flash` (with automatic
 * fallback to `gemini-3.5-flash-lite`), 60s timeouts, strict JSON validation,
 * and accurate transient vs non-retryable error classification.
 */
class GeminiChatService(
    private val policy: RetryPolicy = RetryPolicy(),
) {
    fun createModel(
        systemInstructionText: String,
        thinkingLevel: ThinkingLevel = ThinkingLevel.LOW,
        modelId: String = TARGET_MODEL_ID,
        includeThinkingConfig: Boolean = false,
    ): GenerativeModel {
        val config = generationConfig {
            if (includeThinkingConfig) {
                this.thinkingConfig = thinkingConfig {
                    this.thinkingLevel = thinkingLevel
                }
            }
        }
        return Firebase.ai.generativeModel(
            modelName = modelId,
            generationConfig = config,
            systemInstruction = content { text(systemInstructionText) },
            requestOptions = RequestOptions(timeoutInMillis = policy.unaryTimeoutMs),
        )
    }

    suspend fun generateLocalhostBootstrap(
        workspaceName: String,
        port: Int,
        stackHint: String,
    ): Result<LocalhostBootstrapPlan> {
        val bootstrapSchema = Schema.obj(
            properties = mapOf(
                "summary" to Schema.string("Short 1-sentence summary of what was generated"),
                "setupCommand" to Schema.string("Optional lightweight POSIX sh workspace check or initialization command (no network downloads)"),
                "indexHtml" to Schema.string("Complete starter index.html file tailored to the workspace"),
                "mainScriptName" to Schema.string("Filename for main starter script, e.g. app.py or server.js"),
                "mainScriptContent" to Schema.string("Complete runnable starter code for the requested stack"),
                "readmeMd" to Schema.string("Concise README.md with launch and preview instructions"),
            ),
            optionalProperties = listOf("setupCommand"),
        )
        val primaryConfig = generationConfig {
            responseMimeType = "application/json"
            responseSchema = bootstrapSchema
        }
        val fallbackConfig = generationConfig {
            responseMimeType = "application/json"
            responseSchema = bootstrapSchema
        }
        val prompt = """
            Create a starter localhost code workspace named "$workspaceName" bound to 127.0.0.1:$port for stack: "$stackHint".
            Include complete HTML/code files (`indexHtml` and `mainScriptContent`). Do not provide shell commands; the app creates a password-protected Termux setup command locally.
        """.trimIndent()

        val bootstrapPolicy = RetryPolicy(
            maxAttempts = 2,
            unaryTimeoutMs = 55_000L,
        )

        return try {
            val firebaseReady = runCatching { FirebaseApp.getApps(FirebaseApp.getInstance().applicationContext).isNotEmpty() }
                .getOrElse { false }
            if (!firebaseReady) {
                throw GeminiBootstrapException(
                    GeminiFailureReason.FIREBASE_NOT_INITIALIZED,
                    "Firebase initialization failed: FirebaseApp is not configured on this build.",
                )
            }

            val rawJson = executeWithModelFallback(
                policy = bootstrapPolicy,
                prompt = prompt,
                primaryConfig = primaryConfig,
                fallbackConfig = fallbackConfig,
            )
            val plan = parseLocalhostBootstrapJson(
                rawJson = rawJson,
                workspaceName = workspaceName,
                port = port,
            )
            Result.success(plan)
        } catch (ce: CancellationException) {
            if (ce is TimeoutCancellationException) {
                Result.failure(classifyGeminiFailure(ce))
            } else {
                throw ce
            }
        } catch (t: Throwable) {
            val classified = classifyGeminiFailure(t)
            runCatching { Log.e(TAG, "generateLocalhostBootstrap failed [${classified.reason}]: ${classified.message}", t) }
            Result.failure(classified)
        }
    }

    suspend fun generateWorkspaceCodeEdit(
        prompt: String,
        currentFileName: String,
        currentFileContent: String,
        workspaceFiles: List<String>,
    ): Result<AiWorkspaceEditResult> {
        val extraFileSchema = Schema.obj(
            properties = mapOf(
                "name" to Schema.string("Simple filename without slashes, e.g. styles.css or app.js"),
                "content" to Schema.string("Complete runnable content for this supporting file"),
            ),
        )
        val editSchema = Schema.obj(
            properties = mapOf(
                "summary" to Schema.string("Short 1-sentence summary of what was created or updated"),
                "targetFileName" to Schema.string("Primary filename without slashes, e.g. index.html or app.js"),
                "updatedContent" to Schema.string("Complete updated file content ready to save and preview"),
                "additionalFiles" to Schema.array(
                    items = extraFileSchema,
                    description = "Optional extra files created or updated together with targetFileName",
                ),
            ),
            optionalProperties = listOf("additionalFiles"),
        )
        val primaryConfig = generationConfig {
            responseMimeType = "application/json"
            responseSchema = editSchema
        }
        val fallbackConfig = generationConfig {
            responseMimeType = "application/json"
            responseSchema = editSchema
        }
        val fullPrompt = """
            You are the built-in AI Code Editor inside VS Shell.
            Existing workspace files: ${workspaceFiles.joinToString(", ").ifBlank { "(none)" }}
            Currently open file: ${currentFileName.ifBlank { "index.html" }}
            Current file content:
            ```
            ${currentFileContent.take(12_000)}
            ```
            User request: $prompt
            Return complete, clean, directly runnable code (never placeholders or TODO stubs) for targetFileName, and include additionalFiles if multiple files are needed.
        """.trimIndent()

        return try {
            val firebaseReady = runCatching { FirebaseApp.getInstance() }.isSuccess
            if (!firebaseReady) {
                throw GeminiBootstrapException(
                    GeminiFailureReason.FIREBASE_NOT_INITIALIZED,
                    "Firebase initialization failed: FirebaseApp is not configured.",
                )
            }
            val rawJson = executeWithModelFallback(
                policy = policy,
                prompt = fullPrompt,
                primaryConfig = primaryConfig,
                fallbackConfig = fallbackConfig,
            )
            Result.success(parseWorkspaceEditJson(rawJson, currentFileName))
        } catch (ce: CancellationException) {
            if (ce is TimeoutCancellationException) {
                Result.failure(classifyGeminiFailure(ce))
            } else {
                throw ce
            }
        } catch (t: Throwable) {
            val classified = classifyGeminiFailure(t)
            runCatching { Log.e(TAG, "generateWorkspaceCodeEdit failed [${classified.reason}]", t) }
            Result.failure(classified)
        }
    }

    private suspend fun executeWithModelFallback(
        policy: RetryPolicy,
        prompt: String,
        primaryConfig: GenerationConfig,
        fallbackConfig: GenerationConfig,
    ): String {
        val modelPlans = listOf(
            TARGET_MODEL_ID to primaryConfig,
            FALLBACK_MODEL_ID to fallbackConfig,
        )
        var lastError: Throwable? = null
        for ((index, plan) in modelPlans.withIndex()) {
            val (modelId, config) = plan
            val model = Firebase.ai.generativeModel(
                modelName = modelId,
                generationConfig = config,
                requestOptions = RequestOptions(timeoutInMillis = policy.unaryTimeoutMs),
            )
            try {
                val reply = executeWithExponentialBackoff(policy = policy) {
                    withTimeout(policy.unaryTimeoutMs) {
                        withContext(Dispatchers.IO) {
                            model.generateContent(prompt).text.orEmpty().trim()
                        }
                    }
                }
                if (reply.isBlank()) {
                    throw GeminiBootstrapException(
                        GeminiFailureReason.INVALID_RESPONSE,
                        "Gemini returned an empty response.",
                    )
                }
                return reply
            } catch (ce: CancellationException) {
                if (ce !is TimeoutCancellationException) throw ce
                lastError = ce
                if (index == modelPlans.lastIndex) throw ce
            } catch (t: Throwable) {
                lastError = t
                val canFallback = index < modelPlans.lastIndex && isModelUnavailableError(t)
                if (!canFallback) throw t
                runCatching {
                    Log.w(TAG, "Primary model $modelId unavailable (${t.message}); falling back to ${modelPlans[index + 1].first}")
                }
            }
        }
        throw lastError ?: IllegalStateException("No Gemini model available")
    }

    suspend fun generateReply(
        model: GenerativeModel,
        historyContents: List<Content>,
        prompt: String,
        onPartialText: (String) -> Unit,
        onRetryStatus: (String) -> Unit,
        fallbackModel: GenerativeModel? = null,
        fallbackModelProvider: (() -> GenerativeModel)? = null,
    ): String {
        return generateReplyDetailed(
            model = model,
            primaryModelId = TARGET_MODEL_ID,
            historyContents = historyContents,
            prompt = prompt,
            onPartialText = onPartialText,
            onRetryStatus = onRetryStatus,
            fallbackModel = fallbackModel,
            fallbackModelProvider = fallbackModelProvider,
        ).text
    }

    suspend fun generateReplyDetailed(
        model: GenerativeModel,
        primaryModelId: String = TARGET_MODEL_ID,
        historyContents: List<Content>,
        prompt: String,
        onPartialText: (String) -> Unit,
        onRetryStatus: (String) -> Unit,
        fallbackModel: GenerativeModel? = null,
        fallbackModelProvider: (() -> GenerativeModel)? = null,
    ): GeminiReplyResult {
        return try {
            val text = executeReplyWithModel(
                model = model,
                historyContents = historyContents,
                prompt = prompt,
                onPartialText = onPartialText,
                onRetryStatus = onRetryStatus,
            )
            GeminiReplyResult(text = text, modelUsed = primaryModelId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val resolvedFallback = if (isModelUnavailableError(t)) {
                fallbackModel ?: fallbackModelProvider?.invoke()
            } else {
                null
            }
            if (resolvedFallback != null) {
                Log.w(TAG, "Primary chat model $primaryModelId unavailable (${t.message}); using fallback model $FALLBACK_MODEL_ID")
                onRetryStatus("Switching to $FALLBACK_MODEL_ID...")
                val text = executeReplyWithModel(
                    model = resolvedFallback,
                    historyContents = historyContents,
                    prompt = prompt,
                    onPartialText = onPartialText,
                    onRetryStatus = onRetryStatus,
                )
                GeminiReplyResult(text = text, modelUsed = FALLBACK_MODEL_ID)
            } else {
                throw t
            }
        }
    }

    private suspend fun executeReplyWithModel(
        model: GenerativeModel,
        historyContents: List<Content>,
        prompt: String,
        onPartialText: (String) -> Unit,
        onRetryStatus: (String) -> Unit,
    ): String {
        return executeWithExponentialBackoff(
            policy = policy,
            onRetryScheduled = { nextAttempt, maxAttempts, delayMs, cause ->
                val reason = if (cause is TimeoutCancellationException || cause is SocketTimeoutException) {
                    "Slow response"
                } else {
                    "Connection hiccup"
                }
                onRetryStatus(
                    "$reason — retrying AI in ${delayMs}ms ($nextAttempt/$maxAttempts)...",
                )
            },
        ) { attemptIndex ->
            val useUnaryFallback = attemptIndex >= policy.maxAttempts - 1
            if (!useUnaryFallback) {
                attemptStreamingOrThrow(
                    model = model,
                    historyContents = historyContents,
                    prompt = prompt,
                    onPartialText = onPartialText,
                )
            } else {
                attemptUnaryOrThrow(
                    model = model,
                    historyContents = historyContents,
                    prompt = prompt,
                )
            }
        }
    }

    private suspend fun attemptStreamingOrThrow(
        model: GenerativeModel,
        historyContents: List<Content>,
        prompt: String,
        onPartialText: (String) -> Unit,
    ): String = coroutineScope {
        val accumulated = StringBuilder()
        val channel = Channel<Result<String>>(Channel.UNLIMITED)
        val producerJob = launch(Dispatchers.IO) {
            try {
                val stream = if (historyContents.isEmpty()) {
                    model.generateContentStream(prompt)
                } else {
                    model.startChat(history = historyContents).sendMessageStream(prompt)
                }
                stream.collect { chunk ->
                    val text = chunk.text.orEmpty()
                    if (text.isNotEmpty()) {
                        channel.trySend(Result.success(text))
                    }
                }
                channel.close()
            } catch (e: CancellationException) {
                channel.close(e)
                throw e
            } catch (t: Throwable) {
                channel.trySend(Result.failure(t))
                channel.close()
            }
        }

        try {
            while (true) {
                val waitMs = if (accumulated.isEmpty()) {
                    policy.streamFirstTokenTimeoutMs
                } else {
                    policy.streamChunkIdleTimeoutMs
                }
                val received = withTimeoutOrNull(waitMs) {
                    channel.receiveCatching()
                }
                if (received == null) {
                    if (accumulated.isNotEmpty()) {
                        return@coroutineScope accumulated.toString().trim()
                    }
                    throw StreamStalledTimeoutException(
                        "Timed out waiting ${waitMs}ms for first token from Gemini model",
                    )
                }
                if (received.isClosed) {
                    val cause = received.exceptionOrNull()
                    if (cause != null && cause !is CancellationException) {
                        if (accumulated.isNotEmpty()) {
                            return@coroutineScope accumulated.toString().trim()
                        }
                        throw cause
                    }
                    break
                }
                val chunkResult = received.getOrNull() ?: continue
                chunkResult.onSuccess { text ->
                    accumulated.append(text)
                    onPartialText(accumulated.toString())
                }.onFailure { err ->
                    if (accumulated.isNotEmpty()) {
                        return@coroutineScope accumulated.toString().trim()
                    }
                    throw err
                }
            }
            val finalReply = accumulated.toString().trim()
            if (finalReply.isEmpty()) {
                throw IOException("Gemini model returned an empty stream")
            }
            finalReply
        } finally {
            producerJob.cancel()
            channel.close()
        }
    }

    private suspend fun attemptUnaryOrThrow(
        model: GenerativeModel,
        historyContents: List<Content>,
        prompt: String,
    ): String {
        val reply = withTimeout(policy.unaryTimeoutMs) {
            withContext(Dispatchers.IO) {
                val response = if (historyContents.isEmpty()) {
                    model.generateContent(prompt)
                } else {
                    model.startChat(history = historyContents).sendMessage(prompt)
                }
                response.text.orEmpty().trim()
            }
        }
        if (reply.isEmpty()) {
            throw IOException("Gemini model returned an empty response")
        }
        return reply
    }

    private class StreamStalledTimeoutException(message: String) : IOException(message)

    companion object {
        const val TARGET_MODEL_ID = "gemini-3.8-flash"
        const val FALLBACK_MODEL_ID = "gemini-3.5-flash-lite"

        fun extractJsonObjectString(raw: String): String {
            var trimmed = raw.trim()
            if (trimmed.startsWith("```")) {
                trimmed = trimmed
                    .removePrefix("```json")
                    .removePrefix("```JSON")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
            }
            val firstBrace = trimmed.indexOf('{')
            val lastBrace = trimmed.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return trimmed.substring(firstBrace, lastBrace + 1)
            }
            return trimmed
        }

        private fun sanitizeGeneratedFileName(raw: String, fallback: String): String {
            val cleaned = raw.replace("\\", "/")
                .substringAfterLast('/')
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .trim('.')
            if (cleaned.isBlank() || cleaned.contains("..")) return fallback
            return cleaned.take(80)
        }

        fun parseLocalhostBootstrapJson(
            rawJson: String,
            workspaceName: String,
            port: Int,
        ): LocalhostBootstrapPlan {
            val cleaned = extractJsonObjectString(rawJson)
            if (cleaned.isBlank()) {
                throw GeminiBootstrapException(
                    GeminiFailureReason.INVALID_RESPONSE,
                    "Gemini returned an empty response instead of workspace JSON.",
                )
            }
            val parsed = try {
                SimpleJson.parseObject(cleaned)
            } catch (t: Throwable) {
                throw GeminiBootstrapException(
                    GeminiFailureReason.INVALID_RESPONSE,
                    "Invalid Gemini JSON response: ${t.message ?: "malformed JSON"}",
                    t,
                )
            }

            val indexHtml = SimpleJson.stringValue(parsed, "indexHtml").trim()
            val mainContent = SimpleJson.stringValue(parsed, "mainScriptContent").trim()
            if (indexHtml.isBlank() && mainContent.isBlank()) {
                throw GeminiBootstrapException(
                    GeminiFailureReason.EMPTY_GENERATED_FILES,
                    "Gemini generated empty workspace files (neither indexHtml nor mainScriptContent contained code).",
                )
            }

            val mainName = sanitizeGeneratedFileName(
                SimpleJson.stringValue(parsed, "mainScriptName"),
                fallback = "app.js",
            )
            val setupCmd = runCatching { SimpleJson.stringValue(parsed, "setupCommand").trim() }.getOrDefault("")
            val readmeMd = SimpleJson.stringValue(parsed, "readmeMd").trim()

            val files = linkedMapOf<String, String>()
            if (indexHtml.isNotBlank()) files["index.html"] = indexHtml
            if (mainContent.isNotBlank()) files[mainName] = mainContent
            if (readmeMd.isNotBlank()) files["README.md"] = readmeMd

            return LocalhostBootstrapPlan(
                summary = SimpleJson.stringValue(parsed, "summary").trim()
                    .ifBlank { "Gemini generated $workspaceName starter files for 127.0.0.1:$port" },
                termuxCommand = "",
                starterFiles = files,
                setupCommand = setupCmd,
                generatedByAi = true,
            )
        }

        fun parseWorkspaceEditJson(
            rawJson: String,
            currentFileName: String,
        ): AiWorkspaceEditResult {
            val cleaned = extractJsonObjectString(rawJson)
            if (cleaned.isBlank()) {
                throw GeminiBootstrapException(
                    GeminiFailureReason.INVALID_RESPONSE,
                    "Gemini returned an empty code edit response.",
                )
            }
            val parsed = try {
                SimpleJson.parseObject(cleaned)
            } catch (t: Throwable) {
                throw GeminiBootstrapException(
                    GeminiFailureReason.INVALID_RESPONSE,
                    "Invalid Gemini JSON code edit: ${t.message ?: "malformed JSON"}",
                    t,
                )
            }
            val targetName = sanitizeGeneratedFileName(
                SimpleJson.stringValue(parsed, "targetFileName"),
                fallback = currentFileName.ifBlank { "index.html" },
            )
            val updated = SimpleJson.stringValue(parsed, "updatedContent")
            if (updated.isBlank()) {
                throw GeminiBootstrapException(
                    GeminiFailureReason.EMPTY_GENERATED_FILES,
                    "Gemini returned empty file content for $targetName.",
                )
            }

            val extraFiles = linkedMapOf<String, String>()
            SimpleJson.objectListValue(parsed, "additionalFiles").forEach { item ->
                val name = sanitizeGeneratedFileName(SimpleJson.stringValue(item, "name"), fallback = "")
                val content = SimpleJson.stringValue(item, "content")
                if (name.isNotBlank() && content.isNotBlank() && name != targetName) {
                    extraFiles[name] = content
                }
            }

            return AiWorkspaceEditResult(
                summary = SimpleJson.stringValue(parsed, "summary").trim().ifBlank { "AI updated $targetName" },
                targetFileName = targetName,
                updatedContent = updated,
                extraFiles = extraFiles,
            )
        }

        fun classifyGeminiFailure(error: Throwable): GeminiBootstrapException {
            if (error is GeminiBootstrapException) return error
            var cursor: Throwable? = error
            while (cursor != null) {
                if (cursor is GeminiBootstrapException) return cursor
                if (cursor is TimeoutCancellationException || cursor is SocketTimeoutException) {
                    return GeminiBootstrapException(
                        GeminiFailureReason.TIMEOUT,
                        "Gemini timed out waiting for a workspace response. Check network or retry.",
                        error,
                    )
                }
                if (cursor is UnknownHostException || cursor is ConnectException) {
                    return GeminiBootstrapException(
                        GeminiFailureReason.NETWORK_UNAVAILABLE,
                        "Network unavailable while contacting Gemini AI (${cursor.localizedMessage ?: cursor.javaClass.simpleName}).",
                        error,
                    )
                }
                val msg = cursor.message.orEmpty()
                when {
                    msg.contains("FirebaseApp", ignoreCase = true) ||
                        msg.contains("Firebase is not initialized", ignoreCase = true) ||
                        msg.contains("google-services", ignoreCase = true) -> {
                        return GeminiBootstrapException(
                            GeminiFailureReason.FIREBASE_NOT_INITIALIZED,
                            "Firebase initialization failure: FirebaseApp or Google Services config is missing.",
                            error,
                        )
                    }
                    msg.contains("403") ||
                        msg.contains("AppCheck", ignoreCase = true) ||
                        msg.contains("PERMISSION_DENIED", ignoreCase = true) ||
                        msg.contains("attestation", ignoreCase = true) ||
                        msg.contains("API key not valid", ignoreCase = true) -> {
                        return GeminiBootstrapException(
                            GeminiFailureReason.API_PERMISSION_DENIED,
                            "Gemini API permission denied (App Check attestation or API authorization rejected).",
                            error,
                        )
                    }
                    msg.contains("429") ||
                        msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                        msg.contains("quota", ignoreCase = true) -> {
                        return GeminiBootstrapException(
                            GeminiFailureReason.TIMEOUT,
                            "Gemini rate limit or quota temporarily reached (HTTP 429). Retry shortly.",
                            error,
                        )
                    }
                    msg.contains("404") ||
                        msg.contains("NOT_FOUND", ignoreCase = true) ||
                        msg.contains("unknown model", ignoreCase = true) -> {
                        return GeminiBootstrapException(
                            GeminiFailureReason.API_PERMISSION_DENIED,
                            "Configured Gemini model unavailable (HTTP 404). Update model preset or retry.",
                            error,
                        )
                    }
                    msg.contains("empty response", ignoreCase = true) ||
                        msg.contains("malformed JSON", ignoreCase = true) ||
                        msg.contains("JSONException", ignoreCase = true) -> {
                        return GeminiBootstrapException(
                            GeminiFailureReason.INVALID_RESPONSE,
                            "Invalid Gemini response: unable to parse structured workspace JSON.",
                            error,
                        )
                    }
                }
                cursor = cursor.cause
            }
            return GeminiBootstrapException(
                GeminiFailureReason.UNKNOWN,
                error.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "Gemini bootstrap failed (${error.javaClass.simpleName}).",
                error,
            )
        }

        fun isFirebaseConfigured(context: Context): Boolean =
            FirebaseApp.getApps(context).any { it.name == FirebaseApp.DEFAULT_APP_NAME }

        suspend fun <T> executeWithExponentialBackoff(
            policy: RetryPolicy = RetryPolicy(),
            delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
            onRetryScheduled: (nextAttempt: Int, maxAttempts: Int, delayMs: Long, cause: Throwable) -> Unit = { _, _, _, _ -> },
            block: suspend (attemptIndex: Int) -> T,
        ): T {
            require(policy.maxAttempts >= 1) { "maxAttempts must be at least 1" }
            var lastError: Throwable? = null

            for (attempt in 0 until policy.maxAttempts) {
                try {
                    return block(attempt)
                } catch (err: Throwable) {
                    if (err is CancellationException && err !is TimeoutCancellationException) {
                        throw err
                    }
                    lastError = err
                    val hasMoreAttempts = attempt + 1 < policy.maxAttempts
                    if (!hasMoreAttempts || !isRetryableError(err)) {
                        throw err
                    }
                    val backoffMs = computeBackoffDelayMs(attempt = attempt, policy = policy)
                    runCatching {
                        Log.w(
                            TAG,
                            "Attempt ${attempt + 1}/${policy.maxAttempts} failed (${err.javaClass.simpleName}: ${err.message}); retrying in ${backoffMs}ms",
                        )
                    }
                    onRetryScheduled(attempt + 2, policy.maxAttempts, backoffMs, err)
                    delayFn(backoffMs)
                }
            }
            throw lastError ?: IllegalStateException("Retry loop exited unexpectedly")
        }

        fun computeBackoffDelayMs(
            attempt: Int,
            policy: RetryPolicy = RetryPolicy(),
            randomUnit: Double = Random.nextDouble(),
        ): Long {
            val base = policy.initialDelayMs.toDouble() * policy.backoffMultiplier.pow(attempt.toDouble())
            val capped = min(base, policy.maxDelayMs.toDouble())
            val jitterSpan = capped * policy.jitterRatio.coerceIn(0.0, 0.5)
            val offset = ((randomUnit.coerceIn(0.0, 1.0) * 2.0) - 1.0) * jitterSpan
            return (capped + offset).toLong().coerceAtLeast(100L)
        }

        fun isModelUnavailableError(error: Throwable): Boolean {
            var cursor: Throwable? = error
            while (cursor != null) {
                val msg = cursor.message.orEmpty()
                if (msg.contains("403") ||
                    msg.contains("AppCheck", ignoreCase = true) ||
                    msg.contains("PERMISSION_DENIED", ignoreCase = true)
                ) {
                    return false
                }
                if (msg.contains("404") ||
                    msg.contains("NOT_FOUND", ignoreCase = true) ||
                    msg.contains("not found for API", ignoreCase = true) ||
                    msg.contains("not supported for generateContent", ignoreCase = true) ||
                    msg.contains("unknown model", ignoreCase = true) ||
                    msg.contains("invalid model", ignoreCase = true) ||
                    msg.contains("thinkingLevel", ignoreCase = true) ||
                    msg.contains("thinking_level", ignoreCase = true) ||
                    msg.contains("thinkingConfig", ignoreCase = true) ||
                    msg.contains("thinking", ignoreCase = true)
                ) {
                    return true
                }
                cursor = cursor.cause
            }
            return false
        }

        fun isRetryableError(error: Throwable): Boolean {
            if (error is CancellationException && error !is TimeoutCancellationException) {
                return false
            }
            if (error is GeminiBootstrapException) {
                return error.reason == GeminiFailureReason.TIMEOUT ||
                    error.reason == GeminiFailureReason.NETWORK_UNAVAILABLE
            }
            // Check non-retryable auth/argument/model errors across the full cause chain FIRST
            var checkCursor: Throwable? = error
            while (checkCursor != null) {
                val msg = checkCursor.message.orEmpty()
                if (msg.contains("401") ||
                    msg.contains("403") ||
                    msg.contains("UNAUTHENTICATED", ignoreCase = true) ||
                    msg.contains("AppCheck", ignoreCase = true) ||
                    msg.contains("PERMISSION_DENIED", ignoreCase = true) ||
                    msg.contains("API key not valid", ignoreCase = true) ||
                    msg.contains("400") ||
                    msg.contains("INVALID_ARGUMENT", ignoreCase = true) ||
                    msg.contains("404") ||
                    msg.contains("NOT_FOUND", ignoreCase = true) ||
                    msg.contains("FirebaseApp", ignoreCase = true) ||
                    msg.contains("Firebase is not initialized", ignoreCase = true)
                ) {
                    return false
                }
                checkCursor = checkCursor.cause
            }

            var cursor: Throwable? = error
            while (cursor != null) {
                when (cursor) {
                    is TimeoutCancellationException,
                    is SocketTimeoutException,
                    is ConnectException,
                    is UnknownHostException,
                    is IOException -> return true
                }
                val msg = cursor.message.orEmpty()
                if (msg.contains("429") ||
                    msg.contains("500") ||
                    msg.contains("502") ||
                    msg.contains("503") ||
                    msg.contains("504") ||
                    msg.contains("408") ||
                    msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("timed out", ignoreCase = true) ||
                    msg.contains("unavailable", ignoreCase = true) ||
                    msg.contains("connection reset", ignoreCase = true) ||
                    msg.contains("network", ignoreCase = true)
                ) {
                    return true
                }
                cursor = cursor.cause
            }
            return false
        }
    }
}
