package com.example.runtime

enum class RuntimeId(val label: String) {
    VSCODE_SERVER("VS Code Runtime"),
    USER_TERMINAL("User Terminal"),
}

enum class RuntimeLifecycleState(val label: String) {
    NOT_STARTED("Not Started"),
    INITIALIZING("Initializing"),
    CHECKING_DEPENDENCIES("Checking Dependencies"),
    STARTING("Starting"),
    WAITING_FOR_PORT("Waiting for Port"),
    HEALTH_CHECKING("Health Checking"),
    VERIFYING_IDE("Verifying IDE"),
    READY("Ready"),
    FAILED("Failed"),
    STOPPED("Stopped"),
}

enum class VsCodeIdeBackendKind(val label: String) {
    NONE("Not Running"),
    BUNDLED_CODE_SERVER("Bundled code-server"),
    EXTERNAL_LOOPBACK_CODE_SERVER("External Loopback code-server"),
    MISSING_BINARY("Missing Native Binaries"),
    PORT_CONFLICT("Port Conflict"),
    HEALTH_CHECK_FAILED("Health Check Failed"),
}

data class RuntimeBinaryInventory(
    val nativeLibDirPath: String,
    val hasNodeBinary: Boolean,
    val hasCodeServerBinary: Boolean,
    val hasProotBinary: Boolean,
    val systemShellPath: String,
    val hasSystemShell: Boolean,
    val availableSystemTools: List<String>,
    val missingIdeBinaries: List<String>,
) {
    val isCodeServerRunnableInApp: Boolean
        get() = hasNodeBinary && hasCodeServerBinary
}

data class RuntimeInstanceStatus(
    val runtimeId: RuntimeId,
    val state: RuntimeLifecycleState = RuntimeLifecycleState.NOT_STARTED,
    val backendKind: VsCodeIdeBackendKind = VsCodeIdeBackendKind.NONE,
    val port: Int? = null,
    val url: String = "",
    val workspaceSlug: String = "",
    val workspacePath: String = "",
    val pid: Long? = null,
    val restartCount: Int = 0,
    val statusMessage: String = "Standby",
    val errorMessage: String? = null,
    val isRunning: Boolean = false,
)

data class MemorySnapshot(
    val totalMb: Long,
    val availableMb: Long,
    val lowMemory: Boolean,
)

data class WorkspaceChangeEvent(
    val slug: String,
    val workspacePath: String,
    val source: String,
    val summary: String,
    val revision: Long,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class PendingCommandConfirmation(
    val id: Long,
    val command: String,
    val reason: String,
    val workspaceSlug: String,
    val isSetupStep: Boolean = false,
)

data class CommandExecutionResult(
    val command: String,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val outputLines: List<String> = emptyList(),
    val errorMessage: String? = null,
) {
    val succeeded: Boolean
        get() = exitCode == 0 && !timedOut && !requiresConfirmation && errorMessage == null
}

enum class AiManagedBackendMode(val label: String) {
    NONE("Standby"),
    BUNDLED_CODE_SERVER("Bundled code-server"),
    EXTERNAL_CODE_SERVER("External Loopback code-server"),
    FALLBACK_LOCAL_VIEWER("Managed Local Workspace Server"),
    UNAVAILABLE("Unavailable"),
}

data class RuntimeMetadataRecord(
    val workspaceSlug: String = "",
    val workspaceName: String = "",
    val workspaceDirPath: String = "",
    val port: Int = 8080,
    val url: String = "",
    val backendMode: AiManagedBackendMode = AiManagedBackendMode.NONE,
    val pid: Long? = null,
    val lastVerifiedAtMs: Long = 0L,
)

data class AiManagedRuntimeSnapshot(
    val state: RuntimeLifecycleState = RuntimeLifecycleState.NOT_STARTED,
    val backendMode: AiManagedBackendMode = AiManagedBackendMode.NONE,
    val workspaceSlug: String = "",
    val workspaceDirPath: String = "",
    val port: Int? = null,
    val url: String = "",
    val pid: Long? = null,
    val isRunning: Boolean = false,
    val reusedExistingInstance: Boolean = false,
    val isVerifiedHealthy: Boolean = false,
    val statusMessage: String = "AI-managed runtime idle",
    val errorMessage: String? = null,
)

internal fun waitForProcessCompat(proc: Process, timeoutMs: Long): Boolean {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        return proc.waitFor(timeoutMs.coerceAtLeast(0L), java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    val deadlineNs = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
    while (true) {
        val exited = runCatching {
            proc.exitValue()
            true
        }.getOrDefault(false)
        if (exited) return true
        if (System.nanoTime() >= deadlineNs) return false
        Thread.sleep(25L)
    }
}

internal fun destroyProcessForciblyCompat(proc: Process) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        proc.destroyForcibly()
    } else {
        proc.destroy()
    }
}
