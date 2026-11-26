package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.example.StartupDiagnostics
import com.example.data.model.LocalhostRuntimeKind
import com.example.workspace.LocalhostRuntimeHost
import com.example.workspace.LocalhostServerStatus
import com.example.workspace.WatchdogTarget
import com.example.workspace.WatchdogUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "CodeServerWatchdog"
private const val HEALTH_CHECK_INTERVAL_MS = 4_000L
private const val CONSECUTIVE_PROBES_BEFORE_DEAD = 2

/**
 * Background watchdog service that monitors the localhost code-server process and
 * automatically performs a clean reboot with exponential backoff whenever the
 * background server or process dies, tripping a circuit breaker to prevent crash loops.
 */
class CodeServerWatchdogService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.w(TAG, "Watchdog coroutine error", throwable)
        runCatching { StartupDiagnostics.recordNonFatal(applicationContext, "CodeServerWatchdog", throwable) }
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val backoffTracker = RebootBackoffTracker()
    private var monitorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        backoffTracker.restore(LocalhostRuntimeHost.loadBackoffSnapshot(applicationContext))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_WATCHDOG -> {
                monitorJob?.cancel()
                monitorJob = null
                LocalhostRuntimeHost.clearTarget(applicationContext)
                backoffTracker.reset()
                LocalhostRuntimeHost.saveBackoffSnapshot(applicationContext, backoffTracker.snapshot())
                stopSelfResult(startId)
                return START_NOT_STICKY
            }

            ACTION_RESET_AND_REBOOT -> {
                backoffTracker.reset()
                LocalhostRuntimeHost.saveBackoffSnapshot(applicationContext, backoffTracker.snapshot())
                val (enabled, target) = LocalhostRuntimeHost.loadTarget(applicationContext)
                if (enabled && target != null) {
                    startMonitoringLoop(target, triggeredAfterProcessRestart = false)
                    return START_NOT_STICKY
                }
            }

            ACTION_START_WATCHDOG -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8080).coerceIn(1024, 65535)
                val wsName = intent.getStringExtra(EXTRA_WORKSPACE_NAME).orEmpty()
                    .ifBlank { "AI & Termux Localhost :$port" }
                val wsSlug = intent.getStringExtra(EXTRA_WORKSPACE_SLUG).orEmpty()
                    .ifBlank { "default" }
                val termuxCmd = intent.getStringExtra(EXTRA_TERMUX_CMD).orEmpty()
                    .ifBlank { LocalhostServerStatus.DEFAULT_TERMUX_CODE_SERVER_CMD }
                val isExternal = intent.getBooleanExtra(EXTRA_IS_EXTERNAL, false)
                val runtimeKind = runCatching {
                    intent.getStringExtra(EXTRA_RUNTIME_KIND)?.let { LocalhostRuntimeKind.valueOf(it) }
                }.getOrNull() ?: if (isExternal) {
                    LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER
                } else {
                    LocalhostRuntimeKind.EMBEDDED_EDITOR
                }
                val target = WatchdogTarget(
                    port = port,
                    workspaceName = wsName,
                    workspaceSlug = wsSlug,
                    termuxCommand = termuxCmd,
                    isExternalTermux = isExternal,
                    runtimeKind = runtimeKind,
                )
                LocalhostRuntimeHost.updateTarget(applicationContext, target, enabled = true)
                backoffTracker.reset()
                LocalhostRuntimeHost.saveBackoffSnapshot(applicationContext, backoffTracker.snapshot())
                startMonitoringLoop(target, triggeredAfterProcessRestart = false)
                return START_NOT_STICKY
            }

            null -> {
                val (enabled, persistedTarget) = LocalhostRuntimeHost.loadTarget(applicationContext)
                if (enabled && persistedTarget != null) {
                    LocalhostRuntimeHost.terminalEmulator.warn(
                        "WATCHDOG",
                        "Restoring localhost verification on 127.0.0.1:${persistedTarget.port}...",
                    )
                    startMonitoringLoop(persistedTarget, triggeredAfterProcessRestart = true)
                    return START_NOT_STICKY
                }
            }
        }

        stopSelfResult(startId)
        return START_NOT_STICKY
    }

    private fun startMonitoringLoop(
        target: WatchdogTarget,
        triggeredAfterProcessRestart: Boolean,
    ) {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val engine = LocalhostRuntimeHost.getEngine(applicationContext)
            var consecutiveProbeMisses = if (triggeredAfterProcessRestart) CONSECUTIVE_PROBES_BEFORE_DEAD else 0

            LocalhostRuntimeHost.terminalEmulator.info(
                "WATCHDOG",
                "Watchdog monitoring localhost 127.0.0.1:${target.port} (max ${backoffTracker.maxConsecutiveFailures} auto-reboots).",
            )
            LocalhostRuntimeHost.updateWatchdogUiState(
                WatchdogUiState(
                    isEnabled = true,
                    status = WatchdogHealthStatus.MONITORING,
                    message = "Watching :${target.port}",
                    totalReboots = backoffTracker.snapshot().totalReboots,
                ),
            )

            while (isActive) {
                try {
                    val (stillEnabled, latestTarget) = LocalhostRuntimeHost.loadTarget(applicationContext)
                    if (!stillEnabled || latestTarget == null) {
                        LocalhostRuntimeHost.updateWatchdogUiState(
                            WatchdogUiState(
                                isEnabled = false,
                                status = WatchdogHealthStatus.IDLE,
                                message = "Standby",
                            ),
                        )
                        break
                    }

                    val healthy = isLocalhostHealthy(engine, latestTarget)
                    if (healthy) {
                        consecutiveProbeMisses = 0
                        val nowMs = System.currentTimeMillis()
                        val before = backoffTracker.snapshot()
                        backoffTracker.recordHealthyHeartbeat(nowMs)
                        val after = backoffTracker.snapshot()
                        if (before.consecutiveFailures != after.consecutiveFailures) {
                            LocalhostRuntimeHost.saveBackoffSnapshot(applicationContext, after)
                            LocalhostRuntimeHost.terminalEmulator.success(
                                "WATCHDOG",
                                "Server stable on :${latestTarget.port} — backoff counter reset.",
                            )
                        }
                        LocalhostRuntimeHost.updateWatchdogUiState(
                            WatchdogUiState(
                                isEnabled = true,
                                status = WatchdogHealthStatus.MONITORING,
                                message = "Watching :${latestTarget.port}",
                                attemptNumber = after.consecutiveFailures,
                                maxAttempts = backoffTracker.maxConsecutiveFailures,
                                totalReboots = after.totalReboots,
                            ),
                        )
                    } else {
                        consecutiveProbeMisses += 1
                        if (consecutiveProbeMisses >= CONSECUTIVE_PROBES_BEFORE_DEAD) {
                            val shouldContinue = handleDeadProcessAndReboot(engine, latestTarget)
                            consecutiveProbeMisses = 0
                            if (!shouldContinue) {
                                break
                            }
                        }
                    }

                    delay(HEALTH_CHECK_INTERVAL_MS)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "Watchdog poll warning", t)
                    StartupDiagnostics.recordNonFatal(applicationContext, "CodeServerWatchdog.poll", t)
                    delay(HEALTH_CHECK_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun isLocalhostHealthy(
        engine: com.example.workspace.LocalhostServerEngine,
        target: WatchdogTarget,
    ): Boolean {
        val aiRuntime = LocalhostRuntimeHost.getRuntimeManager(applicationContext).aiRuntime
        return when {
            target.runtimeKind == LocalhostRuntimeKind.CODE_SERVER_RUNTIME ->
                aiRuntime.vsCodeRuntime.isHealthy()
            target.isExternalTermux ->
                engine.isPortAcceptingConnections(target.port)
            else ->
                aiRuntime.isHealthy() || (engine.isEmbeddedServerHealthy(target.port) && engine.probeLocalhostHttp(target.port))
        }
    }

    private suspend fun handleDeadProcessAndReboot(
        engine: com.example.workspace.LocalhostServerEngine,
        target: WatchdogTarget,
    ): Boolean {
        val nowMs = System.currentTimeMillis()
        val decision = backoffTracker.onProcessDied(nowMs)
        LocalhostRuntimeHost.saveBackoffSnapshot(applicationContext, backoffTracker.snapshot())

        when (decision) {
            is RebootDecision.CircuitOpen -> {
                LocalhostRuntimeHost.terminalEmulator.error("WATCHDOG", decision.reason)
                StartupDiagnostics.recordNonFatal(
                    applicationContext,
                    "CodeServerWatchdog.CircuitOpen",
                    IllegalStateException(decision.reason),
                )
                LocalhostRuntimeHost.updateWatchdogUiState(
                    WatchdogUiState(
                        isEnabled = true,
                        status = WatchdogHealthStatus.CIRCUIT_OPEN,
                        message = "Crash-loop guard paused — tap Restart",
                        attemptNumber = decision.consecutiveFailures,
                        maxAttempts = backoffTracker.maxConsecutiveFailures,
                        totalReboots = backoffTracker.snapshot().totalReboots,
                    ),
                )
                return false
            }

            is RebootDecision.ScheduleReboot -> {
                LocalhostRuntimeHost.terminalEmulator.warn(
                    "WATCHDOG",
                    "Code-server process down on :${target.port}. Clean reboot #${decision.attemptNumber}/${decision.maxAttempts} in ${decision.delayMs / 1000}s...",
                )
                var remainingMs = decision.delayMs
                while (remainingMs > 0L) {
                    val secs = ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(1)
                    LocalhostRuntimeHost.updateWatchdogUiState(
                        WatchdogUiState(
                            isEnabled = true,
                            status = WatchdogHealthStatus.WAITING_BACKOFF,
                            message = "Rebooting :${target.port} in ${secs}s (${decision.attemptNumber}/${decision.maxAttempts})",
                            attemptNumber = decision.attemptNumber,
                            maxAttempts = decision.maxAttempts,
                            totalReboots = backoffTracker.snapshot().totalReboots,
                            nextRetryInSec = secs,
                        ),
                    )
                    val step = minOf(1_000L, remainingMs)
                    delay(step)
                    remainingMs -= step
                }

                LocalhostRuntimeHost.updateWatchdogUiState(
                    WatchdogUiState(
                        isEnabled = true,
                        status = WatchdogHealthStatus.REBOOTING,
                        message = "Rebooting code-server on :${target.port}...",
                        attemptNumber = decision.attemptNumber,
                        maxAttempts = decision.maxAttempts,
                        totalReboots = backoffTracker.snapshot().totalReboots,
                    ),
                )

                val rebootStatus = try {
                    val runtimeMgr = LocalhostRuntimeHost.getRuntimeManager(applicationContext)
                    if (target.isExternalTermux && target.runtimeKind == LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER) {
                        runCatching { engine.stopSuspending() }
                        val externalProbe = engine.probeHttpIdentity(target.port)
                        if (externalProbe.kind == com.example.workspace.PortProbeKind.EXTERNAL_HTTP_SERVER) {
                            LocalhostServerStatus(
                                isRunning = true,
                                port = target.port,
                                url = "http://127.0.0.1:${target.port}",
                                isEmbeddedServer = false,
                                runtimeKind = LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER,
                                sourceLabel = "Termux / External HTTP Server (127.0.0.1:${target.port})",
                                workspaceDirPath = runtimeMgr.sharedFilesystem.resolveWorkspaceDir(target.workspaceSlug).absolutePath,
                                termuxBootstrapCommand = target.termuxCommand,
                            )
                        } else {
                            LocalhostServerStatus(
                                isRunning = false,
                                port = target.port,
                                url = "http://127.0.0.1:${target.port}",
                                isEmbeddedServer = false,
                                runtimeKind = LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER,
                                sourceLabel = "Termux code-server offline (:${target.port})",
                                workspaceDirPath = runtimeMgr.sharedFilesystem.resolveWorkspaceDir(target.workspaceSlug).absolutePath,
                                termuxBootstrapCommand = target.termuxCommand,
                                failureDetail = "External Termux server on :${target.port} is offline (${externalProbe.detail})",
                            )
                        }
                    } else {
                        val preferGenuine = target.runtimeKind == LocalhostRuntimeKind.CODE_SERVER_RUNTIME
                        val snap = runtimeMgr.aiRuntime.restartRuntime(
                            workspaceName = target.workspaceName,
                            workspaceSlug = target.workspaceSlug,
                            requestedPort = target.port,
                            preferGenuineCodeServer = preferGenuine,
                            allowFallbackViewer = !preferGenuine,
                        )
                        val isEmbedded = snap.backendMode == com.example.runtime.AiManagedBackendMode.FALLBACK_LOCAL_VIEWER
                        val resolvedRuntimeKind = when (snap.backendMode) {
                            com.example.runtime.AiManagedBackendMode.BUNDLED_CODE_SERVER ->
                                LocalhostRuntimeKind.CODE_SERVER_RUNTIME
                            com.example.runtime.AiManagedBackendMode.EXTERNAL_CODE_SERVER ->
                                LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER
                            else -> LocalhostRuntimeKind.EMBEDDED_EDITOR
                        }
                        LocalhostServerStatus(
                            isRunning = snap.isRunning && snap.isVerifiedHealthy,
                            port = snap.port ?: target.port,
                            url = snap.url,
                            isEmbeddedServer = isEmbedded,
                            runtimeKind = resolvedRuntimeKind,
                            sourceLabel = snap.statusMessage,
                            workspaceDirPath = snap.workspaceDirPath,
                            termuxBootstrapCommand = target.termuxCommand,
                            failureDetail = snap.errorMessage.orEmpty(),
                        )
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    StartupDiagnostics.recordNonFatal(applicationContext, "CodeServerWatchdog.reboot", t)
                    LocalhostRuntimeHost.terminalEmulator.error(
                        "WATCHDOG",
                        "Reboot #${decision.attemptNumber} failed: ${t.localizedMessage ?: t.javaClass.simpleName}",
                    )
                    return true
                }

                if (rebootStatus.isRunning) {
                    LocalhostRuntimeHost.terminalEmulator.success(
                        "WATCHDOG",
                        "Clean reboot #${decision.attemptNumber} succeeded on ${rebootStatus.url}.",
                    )
                    LocalhostRuntimeHost.updateTarget(
                        applicationContext,
                        target.copy(
                            port = rebootStatus.port,
                            isExternalTermux = !rebootStatus.isEmbeddedServer,
                            runtimeKind = rebootStatus.runtimeKind,
                        ),
                        enabled = true,
                    )
                    LocalhostRuntimeHost.emitRebootSuccess(rebootStatus)
                    LocalhostRuntimeHost.updateWatchdogUiState(
                        WatchdogUiState(
                            isEnabled = true,
                            status = WatchdogHealthStatus.MONITORING,
                            message = "Recovered :${rebootStatus.port} (reboots=${backoffTracker.snapshot().totalReboots})",
                            attemptNumber = decision.attemptNumber,
                            maxAttempts = decision.maxAttempts,
                            totalReboots = backoffTracker.snapshot().totalReboots,
                        ),
                    )
                } else {
                    LocalhostRuntimeHost.terminalEmulator.error(
                        "WATCHDOG",
                        "Clean reboot #${decision.attemptNumber} did not bind port ${target.port}.",
                    )
                }
                return true
            }
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START_WATCHDOG = "com.example.service.ACTION_START_WATCHDOG"
        private const val ACTION_STOP_WATCHDOG = "com.example.service.ACTION_STOP_WATCHDOG"
        private const val ACTION_RESET_AND_REBOOT = "com.example.service.ACTION_RESET_AND_REBOOT"
        private const val EXTRA_PORT = "extra_port"
        private const val EXTRA_WORKSPACE_NAME = "extra_workspace_name"
        private const val EXTRA_WORKSPACE_SLUG = "extra_workspace_slug"
        private const val EXTRA_TERMUX_CMD = "extra_termux_cmd"
        private const val EXTRA_IS_EXTERNAL = "extra_is_external"
        private const val EXTRA_RUNTIME_KIND = "extra_runtime_kind"

        fun startMonitoring(
            context: Context,
            port: Int,
            workspaceName: String,
            workspaceSlug: String,
            termuxCommand: String,
            isExternalTermux: Boolean,
            runtimeKind: LocalhostRuntimeKind = if (isExternalTermux) {
                LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER
            } else {
                LocalhostRuntimeKind.EMBEDDED_EDITOR
            },
        ) {
            val appCtx = context.applicationContext
            val target = WatchdogTarget(
                port = port.coerceIn(1024, 65535),
                workspaceName = workspaceName,
                workspaceSlug = workspaceSlug,
                termuxCommand = termuxCommand,
                isExternalTermux = isExternalTermux,
                runtimeKind = runtimeKind,
            )
            LocalhostRuntimeHost.updateTarget(appCtx, target, enabled = true)
            runCatching {
                val intent = Intent(appCtx, CodeServerWatchdogService::class.java).apply {
                    action = ACTION_START_WATCHDOG
                    putExtra(EXTRA_PORT, target.port)
                    putExtra(EXTRA_WORKSPACE_NAME, target.workspaceName)
                    putExtra(EXTRA_WORKSPACE_SLUG, target.workspaceSlug)
                    putExtra(EXTRA_TERMUX_CMD, target.termuxCommand)
                    putExtra(EXTRA_IS_EXTERNAL, target.isExternalTermux)
                    putExtra(EXTRA_RUNTIME_KIND, target.runtimeKind.name)
                }
                appCtx.startService(intent)
            }.onFailure { err ->
                Log.w(TAG, "Unable to start CodeServerWatchdogService", err)
            }
        }

        fun resetCircuitAndRestart(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                val intent = Intent(appCtx, CodeServerWatchdogService::class.java).apply {
                    action = ACTION_RESET_AND_REBOOT
                }
                appCtx.startService(intent)
            }.onFailure { err ->
                Log.w(TAG, "Unable to reset CodeServerWatchdogService", err)
            }
        }

        fun stopMonitoring(context: Context) {
            val appCtx = context.applicationContext
            LocalhostRuntimeHost.clearTarget(appCtx)
            LocalhostRuntimeHost.updateWatchdogUiState(
                WatchdogUiState(
                    isEnabled = false,
                    status = WatchdogHealthStatus.IDLE,
                    message = "Standby",
                ),
            )
            runCatching {
                appCtx.stopService(Intent(appCtx, CodeServerWatchdogService::class.java))
            }.onFailure { err ->
                Log.w(TAG, "Unable to stop CodeServerWatchdogService cleanly", err)
            }
        }
    }
}
