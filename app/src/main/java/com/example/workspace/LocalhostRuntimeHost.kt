package com.example.workspace

import android.content.Context
import com.example.ai.GeminiChatService
import com.example.data.model.LocalhostRuntimeKind
import com.example.security.SecureVault
import com.example.service.WatchdogHealthStatus
import com.example.service.WatchdogSnapshot
import com.example.terminal.TerminalEmulator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

private const val WATCHDOG_PREFS = "vsshell_watchdog_state"
private const val KEY_ENABLED = "watchdog_enabled"
private const val KEY_PORT = "watchdog_port"
private const val KEY_WS_NAME = "watchdog_ws_name"
private const val KEY_WS_SLUG = "watchdog_ws_slug"
private const val KEY_TERMUX_CMD = "watchdog_termux_cmd"
private const val KEY_IS_EXTERNAL = "watchdog_is_external"
private const val KEY_RUNTIME_KIND = "watchdog_runtime_kind"
private const val KEY_FAILURES = "watchdog_failures"
private const val KEY_HEALTHY_START = "watchdog_healthy_start_ms"
private const val KEY_LAST_FAILURE = "watchdog_last_failure_ms"
private const val KEY_TOTAL_REBOOTS = "watchdog_total_reboots"

data class WatchdogTarget(
    val port: Int = 8080,
    val workspaceName: String = "AI & Termux Localhost :8080",
    val workspaceSlug: String = "default",
    val termuxCommand: String = LocalhostServerStatus.DEFAULT_TERMUX_CODE_SERVER_CMD,
    val isExternalTermux: Boolean = false,
    val runtimeKind: LocalhostRuntimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
)

data class WatchdogUiState(
    val isEnabled: Boolean = false,
    val status: WatchdogHealthStatus = WatchdogHealthStatus.IDLE,
    val message: String = "Standby",
    val attemptNumber: Int = 0,
    val maxAttempts: Int = 5,
    val totalReboots: Int = 0,
    val nextRetryInSec: Int = 0,
)

/**
 * Process-scoped runtime host so [CodeServerWatchdogService] and [WorkspaceViewModel]
 * share the same [LocalhostServerEngine], [TerminalEmulator], and persisted watchdog target.
 */
object LocalhostRuntimeHost {

    @Volatile
    private var runtimeManagerInstance: com.example.runtime.RuntimeManager? = null

    @Volatile
    private var engineInstance: LocalhostServerEngine? = null

    val terminalEmulator = TerminalEmulator()

    private val defaultGeminiService by lazy { GeminiChatService() }

    private val _watchdogState = MutableStateFlow(WatchdogUiState())
    val watchdogState: StateFlow<WatchdogUiState> = _watchdogState.asStateFlow()

    private val _rebootEvents = MutableSharedFlow<LocalhostServerStatus>(extraBufferCapacity = 8)
    val rebootEvents: SharedFlow<LocalhostServerStatus> = _rebootEvents.asSharedFlow()

    @Volatile
    private var activeTarget: WatchdogTarget? = null

    fun getRuntimeManager(context: Context): com.example.runtime.RuntimeManager {
        return runtimeManagerInstance ?: synchronized(this) {
            runtimeManagerInstance ?: run {
                val appCtx = context.applicationContext
                val nativeLibDir = runCatching { appCtx.applicationInfo.nativeLibraryDir }.getOrNull()
                com.example.runtime.RuntimeManager(
                    appFilesDir = appCtx.filesDir,
                    nativeLibDirPath = nativeLibDir,
                    context = appCtx,
                ).also { manager ->
                    runtimeManagerInstance = manager
                }
            }
        }
    }

    fun getEngine(context: Context): LocalhostServerEngine {
        val manager = getRuntimeManager(context)
        return engineInstance ?: synchronized(this) {
            engineInstance ?: manager.localhostEngine.also { engine ->
                engine.setLogSink { source, ansiMessage ->
                    terminalEmulator.appendAnsi(source, ansiMessage)
                }
                engine.setAiCodeHandler { prompt, currentFile, currentContent, files ->
                    terminalEmulator.info("GEMINI", "AI build request for '${currentFile.ifBlank { "index.html" }}': ${prompt.take(72)}")
                    defaultGeminiService.generateWorkspaceCodeEdit(
                        prompt = prompt,
                        currentFileName = currentFile,
                        currentFileContent = currentContent,
                        workspaceFiles = files,
                    ).onSuccess { edit ->
                        terminalEmulator.success("GEMINI", "Built ${edit.targetFileName}: ${edit.summary}")
                    }.onFailure { err ->
                        terminalEmulator.error("GEMINI", "AI build failed: ${err.localizedMessage ?: err.javaClass.simpleName}")
                    }.map { edit ->
                        AiBuildResponse(
                            summary = edit.summary,
                            targetFileName = edit.targetFileName,
                            updatedContent = edit.updatedContent,
                            extraFiles = edit.extraFiles,
                        )
                    }
                }
                engineInstance = engine
            }
        }
    }

    fun updateTarget(context: Context, target: WatchdogTarget, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(WATCHDOG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_PORT, target.port)
            .putString(KEY_WS_NAME, target.workspaceName)
            .putString(KEY_WS_SLUG, target.workspaceSlug)
            .putString(KEY_TERMUX_CMD, SecureVault.encryptSecret(target.termuxCommand))
            .putBoolean(KEY_IS_EXTERNAL, target.isExternalTermux)
            .putString(KEY_RUNTIME_KIND, target.runtimeKind.name)
            .apply()
        activeTarget = if (enabled) target else null
    }

    fun clearTarget(context: Context) {
        activeTarget = null
        context.applicationContext
            .getSharedPreferences(WATCHDOG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, false)
            .apply()
        _watchdogState.value = WatchdogUiState(
            isEnabled = false,
            status = WatchdogHealthStatus.IDLE,
            message = "Standby",
        )
    }

    fun loadTarget(context: Context): Pair<Boolean, WatchdogTarget?> {
        val current = activeTarget
        val prefs = runCatching {
            context.applicationContext.getSharedPreferences(WATCHDOG_PREFS, Context.MODE_PRIVATE)
        }.getOrNull() ?: return Pair(current != null, current)

        val enabled = prefs.getBoolean(KEY_ENABLED, current != null)
        if (!enabled) return Pair(false, null)
        if (current != null) return Pair(true, current)

        val port = prefs.getInt(KEY_PORT, 8080).coerceIn(1024, 65535)
        val wsName = prefs.getString(KEY_WS_NAME, null)?.takeIf { it.isNotBlank() }
            ?: "AI & Termux Localhost :$port"
        val wsSlug = prefs.getString(KEY_WS_SLUG, null)?.takeIf { it.isNotBlank() }
            ?: "default"
        val storedCommand = prefs.getString(KEY_TERMUX_CMD, null).orEmpty()
        val decryptedCommand = SecureVault.decryptSecret(storedCommand)
        if (storedCommand.isNotBlank() && !SecureVault.isEncryptedPayload(storedCommand)) {
            prefs.edit().putString(KEY_TERMUX_CMD, SecureVault.encryptSecret(decryptedCommand)).apply()
        }
        val cmd = decryptedCommand.takeIf { it.isNotBlank() }
            ?: LocalhostServerStatus.DEFAULT_TERMUX_CODE_SERVER_CMD
        val isExternal = prefs.getBoolean(KEY_IS_EXTERNAL, false)
        val rawKind = prefs.getString(KEY_RUNTIME_KIND, null)
        val runtimeKind = runCatching {
            rawKind?.let { LocalhostRuntimeKind.valueOf(it) }
        }.getOrNull() ?: if (isExternal) {
            LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER
        } else {
            LocalhostRuntimeKind.EMBEDDED_EDITOR
        }
        return Pair(
            true,
            WatchdogTarget(
                port = port,
                workspaceName = wsName,
                workspaceSlug = wsSlug,
                termuxCommand = cmd,
                isExternalTermux = isExternal,
                runtimeKind = runtimeKind,
            ),
        )
    }

    fun loadBackoffSnapshot(context: Context): WatchdogSnapshot {
        val prefs = runCatching {
            context.applicationContext.getSharedPreferences(WATCHDOG_PREFS, Context.MODE_PRIVATE)
        }.getOrNull() ?: return WatchdogSnapshot()

        return WatchdogSnapshot(
            consecutiveFailures = prefs.getInt(KEY_FAILURES, 0),
            lastHealthyStartMs = prefs.getLong(KEY_HEALTHY_START, 0L),
            lastFailureMs = prefs.getLong(KEY_LAST_FAILURE, 0L),
            totalReboots = prefs.getInt(KEY_TOTAL_REBOOTS, 0),
        )
    }

    fun saveBackoffSnapshot(context: Context, snapshot: WatchdogSnapshot) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(WATCHDOG_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_FAILURES, snapshot.consecutiveFailures)
                .putLong(KEY_HEALTHY_START, snapshot.lastHealthyStartMs)
                .putLong(KEY_LAST_FAILURE, snapshot.lastFailureMs)
                .putInt(KEY_TOTAL_REBOOTS, snapshot.totalReboots)
                .apply()
        }
    }

    fun updateWatchdogUiState(state: WatchdogUiState) {
        _watchdogState.value = state
    }

    fun emitRebootSuccess(status: LocalhostServerStatus) {
        _rebootEvents.tryEmit(status)
    }
}
