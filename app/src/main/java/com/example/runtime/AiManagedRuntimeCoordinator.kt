package com.example.runtime

import com.example.terminal.TerminalEmulator
import com.example.terminal.TerminalLine
import com.example.util.SimpleJson
import com.example.workspace.LocalhostServerEngine
import com.example.workspace.LocalhostServerStatus
import com.example.workspace.PortProbeKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Single dedicated AI-managed internal runtime coordinator for VS Shell's AI workspace
 * and localhost / code-server lifecycle.
 *
 * Replaces competing dual-IDE orchestration with ONE managed backend that:
 * 1. Inspects runtime health & cleans up stale metadata on startup or workspace open.
 * 2. Reuses healthy compatible server instances instead of spawning duplicate processes.
 * 3. Resolves port conflicts, captures process/setup logs, validates AI setup commands,
 *    preserves existing workspace files, and verifies real HTTP readiness before reporting READY.
 */
class AiManagedRuntimeCoordinator(
    private val appFilesDir: File,
    val sharedFilesystem: SharedWorkspaceFilesystem,
    val vsCodeRuntime: VsCodeRuntimeController,
    val localhostEngine: LocalhostServerEngine,
    private val shellExecutableOverride: String? = null,
) {
    val terminalEmulator: TerminalEmulator = vsCodeRuntime.terminalEmulator
    val logs: StateFlow<List<TerminalLine>> = vsCodeRuntime.logs

    private val lifecycleMutex = Mutex()
    private val metadataDir = File(appFilesDir, "ai_runtime")
    private val metadataFile = File(metadataDir, "runtime_metadata.json")

    private val _snapshot = MutableStateFlow(AiManagedRuntimeSnapshot())
    val snapshot: StateFlow<AiManagedRuntimeSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var lastLoadedMetadata: RuntimeMetadataRecord? = readMetadataSafely()

    suspend fun inspectAndCleanupStaleMetadata(): RuntimeMetadataRecord? = withContext(Dispatchers.IO) {
        val currentMeta = readMetadataSafely() ?: return@withContext null
        lastLoadedMetadata = currentMeta
        if (currentMeta.port !in 1024..65535) {
            clearMetadataFile()
            return@withContext null
        }

        val embeddedAlive = localhostEngine.isEmbeddedServerHealthy(currentMeta.port)
        val vsCodeAlive = vsCodeRuntime.isHealthy() && vsCodeRuntime.status.value.port == currentMeta.port
        if (embeddedAlive || vsCodeAlive) {
            return@withContext currentMeta
        }

        val probe = localhostEngine.probeHttpIdentity(currentMeta.port)
        val codeServerProbe = VsCodeRuntimeController.verifyCodeServerHttpEndpoint(currentMeta.port)
        if (codeServerProbe.isGenuineCodeServer || probe.kind == PortProbeKind.EMBEDDED_VSSHELL_HEALTHY) {
            return@withContext currentMeta
        }

        // Metadata points to a dead or non-HTTP socket: clean up stale PID/port markers
        terminalEmulator.warn(
            "AI-RUNTIME",
            "Cleaned up stale AI runtime metadata (workspace='${currentMeta.workspaceSlug}', port=${currentMeta.port}).",
        )
        val cleaned = currentMeta.copy(
            pid = null,
            url = "",
            backendMode = AiManagedBackendMode.NONE,
        )
        writeMetadataSafely(cleaned)
        lastLoadedMetadata = cleaned
        cleaned
    }

    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        if (vsCodeRuntime.isHealthy()) return@withContext true
        val snap = _snapshot.value
        val port = snap.port ?: return@withContext false
        if (port !in 1024..65535) return@withContext false
        when (snap.backendMode) {
            AiManagedBackendMode.BUNDLED_CODE_SERVER,
            AiManagedBackendMode.EXTERNAL_CODE_SERVER,
            -> VsCodeRuntimeController.verifyCodeServerHttpEndpoint(port).isGenuineCodeServer
            AiManagedBackendMode.FALLBACK_LOCAL_VIEWER ->
                localhostEngine.isEmbeddedServerHealthy(port) && localhostEngine.probeLocalhostHttp(port)
            else -> false
        }
    }

    /**
     * Checks runtime health on app launch and either reuses a healthy compatible server
     * or initializes/starts the single AI-managed runtime automatically when allowed.
     */
    suspend fun ensureRuntimeForAppEntry(
        preferredPort: Int = 8080,
        preferredSlug: String = "default",
        workspaceName: String = "AI Local Workspace",
        allowAutoStart: Boolean = true,
        requireGenuineCodeServer: Boolean = false,
    ): AiManagedRuntimeSnapshot = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            val cleanedMeta = inspectAndCleanupStaleMetadata()
            val targetSlug = sharedFilesystem.sanitizeSlug(
                preferredSlug.ifBlank { cleanedMeta?.workspaceSlug.orEmpty().ifBlank { "default" } },
            )
            val targetPort = (cleanedMeta?.port?.takeIf { it in 1024..65535 } ?: preferredPort).coerceIn(1024, 65535)

            // 1. Check whether genuine code-server is already healthy (bundled or external) for this workspace
            val currentVsSlug = vsCodeRuntime.status.value.workspaceSlug.ifBlank { targetSlug }
            if (vsCodeRuntime.isHealthy() && (preferredSlug.isBlank() || currentVsSlug == targetSlug)) {
                val rt = vsCodeRuntime.status.value
                val rtMode = if (rt.backendKind == VsCodeIdeBackendKind.BUNDLED_CODE_SERVER) {
                    AiManagedBackendMode.BUNDLED_CODE_SERVER
                } else {
                    AiManagedBackendMode.EXTERNAL_CODE_SERVER
                }
                val reused = AiManagedRuntimeSnapshot(
                    state = RuntimeLifecycleState.READY,
                    backendMode = rtMode,
                    workspaceSlug = rt.workspaceSlug.ifBlank { targetSlug },
                    workspaceDirPath = rt.workspacePath,
                    port = rt.port,
                    url = rt.url,
                    pid = rt.pid,
                    isRunning = true,
                    reusedExistingInstance = true,
                    isVerifiedHealthy = true,
                    statusMessage = "Reused healthy ${rtMode.label} on ${rt.url}",
                )
                _snapshot.value = reused
                terminalEmulator.info("AI-RUNTIME", reused.statusMessage)
                return@withLock reused
            }

            // 2. Check whether embedded local workspace server is already healthy for this workspace
            if (!requireGenuineCodeServer &&
                localhostEngine.isEmbeddedServerRunning() &&
                (preferredSlug.isBlank() || sharedFilesystem.activeWorkspaceSlug.value == targetSlug)
            ) {
                val snapPort = _snapshot.value.port ?: cleanedMeta?.port ?: targetPort
                if (localhostEngine.probeLocalhostHttp(snapPort)) {
                    val activeDir = sharedFilesystem.getActiveWorkspaceDir()
                    val reused = AiManagedRuntimeSnapshot(
                        state = RuntimeLifecycleState.READY,
                        backendMode = AiManagedBackendMode.FALLBACK_LOCAL_VIEWER,
                        workspaceSlug = sharedFilesystem.activeWorkspaceSlug.value,
                        workspaceDirPath = activeDir.absolutePath,
                        port = snapPort,
                        url = "http://127.0.0.1:$snapPort",
                        isRunning = true,
                        reusedExistingInstance = true,
                        isVerifiedHealthy = true,
                        statusMessage = "Reused healthy Managed Local Server on 127.0.0.1:$snapPort",
                    )
                    _snapshot.value = reused
                    terminalEmulator.info("AI-RUNTIME", reused.statusMessage)
                    return@withLock reused
                }
            }

            // 3. Probe target port for an external loopback code-server already alive
            val codeProbe = VsCodeRuntimeController.verifyCodeServerHttpEndpoint(targetPort)
            if (codeProbe.isGenuineCodeServer) {
                val wsDir = sharedFilesystem.setActiveWorkspace(targetSlug, "ai-runtime-attach")
                val attached = vsCodeRuntime.startRuntime(
                    workspaceDir = wsDir,
                    workspaceSlug = targetSlug,
                    requestedPort = targetPort,
                    allowExternalLoopbackAttach = true,
                    allowPortFallback = false,
                )
                if (attached.isRunning) {
                    return@withLock commitReadySnapshotLocked(
                        mode = AiManagedBackendMode.EXTERNAL_CODE_SERVER,
                        workspaceName = workspaceName,
                        slug = targetSlug,
                        workspaceDir = wsDir,
                        port = attached.port ?: targetPort,
                        url = attached.url,
                        pid = attached.pid,
                        reusedExisting = true,
                        statusMessage = "Attached to verified code-server on ${attached.url}",
                    )
                }
            }

            if (!allowAutoStart) {
                val idle = AiManagedRuntimeSnapshot(
                    state = RuntimeLifecycleState.STOPPED,
                    backendMode = AiManagedBackendMode.NONE,
                    workspaceSlug = targetSlug,
                    workspaceDirPath = sharedFilesystem.resolveWorkspaceDir(targetSlug).absolutePath,
                    port = targetPort,
                    statusMessage = "AI runtime idle (auto-start disabled)",
                )
                _snapshot.value = idle
                return@withLock idle
            }

            // 4. Auto-start inside single AI-managed runtime
            prepareAndStartWorkspaceLocked(
                workspaceName = workspaceName,
                workspaceSlug = targetSlug,
                requestedPort = targetPort,
                starterFiles = emptyMap(),
                setupCommand = "",
                termuxCommand = LocalhostServerStatus.DEFAULT_TERMUX_CODE_SERVER_CMD,
                overwriteStarterFiles = false,
                preferGenuineCodeServer = requireGenuineCodeServer,
                allowFallbackViewer = !requireGenuineCodeServer,
            )
        }
    }

    /**
     * Prepares workspace files safely, validates optional setup commands via [CommandSafetyValidator],
     * reuses a compatible healthy server or resolves port conflicts, verifies HTTP readiness,
     * and persists runtime metadata before reporting READY.
     */
    suspend fun prepareAndStartWorkspace(
        workspaceName: String,
        workspaceSlug: String,
        requestedPort: Int = 8080,
        starterFiles: Map<String, String> = emptyMap(),
        setupCommand: String = "",
        termuxCommand: String = LocalhostServerStatus.DEFAULT_TERMUX_CODE_SERVER_CMD,
        overwriteStarterFiles: Boolean = false,
        preferGenuineCodeServer: Boolean = false,
        allowFallbackViewer: Boolean = true,
    ): AiManagedRuntimeSnapshot = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            prepareAndStartWorkspaceLocked(
                workspaceName = workspaceName,
                workspaceSlug = workspaceSlug,
                requestedPort = requestedPort,
                starterFiles = starterFiles,
                setupCommand = setupCommand,
                termuxCommand = termuxCommand,
                overwriteStarterFiles = overwriteStarterFiles,
                preferGenuineCodeServer = preferGenuineCodeServer,
                allowFallbackViewer = allowFallbackViewer,
            )
        }
    }

    private suspend fun prepareAndStartWorkspaceLocked(
        workspaceName: String,
        workspaceSlug: String,
        requestedPort: Int,
        starterFiles: Map<String, String>,
        setupCommand: String,
        termuxCommand: String,
        overwriteStarterFiles: Boolean,
        preferGenuineCodeServer: Boolean,
        allowFallbackViewer: Boolean,
    ): AiManagedRuntimeSnapshot {
        val targetPort = requestedPort.coerceIn(1024, 65535)
        val cleanSlug = sharedFilesystem.sanitizeSlug(workspaceSlug.ifBlank { "default" })
        val wsDir = sharedFilesystem.setActiveWorkspace(cleanSlug, "ai-runtime")

        val previousSnapshot = _snapshot.value
        _snapshot.value = AiManagedRuntimeSnapshot(
            state = RuntimeLifecycleState.INITIALIZING,
            backendMode = previousSnapshot.backendMode,
            workspaceSlug = cleanSlug,
            workspaceDirPath = wsDir.absolutePath,
            port = targetPort,
            statusMessage = "Preparing AI-managed workspace '$cleanSlug'...",
        )

        if (starterFiles.isNotEmpty()) {
            try {
                val written = sharedFilesystem.writeFiles(
                    workspaceDir = wsDir,
                    files = starterFiles,
                    overwriteExisting = overwriteStarterFiles,
                    source = "ai-runtime",
                )
                terminalEmulator.info("AI-RUNTIME", "Prepared ${starterFiles.size} workspace file(s) ($written written) in ${wsDir.name}.")
            } catch (t: Throwable) {
                val err = "Workspace file preparation failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
                terminalEmulator.error("AI-RUNTIME", err)
                val failed = AiManagedRuntimeSnapshot(
                    state = RuntimeLifecycleState.FAILED,
                    backendMode = AiManagedBackendMode.UNAVAILABLE,
                    workspaceSlug = cleanSlug,
                    workspaceDirPath = wsDir.absolutePath,
                    port = targetPort,
                    statusMessage = "File write failed",
                    errorMessage = err,
                )
                _snapshot.value = failed
                return failed
            }
        }

        if (setupCommand.isNotBlank()) {
            runValidatedSetupCommand(setupCommand, wsDir)
        }

        val inv = vsCodeRuntime.refreshBinaryInventory()
        val externalProbe = VsCodeRuntimeController.verifyCodeServerHttpEndpoint(targetPort)
        val shouldTryCodeServer = preferGenuineCodeServer || inv.isCodeServerRunnableInApp || externalProbe.isGenuineCodeServer

        if (shouldTryCodeServer) {
            // Stop embedded server first if switching modes onto genuine code-server to free port/resources
            if (localhostEngine.isEmbeddedServerRunning()) {
                localhostEngine.stopSuspending()
            }

            // Reuse existing healthy code-server if slug matches or external server is already serving cleanly
            if (vsCodeRuntime.isHealthy() && vsCodeRuntime.status.value.workspaceSlug == cleanSlug) {
                val rt = vsCodeRuntime.status.value
                val mode = if (rt.backendKind == VsCodeIdeBackendKind.BUNDLED_CODE_SERVER) {
                    AiManagedBackendMode.BUNDLED_CODE_SERVER
                } else {
                    AiManagedBackendMode.EXTERNAL_CODE_SERVER
                }
                return commitReadySnapshotLocked(
                    mode = mode,
                    workspaceName = workspaceName,
                    slug = cleanSlug,
                    workspaceDir = wsDir,
                    port = rt.port ?: targetPort,
                    url = rt.url,
                    pid = rt.pid,
                    reusedExisting = true,
                    statusMessage = "Reused active ${mode.label} on ${rt.url}",
                )
            }

            _snapshot.value = _snapshot.value.copy(
                state = RuntimeLifecycleState.STARTING,
                statusMessage = "Starting AI-managed code-server on 127.0.0.1:$targetPort...",
            )
            val rtStatus = vsCodeRuntime.startRuntime(
                workspaceDir = wsDir,
                workspaceSlug = cleanSlug,
                requestedPort = targetPort,
                allowExternalLoopbackAttach = true,
                allowPortFallback = true,
            )
            if (rtStatus.isRunning) {
                val mode = if (rtStatus.backendKind == VsCodeIdeBackendKind.BUNDLED_CODE_SERVER) {
                    AiManagedBackendMode.BUNDLED_CODE_SERVER
                } else {
                    AiManagedBackendMode.EXTERNAL_CODE_SERVER
                }
                return commitReadySnapshotLocked(
                    mode = mode,
                    workspaceName = workspaceName,
                    slug = cleanSlug,
                    workspaceDir = wsDir,
                    port = rtStatus.port ?: targetPort,
                    url = rtStatus.url,
                    pid = rtStatus.pid,
                    reusedExisting = false,
                    statusMessage = rtStatus.statusMessage,
                )
            }

            if (!allowFallbackViewer) {
                val err = rtStatus.errorMessage ?: rtStatus.statusMessage
                val failed = AiManagedRuntimeSnapshot(
                    state = RuntimeLifecycleState.FAILED,
                    backendMode = AiManagedBackendMode.UNAVAILABLE,
                    workspaceSlug = cleanSlug,
                    workspaceDirPath = wsDir.absolutePath,
                    port = targetPort,
                    statusMessage = rtStatus.statusMessage,
                    errorMessage = err,
                )
                _snapshot.value = failed
                return failed
            }
            terminalEmulator.warn(
                "AI-RUNTIME",
                "Bundled/external code-server unavailable (${rtStatus.statusMessage}); switching cleanly to Managed Local Workspace Server.",
            )
        }

        // Reuse healthy embedded local server if already serving this exact workspace on a live port
        val curPort = previousSnapshot.port
        if (previousSnapshot.isRunning &&
            previousSnapshot.backendMode == AiManagedBackendMode.FALLBACK_LOCAL_VIEWER &&
            previousSnapshot.workspaceSlug == cleanSlug &&
            curPort != null &&
            localhostEngine.isEmbeddedServerHealthy(curPort) &&
            localhostEngine.probeLocalhostHttp(curPort)
        ) {
            val reused = previousSnapshot.copy(
                state = RuntimeLifecycleState.READY,
                reusedExistingInstance = true,
                isVerifiedHealthy = true,
                statusMessage = "Reused healthy Managed Local Server on 127.0.0.1:$curPort",
            )
            _snapshot.value = reused
            terminalEmulator.info("AI-RUNTIME", reused.statusMessage)
            return reused
        }

        // Stop any abandoned code-server process before starting the Managed Local Workspace Server
        if (vsCodeRuntime.status.value.isRunning) {
            vsCodeRuntime.stopRuntime()
        }

        _snapshot.value = _snapshot.value.copy(
            state = RuntimeLifecycleState.STARTING,
            backendMode = AiManagedBackendMode.FALLBACK_LOCAL_VIEWER,
            statusMessage = "Starting Managed Local Workspace Server on 127.0.0.1:$targetPort...",
        )

        val localStatus = localhostEngine.ensureLocalhostReady(
            requestedPort = targetPort,
            workspaceName = workspaceName,
            workspaceSlug = cleanSlug,
            termuxCommand = termuxCommand,
            starterFiles = starterFiles,
            allowExternalAttach = false,
            allowPortFallback = true,
            overwriteStarterFiles = overwriteStarterFiles,
        )

        if (!localStatus.isRunning) {
            val err = localStatus.failureDetail.ifBlank { "Unable to start local workspace server on port $targetPort" }
            val failed = AiManagedRuntimeSnapshot(
                state = RuntimeLifecycleState.FAILED,
                backendMode = AiManagedBackendMode.UNAVAILABLE,
                workspaceSlug = cleanSlug,
                workspaceDirPath = wsDir.absolutePath,
                port = targetPort,
                statusMessage = err,
                errorMessage = err,
            )
            _snapshot.value = failed
            return failed
        }

        _snapshot.value = _snapshot.value.copy(
            state = RuntimeLifecycleState.HEALTH_CHECKING,
            port = localStatus.port,
            url = localStatus.url,
            statusMessage = "Verifying HTTP health on ${localStatus.url}...",
        )

        val verified = localhostEngine.probeLocalhostHttp(localStatus.port)
        if (!verified) {
            localhostEngine.stopSuspending()
            val err = "Server bound ${localStatus.url} but failed HTTP /api/info readiness verification."
            terminalEmulator.error("AI-RUNTIME", err)
            val failed = AiManagedRuntimeSnapshot(
                state = RuntimeLifecycleState.FAILED,
                backendMode = AiManagedBackendMode.UNAVAILABLE,
                workspaceSlug = cleanSlug,
                workspaceDirPath = wsDir.absolutePath,
                port = targetPort,
                statusMessage = err,
                errorMessage = err,
            )
            _snapshot.value = failed
            return failed
        }

        return commitReadySnapshotLocked(
            mode = AiManagedBackendMode.FALLBACK_LOCAL_VIEWER,
            workspaceName = workspaceName,
            slug = cleanSlug,
            workspaceDir = wsDir,
            port = localStatus.port,
            url = localStatus.url,
            pid = null,
            reusedExisting = false,
            statusMessage = "Verified Managed Local Workspace Server on ${localStatus.url}",
        )
    }

    suspend fun restartRuntime(
        workspaceName: String = lastLoadedMetadata?.workspaceName.orEmpty().ifBlank { "AI Local Workspace" },
        workspaceSlug: String = _snapshot.value.workspaceSlug.ifBlank { sharedFilesystem.activeWorkspaceSlug.value },
        requestedPort: Int = _snapshot.value.port ?: lastLoadedMetadata?.port ?: 8080,
        preferGenuineCodeServer: Boolean = false,
        allowFallbackViewer: Boolean = true,
    ): AiManagedRuntimeSnapshot = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            terminalEmulator.warn("AI-RUNTIME", "Restarting AI-managed runtime for '$workspaceSlug'...")
            vsCodeRuntime.stopRuntime()
            localhostEngine.stopSuspending()
            prepareAndStartWorkspaceLocked(
                workspaceName = workspaceName,
                workspaceSlug = workspaceSlug,
                requestedPort = requestedPort,
                starterFiles = emptyMap(),
                setupCommand = "",
                termuxCommand = LocalhostServerStatus.DEFAULT_TERMUX_CODE_SERVER_CMD,
                overwriteStarterFiles = false,
                preferGenuineCodeServer = preferGenuineCodeServer,
                allowFallbackViewer = allowFallbackViewer,
            )
        }
    }

    suspend fun stopRuntime(): AiManagedRuntimeSnapshot = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            vsCodeRuntime.stopRuntime()
            localhostEngine.stopSuspending()
            val prev = _snapshot.value
            val stopped = prev.copy(
                state = RuntimeLifecycleState.STOPPED,
                pid = null,
                isRunning = false,
                reusedExistingInstance = false,
                isVerifiedHealthy = false,
                statusMessage = "AI-managed runtime stopped",
                errorMessage = null,
            )
            _snapshot.value = stopped
            lastLoadedMetadata?.let { meta ->
                writeMetadataSafely(
                    meta.copy(
                        pid = null,
                        url = "",
                        backendMode = AiManagedBackendMode.NONE,
                    ),
                )
            }
            stopped
        }
    }

    private fun runValidatedSetupCommand(rawCommand: String, workspaceDir: File) {
        val safety = CommandSafetyValidator.validate(rawCommand)
        when (safety.decision) {
            CommandSafetyDecision.BLOCKED -> {
                terminalEmulator.warn("SECURITY", "Blocked unsafe setup command: ${safety.reason}")
                return
            }
            CommandSafetyDecision.REQUIRES_CONFIRMATION -> {
                terminalEmulator.warn("AI-RUNTIME", "Skipped setup command requiring confirmation: $rawCommand")
                return
            }
            CommandSafetyDecision.ALLOWED -> Unit
        }
        val shell = shellExecutableOverride?.takeIf { File(it).canExecute() }
            ?: listOf("/system/bin/sh", "/bin/sh").firstOrNull { File(it).canExecute() }
            ?: return
        try {
            val pb = ProcessBuilder(shell, "-c", rawCommand)
                .directory(workspaceDir)
                .redirectErrorStream(true)
            val proc = pb.start()
            runCatching { proc.outputStream.close() }
            val captured = java.io.ByteArrayOutputStream()
            val readerThread = Thread(
                {
                    runCatching {
                        val buf = ByteArray(1024)
                        val maxCaptureBytes = 16 * 1024
                        while (true) {
                            val read = proc.inputStream.read(buf)
                            if (read <= 0) break
                            val remaining = maxCaptureBytes - captured.size()
                            if (remaining > 0) {
                                captured.write(buf, 0, minOf(read, remaining))
                            }
                        }
                    }
                },
                "vsshell-setup-drain",
            ).apply {
                isDaemon = true
                start()
            }

            val finished = waitForProcessCompat(proc, 8_000L)
            if (!finished) {
                destroyProcessForciblyCompat(proc)
                runCatching { proc.inputStream.close() }
                runCatching { readerThread.join(300) }
                terminalEmulator.warn("AI-RUNTIME", "Setup command timed out after 8s: $rawCommand")
                return
            }
            runCatching { readerThread.join(500) }
            val output = String(captured.toByteArray(), StandardCharsets.UTF_8)
            val code = proc.exitValue()
            if (code == 0) {
                terminalEmulator.info("AI-RUNTIME", "Setup command succeeded ($rawCommand): ${output.lines().firstOrNull().orEmpty()}")
            } else {
                terminalEmulator.warn("AI-RUNTIME", "Setup command exited $code ($rawCommand): ${output.take(180)}")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            terminalEmulator.warn("AI-RUNTIME", "Setup command warning: ${t.localizedMessage ?: t.javaClass.simpleName}")
        }
    }

    private fun commitReadySnapshotLocked(
        mode: AiManagedBackendMode,
        workspaceName: String,
        slug: String,
        workspaceDir: File,
        port: Int,
        url: String,
        pid: Long?,
        reusedExisting: Boolean,
        statusMessage: String,
    ): AiManagedRuntimeSnapshot {
        val now = System.currentTimeMillis()
        val meta = RuntimeMetadataRecord(
            workspaceSlug = slug,
            workspaceName = workspaceName,
            workspaceDirPath = workspaceDir.absolutePath,
            port = port,
            url = url,
            backendMode = mode,
            pid = pid,
            lastVerifiedAtMs = now,
        )
        writeMetadataSafely(meta)
        lastLoadedMetadata = meta
        val ready = AiManagedRuntimeSnapshot(
            state = RuntimeLifecycleState.READY,
            backendMode = mode,
            workspaceSlug = slug,
            workspaceDirPath = workspaceDir.absolutePath,
            port = port,
            url = url,
            pid = pid,
            isRunning = true,
            reusedExistingInstance = reusedExisting,
            isVerifiedHealthy = true,
            statusMessage = statusMessage,
            errorMessage = null,
        )
        _snapshot.value = ready
        terminalEmulator.success("AI-RUNTIME", statusMessage)
        return ready
    }

    private fun readMetadataSafely(): RuntimeMetadataRecord? {
        if (!metadataFile.exists()) return null
        return runCatching {
            val map = SimpleJson.parseObject(metadataFile.readText(StandardCharsets.UTF_8))
            val rawMode = SimpleJson.stringValue(map, "backendMode")
            val mode = runCatching { AiManagedBackendMode.valueOf(rawMode) }
                .getOrDefault(AiManagedBackendMode.NONE)
            val portNum = (map["port"] as? Number)?.toInt() ?: 8080
            val pidNum = (map["pid"] as? Number)?.toLong()
            val verifiedMs = (map["lastVerifiedAtMs"] as? Number)?.toLong() ?: 0L
            RuntimeMetadataRecord(
                workspaceSlug = SimpleJson.stringValue(map, "workspaceSlug"),
                workspaceName = SimpleJson.stringValue(map, "workspaceName"),
                workspaceDirPath = SimpleJson.stringValue(map, "workspaceDirPath"),
                port = portNum,
                url = SimpleJson.stringValue(map, "url"),
                backendMode = mode,
                pid = pidNum,
                lastVerifiedAtMs = verifiedMs,
            )
        }.getOrNull()
    }

    private fun writeMetadataSafely(record: RuntimeMetadataRecord) {
        runCatching {
            metadataDir.mkdirs()
            val json = buildString {
                append("{\n")
                append("  \"workspaceSlug\": ").append(jsonQuote(record.workspaceSlug)).append(",\n")
                append("  \"workspaceName\": ").append(jsonQuote(record.workspaceName)).append(",\n")
                append("  \"workspaceDirPath\": ").append(jsonQuote(record.workspaceDirPath)).append(",\n")
                append("  \"port\": ").append(record.port).append(",\n")
                append("  \"url\": ").append(jsonQuote(record.url)).append(",\n")
                append("  \"backendMode\": ").append(jsonQuote(record.backendMode.name)).append(",\n")
                append("  \"pid\": ").append(record.pid?.toString() ?: "null").append(",\n")
                append("  \"lastVerifiedAtMs\": ").append(record.lastVerifiedAtMs).append("\n")
                append("}\n")
            }
            metadataFile.writeText(json, StandardCharsets.UTF_8)
        }
    }

    private fun clearMetadataFile() {
        runCatching {
            if (metadataFile.exists()) {
                metadataFile.delete()
            }
        }
        lastLoadedMetadata = null
    }

    private fun jsonQuote(raw: String): String = buildString(raw.length + 2) {
        append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    fun destroy() {
        runCatching { vsCodeRuntime.destroy() }
        runCatching { localhostEngine.stop() }
        _snapshot.value = _snapshot.value.copy(
            state = RuntimeLifecycleState.STOPPED,
            pid = null,
            isRunning = false,
            isVerifiedHealthy = false,
            statusMessage = "AI-managed runtime destroyed",
        )
    }
}
