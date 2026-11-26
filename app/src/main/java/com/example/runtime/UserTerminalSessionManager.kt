package com.example.runtime

import com.example.security.SecureVault
import com.example.terminal.TerminalEmulator
import com.example.terminal.TerminalLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val MAX_HISTORY_ITEMS = 200
private const val PWD_MARKER_PREFIX = "__VSSHELL_PWD__:"
private const val DONE_MARKER_PREFIX = "__VSSHELL_DONE__:"

/**
 * Independent built-in User Terminal running a real interactive OS shell (`/system/bin/sh`
 * on Android or `/bin/sh` fallback) with persistent command history, live stdin/stdout/stderr
 * streaming, working-directory tracking inside [SharedWorkspaceFilesystem], and strict lifecycle
 * isolation from [VsCodeRuntimeController].
 */
class UserTerminalSessionManager(
    private val appFilesDir: File,
    private val sharedFilesystem: SharedWorkspaceFilesystem,
    private val shellExecutableOverride: String? = null,
    private val binaryInventoryProvider: () -> RuntimeBinaryInventory = {
        VsCodeRuntimeController.inspectDeviceBinaries(appFilesDir = appFilesDir, nativeLibDir = null)
    },
) {
    val terminalEmulator = TerminalEmulator(maxLines = 500)
    val lines: StateFlow<List<TerminalLine>> = terminalEmulator.lines

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        terminalEmulator.error("TERM", "Background terminal error: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val lifecycleMutex = Mutex()
    private val commandExecMutex = Mutex()
    private val confirmationSeq = AtomicLong(1L)
    private val markerSeq = AtomicLong(1L)
    private val historyFile = File(appFilesDir, "user_terminal_history.txt")

    private data class PendingCommandCompletion(
        val token: String,
        val deferred: CompletableDeferred<Int>,
        val capturedOutput: MutableList<String> = Collections.synchronizedList(mutableListOf()),
    )

    private val activeCommandCompletion = AtomicReference<PendingCommandCompletion?>(null)

    @Volatile
    private var shellProcess: Process? = null

    @Volatile
    private var stdinWriter: BufferedWriter? = null

    @Volatile
    private var stdoutJob: Job? = null

    @Volatile
    private var stderrJob: Job? = null

    @Volatile
    private var monitorJob: Job? = null

    @Volatile
    private var currentWorkspaceDir: File = sharedFilesystem.getActiveWorkspaceDir()

    @Volatile
    private var currentWorkspaceSlug: String = sharedFilesystem.activeWorkspaceSlug.value

    private val _status = MutableStateFlow(
        RuntimeInstanceStatus(
            runtimeId = RuntimeId.USER_TERMINAL,
            state = RuntimeLifecycleState.NOT_STARTED,
            workspaceSlug = sharedFilesystem.activeWorkspaceSlug.value,
            workspacePath = sharedFilesystem.getActiveWorkspaceDir().absolutePath,
            statusMessage = "Shell idle — tap Start or run a command",
            isRunning = false,
        ),
    )
    val status: StateFlow<RuntimeInstanceStatus> = _status.asStateFlow()

    private val _commandHistory = MutableStateFlow(loadPersistedHistory())
    val commandHistory: StateFlow<List<String>> = _commandHistory.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingCommandConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingCommandConfirmation?> = _pendingConfirmation.asStateFlow()

    init {
        terminalEmulator.info(
            "USER-TERM",
            "Interactive shell ready (type 'help', 'ws-info', 'ws-tools', 'ws-files'). Independent from VS Code runtime.",
        )
    }

    fun resolveShellExecutable(): String? {
        shellExecutableOverride?.let { overridePath ->
            if (File(overridePath).canExecute()) return overridePath
        }
        return listOf("/system/bin/sh", "/bin/sh").firstOrNull { candidate ->
            runCatching { File(candidate).canExecute() }.getOrDefault(false)
        }
    }

    suspend fun ensureStarted(workspaceDir: File = sharedFilesystem.getActiveWorkspaceDir()): RuntimeInstanceStatus {
        val currentProc = shellProcess
        if (currentProc != null && isProcessAliveCompat(currentProc)) {
            syncWorkspaceDirectory(workspaceDir, sharedFilesystem.activeWorkspaceSlug.value)
            return _status.value
        }
        return startShell(workspaceDir, sharedFilesystem.activeWorkspaceSlug.value)
    }

    suspend fun startShell(
        workspaceDir: File = sharedFilesystem.getActiveWorkspaceDir(),
        workspaceSlug: String = sharedFilesystem.activeWorkspaceSlug.value,
    ): RuntimeInstanceStatus = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            stopShellInternal(updateStateToStopped = false)

            val safeDir = if (workspaceDir.exists() && workspaceDir.isDirectory) {
                workspaceDir
            } else {
                sharedFilesystem.resolveWorkspaceDir(workspaceSlug)
            }
            currentWorkspaceDir = safeDir
            currentWorkspaceSlug = workspaceSlug

            val shellPath = resolveShellExecutable()
            if (shellPath == null) {
                val failStatus = RuntimeInstanceStatus(
                    runtimeId = RuntimeId.USER_TERMINAL,
                    state = RuntimeLifecycleState.FAILED,
                    workspaceSlug = workspaceSlug,
                    workspacePath = safeDir.absolutePath,
                    statusMessage = "No POSIX shell binary found",
                    errorMessage = "Neither /system/bin/sh nor /bin/sh is executable on this device.",
                    isRunning = false,
                )
                _status.value = failStatus
                terminalEmulator.error("USER-TERM", failStatus.errorMessage.orEmpty())
                return@withLock failStatus
            }

            _status.value = RuntimeInstanceStatus(
                runtimeId = RuntimeId.USER_TERMINAL,
                state = RuntimeLifecycleState.STARTING,
                workspaceSlug = workspaceSlug,
                workspacePath = safeDir.absolutePath,
                statusMessage = "Starting $shellPath...",
                isRunning = false,
            )

            try {
                val tmpDir = File(appFilesDir, "tmp").apply { mkdirs() }
                val homeDir = safeDir
                val pb = ProcessBuilder(shellPath)
                    .directory(safeDir)
                    .redirectErrorStream(false)
                pb.environment().apply {
                    put("HOME", homeDir.absolutePath)
                    put("PWD", safeDir.absolutePath)
                    put("WS_ROOT", safeDir.absolutePath)
                    put("TMPDIR", tmpDir.absolutePath)
                    put("TERM", "xterm-256color")
                    val appUsrBin = File(appFilesDir, "usr/bin").absolutePath
                    put("PATH", "$appUsrBin:/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system/bin:/system/xbin:/bin:/usr/bin")
                }

                val proc = pb.start()
                shellProcess = proc
                stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))

                startStreamReaders(proc)
                startExitMonitor(proc, workspaceSlug)

                val pid = extractPidCompat(proc)
                val ready = RuntimeInstanceStatus(
                    runtimeId = RuntimeId.USER_TERMINAL,
                    state = RuntimeLifecycleState.READY,
                    workspaceSlug = workspaceSlug,
                    workspacePath = safeDir.absolutePath,
                    pid = pid,
                    statusMessage = "$shellPath active (${safeDir.name})",
                    isRunning = true,
                )
                _status.value = ready
                terminalEmulator.success("USER-TERM", "Started $shellPath in ${safeDir.absolutePath}")
                ready
            } catch (t: Throwable) {
                val err = "Failed to launch shell ($shellPath): ${t.localizedMessage ?: t.javaClass.simpleName}"
                val failed = RuntimeInstanceStatus(
                    runtimeId = RuntimeId.USER_TERMINAL,
                    state = RuntimeLifecycleState.FAILED,
                    workspaceSlug = workspaceSlug,
                    workspacePath = safeDir.absolutePath,
                    statusMessage = "Shell launch failed",
                    errorMessage = err,
                    isRunning = false,
                )
                _status.value = failed
                terminalEmulator.error("USER-TERM", err)
                failed
            }
        }
    }

    suspend fun syncWorkspaceDirectory(workspaceDir: File, slug: String) {
        val dir = if (workspaceDir.exists() && workspaceDir.isDirectory) workspaceDir else return
        val dirChanged = currentWorkspaceDir.canonicalPath != dir.canonicalPath
        currentWorkspaceDir = dir
        currentWorkspaceSlug = slug
        val proc = shellProcess
        if (dirChanged && proc != null && isProcessAliveCompat(proc)) {
            writeLineToShell("cd ${shellQuote(dir.absolutePath)}")
            _status.value = _status.value.copy(
                workspaceSlug = slug,
                workspacePath = dir.absolutePath,
                statusMessage = "Shell cwd: ${dir.name}",
            )
            terminalEmulator.info("USER-TERM", "Synced shell working directory -> ${dir.absolutePath}")
        } else {
            _status.value = _status.value.copy(
                workspaceSlug = slug,
                workspacePath = dir.absolutePath,
            )
        }
    }

    private suspend fun tryHandleBuiltInCommand(cleanCmd: String): String? {
        return when (cleanCmd.lowercase()) {
            "help", "?" -> {
                val helpMsg = "Built-in helpers: ws-info | ws-files | ws-tools | ws-sync | history | clear | stop-shell | restart-shell. Any standard POSIX shell command (ls, pwd, cat, mkdir, sh, echo, grep, find) runs in the active workspace."
                terminalEmulator.info("USER-TERM", helpMsg)
                helpMsg
            }
            "clear", "cls" -> {
                terminalEmulator.clear()
                terminalEmulator.info("USER-TERM", "Cleared terminal buffer (${currentWorkspaceDir.name}).")
                "cleared"
            }
            "ws-info" -> {
                val st = _status.value
                val msg = "workspace=${currentWorkspaceSlug} path=${currentWorkspaceDir.absolutePath} shellRunning=${st.isRunning} pid=${st.pid ?: "-"}"
                terminalEmulator.info("USER-TERM", msg)
                msg
            }
            "ws-files" -> {
                val files = withContext(Dispatchers.IO) { sharedFilesystem.listFiles(currentWorkspaceDir) }
                val summary = if (files.isEmpty()) {
                    "Workspace is empty (${currentWorkspaceDir.absolutePath})"
                } else {
                    files.joinToString("\n") { f ->
                        " - ${sharedFilesystem.relativePathOf(currentWorkspaceDir, f)} (${f.length()} bytes)"
                    }
                }
                terminalEmulator.appendPlain("FILES", summary)
                summary
            }
            "ws-tools" -> {
                val inv = withContext(Dispatchers.IO) { binaryInventoryProvider() }
                val toolsLine = "Shell=${inv.systemShellPath.ifBlank { "none" }} | Device tools: ${inv.availableSystemTools.joinToString(", ").ifBlank { "none" }} | Bundled code-server ready: ${inv.isCodeServerRunnableInApp} (missing: ${inv.missingIdeBinaries.joinToString(", ").ifBlank { "none" }})"
                terminalEmulator.info("TOOLS", toolsLine)
                toolsLine
            }
            "ws-sync" -> {
                sharedFilesystem.notifyChanged("user-terminal", currentWorkspaceSlug, "Manual filesystem sync")
                terminalEmulator.success("USER-TERM", "Notified Phone Files & Editor listeners of workspace changes.")
                "synced"
            }
            "history" -> {
                val recent = _commandHistory.value.takeLast(15)
                if (recent.isEmpty()) {
                    terminalEmulator.info("HISTORY", "(empty)")
                } else {
                    recent.forEachIndexed { idx, entry ->
                        terminalEmulator.appendPlain("HISTORY", "${idx + 1}: ${SecureVault.redactSensitiveText(entry)}")
                    }
                }
                "history"
            }
            "stop-shell" -> {
                stopShell()
                "stopped"
            }
            "restart-shell" -> {
                restartShell()
                "restarted"
            }
            else -> null
        }
    }

    suspend fun sendCommand(
        rawCommand: String,
        bypassConfirmation: Boolean = false,
        isSetupStep: Boolean = false,
    ): Result<String> {
        val cleanCmd = rawCommand.trim()
        if (cleanCmd.isBlank()) return Result.success("")

        val sanitizedForLog = SecureVault.redactSensitiveText(cleanCmd)
        terminalEmulator.appendAnsi("USER-TERM", "\u001B[1;32m$ $sanitizedForLog\u001B[0m")
        recordHistory(cleanCmd)

        tryHandleBuiltInCommand(cleanCmd)?.let { result ->
            return Result.success(result)
        }

        val validation = CommandSafetyValidator.validate(cleanCmd)
        when (validation.decision) {
            CommandSafetyDecision.BLOCKED -> {
                terminalEmulator.error("SECURITY", validation.reason)
                return Result.failure(SecurityException(validation.reason))
            }
            CommandSafetyDecision.REQUIRES_CONFIRMATION -> {
                if (!bypassConfirmation) {
                    val req = PendingCommandConfirmation(
                        id = confirmationSeq.getAndIncrement(),
                        command = cleanCmd,
                        reason = validation.reason,
                        workspaceSlug = currentWorkspaceSlug,
                        isSetupStep = isSetupStep,
                    )
                    _pendingConfirmation.value = req
                    terminalEmulator.warn("CONFIRM", "Confirmation required: ${validation.reason}")
                    return Result.failure(IllegalStateException("Confirmation required: ${validation.reason}"))
                }
            }
            CommandSafetyDecision.ALLOWED -> Unit
        }

        return commandExecMutex.withLock {
            val active = ensureStarted(currentWorkspaceDir)
            if (!active.isRunning) {
                return@withLock Result.failure(IllegalStateException(active.errorMessage ?: "User shell unavailable"))
            }

            val sent = withContext(Dispatchers.IO) {
                writeLineToShell(cleanCmd) && writeLineToShell("printf '\\n${PWD_MARKER_PREFIX}%s\\n' \"\$PWD\"")
            }
            if (sent) {
                warnIfRequestedDevToolMissing(cleanCmd)
                sharedFilesystem.notifyChanged(
                    source = "user-terminal",
                    slug = currentWorkspaceSlug,
                    summary = "Executed ${cleanCmd.substringBefore(' ')}",
                )
                Result.success("executed")
            } else {
                val err = "Shell stdin pipe is closed — restarting or tap Restart Shell."
                terminalEmulator.error("USER-TERM", err)
                Result.failure(IllegalStateException(err))
            }
        }
    }

    /**
     * Executes a shell command in the live User Terminal session and suspends until the
     * sentinel exit-code marker is emitted on stdout or [timeoutMs] expires.
     */
    suspend fun executeCommandAndAwaitExit(
        rawCommand: String,
        timeoutMs: Long = 10_000L,
        bypassConfirmation: Boolean = false,
        isSetupStep: Boolean = false,
    ): CommandExecutionResult {
        val cleanCmd = rawCommand.trim()
        if (cleanCmd.isBlank()) {
            return CommandExecutionResult(command = "", exitCode = 0)
        }

        tryHandleBuiltInCommand(cleanCmd)?.let { result ->
            return CommandExecutionResult(
                command = cleanCmd,
                exitCode = 0,
                outputLines = listOf(result),
            )
        }

        val validation = CommandSafetyValidator.validate(cleanCmd)
        when (validation.decision) {
            CommandSafetyDecision.BLOCKED -> {
                terminalEmulator.error("SECURITY", validation.reason)
                return CommandExecutionResult(
                    command = cleanCmd,
                    errorMessage = validation.reason,
                )
            }
            CommandSafetyDecision.REQUIRES_CONFIRMATION -> {
                if (!bypassConfirmation) {
                    val req = PendingCommandConfirmation(
                        id = confirmationSeq.getAndIncrement(),
                        command = cleanCmd,
                        reason = validation.reason,
                        workspaceSlug = currentWorkspaceSlug,
                        isSetupStep = isSetupStep,
                    )
                    _pendingConfirmation.value = req
                    terminalEmulator.warn("CONFIRM", "Confirmation required: ${validation.reason}")
                    return CommandExecutionResult(
                        command = cleanCmd,
                        requiresConfirmation = true,
                        errorMessage = "Confirmation required: ${validation.reason}",
                    )
                }
            }
            CommandSafetyDecision.ALLOWED -> Unit
        }

        return commandExecMutex.withLock {
            val sanitizedForLog = SecureVault.redactSensitiveText(cleanCmd)
            terminalEmulator.appendAnsi("USER-TERM", "\u001B[1;32m$ $sanitizedForLog\u001B[0m")
            recordHistory(cleanCmd)

            val active = ensureStarted(currentWorkspaceDir)
            if (!active.isRunning) {
                return@withLock CommandExecutionResult(
                    command = cleanCmd,
                    errorMessage = active.errorMessage ?: "User shell unavailable",
                )
            }

            val token = "cmd_${markerSeq.getAndIncrement()}"
            val completion = PendingCommandCompletion(token = token, deferred = CompletableDeferred())
            activeCommandCompletion.set(completion)

            val sent = withContext(Dispatchers.IO) {
                writeLineToShell(cleanCmd) &&
                    writeLineToShell("__vs_ec=\$?; printf '\\n${PWD_MARKER_PREFIX}%s\\n${DONE_MARKER_PREFIX}$token:%d\\n' \"\$PWD\" \"\$__vs_ec\"")
            }
            if (!sent) {
                activeCommandCompletion.compareAndSet(completion, null)
                return@withLock CommandExecutionResult(
                    command = cleanCmd,
                    errorMessage = "Shell stdin pipe closed while writing command.",
                )
            }

            warnIfRequestedDevToolMissing(cleanCmd)
            val exitCode = withContext(Dispatchers.IO) {
                persistHistoryNow(_commandHistory.value)
                withTimeoutOrNull(timeoutMs.coerceAtLeast(250L)) {
                    completion.deferred.await()
                }
            }
            activeCommandCompletion.compareAndSet(completion, null)

            sharedFilesystem.notifyChanged(
                source = "user-terminal",
                slug = currentWorkspaceSlug,
                summary = "Executed ${cleanCmd.substringBefore(' ')}",
            )

            if (exitCode == null) {
                CommandExecutionResult(
                    command = cleanCmd,
                    timedOut = true,
                    outputLines = completion.capturedOutput.toList(),
                    errorMessage = "Command timed out after ${timeoutMs}ms waiting for shell completion.",
                )
            } else {
                CommandExecutionResult(
                    command = cleanCmd,
                    exitCode = exitCode,
                    outputLines = completion.capturedOutput.toList(),
                    errorMessage = if (exitCode == 0) null else "Command exited with POSIX code $exitCode",
                )
            }
        }
    }

    private suspend fun warnIfRequestedDevToolMissing(cleanCmd: String) {
        val leadingBinary = cleanCmd
            .trimStart()
            .split(Regex("\\s+"))
            .firstOrNull()
            .orEmpty()
            .substringAfterLast('/')
            .lowercase()
        if (leadingBinary in setOf("pkg", "apt", "apt-get", "node", "npm", "npx", "python", "python3", "pip", "pip3", "git", "code-server")) {
            val inv = withContext(Dispatchers.IO) { binaryInventoryProvider() }
            if (leadingBinary !in inv.availableSystemTools &&
                !(leadingBinary == "code-server" && inv.isCodeServerRunnableInApp)
            ) {
                terminalEmulator.warn(
                    "RUNTIME",
                    "'$leadingBinary' is not pre-installed in Android's POSIX environment (${inv.systemShellPath.ifBlank { "/system/bin/sh" }}). Android 11+ (API 30+) W^X and Play policy block executing unbundled binaries from app-writable storage; run 'ws-tools' to inspect available binaries.",
                )
            }
        }
    }

    suspend fun confirmPendingCommand(): Result<String> {
        val pending = _pendingConfirmation.value ?: return Result.success("")
        _pendingConfirmation.value = null
        return sendCommand(
            rawCommand = pending.command,
            bypassConfirmation = true,
            isSetupStep = pending.isSetupStep,
        )
    }

    fun dismissPendingCommand() {
        val pending = _pendingConfirmation.value
        _pendingConfirmation.value = null
        if (pending != null) {
            terminalEmulator.warn("USER-TERM", "Cancelled command requiring confirmation.")
        }
    }

    suspend fun stopShell() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            stopShellInternal(updateStateToStopped = true)
        }
    }

    suspend fun restartShell(): RuntimeInstanceStatus {
        return startShell(currentWorkspaceDir, currentWorkspaceSlug)
    }

    suspend fun interruptRunningCommand(): RuntimeInstanceStatus {
        terminalEmulator.warn("USER-TERM", "Interrupting shell session and spawning fresh clean shell...")
        return restartShell()
    }

    fun clearLogs() {
        terminalEmulator.clear()
        terminalEmulator.info("USER-TERM", "Logs cleared (cwd=${currentWorkspaceDir.name}).")
    }

    fun dumpLogsPlainText(): String = terminalEmulator.dumpPlainText()

    private fun writeLineToShell(command: String): Boolean {
        return runCatching {
            val writer = stdinWriter ?: return@runCatching false
            writer.write(command)
            writer.newLine()
            writer.flush()
            true
        }.getOrDefault(false)
    }

    private fun startStreamReaders(proc: Process) {
        stdoutJob = scope.launch {
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith(PWD_MARKER_PREFIX) -> {
                                val newPwd = line.removePrefix(PWD_MARKER_PREFIX).trim()
                                updateTrackedPwd(newPwd)
                            }
                            line.startsWith(DONE_MARKER_PREFIX) -> {
                                val payload = line.removePrefix(DONE_MARKER_PREFIX).trim()
                                val token = payload.substringBefore(':')
                                val exitCode = payload.substringAfter(':', "-1").toIntOrNull() ?: -1
                                val pending = activeCommandCompletion.get()
                                if (pending != null && pending.token == token) {
                                    pending.deferred.complete(exitCode)
                                }
                            }
                            else -> {
                                val redacted = SecureVault.redactSensitiveText(line)
                                activeCommandCompletion.get()?.capturedOutput?.add(redacted)
                                terminalEmulator.appendPlain("OUT", redacted)
                            }
                        }
                    }
                }
            }.onFailure { err ->
                if (err !is CancellationException) {
                    terminalEmulator.warn("USER-TERM", "Stdout stream ended: ${err.message}")
                }
            }
        }

        stderrJob = scope.launch {
            runCatching {
                BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        terminalEmulator.error("ERR", SecureVault.redactSensitiveText(line))
                    }
                }
            }.onFailure { err ->
                if (err !is CancellationException) {
                    terminalEmulator.warn("USER-TERM", "Stderr stream ended: ${err.message}")
                }
            }
        }
    }

    private fun updateTrackedPwd(rawPwd: String) {
        if (rawPwd.isBlank()) return
        runCatching {
            val pwdDir = File(rawPwd).canonicalFile
            val rootCanonical = currentWorkspaceDir.canonicalFile
            val isInsideWorkspace = pwdDir.path == rootCanonical.path ||
                pwdDir.path.startsWith(rootCanonical.path + File.separator)
            if (pwdDir.exists() && pwdDir.isDirectory && isInsideWorkspace) {
                _status.value = _status.value.copy(workspacePath = pwdDir.absolutePath)
            }
        }
    }

    private fun startExitMonitor(proc: Process, slug: String) {
        monitorJob = scope.launch {
            val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
            if (shellProcess === proc) {
                stdinWriter = null
                shellProcess = null
                _status.value = _status.value.copy(
                    state = RuntimeLifecycleState.STOPPED,
                    pid = null,
                    isRunning = false,
                    workspaceSlug = slug,
                    statusMessage = "Shell exited ($exitCode)",
                )
                terminalEmulator.warn("USER-TERM", "Shell exited with code $exitCode.")
            }
        }
    }

    private fun stopShellInternal(updateStateToStopped: Boolean) {
        activeCommandCompletion.getAndSet(null)?.deferred?.cancel()
        val proc = shellProcess
        shellProcess = null
        runCatching { stdinWriter?.close() }
        stdinWriter = null
        stdoutJob?.cancel()
        stderrJob?.cancel()
        monitorJob?.cancel()
        stdoutJob = null
        stderrJob = null
        monitorJob = null
        if (proc != null) {
            runCatching {
                proc.destroy()
                if (!waitForProcessCompat(proc, 400L)) {
                    destroyProcessForciblyCompat(proc)
                }
            }
        }
        if (updateStateToStopped) {
            _status.value = _status.value.copy(
                state = RuntimeLifecycleState.STOPPED,
                pid = null,
                isRunning = false,
                statusMessage = "Shell stopped",
            )
            terminalEmulator.warn("USER-TERM", "User shell process stopped.")
        }
    }

    private fun loadPersistedHistory(): List<String> {
        return runCatching {
            if (!historyFile.exists()) return emptyList()
            historyFile.readLines(StandardCharsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .takeLast(MAX_HISTORY_ITEMS)
        }.getOrDefault(emptyList())
    }

    private fun recordHistory(command: String) {
        val next = (_commandHistory.value + command)
            .filter { it.isNotBlank() }
            .takeLast(MAX_HISTORY_ITEMS)
        _commandHistory.value = next
        scope.launch {
            persistHistoryNow(next)
        }
    }

    private fun persistHistoryNow(entries: List<String>) {
        runCatching {
            historyFile.parentFile?.mkdirs()
            historyFile.writeText(entries.joinToString("\n"), StandardCharsets.UTF_8)
        }
    }

    private fun isProcessAliveCompat(proc: Process): Boolean {
        return runCatching {
            proc.exitValue()
            false
        }.getOrDefault(true)
    }

    private fun extractPidCompat(proc: Process): Long? {
        return runCatching {
            val method = Process::class.java.getMethod("pid")
            (method.invoke(proc) as? Number)?.toLong()
        }.getOrNull()
    }

    private fun shellQuote(path: String): String {
        return "'" + path.replace("'", "'\\''") + "'"
    }

    fun destroy() {
        stopShellInternal(updateStateToStopped = true)
    }
}
