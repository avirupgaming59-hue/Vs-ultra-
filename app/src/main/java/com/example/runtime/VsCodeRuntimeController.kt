package com.example.runtime

import com.example.security.SecureVault
import com.example.terminal.TerminalEmulator
import com.example.terminal.TerminalLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale

private const val MAX_AUTO_RESTARTS = 3
private const val STARTUP_HEALTH_ATTEMPTS = 12
private const val STARTUP_HEALTH_POLL_MS = 350L

data class CodeServerHttpVerification(
    val isGenuineCodeServer: Boolean,
    val statusCode: Int,
    val detail: String,
)

/**
 * Independent controller dedicated solely to launching and verifying a genuine VS Code
 * (`code-server`) HTTP runtime on `127.0.0.1:<port>`.
 *
 * Truthfulness rules:
 * - Never masks a missing binary by launching `buildIdeHtml()` and claiming VS Code is running.
 * - Never classifies `X-VSShell-Server: vsshell-local-editor` (the emergency fallback file viewer)
 *   as a genuine `code-server` IDE.
 * - Performs explicit binary dependency checks (`libnode.so` + `libcodeserver.so` in `nativeLibraryDir`,
 *   without violating Android W^X or Play Store DCL rules), port allocation & collision detection,
 *   process health monitoring, HTTP `/healthz` + IDE workbench HTML checks, and bounded recovery.
 */
class VsCodeRuntimeController(
    private val appFilesDir: File,
    private val nativeLibDirPath: String?,
) {
    val terminalEmulator = TerminalEmulator(maxLines = 400)
    val logs: StateFlow<List<TerminalLine>> = terminalEmulator.lines

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        terminalEmulator.error("VSCODE-RT", "Runtime supervisor error: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val lifecycleMutex = Mutex()

    @Volatile
    private var managedProcess: Process? = null

    @Volatile
    private var stdoutJob: Job? = null

    @Volatile
    private var stderrJob: Job? = null

    @Volatile
    private var supervisorJob: Job? = null

    @Volatile
    private var manualStopRequested: Boolean = false

    @Volatile
    private var activeWorkspaceDir: File = File(appFilesDir, "workspaces/default")

    @Volatile
    private var activeSlug: String = "default"

    @Volatile
    private var activePort: Int = 8080

    @Volatile
    private var restartCounter: Int = 0

    private val authPassword: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val passwordFile = File(appFilesDir, "vscode-auth")
        val stored = if (passwordFile.isFile) {
            SecureVault.decryptSecret(passwordFile.readText(StandardCharsets.UTF_8))
        } else {
            ""
        }
        stored.takeIf { it.isNotBlank() } ?: SecureVault.generateRandomHexToken().also { password ->
            val encrypted = SecureVault.encryptSecret(password)
            check(SecureVault.isEncryptedPayload(encrypted)) {
                "Android Keystore is unavailable; refusing to store a code-server password in plaintext"
            }
            passwordFile.writeText(encrypted, StandardCharsets.UTF_8)
        }
    }

    private val _status = MutableStateFlow(
        RuntimeInstanceStatus(
            runtimeId = RuntimeId.VSCODE_SERVER,
            state = RuntimeLifecycleState.NOT_STARTED,
            backendKind = VsCodeIdeBackendKind.NONE,
            statusMessage = "VS Code runtime idle",
            isRunning = false,
        ),
    )
    val status: StateFlow<RuntimeInstanceStatus> = _status.asStateFlow()

    private val _binaryInventory = MutableStateFlow(
        inspectDeviceBinaries(appFilesDir = appFilesDir, nativeLibDir = nativeLibDirPath),
    )
    val binaryInventory: StateFlow<RuntimeBinaryInventory> = _binaryInventory.asStateFlow()

    init {
        terminalEmulator.info(
            "VSCODE-RT",
            "VS Code Runtime Controller ready (bundled code-server runnable=${_binaryInventory.value.isCodeServerRunnableInApp}).",
        )
    }

    fun refreshBinaryInventory(): RuntimeBinaryInventory {
        val inv = inspectDeviceBinaries(appFilesDir = appFilesDir, nativeLibDir = nativeLibDirPath)
        _binaryInventory.value = inv
        return inv
    }

    fun authPasswordForLogin(): String = authPassword

    suspend fun startRuntime(
        workspaceDir: File,
        workspaceSlug: String,
        requestedPort: Int = 8080,
        allowExternalLoopbackAttach: Boolean = true,
        allowPortFallback: Boolean = false,
    ): RuntimeInstanceStatus = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            manualStopRequested = false

            // Reuse existing healthy managed process when serving the same workspace & compatible port
            val existingProc = managedProcess
            if (existingProc != null) {
                if (isProcessAliveCompat(existingProc) &&
                    activeSlug == workspaceSlug &&
                    (activePort == requestedPort || allowPortFallback) &&
                    verifyCodeServerHttpEndpoint(activePort).isGenuineCodeServer
                ) {
                    val reusedUrl = "http://127.0.0.1:$activePort"
                    val reused = RuntimeInstanceStatus(
                        runtimeId = RuntimeId.VSCODE_SERVER,
                        state = RuntimeLifecycleState.READY,
                        backendKind = VsCodeIdeBackendKind.BUNDLED_CODE_SERVER,
                        port = activePort,
                        url = reusedUrl,
                        workspaceSlug = workspaceSlug,
                        workspacePath = activeWorkspaceDir.absolutePath,
                        pid = extractPidCompat(existingProc),
                        restartCount = restartCounter,
                        statusMessage = "Reused healthy code-server IDE on $reusedUrl",
                        isRunning = true,
                    )
                    _status.value = reused
                    terminalEmulator.info("VSCODE-RT", reused.statusMessage)
                    return@withLock reused
                } else if (!isProcessAliveCompat(existingProc)) {
                    stopProcessInternal(updateStateToStopped = false)
                }
            }

            activeWorkspaceDir = workspaceDir
            activeSlug = workspaceSlug

            _status.value = RuntimeInstanceStatus(
                runtimeId = RuntimeId.VSCODE_SERVER,
                state = RuntimeLifecycleState.INITIALIZING,
                workspaceSlug = workspaceSlug,
                workspacePath = workspaceDir.absolutePath,
                port = requestedPort,
                statusMessage = "Initializing VS Code runtime...",
            )
            terminalEmulator.info("VSCODE-RT", "Initializing VS Code runtime for '$workspaceSlug' on 127.0.0.1:$requestedPort")

            // Step 1: Verify whether an external genuine code-server is already live on target port
            if (allowExternalLoopbackAttach) {
                _status.value = _status.value.copy(
                    state = RuntimeLifecycleState.HEALTH_CHECKING,
                    statusMessage = "Checking 127.0.0.1:$requestedPort for live code-server...",
                )
                val probe = verifyCodeServerHttpEndpoint(requestedPort)
                if (probe.isGenuineCodeServer) {
                    val url = "http://127.0.0.1:$requestedPort"
                    activePort = requestedPort
                    val ready = RuntimeInstanceStatus(
                        runtimeId = RuntimeId.VSCODE_SERVER,
                        state = RuntimeLifecycleState.READY,
                        backendKind = VsCodeIdeBackendKind.EXTERNAL_LOOPBACK_CODE_SERVER,
                        port = requestedPort,
                        url = url,
                        workspaceSlug = workspaceSlug,
                        workspacePath = workspaceDir.absolutePath,
                        restartCount = restartCounter,
                        statusMessage = "Connected to verified code-server on $url",
                        isRunning = true,
                    )
                    _status.value = ready
                    terminalEmulator.success("VSCODE-RT", "Verified genuine code-server IDE on $url (${probe.detail})")
                    return@withLock ready
                }
            }

            // Step 2: Check bundled native code-server dependencies honestly
            _status.value = _status.value.copy(
                state = RuntimeLifecycleState.CHECKING_DEPENDENCIES,
                statusMessage = "Inspecting bundled native Node.js & code-server binaries...",
            )
            val inventory = refreshBinaryInventory()
            if (!inventory.isCodeServerRunnableInApp) {
                val missingList = inventory.missingIdeBinaries.joinToString(", ").ifBlank { "libnode.so, libcodeserver.so" }
                val honestError = "Built-in VS Code (code-server) native binaries are not bundled in this APK (missing: $missingList in ${inventory.nativeLibDirPath.ifBlank { "nativeLibraryDir" }}). " +
                    "Android 11+ (SELinux W^X) blocks running downloaded executables from writable storage, and Play Policy prohibits dynamic native binary downloads. " +
                    "Use an external Termux/Linux code-server, a remote Cloud IDE / Codespace, or explicitly choose the Fallback File Viewer."
                val failed = RuntimeInstanceStatus(
                    runtimeId = RuntimeId.VSCODE_SERVER,
                    state = RuntimeLifecycleState.FAILED,
                    backendKind = VsCodeIdeBackendKind.MISSING_BINARY,
                    port = requestedPort,
                    workspaceSlug = workspaceSlug,
                    workspacePath = workspaceDir.absolutePath,
                    statusMessage = "Missing bundled code-server runtime ($missingList)",
                    errorMessage = honestError,
                    isRunning = false,
                )
                _status.value = failed
                terminalEmulator.error("VSCODE-RT", honestError)
                return@withLock failed
            }

            // Step 3: Allocate verified free port & avoid collisions
            val resolvedPort = allocatePort(requestedPort, allowPortFallback)
            if (resolvedPort == null) {
                val err = "Port 127.0.0.1:$requestedPort is already occupied and port fallback is disabled."
                val conflict = RuntimeInstanceStatus(
                    runtimeId = RuntimeId.VSCODE_SERVER,
                    state = RuntimeLifecycleState.FAILED,
                    backendKind = VsCodeIdeBackendKind.PORT_CONFLICT,
                    port = requestedPort,
                    workspaceSlug = workspaceSlug,
                    workspacePath = workspaceDir.absolutePath,
                    statusMessage = "Port $requestedPort unavailable",
                    errorMessage = err,
                    isRunning = false,
                )
                _status.value = conflict
                terminalEmulator.error("VSCODE-RT", err)
                return@withLock conflict
            }
            activePort = resolvedPort

            // Step 4: Spawn native bundled code-server process
            stopProcessInternal(updateStateToStopped = false)
            launchNativeCodeServerLocked(
                inventory = inventory,
                workspaceDir = workspaceDir,
                workspaceSlug = workspaceSlug,
                port = resolvedPort,
            )
        }
    }

    private suspend fun launchNativeCodeServerLocked(
        inventory: RuntimeBinaryInventory,
        workspaceDir: File,
        workspaceSlug: String,
        port: Int,
    ): RuntimeInstanceStatus {
        _status.value = _status.value.copy(
            state = RuntimeLifecycleState.STARTING,
            port = port,
            statusMessage = "Launching native code-server on 127.0.0.1:$port...",
            errorMessage = null,
        )

        val dir = File(inventory.nativeLibDirPath)
        val nodeBin = File(dir, "libnode.so")
        val codeServerEntry = File(dir, "libcodeserver.so")

        val cmd = if (codeServerEntry.canExecute()) {
            listOf(
                codeServerEntry.absolutePath,
                "--bind-addr",
                "127.0.0.1:$port",
                "--auth",
                "password",
                "--disable-telemetry",
                workspaceDir.absolutePath,
            )
        } else {
            listOf(
                nodeBin.absolutePath,
                codeServerEntry.absolutePath,
                "--bind-addr",
                "127.0.0.1:$port",
                "--auth",
                "password",
                "--disable-telemetry",
                workspaceDir.absolutePath,
            )
        }

        val proc = try {
            val pb = ProcessBuilder(cmd)
                .directory(workspaceDir)
                .redirectErrorStream(false)
            pb.environment().apply {
                put("HOME", workspaceDir.absolutePath)
                put("XDG_CONFIG_HOME", File(appFilesDir, "vscode-config").apply { mkdirs() }.absolutePath)
                put("XDG_DATA_HOME", File(appFilesDir, "vscode-data").apply { mkdirs() }.absolutePath)
                put("NODE_ENV", "production")
                put("PASSWORD", authPassword)
                put("CS_DISABLE_UPDATE_CHECK", "1")
            }
            pb.start()
        } catch (t: Throwable) {
            val msg = "Failed starting native code-server binary: ${t.localizedMessage ?: t.javaClass.simpleName}"
            val failed = RuntimeInstanceStatus(
                runtimeId = RuntimeId.VSCODE_SERVER,
                state = RuntimeLifecycleState.FAILED,
                backendKind = VsCodeIdeBackendKind.HEALTH_CHECK_FAILED,
                port = port,
                workspaceSlug = workspaceSlug,
                workspacePath = workspaceDir.absolutePath,
                restartCount = restartCounter,
                statusMessage = "Failed spawning process",
                errorMessage = msg,
                isRunning = false,
            )
            _status.value = failed
            terminalEmulator.error("VSCODE-RT", msg)
            return failed
        }

        managedProcess = proc
        val pid = extractPidCompat(proc)
        startStreamReaders(proc)

        _status.value = _status.value.copy(
            state = RuntimeLifecycleState.WAITING_FOR_PORT,
            pid = pid,
            statusMessage = "Waiting for 127.0.0.1:$port to bind...",
        )

        var lastProbe = CodeServerHttpVerification(false, 0, "Not reachable yet")
        for (attempt in 1..STARTUP_HEALTH_ATTEMPTS) {
            if (!isProcessAliveCompat(proc)) {
                val exitCode = runCatching { proc.exitValue() }.getOrDefault(-1)
                val crashedMsg = "code-server process exited prematurely with code $exitCode."
                terminalEmulator.error("VSCODE-RT", crashedMsg)
                stopProcessInternal(updateStateToStopped = false)
                val failed = RuntimeInstanceStatus(
                    runtimeId = RuntimeId.VSCODE_SERVER,
                    state = RuntimeLifecycleState.FAILED,
                    backendKind = VsCodeIdeBackendKind.HEALTH_CHECK_FAILED,
                    port = port,
                    workspaceSlug = workspaceSlug,
                    workspacePath = workspaceDir.absolutePath,
                    restartCount = restartCounter,
                    statusMessage = "Process exited ($exitCode)",
                    errorMessage = crashedMsg,
                    isRunning = false,
                )
                _status.value = failed
                return failed
            }

            _status.value = _status.value.copy(
                state = RuntimeLifecycleState.VERIFYING_IDE,
                statusMessage = "Verifying IDE readiness on :$port ($attempt/$STARTUP_HEALTH_ATTEMPTS)...",
            )
            lastProbe = verifyCodeServerHttpEndpoint(port)
            if (lastProbe.isGenuineCodeServer) {
                val url = "http://127.0.0.1:$port"
                val ready = RuntimeInstanceStatus(
                    runtimeId = RuntimeId.VSCODE_SERVER,
                    state = RuntimeLifecycleState.READY,
                    backendKind = VsCodeIdeBackendKind.BUNDLED_CODE_SERVER,
                    port = port,
                    url = url,
                    workspaceSlug = workspaceSlug,
                    workspacePath = workspaceDir.absolutePath,
                    pid = pid,
                    restartCount = restartCounter,
                    statusMessage = "code-server IDE ready on $url",
                    isRunning = true,
                )
                _status.value = ready
                terminalEmulator.success("VSCODE-RT", "Verified bundled code-server IDE ready on $url")
                startProcessSupervisor(proc, workspaceDir, workspaceSlug, port)
                return ready
            }
            delay(STARTUP_HEALTH_POLL_MS)
        }

        val timeoutErr = "code-server bound pid=${pid ?: "?"} but failed IDE HTTP verification on 127.0.0.1:$port (${lastProbe.detail})"
        terminalEmulator.error("VSCODE-RT", timeoutErr)
        stopProcessInternal(updateStateToStopped = false)
        val failed = RuntimeInstanceStatus(
            runtimeId = RuntimeId.VSCODE_SERVER,
            state = RuntimeLifecycleState.FAILED,
            backendKind = VsCodeIdeBackendKind.HEALTH_CHECK_FAILED,
            port = port,
            workspaceSlug = workspaceSlug,
            workspacePath = workspaceDir.absolutePath,
            restartCount = restartCounter,
            statusMessage = "Health verification timed out",
            errorMessage = timeoutErr,
            isRunning = false,
        )
        _status.value = failed
        return failed
    }

    private fun startProcessSupervisor(
        proc: Process,
        workspaceDir: File,
        workspaceSlug: String,
        port: Int,
    ) {
        supervisorJob?.cancel()
        supervisorJob = scope.launch {
            val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
            if (manualStopRequested || managedProcess !== proc) return@launch

            terminalEmulator.warn("VSCODE-RT", "code-server exited unexpectedly (code=$exitCode).")
            if (restartCounter >= MAX_AUTO_RESTARTS) {
                val circuitErr = "VS Code runtime exceeded $MAX_AUTO_RESTARTS consecutive restarts (last exit=$exitCode). Tap Restart to retry."
                terminalEmulator.error("VSCODE-RT", circuitErr)
                _status.value = _status.value.copy(
                    state = RuntimeLifecycleState.FAILED,
                    backendKind = VsCodeIdeBackendKind.HEALTH_CHECK_FAILED,
                    isRunning = false,
                    pid = null,
                    errorMessage = circuitErr,
                    statusMessage = "Restart limit reached",
                )
                return@launch
            }

            restartCounter += 1
            terminalEmulator.info("VSCODE-RT", "Auto-recovering VS Code runtime (attempt $restartCounter/$MAX_AUTO_RESTARTS)...")
            delay(1_000L * restartCounter)
            lifecycleMutex.withLock {
                if (!manualStopRequested) {
                    val inv = refreshBinaryInventory()
                    if (inv.isCodeServerRunnableInApp) {
                        launchNativeCodeServerLocked(inv, workspaceDir, workspaceSlug, port)
                    }
                }
            }
        }
    }

    suspend fun stopRuntime() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            manualStopRequested = true
            restartCounter = 0
            stopProcessInternal(updateStateToStopped = true)
        }
    }

    suspend fun isHealthy(): Boolean {
        val current = _status.value
        val port = current.port ?: activePort
        if (!current.isRunning || port !in 1..65535) return false
        val proc = managedProcess
        if (proc != null && !isProcessAliveCompat(proc)) return false
        return withContext(Dispatchers.IO) {
            verifyCodeServerHttpEndpoint(port).isGenuineCodeServer
        }
    }

    suspend fun restartRuntime(
        workspaceDir: File = activeWorkspaceDir,
        workspaceSlug: String = activeSlug,
        requestedPort: Int = activePort,
        allowExternalLoopbackAttach: Boolean = true,
    ): RuntimeInstanceStatus {
        stopRuntime()
        return startRuntime(
            workspaceDir = workspaceDir,
            workspaceSlug = workspaceSlug,
            requestedPort = requestedPort,
            allowExternalLoopbackAttach = allowExternalLoopbackAttach,
            allowPortFallback = true,
        )
    }

    fun clearLogs() {
        terminalEmulator.clear()
        terminalEmulator.info("VSCODE-RT", "VS Code runtime logs cleared.")
    }

    private fun startStreamReaders(proc: Process) {
        stdoutJob = scope.launch {
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        terminalEmulator.appendPlain("IDE-OUT", SecureVault.redactSensitiveText(line))
                    }
                }
            }
        }
        stderrJob = scope.launch {
            runCatching {
                BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        terminalEmulator.error("IDE-ERR", SecureVault.redactSensitiveText(line))
                    }
                }
            }
        }
    }

    private fun stopProcessInternal(updateStateToStopped: Boolean) {
        val proc = managedProcess
        managedProcess = null
        supervisorJob?.cancel()
        stdoutJob?.cancel()
        stderrJob?.cancel()
        supervisorJob = null
        stdoutJob = null
        stderrJob = null
        if (proc != null) {
            runCatching {
                proc.destroy()
                if (!waitForProcessCompat(proc, 500L)) {
                    destroyProcessForciblyCompat(proc)
                }
            }
        }
        if (updateStateToStopped) {
            _status.value = _status.value.copy(
                state = RuntimeLifecycleState.STOPPED,
                backendKind = VsCodeIdeBackendKind.NONE,
                pid = null,
                isRunning = false,
                statusMessage = "VS Code runtime stopped",
                errorMessage = null,
            )
            terminalEmulator.warn("VSCODE-RT", "VS Code runtime stopped.")
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

    fun destroy() {
        manualStopRequested = true
        stopProcessInternal(updateStateToStopped = true)
    }

    companion object {
        fun isPortAvailable(port: Int): Boolean {
            if (port !in 1024..65535) return false
            return runCatching {
                ServerSocket().use { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                    true
                }
            }.getOrDefault(false)
        }

        fun allocatePort(preferredPort: Int, allowFallback: Boolean): Int? {
            val normalized = preferredPort.coerceIn(1024, 65535)
            if (isPortAvailable(normalized)) return normalized
            if (!allowFallback) return null
            return runCatching {
                ServerSocket().use { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                    ss.localPort
                }
            }.getOrNull()
        }

        /**
         * Verifies that 127.0.0.1:[port] serves a genuine VS Code / code-server IDE
         * (NOT VS Shell's fallback local file viewer header).
         */
        fun verifyCodeServerHttpEndpoint(port: Int): CodeServerHttpVerification {
            if (port !in 1..65535) {
                return CodeServerHttpVerification(false, 0, "Invalid port $port")
            }
            return try {
                Socket().use { socket ->
                    socket.soTimeout = 1_200
                    socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 600)
                    val out = socket.getOutputStream()
                    out.write(
                        "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n"
                            .toByteArray(StandardCharsets.UTF_8),
                    )
                    out.flush()
                    val (headers, body) = readBoundedHttpResponse(socket.getInputStream())
                    val firstLine = headers.lineSequence().firstOrNull().orEmpty()
                    val statusCode = firstLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0

                    if (headers.contains("X-VSShell-Server: vsshell-local-editor", ignoreCase = true)) {
                        return CodeServerHttpVerification(
                            isGenuineCodeServer = false,
                            statusCode = statusCode,
                            detail = "Port $port is serving the Fallback Local File Viewer, not code-server.",
                        )
                    }
                    if (!firstLine.startsWith("HTTP/1.", ignoreCase = true) || statusCode !in 200..399) {
                        return CodeServerHttpVerification(
                            isGenuineCodeServer = false,
                            statusCode = statusCode,
                            detail = "Unexpected HTTP status ($statusCode)",
                        )
                    }

                    val bodyLower = body.lowercase(Locale.US)
                    val headersLower = headers.lowercase(Locale.US)
                    val looksLikeCodeServer = headersLower.contains("code-server") ||
                        bodyLower.contains("code-server") ||
                        bodyLower.contains("monaco-workbench") ||
                        bodyLower.contains("vscode-workbench") ||
                        bodyLower.contains("\"status\":\"alive\"") ||
                        headersLower.contains("location: ./login") ||
                        headersLower.contains("location: /login")

                    if (looksLikeCodeServer) {
                        CodeServerHttpVerification(
                            isGenuineCodeServer = true,
                            statusCode = statusCode,
                            detail = "Verified code-server HTTP $statusCode",
                        )
                    } else {
                        CodeServerHttpVerification(
                            isGenuineCodeServer = false,
                            statusCode = statusCode,
                            detail = "HTTP $statusCode responded without code-server IDE markers",
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                CodeServerHttpVerification(
                    isGenuineCodeServer = false,
                    statusCode = 0,
                    detail = t.localizedMessage ?: t.javaClass.simpleName,
                )
            }
        }

        internal fun readBoundedHttpResponse(
            input: java.io.InputStream,
            maxBodyBytes: Int = 65_536,
        ): Pair<String, String> {
            val headerStream = java.io.ByteArrayOutputStream()
            val bodyStream = java.io.ByteArrayOutputStream()
            val buf = ByteArray(1024)
            var headerText = ""
            var headerEnded = false

            while (!headerEnded && headerStream.size() < 32_768) {
                val b = input.read()
                if (b == -1) break
                headerStream.write(b)
                val arr = headerStream.toByteArray()
                val n = arr.size
                if (n >= 4 &&
                    arr[n - 4] == '\r'.code.toByte() &&
                    arr[n - 3] == '\n'.code.toByte() &&
                    arr[n - 2] == '\r'.code.toByte() &&
                    arr[n - 1] == '\n'.code.toByte()
                ) {
                    headerText = String(arr, 0, n - 4, StandardCharsets.UTF_8)
                    headerEnded = true
                }
            }
            if (!headerEnded) {
                return String(headerStream.toByteArray(), StandardCharsets.UTF_8) to ""
            }

            val contentLength = headerText.lineSequence()
                .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()

            val targetRead = when {
                contentLength != null -> contentLength.coerceIn(0, maxBodyBytes)
                else -> maxBodyBytes
            }
            if (targetRead > 0) {
                runCatching {
                    while (bodyStream.size() < targetRead) {
                        val toRead = minOf(buf.size, targetRead - bodyStream.size())
                        val read = input.read(buf, 0, toRead)
                        if (read <= 0) break
                        bodyStream.write(buf, 0, read)
                    }
                }
            }
            return headerText to String(bodyStream.toByteArray(), StandardCharsets.UTF_8)
        }

        fun inspectDeviceBinaries(
            appFilesDir: File,
            nativeLibDir: String?,
        ): RuntimeBinaryInventory {
            val libDir = nativeLibDir?.takeIf { it.isNotBlank() }?.let { File(it) }
            val hasNode = libDir?.let { File(it, "libnode.so").canExecute() } == true
            val hasCodeServer = libDir?.let {
                val f = File(it, "libcodeserver.so")
                f.exists() && f.length() > 0L
            } == true
            val hasProot = libDir?.let { File(it, "libproot.so").canExecute() } == true

            val shellPath = listOf("/system/bin/sh", "/bin/sh").firstOrNull {
                runCatching { File(it).canExecute() }.getOrDefault(false)
            }.orEmpty()

            val candidateTools = listOf(
                "sh", "toybox", "ls", "cat", "grep", "find", "chmod", "mkdir",
                "curl", "git", "node", "npm", "python", "python3", "pip",
                "sqlite3", "tar", "unzip", "gzip", "sed", "awk", "vi", "nano",
            )
            val envPathDirs = System.getenv("PATH")
                ?.split(':')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            val searchDirs = (
                listOf(
                    appFilesDir.resolve("usr/bin").absolutePath,
                    "/product/bin",
                    "/system/bin",
                    "/system/xbin",
                    "/bin",
                    "/usr/bin",
                ) + envPathDirs
                ).distinct()
            val availableTools = candidateTools.filter { tool ->
                searchDirs.any { dir ->
                    runCatching { File(dir, tool).canExecute() }.getOrDefault(false)
                }
            }

            val missing = mutableListOf<String>()
            if (!hasNode) missing.add("libnode.so")
            if (!hasCodeServer) missing.add("libcodeserver.so")

            return RuntimeBinaryInventory(
                nativeLibDirPath = libDir?.absolutePath.orEmpty(),
                hasNodeBinary = hasNode,
                hasCodeServerBinary = hasCodeServer,
                hasProotBinary = hasProot,
                systemShellPath = shellPath,
                hasSystemShell = shellPath.isNotBlank(),
                availableSystemTools = availableTools,
                missingIdeBinaries = missing,
            )
        }
    }
}
