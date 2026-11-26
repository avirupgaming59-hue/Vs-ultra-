package com.example.ui.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.StartupDiagnostics
import com.example.ai.GeminiBootstrapException
import com.example.ai.GeminiChatService
import com.example.ai.GeminiFailureReason
import com.example.data.local.AppDatabase
import com.example.data.model.ActiveSessionEntity
import com.example.data.model.ConnectionMode
import com.example.data.model.LocalhostRuntimeKind
import com.example.data.model.ServerProfile
import com.example.data.model.WorkspaceProvisioningState
import com.example.data.repository.AppPreferences
import com.example.data.repository.ServerProfileRepository
import com.example.data.repository.SettingsRepository
import com.example.runtime.AiManagedBackendMode
import com.example.runtime.AiManagedRuntimeSnapshot
import com.example.runtime.CommandSafetyDecision
import com.example.runtime.CommandSafetyValidator
import com.example.runtime.MemorySnapshot
import com.example.runtime.PendingCommandConfirmation
import com.example.runtime.RuntimeBinaryInventory
import com.example.runtime.RuntimeInstanceStatus
import com.example.runtime.RuntimeLifecycleState
import com.example.runtime.VsCodeIdeBackendKind
import com.example.service.CodeServerWatchdogService
import com.example.service.KeepAliveService
import com.example.terminal.TerminalLine
import com.example.workspace.AiBuildResponse
import com.example.workspace.ChromeCustomTabsManager
import com.example.workspace.InAppWebWorkspaceController
import com.example.workspace.LocalhostRuntimeHost
import com.example.workspace.LocalhostServerStatus
import com.example.workspace.PortProbeKind
import com.example.workspace.TermuxRunCommand
import com.example.workspace.WatchdogUiState
import com.example.workspace.WebViewSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.Locale

private const val TAG = "WorkspaceViewModel"
private const val TERMUX_PACKAGE_NAME = "com.termux"

data class LocalhostUiState(
    val isProvisioning: Boolean = false,
    val provisioningState: WorkspaceProvisioningState = WorkspaceProvisioningState.NOT_STARTED,
    val runtimeKind: LocalhostRuntimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
    val statusMessage: String = "",
    val activeStatus: LocalhostServerStatus? = null,
    val geminiNotice: String? = null,
    val errorMessage: String? = null,
    val draftProfileId: Long? = null,
    val termuxInstalled: Boolean = false,
    val pendingTermuxCommand: String = "",
    val pendingTargetPort: Int = 8080,
    val canOfferOfflineFallback: Boolean = false,
    val isMissingIdeBinary: Boolean = false,
)

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val profileRepository = ServerProfileRepository(
        dao = database.serverProfileDao(),
        activeSessionDao = database.activeSessionDao(),
    )
    private val settingsRepository = SettingsRepository(appContext)

    val webController = InAppWebWorkspaceController(appContext)
    val runtimeManager = LocalhostRuntimeHost.getRuntimeManager(appContext)
    private val localhostEngine = LocalhostRuntimeHost.getEngine(appContext)
    private val geminiService = GeminiChatService()
    private val customTabsManager = ChromeCustomTabsManager(appContext)
    val terminalEmulator = LocalhostRuntimeHost.terminalEmulator

    private val connectionMutex = Mutex()
    private var activeConnectJob: Job? = null
    private var geminiBootstrapJob: Job? = null

    @Volatile
    private var initializedProfiles: Boolean = false

    @Volatile
    private var hasHandledCurrentEntry: Boolean = false

    @Volatile
    private var userManuallySelectedRemoteInSession: Boolean = false

    @Volatile
    private var isUserConnectionInProgress: Boolean = false

    val profiles: StateFlow<List<ServerProfile>> = profileRepository.profiles
        .catch { err ->
            Log.w(TAG, "Profiles flow warning", err)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProfile: StateFlow<ServerProfile?> = profileRepository.activeProfile
        .catch { err ->
            Log.w(TAG, "Active profile flow warning", err)
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val persistedSession: StateFlow<ActiveSessionEntity?> = profileRepository.activeSession
        .catch { err ->
            Log.w(TAG, "Active session flow warning", err)
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val sessionState: StateFlow<WebViewSessionState> = webController.state
    val terminalLines: StateFlow<List<TerminalLine>> = terminalEmulator.lines
    val watchdogState: StateFlow<WatchdogUiState> = LocalhostRuntimeHost.watchdogState

    val aiRuntimeState: StateFlow<AiManagedRuntimeSnapshot> = runtimeManager.aiRuntime.snapshot
    val vsCodeRuntimeState: StateFlow<RuntimeInstanceStatus> = runtimeManager.vsCodeRuntime.status
    val vsCodeRuntimeLogs: StateFlow<List<TerminalLine>> = runtimeManager.vsCodeRuntime.logs
    val binaryInventory: StateFlow<RuntimeBinaryInventory> = runtimeManager.vsCodeRuntime.binaryInventory

    val userTerminalState: StateFlow<RuntimeInstanceStatus> = runtimeManager.userTerminal.status
    val userTerminalLines: StateFlow<List<TerminalLine>> = runtimeManager.userTerminal.lines
    val userCommandHistory: StateFlow<List<String>> = runtimeManager.userTerminal.commandHistory
    val pendingCommandConfirmation: StateFlow<PendingCommandConfirmation?> = runtimeManager.userTerminal.pendingConfirmation

    private val _memorySnapshot = MutableStateFlow(runtimeManager.checkMemoryBudget())
    val memorySnapshot: StateFlow<MemorySnapshot> = _memorySnapshot.asStateFlow()

    private val _localhostState = MutableStateFlow(
        LocalhostUiState(termuxInstalled = isTermuxInstalled(appContext)),
    )
    val localhostState: StateFlow<LocalhostUiState> = _localhostState.asStateFlow()

    init {
        if (terminalEmulator.lines.value.isEmpty()) {
            terminalEmulator.info("SHELL", "VS Shell Terminal Emulator ready (type 'help' for commands)")
        }

        viewModelScope.launch {
            LocalhostRuntimeHost.rebootEvents.collect { rebootedStatus ->
                _localhostState.update {
                    it.copy(
                        isProvisioning = false,
                        provisioningState = WorkspaceProvisioningState.READY,
                        runtimeKind = rebootedStatus.runtimeKind,
                        statusMessage = rebootedStatus.sourceLabel,
                        activeStatus = rebootedStatus,
                        errorMessage = null,
                        canOfferOfflineFallback = false,
                    )
                }
                val active = activeProfile.value
                val prefs = runCatching { settingsRepository.preferences.first() }.getOrNull()
                if (active != null && extractLocalhostPort(active.url) != null) {
                    val slug = active.workspaceSlug.ifBlank { workspaceSlugFor(active.id, active.name) }
                    runCatching {
                        profileRepository.commitReadyProfile(
                            profileId = active.id,
                            actualUrl = rebootedStatus.url,
                            actualSlug = slug,
                            runtimeKind = rebootedStatus.runtimeKind,
                        )
                    }
                    webController.loadProfileUrl(
                        profileId = active.id,
                        rawUrl = rebootedStatus.url,
                        desktopMode = active.useDesktopUserAgent,
                        autoLoginPassword = if (prefs != null) resolveEffectivePassword(active, prefs) else "",
                        forceReload = true,
                    )
                } else {
                    webController.reload()
                }
            }
        }

        localhostEngine.setAiCodeHandler { prompt, currentFile, currentContent, files ->
            terminalEmulator.info("GEMINI", "AI build request for '${currentFile.ifBlank { "index.html" }}': ${prompt.take(72)}")
            geminiService.generateWorkspaceCodeEdit(
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

        viewModelScope.launch {
            connectionMutex.withLock {
                runCatching {
                    val initialPrefs = settingsRepository.preferences.first()
                    ensureProfilesInitializedLocked(initialPrefs)
                }.onFailure { err ->
                    if (err is CancellationException) throw err
                    Log.w(TAG, "Initial profile setup warning", err)
                    terminalEmulator.error("SYSTEM", "Profile init warning: ${err.localizedMessage}")
                }
            }
        }
    }

    private suspend fun ensureProfilesInitializedLocked(prefs: AppPreferences) {
        if (initializedProfiles) return
        if (!prefs.hasSeededDefaults) {
            profileRepository.seedDefaultProfilesIfNeeded()
            settingsRepository.setHasSeededDefaults(true)
        } else {
            profileRepository.migrateLegacyProfilesIfNeeded()
        }
        initializedProfiles = true
    }

    fun onAppLeftForeground() {
        hasHandledCurrentEntry = false
    }

    fun syncKeepAliveService(prefs: AppPreferences, activeServerName: String?) {
        val anyRuntimeActive = runtimeManager.hasAnyActiveRuntime(localhostEngine.isEmbeddedServerRunning())
        if (prefs.keepAliveServiceEnabled && anyRuntimeActive) {
            KeepAliveService.start(appContext, activeServerName ?: "Local Workspace")
        } else {
            KeepAliveService.stop(appContext)
        }
    }

    private fun refreshKeepAliveWithCurrentPrefs(activeServerName: String? = activeProfile.value?.name) {
        viewModelScope.launch {
            val prefs = runCatching { settingsRepository.preferences.first() }.getOrNull() ?: return@launch
            syncKeepAliveService(prefs, activeServerName)
        }
    }

    fun onAppEntered(
        prefs: AppPreferences,
        safeMode: Boolean = false,
        onOpenWorkspace: () -> Unit = {},
    ) {
        if (hasHandledCurrentEntry || isUserConnectionInProgress) return
        hasHandledCurrentEntry = true
        activeConnectJob?.cancel()
        activeConnectJob = viewModelScope.launch {
            connectionMutex.withLock {
                runCatching {
                    val freshPrefs = runCatching { settingsRepository.preferences.first() }
                        .getOrDefault(prefs)
                    ensureProfilesInitializedLocked(freshPrefs)
                    if (safeMode) {
                        runtimeManager.aiRuntime.inspectAndCleanupStaleMetadata()
                        terminalEmulator.warn("SYSTEM", "Entered Safe Mode — automatic server startup paused.")
                        return@runCatching
                    }

                    // Priority 1: Always inspect runtime health & clean up stale metadata on entry.
                    val cleanedMeta = runtimeManager.aiRuntime.inspectAndCleanupStaleMetadata()

                    if (freshPrefs.aiAutoCodeServerOnLaunch && !userManuallySelectedRemoteInSession) {
                        val active = profileRepository.activeProfile.first()
                        val localPortFromActive = active?.let { extractLocalhostPort(it.url) }
                        val targetProfile = if (
                            active != null &&
                            localPortFromActive != null &&
                            active.provisioningState != WorkspaceProvisioningState.FAILED
                        ) {
                            active
                        } else {
                            profileRepository.ensureLocalhostProfileAndActivate(
                                cleanedMeta?.port?.takeIf { it in 1024..65535 } ?: 8080,
                            )
                        }
                        val opened = connectProfileInternal(
                            profile = targetProfile,
                            prefs = freshPrefs,
                            forceReload = false,
                            previousActiveId = active?.id,
                        )
                        if (opened) {
                            onOpenWorkspace()
                        }
                    } else {
                        // Reuse already-running healthy AI runtime instance or clean idle probe without forcing navigation
                        runtimeManager.aiRuntime.ensureRuntimeForAppEntry(
                            preferredPort = cleanedMeta?.port?.takeIf { it in 1024..65535 } ?: 8080,
                            preferredSlug = cleanedMeta?.workspaceSlug.orEmpty().ifBlank { "default" },
                            workspaceName = cleanedMeta?.workspaceName.orEmpty().ifBlank { "AI Local Workspace" },
                            allowAutoStart = false,
                        )
                        if (freshPrefs.autoLaunchBackground) {
                            val active = profileRepository.activeProfile.first() ?: return@runCatching
                            if (active.provisioningState == WorkspaceProvisioningState.FAILED) {
                                _localhostState.update {
                                    it.copy(
                                        isProvisioning = false,
                                        provisioningState = WorkspaceProvisioningState.FAILED,
                                        runtimeKind = active.runtimeKind,
                                        draftProfileId = active.id,
                                        errorMessage = active.lastError.ifBlank { "Previous workspace failed provisioning" },
                                        statusMessage = "Provisioning failed",
                                        canOfferOfflineFallback = extractLocalhostPort(active.url) != null,
                                    )
                                }
                                return@runCatching
                            }
                            val session = profileRepository.activeSession.first()
                            val targetUrl = session
                                ?.takeIf { it.activeProfileId == active.id && it.lastUrl.isNotBlank() }
                                ?.lastUrl
                                ?: active.url
                            connectProfileInternal(
                                profile = active.copy(url = targetUrl),
                                prefs = freshPrefs,
                                forceReload = false,
                                previousActiveId = active.id,
                            )
                        }
                    }
                }.onFailure { err ->
                    if (err is CancellationException) throw err
                    Log.w(TAG, "onAppEntered warning", err)
                    val errText = err.localizedMessage ?: "Auto-launch failed"
                    terminalEmulator.error("SYSTEM", "App entry error: $errText")
                    StartupDiagnostics.recordNonFatal(appContext, "WorkspaceViewModel.onAppEntered", err)
                    webController.reportConnectionError(errText)
                }
            }
        }
    }

    fun syncWatchdogPreference(prefs: AppPreferences, safeMode: Boolean) {
        if (!prefs.autoRebootWatchdogEnabled || safeMode) {
            CodeServerWatchdogService.stopMonitoring(appContext)
            return
        }
        val activeLocal = _localhostState.value.activeStatus
        val profile = activeProfile.value
        if (activeLocal != null && activeLocal.isRunning && profile != null) {
            val slug = profile.workspaceSlug.ifBlank { workspaceSlugFor(profile.id, profile.name) }
            armWatchdog(
                status = activeLocal,
                workspaceName = profile.name,
                workspaceSlug = slug,
            )
        }
    }

    private fun armWatchdog(
        status: LocalhostServerStatus,
        workspaceName: String,
        workspaceSlug: String,
    ) {
        CodeServerWatchdogService.startMonitoring(
            context = appContext,
            port = status.port,
            workspaceName = workspaceName,
            workspaceSlug = workspaceSlug,
            termuxCommand = status.termuxBootstrapCommand,
            isExternalTermux = !status.isEmbeddedServer,
            runtimeKind = status.runtimeKind,
        )
    }

    fun restartLocalCodeServer(
        prefs: AppPreferences,
        onRestarted: () -> Unit = {},
    ) {
        userManuallySelectedRemoteInSession = false
        activeConnectJob?.cancel()
        geminiBootstrapJob?.cancel()
        activeConnectJob = viewModelScope.launch {
            connectionMutex.withLock {
                try {
                    terminalEmulator.warn("SERVER", "Restarting local workspace server for recovery...")
                    _localhostState.update {
                        it.copy(
                            isProvisioning = true,
                            provisioningState = WorkspaceProvisioningState.STARTING,
                            statusMessage = "Stopping & restarting local server...",
                            errorMessage = null,
                            canOfferOfflineFallback = false,
                        )
                    }
                    localhostEngine.stopSuspending()

                    val active = profileRepository.activeProfile.first()
                    val localPortFromActive = active?.let { extractLocalhostPort(it.url) }
                    val targetProfile = if (active != null && localPortFromActive != null) {
                        active
                    } else {
                        profileRepository.ensureLocalhostProfileAndActivate(8080)
                    }
                    val port = extractLocalhostPort(targetProfile.url) ?: 8080
                    val slug = targetProfile.workspaceSlug.ifBlank { workspaceSlugFor(targetProfile.id, targetProfile.name) }

                    val ok = if (targetProfile.runtimeKind == LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER) {
                        connectProfileInternal(
                            profile = targetProfile,
                            prefs = prefs,
                            forceReload = true,
                            previousActiveId = active?.id,
                        )
                    } else {
                        val preferGenuine = targetProfile.runtimeKind == LocalhostRuntimeKind.CODE_SERVER_RUNTIME
                        val snap = runtimeManager.aiRuntime.restartRuntime(
                            workspaceName = targetProfile.name,
                            workspaceSlug = slug,
                            requestedPort = port,
                            preferGenuineCodeServer = preferGenuine,
                            allowFallbackViewer = !preferGenuine,
                        )
                        if (snap.isRunning && snap.isVerifiedHealthy) {
                            val isEmbedded = snap.backendMode == AiManagedBackendMode.FALLBACK_LOCAL_VIEWER
                            val resolvedKind = if (isEmbedded) {
                                LocalhostRuntimeKind.EMBEDDED_EDITOR
                            } else {
                                LocalhostRuntimeKind.CODE_SERVER_RUNTIME
                            }
                            val committed = profileRepository.commitReadyProfile(
                                profileId = targetProfile.id,
                                actualUrl = snap.url,
                                actualSlug = slug,
                                runtimeKind = resolvedKind,
                            )
                            val readyStatus = LocalhostServerStatus(
                                isRunning = true,
                                port = snap.port ?: port,
                                url = snap.url,
                                isEmbeddedServer = isEmbedded,
                                runtimeKind = resolvedKind,
                                sourceLabel = snap.statusMessage,
                                workspaceDirPath = snap.workspaceDirPath,
                                termuxBootstrapCommand = defaultTermuxCommandForPort(snap.port ?: port),
                            )
                            _localhostState.update {
                                it.copy(
                                    isProvisioning = false,
                                    provisioningState = WorkspaceProvisioningState.READY,
                                    runtimeKind = resolvedKind,
                                    activeStatus = readyStatus,
                                    draftProfileId = null,
                                    errorMessage = null,
                                    isMissingIdeBinary = false,
                                    canOfferOfflineFallback = false,
                                    statusMessage = snap.statusMessage,
                                )
                            }
                            syncKeepAliveService(prefs, committed.name)
                            webController.loadProfileUrl(
                                profileId = committed.id,
                                rawUrl = snap.url,
                                desktopMode = committed.useDesktopUserAgent,
                                autoLoginPassword = resolveEffectivePassword(committed, prefs),
                                forceReload = true,
                            )
                            true
                        } else {
                            val err = snap.errorMessage ?: snap.statusMessage
                            _localhostState.update {
                                it.copy(
                                    isProvisioning = false,
                                    provisioningState = WorkspaceProvisioningState.FAILED,
                                    runtimeKind = targetProfile.runtimeKind,
                                    draftProfileId = targetProfile.id,
                                    pendingTargetPort = port,
                                    pendingTermuxCommand = defaultTermuxCommandForPort(port),
                                    isMissingIdeBinary = runtimeManager.vsCodeRuntime.status.value.backendKind == VsCodeIdeBackendKind.MISSING_BINARY,
                                    canOfferOfflineFallback = true,
                                    statusMessage = snap.statusMessage,
                                    errorMessage = err,
                                )
                            }
                            webController.reportConnectionError(err)
                            false
                        }
                    }
                    if (ok && prefs.autoRebootWatchdogEnabled) {
                        CodeServerWatchdogService.resetCircuitAndRestart(appContext)
                        onRestarted()
                    } else if (ok) {
                        onRestarted()
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.e(TAG, "restartLocalCodeServer failed", t)
                    val errText = t.localizedMessage ?: "Restart failed"
                    terminalEmulator.error("SERVER", "Restart failed: $errText")
                    StartupDiagnostics.recordNonFatal(appContext, "restartLocalCodeServer", t)
                    _localhostState.update {
                        it.copy(
                            isProvisioning = false,
                            provisioningState = WorkspaceProvisioningState.FAILED,
                            statusMessage = errText,
                            errorMessage = errText,
                        )
                    }
                    webController.reportConnectionError(errText)
                }
            }
        }
    }

    fun clearTerminalLogs() {
        terminalEmulator.clear()
        terminalEmulator.info("SHELL", "Terminal logs cleared.")
    }

    fun startUserTerminal() {
        viewModelScope.launch {
            _memorySnapshot.value = runtimeManager.checkMemoryBudget()
            val dir = runtimeManager.sharedFilesystem.getActiveWorkspaceDir()
            val slug = runtimeManager.sharedFilesystem.activeWorkspaceSlug.value
            runtimeManager.userTerminal.startShell(dir, slug)
            refreshKeepAliveWithCurrentPrefs()
        }
    }

    fun stopUserTerminal() {
        viewModelScope.launch {
            runtimeManager.userTerminal.stopShell()
            refreshKeepAliveWithCurrentPrefs()
        }
    }

    fun restartUserTerminal() {
        viewModelScope.launch {
            _memorySnapshot.value = runtimeManager.checkMemoryBudget()
            runtimeManager.userTerminal.restartShell()
            refreshKeepAliveWithCurrentPrefs()
        }
    }

    fun interruptUserTerminal() {
        viewModelScope.launch {
            runtimeManager.userTerminal.interruptRunningCommand()
            refreshKeepAliveWithCurrentPrefs()
        }
    }

    fun executeUserTerminalCommand(rawCommand: String) {
        if (rawCommand.isBlank()) return
        viewModelScope.launch {
            _memorySnapshot.value = runtimeManager.checkMemoryBudget()
            runtimeManager.userTerminal.sendCommand(rawCommand)
            refreshKeepAliveWithCurrentPrefs()
        }
    }

    fun confirmPendingUserCommand() {
        viewModelScope.launch {
            runtimeManager.userTerminal.confirmPendingCommand()
            refreshKeepAliveWithCurrentPrefs()
        }
    }

    fun dismissPendingUserCommand() {
        runtimeManager.userTerminal.dismissPendingCommand()
    }

    fun clearUserTerminalLogs() {
        runtimeManager.userTerminal.clearLogs()
    }

    fun startVsCodeRuntime(prefs: AppPreferences) {
        viewModelScope.launch {
            _memorySnapshot.value = runtimeManager.checkMemoryBudget()
            val active = activeProfile.value
            val slug = active?.workspaceSlug?.ifBlank { workspaceSlugFor(active.id, active.name) }
                ?: runtimeManager.sharedFilesystem.activeWorkspaceSlug.value
            val port = active?.let { extractLocalhostPort(it.url) } ?: _localhostState.value.pendingTargetPort
            val snap = runtimeManager.aiRuntime.prepareAndStartWorkspace(
                workspaceName = active?.name ?: "AI Local Workspace",
                workspaceSlug = slug,
                requestedPort = port,
                overwriteStarterFiles = false,
                preferGenuineCodeServer = true,
                allowFallbackViewer = true,
            )
            if (snap.isRunning && snap.isVerifiedHealthy) {
                val isEmbedded = snap.backendMode == AiManagedBackendMode.FALLBACK_LOCAL_VIEWER
                val resolvedKind = if (isEmbedded) {
                    LocalhostRuntimeKind.EMBEDDED_EDITOR
                } else {
                    LocalhostRuntimeKind.CODE_SERVER_RUNTIME
                }
                val status = LocalhostServerStatus(
                    isRunning = true,
                    port = snap.port ?: port,
                    url = snap.url,
                    isEmbeddedServer = isEmbedded,
                    runtimeKind = resolvedKind,
                    sourceLabel = snap.statusMessage,
                    workspaceDirPath = snap.workspaceDirPath,
                    termuxBootstrapCommand = defaultTermuxCommandForPort(snap.port ?: port),
                )
                _localhostState.update {
                    it.copy(
                        isProvisioning = false,
                        provisioningState = WorkspaceProvisioningState.READY,
                        runtimeKind = resolvedKind,
                        activeStatus = status,
                        errorMessage = null,
                        isMissingIdeBinary = false,
                        canOfferOfflineFallback = false,
                        statusMessage = snap.statusMessage,
                    )
                }
                if (prefs.autoRebootWatchdogEnabled) {
                    armWatchdog(
                        status = status,
                        workspaceName = active?.name ?: "AI Local Workspace",
                        workspaceSlug = slug,
                    )
                }
                if (active != null) {
                    webController.loadProfileUrl(
                        profileId = active.id,
                        rawUrl = snap.url,
                        desktopMode = active.useDesktopUserAgent,
                        autoLoginPassword = resolveEffectivePassword(active, prefs),
                        forceReload = !snap.reusedExistingInstance,
                    )
                }
            } else {
                _localhostState.update {
                    it.copy(
                        isProvisioning = false,
                        provisioningState = WorkspaceProvisioningState.FAILED,
                        errorMessage = snap.errorMessage ?: snap.statusMessage,
                        statusMessage = snap.statusMessage,
                        isMissingIdeBinary = runtimeManager.vsCodeRuntime.status.value.backendKind == VsCodeIdeBackendKind.MISSING_BINARY,
                        canOfferOfflineFallback = true,
                    )
                }
            }
            refreshKeepAliveWithCurrentPrefs(active?.name)
        }
    }

    fun stopVsCodeRuntime() {
        viewModelScope.launch {
            runtimeManager.aiRuntime.stopRuntime()
            CodeServerWatchdogService.stopMonitoring(appContext)
            _localhostState.update {
                it.copy(
                    isProvisioning = false,
                    provisioningState = WorkspaceProvisioningState.STOPPED,
                    activeStatus = it.activeStatus?.copy(isRunning = false, sourceLabel = "Stopped"),
                    statusMessage = "AI-managed runtime stopped",
                )
            }
            refreshKeepAliveWithCurrentPrefs()
        }
    }

    fun clearVsCodeRuntimeLogs() {
        runtimeManager.vsCodeRuntime.clearLogs()
        terminalEmulator.clear()
        terminalEmulator.info("SHELL", "Runtime logs cleared.")
    }

    fun recreateWorkspaceWebView() {
        terminalEmulator.info("WEBVIEW", "Recreating in-app WebView instance...")
        webController.recreateMainWebView()
    }

    fun dumpCombinedRuntimeLogs(): String {
        return buildString {
            appendLine("=== VS CODE RUNTIME LOGS ===")
            appendLine(runtimeManager.vsCodeRuntime.terminalEmulator.dumpPlainText())
            appendLine()
            appendLine("=== USER TERMINAL SHELL LOGS ===")
            appendLine(runtimeManager.userTerminal.dumpLogsPlainText())
            appendLine()
            appendLine("=== APP SERVER & WEBVIEW DIAGNOSTICS ===")
            appendLine(terminalEmulator.dumpPlainText())
        }
    }

    fun executeTerminalCommand(rawCommand: String, prefs: AppPreferences) {
        val cmd = rawCommand.trim()
        if (cmd.isBlank()) return
        terminalEmulator.appendAnsi("SHELL", "\u001B[1;36m$ $cmd\u001B[0m")
        when (cmd.lowercase()) {
            "help", "?" -> {
                terminalEmulator.info("SHELL", "Commands: status | watchdog | restart | stop-server | files | crash-log | binaries | recreate-webview | termux-cmd | clear | help")
            }
            "clear", "cls" -> clearTerminalLogs()
            "status" -> {
                val ls = _localhostState.value.activeStatus
                val ai = runtimeManager.aiRuntime.snapshot.value
                val ut = runtimeManager.userTerminal.status.value
                val ws = sessionState.value
                val wd = watchdogState.value
                terminalEmulator.info(
                    "STATUS",
                    "aiRuntime=${ai.state} (${ai.backendMode.label}) userShell=${ut.state} port=${ai.port ?: ls?.port ?: "-"} watchdog=${wd.status} web=${ws.status} domOk=${ws.isDomVerified} blank=${ws.isBlankScreenDetected}",
                )
            }
            "binaries" -> {
                val inv = runtimeManager.vsCodeRuntime.refreshBinaryInventory()
                terminalEmulator.info(
                    "BINARIES",
                    "codeServerRunnable=${inv.isCodeServerRunnableInApp} missing=${inv.missingIdeBinaries.joinToString(", ").ifBlank { "none" }} shell=${inv.systemShellPath} tools=${inv.availableSystemTools.joinToString(", ")}",
                )
            }
            "recreate-webview" -> recreateWorkspaceWebView()
            "watchdog" -> {
                val wd = watchdogState.value
                terminalEmulator.info(
                    "WATCHDOG",
                    "enabled=${wd.isEnabled} status=${wd.status} attempt=${wd.attemptNumber}/${wd.maxAttempts} totalReboots=${wd.totalReboots} (${wd.message})",
                )
            }
            "stop-server" -> {
                terminalEmulator.warn("SHELL", "Stopping AI-managed workspace servers...")
                viewModelScope.launch {
                    CodeServerWatchdogService.stopMonitoring(appContext)
                    runtimeManager.aiRuntime.stopRuntime()
                    activeProfile.value?.let { cur ->
                        if (extractLocalhostPort(cur.url) != null) {
                            profileRepository.updateProvisioningState(
                                profileId = cur.id,
                                state = WorkspaceProvisioningState.STOPPED,
                                lastError = "",
                            )
                        }
                    }
                    _localhostState.update {
                        it.copy(
                            isProvisioning = false,
                            provisioningState = WorkspaceProvisioningState.STOPPED,
                            activeStatus = it.activeStatus?.copy(isRunning = false, sourceLabel = "Stopped"),
                            statusMessage = "AI-managed server stopped",
                        )
                    }
                    refreshKeepAliveWithCurrentPrefs()
                }
            }
            "restart" -> restartLocalCodeServer(prefs)
            "files" -> {
                val path = _localhostState.value.activeStatus?.workspaceDirPath
                    ?: runtimeManager.sharedFilesystem.getActiveWorkspaceDir().absolutePath
                viewModelScope.launch {
                    val names = withContext(Dispatchers.IO) {
                        val dir = File(path)
                        runtimeManager.sharedFilesystem.listFiles(dir)
                            .map { "${runtimeManager.sharedFilesystem.relativePathOf(dir, it)} (${it.length()}B)" }
                    }
                    if (names.isEmpty()) {
                        terminalEmulator.warn("FILES", "Workspace directory has no files yet.")
                    } else {
                        terminalEmulator.success("FILES", names.joinToString(", "))
                    }
                }
            }
            "crash-log", "crash" -> {
                viewModelScope.launch {
                    val logContent = withContext(Dispatchers.IO) {
                        StartupDiagnostics.readLastCrashLogFile(appContext)
                    }
                    terminalEmulator.appendPlain("CRASH-LOG", logContent)
                }
            }
            "termux-cmd" -> {
                val command = _localhostState.value.pendingTermuxCommand.ifBlank {
                    _localhostState.value.activeStatus?.termuxBootstrapCommand
                        ?: LocalhostServerStatus.DEFAULT_TERMUX_CODE_SERVER_CMD
                }
                terminalEmulator.success("TERMUX", command)
            }
            else -> {
                terminalEmulator.warn("SHELL", "Unknown diagnostic command '$cmd'. Switch to the User Terminal tab to run OS shell commands.")
            }
        }
    }

    fun resolveEffectivePassword(profile: ServerProfile, prefs: AppPreferences): String {
        if (profile.passwordHint.isNotBlank()) return profile.passwordHint
        if (profile.runtimeKind == LocalhostRuntimeKind.CODE_SERVER_RUNTIME) {
            return runtimeManager.vsCodeRuntime.authPasswordForLogin()
        }
        return if (profile.useMasterAutoLogin) prefs.masterPasswordOrToken else ""
    }

    private fun termuxCommandForProfile(profile: ServerProfile, port: Int, slug: String): String {
        val password = profile.passwordHint
        return if (
            profile.runtimeKind == LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER &&
            password.matches(Regex("[a-f0-9]{48}"))
        ) {
            TermuxRunCommand.codeServerCommand(port, password, slug)
        } else {
            defaultTermuxCommandForPort(port)
        }
    }

    fun connectProfileInApp(
        profile: ServerProfile,
        prefs: AppPreferences,
        forceReload: Boolean = false,
        onReadyInApp: () -> Unit = {},
    ) {
        userManuallySelectedRemoteInSession = extractLocalhostPort(profile.url) == null
        isUserConnectionInProgress = true
        activeConnectJob?.cancel()
        geminiBootstrapJob?.cancel()
        activeConnectJob = viewModelScope.launch {
            connectionMutex.withLock {
                try {
                    val previousActiveId = activeProfile.value?.id
                    connectProfileInternal(
                        profile = profile,
                        prefs = prefs,
                        forceReload = forceReload,
                        previousActiveId = previousActiveId,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "connectProfileInApp warning", t)
                    val errText = t.localizedMessage ?: t.javaClass.simpleName
                    terminalEmulator.error("CONNECT", "Connection error: $errText")
                    StartupDiagnostics.recordNonFatal(appContext, "connectProfileInApp", t)
                    webController.reportConnectionError(errText)
                } finally {
                    isUserConnectionInProgress = false
                }
                onReadyInApp()
            }
        }
    }

    private suspend fun connectProfileInternal(
        profile: ServerProfile,
        prefs: AppPreferences,
        forceReload: Boolean,
        previousActiveId: Long?,
    ): Boolean {
        val localPort = extractLocalhostPort(profile.url)
        if (localPort == null) {
            CodeServerWatchdogService.stopMonitoring(appContext)
            _localhostState.update {
                it.copy(
                    isProvisioning = false,
                    provisioningState = WorkspaceProvisioningState.READY,
                    runtimeKind = LocalhostRuntimeKind.REMOTE_CLOUD_IDE,
                    activeStatus = null,
                    draftProfileId = null,
                    errorMessage = null,
                    canOfferOfflineFallback = false,
                    statusMessage = "Remote session (${profile.name})",
                )
            }
            profileRepository.activateProfile(profile.id)
            if (profile.provisioningState != WorkspaceProvisioningState.READY) {
                profileRepository.updateProvisioningState(profile.id, WorkspaceProvisioningState.READY, "")
            }
            webController.loadProfileUrl(
                profileId = profile.id,
                rawUrl = profile.url,
                desktopMode = profile.useDesktopUserAgent,
                autoLoginPassword = resolveEffectivePassword(profile, prefs),
                forceReload = forceReload,
            )
            return true
        }

        val slug = profile.workspaceSlug.ifBlank { workspaceSlugFor(profile.id, profile.name) }
        val termuxCmd = termuxCommandForProfile(profile, localPort, slug)
        val workspaceDir = withContext(Dispatchers.IO) {
            runtimeManager.switchWorkspace(slug, "connect-profile")
        }
        runtimeManager.userTerminal.syncWorkspaceDirectory(workspaceDir, slug)
        _memorySnapshot.value = runtimeManager.checkMemoryBudget()

        if (profile.runtimeKind == LocalhostRuntimeKind.CODE_SERVER_RUNTIME) {
            _localhostState.update {
                it.copy(
                    isProvisioning = true,
                    provisioningState = WorkspaceProvisioningState.STARTING,
                    runtimeKind = LocalhostRuntimeKind.CODE_SERVER_RUNTIME,
                    draftProfileId = profile.id,
                    pendingTargetPort = localPort,
                    pendingTermuxCommand = termuxCmd,
                    termuxInstalled = isTermuxInstalled(appContext),
                    errorMessage = null,
                    isMissingIdeBinary = false,
                    canOfferOfflineFallback = false,
                    statusMessage = "Starting built-in VS Code runtime on 127.0.0.1:$localPort...",
                )
            }
            val rtStatus = runtimeManager.vsCodeRuntime.startRuntime(
                workspaceDir = workspaceDir,
                workspaceSlug = slug,
                requestedPort = localPort,
                allowExternalLoopbackAttach = true,
                allowPortFallback = true,
            )
            return if (rtStatus.isRunning) {
                val actualPort = rtStatus.port ?: localPort
                val committed = profileRepository.commitReadyProfile(
                    profileId = profile.id,
                    actualUrl = rtStatus.url,
                    actualSlug = slug,
                    runtimeKind = LocalhostRuntimeKind.CODE_SERVER_RUNTIME,
                )
                val status = LocalhostServerStatus(
                    isRunning = true,
                    port = actualPort,
                    url = rtStatus.url,
                    isEmbeddedServer = false,
                    runtimeKind = LocalhostRuntimeKind.CODE_SERVER_RUNTIME,
                    sourceLabel = rtStatus.statusMessage,
                    workspaceDirPath = workspaceDir.absolutePath,
                    termuxBootstrapCommand = termuxCmd,
                )
                _localhostState.update {
                    it.copy(
                        isProvisioning = false,
                        provisioningState = WorkspaceProvisioningState.READY,
                        runtimeKind = LocalhostRuntimeKind.CODE_SERVER_RUNTIME,
                        activeStatus = status,
                        draftProfileId = null,
                        errorMessage = null,
                        isMissingIdeBinary = false,
                        canOfferOfflineFallback = false,
                        statusMessage = rtStatus.statusMessage,
                    )
                }
                syncKeepAliveService(prefs, committed.name)
                webController.loadProfileUrl(
                    profileId = committed.id,
                    rawUrl = rtStatus.url,
                    desktopMode = committed.useDesktopUserAgent,
                    autoLoginPassword = resolveEffectivePassword(committed, prefs),
                    forceReload = forceReload,
                )
                true
            } else {
                val err = rtStatus.errorMessage ?: rtStatus.statusMessage
                profileRepository.rollbackDraftProfile(
                    draftId = profile.id,
                    previousActiveId = previousActiveId,
                    deleteDraft = false,
                    failureState = WorkspaceProvisioningState.FAILED,
                    errorMessage = err,
                )
                _localhostState.update {
                    it.copy(
                        isProvisioning = false,
                        provisioningState = WorkspaceProvisioningState.FAILED,
                        runtimeKind = LocalhostRuntimeKind.CODE_SERVER_RUNTIME,
                        draftProfileId = profile.id,
                        pendingTargetPort = localPort,
                        pendingTermuxCommand = termuxCmd,
                        errorMessage = err,
                        statusMessage = rtStatus.statusMessage,
                        isMissingIdeBinary = rtStatus.backendKind == VsCodeIdeBackendKind.MISSING_BINARY,
                        canOfferOfflineFallback = true,
                    )
                }
                webController.reportConnectionError(err)
                false
            }
        }

        if (profile.runtimeKind == LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER) {
            _localhostState.update {
                it.copy(
                    isProvisioning = true,
                    provisioningState = WorkspaceProvisioningState.VERIFYING,
                    runtimeKind = LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER,
                    draftProfileId = profile.id,
                    pendingTargetPort = localPort,
                    pendingTermuxCommand = termuxCmd,
                    termuxInstalled = isTermuxInstalled(appContext),
                    errorMessage = null,
                    isMissingIdeBinary = false,
                    canOfferOfflineFallback = false,
                    statusMessage = "Verifying Termux code-server on 127.0.0.1:$localPort...",
                )
            }
            val probe = localhostEngine.probeHttpIdentity(localPort)
            return if (probe.kind == PortProbeKind.EXTERNAL_HTTP_SERVER) {
                val url = "http://127.0.0.1:$localPort"
                val committed = profileRepository.commitReadyProfile(
                    profileId = profile.id,
                    actualUrl = url,
                    actualSlug = slug,
                    runtimeKind = LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER,
                )
                val status = LocalhostServerStatus(
                    isRunning = true,
                    port = localPort,
                    url = url,
                    isEmbeddedServer = false,
                    runtimeKind = LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER,
                    sourceLabel = "Termux / External Server (127.0.0.1:$localPort)",
                    workspaceDirPath = workspaceDir.absolutePath,
                    termuxBootstrapCommand = termuxCmd,
                )
                _localhostState.update {
                    it.copy(
                        isProvisioning = false,
                        provisioningState = WorkspaceProvisioningState.READY,
                        runtimeKind = LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER,
                        activeStatus = status,
                        draftProfileId = null,
                        errorMessage = null,
                        isMissingIdeBinary = false,
                        canOfferOfflineFallback = false,
                        statusMessage = status.sourceLabel,
                    )
                }
                if (prefs.autoRebootWatchdogEnabled) {
                    armWatchdog(status, committed.name, slug)
                }
                syncKeepAliveService(prefs, committed.name)
                webController.loadProfileUrl(
                    profileId = committed.id,
                    rawUrl = url,
                    desktopMode = committed.useDesktopUserAgent,
                    autoLoginPassword = resolveEffectivePassword(committed, prefs),
                    forceReload = forceReload,
                )
                true
            } else {
                val installed = isTermuxInstalled(appContext)
                val waitMsg = if (installed) {
                    "Termux code-server is not responding with HTTP on 127.0.0.1:$localPort yet. Start it in Termux, then tap Verify & Connect."
                } else {
                    "Termux (com.termux) is not installed on this device. Install Termux and start code-server on :$localPort, or launch the Fallback File Viewer instead."
                }
                profileRepository.updateProvisioningState(
                    profileId = profile.id,
                    state = WorkspaceProvisioningState.WAITING_FOR_INSTALLATION,
                    lastError = waitMsg,
                )
                _localhostState.update {
                    it.copy(
                        isProvisioning = false,
                        provisioningState = WorkspaceProvisioningState.WAITING_FOR_INSTALLATION,
                        runtimeKind = LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER,
                        draftProfileId = profile.id,
                        pendingTargetPort = localPort,
                        pendingTermuxCommand = termuxCmd,
                        termuxInstalled = installed,
                        errorMessage = null,
                        isMissingIdeBinary = false,
                        geminiNotice = waitMsg,
                        canOfferOfflineFallback = true,
                        statusMessage = "Waiting for Termux code-server on :$localPort",
                    )
                }
                false
            }
        }

        // Fallback Local File Viewer profile reopen (preserves existing workspace files on disk)
        return connectLocalEmbeddedWorkspace(
            profile = profile,
            port = localPort,
            slug = slug,
            termuxCommand = termuxCmd,
            prefs = prefs,
            forceReload = forceReload,
            allowExternalAttach = true,
            previousActiveId = previousActiveId,
        )
    }

    private suspend fun connectLocalEmbeddedWorkspace(
        profile: ServerProfile,
        port: Int,
        slug: String,
        termuxCommand: String,
        prefs: AppPreferences,
        forceReload: Boolean,
        allowExternalAttach: Boolean,
        previousActiveId: Long?,
    ): Boolean {
        profileRepository.updateProvisioningState(profile.id, WorkspaceProvisioningState.STARTING, "")
        _localhostState.update {
            it.copy(
                isProvisioning = true,
                provisioningState = WorkspaceProvisioningState.STARTING,
                runtimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
                draftProfileId = profile.id,
                pendingTargetPort = port,
                pendingTermuxCommand = termuxCommand,
                errorMessage = null,
                isMissingIdeBinary = false,
                canOfferOfflineFallback = false,
                statusMessage = "Checking & starting AI-managed local server on 127.0.0.1:$port...",
            )
        }

        val snap = if (allowExternalAttach) {
            runtimeManager.aiRuntime.prepareAndStartWorkspace(
                workspaceName = profile.name,
                workspaceSlug = slug,
                requestedPort = port,
                termuxCommand = termuxCommand,
                overwriteStarterFiles = false,
                preferGenuineCodeServer = false,
                allowFallbackViewer = true,
            )
        } else {
            val localStatus = localhostEngine.ensureLocalhostReady(
                requestedPort = port,
                workspaceName = profile.name,
                workspaceSlug = slug,
                termuxCommand = termuxCommand,
                allowExternalAttach = false,
                allowPortFallback = true,
                overwriteStarterFiles = false,
            )
            if (localStatus.isRunning && localhostEngine.probeLocalhostHttp(localStatus.port)) {
                AiManagedRuntimeSnapshot(
                    state = RuntimeLifecycleState.READY,
                    backendMode = AiManagedBackendMode.FALLBACK_LOCAL_VIEWER,
                    workspaceSlug = slug,
                    workspaceDirPath = localStatus.workspaceDirPath,
                    port = localStatus.port,
                    url = localStatus.url,
                    isRunning = true,
                    reusedExistingInstance = false,
                    isVerifiedHealthy = true,
                    statusMessage = localStatus.sourceLabel,
                )
            } else {
                AiManagedRuntimeSnapshot(
                    state = RuntimeLifecycleState.FAILED,
                    backendMode = AiManagedBackendMode.UNAVAILABLE,
                    workspaceSlug = slug,
                    workspaceDirPath = localStatus.workspaceDirPath,
                    port = port,
                    statusMessage = localStatus.failureDetail.ifBlank { "Unable to verify local server on port $port" },
                    errorMessage = localStatus.failureDetail.ifBlank { "Unable to bind built-in local editor on port $port" },
                )
            }
        }

        if (!snap.isRunning || !snap.isVerifiedHealthy) {
            val err = snap.errorMessage ?: snap.statusMessage
            profileRepository.rollbackDraftProfile(
                draftId = profile.id,
                previousActiveId = previousActiveId,
                deleteDraft = false,
                failureState = WorkspaceProvisioningState.FAILED,
                errorMessage = err,
            )
            _localhostState.update {
                it.copy(
                    isProvisioning = false,
                    provisioningState = WorkspaceProvisioningState.FAILED,
                    statusMessage = err,
                    errorMessage = err,
                    draftProfileId = profile.id,
                    canOfferOfflineFallback = false,
                )
            }
            webController.reportConnectionError(err)
            return false
        }

        val isEmbedded = snap.backendMode == AiManagedBackendMode.FALLBACK_LOCAL_VIEWER
        val resolvedKind = if (isEmbedded) {
            LocalhostRuntimeKind.EMBEDDED_EDITOR
        } else {
            LocalhostRuntimeKind.CODE_SERVER_RUNTIME
        }
        val status = LocalhostServerStatus(
            isRunning = true,
            port = snap.port ?: port,
            url = snap.url,
            isEmbeddedServer = isEmbedded,
            runtimeKind = resolvedKind,
            sourceLabel = snap.statusMessage,
            workspaceDirPath = snap.workspaceDirPath,
            termuxBootstrapCommand = termuxCommand,
        )

        val committed = profileRepository.commitReadyProfile(
            profileId = profile.id,
            actualUrl = status.url,
            actualSlug = slug,
            runtimeKind = resolvedKind,
        )
        _localhostState.update {
            it.copy(
                isProvisioning = false,
                provisioningState = WorkspaceProvisioningState.READY,
                runtimeKind = resolvedKind,
                statusMessage = status.sourceLabel,
                activeStatus = status,
                draftProfileId = null,
                errorMessage = null,
                isMissingIdeBinary = false,
                canOfferOfflineFallback = false,
            )
        }
        if (prefs.autoRebootWatchdogEnabled) {
            armWatchdog(status, committed.name, slug)
        } else {
            CodeServerWatchdogService.stopMonitoring(appContext)
        }
        syncKeepAliveService(prefs, committed.name)
        webController.loadProfileUrl(
            profileId = committed.id,
            rawUrl = status.url,
            desktopMode = committed.useDesktopUserAgent,
            autoLoginPassword = resolveEffectivePassword(committed, prefs),
            forceReload = forceReload || !snap.reusedExistingInstance,
        )
        return true
    }

    fun saveAndConnectInApp(
        existingId: Long,
        name: String,
        url: String,
        mode: ConnectionMode,
        passwordHint: String,
        useDesktopUa: Boolean,
        autoConnect: Boolean,
        useMasterAutoLogin: Boolean,
        prefs: AppPreferences,
        onReadyInApp: () -> Unit = {},
    ) {
        val cleanUrl = url.trim()
        val isLocal = extractLocalhostPort(cleanUrl) != null
        userManuallySelectedRemoteInSession = !isLocal
        isUserConnectionInProgress = true
        activeConnectJob?.cancel()
        activeConnectJob = viewModelScope.launch {
            connectionMutex.withLock {
                try {
                    val previousActiveId = activeProfile.value?.id
                    val existing = if (existingId > 0L) profileRepository.getById(existingId) else null
                    val runtimeKind = when {
                        !isLocal -> LocalhostRuntimeKind.REMOTE_CLOUD_IDE
                        existing?.runtimeKind in setOf(
                            LocalhostRuntimeKind.CODE_SERVER_RUNTIME,
                            LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER,
                            LocalhostRuntimeKind.EMBEDDED_EDITOR,
                        ) -> existing!!.runtimeKind
                        else -> LocalhostRuntimeKind.EMBEDDED_EDITOR
                    }
                    val cleanName = name.trim().ifBlank { mode.shortLabel }
                    val slug = if (isLocal) {
                        existing?.workspaceSlug?.takeIf { it.isNotBlank() } ?: buildUniqueWorkspaceSlug(cleanName)
                    } else {
                        ""
                    }
                    val saved = profileRepository.saveAndActivate(
                        ServerProfile(
                            id = existingId,
                            name = cleanName,
                            url = cleanUrl,
                            connectionMode = mode,
                            passwordHint = passwordHint,
                            useDesktopUserAgent = useDesktopUa,
                            autoConnectOnLaunch = autoConnect,
                            useMasterAutoLogin = useMasterAutoLogin,
                            isActive = true,
                            provisioningState = if (isLocal) {
                                WorkspaceProvisioningState.STARTING
                            } else {
                                WorkspaceProvisioningState.READY
                            },
                            runtimeKind = runtimeKind,
                            workspaceSlug = slug,
                        ),
                    )
                    connectProfileInternal(
                        profile = saved,
                        prefs = prefs,
                        forceReload = true,
                        previousActiveId = previousActiveId,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "saveAndConnectInApp warning", t)
                    val errText = t.localizedMessage ?: t.javaClass.simpleName
                    terminalEmulator.error("CONNECT", "Save & launch error: $errText")
                    StartupDiagnostics.recordNonFatal(appContext, "saveAndConnectInApp", t)
                    webController.reportConnectionError(errText)
                } finally {
                    isUserConnectionInProgress = false
                }
                onReadyInApp()
            }
        }
    }

    /**
     * Truthful, transactional workspace provisioning flow:
     * 1. Creates an inactive draft profile (`GENERATING`).
     * 2. Calls Gemini to generate starter files & Termux script. Failures are surfaced honestly
     *    (`FAILED`) and rolled back without registering an active READY workspace.
     * 3. Writes starter files with `overwriteExisting = false` (never clobbering user files).
     * 4. For `EXTERNAL_TERMUX_CODE_SERVER`: verifies real external HTTP code-server, or transitions
     *    to `WAITING_FOR_INSTALLATION` requesting user interaction in Termux.
     * 5. For `EMBEDDED_EDITOR`: starts embedded HTTP server, runs live `/api/info` HTTP verification,
     *    and only then commits `READY` + active session with the actual bound port URL.
     */
    fun createAndStartTermuxLocalhost(
        workspaceName: String,
        port: Int,
        stackHint: String,
        runtimeKind: LocalhostRuntimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
        prefs: AppPreferences,
        existingDraftId: Long? = null,
        generateWithAi: Boolean = true,
        onLaunchedInApp: () -> Unit = {},
    ) {
        val curState = _localhostState.value.provisioningState
        if (_localhostState.value.isProvisioning ||
            curState in setOf(
                WorkspaceProvisioningState.GENERATING,
                WorkspaceProvisioningState.STARTING,
                WorkspaceProvisioningState.VERIFYING,
                WorkspaceProvisioningState.INSTALLING,
            )
        ) {
            return
        }

        val targetPort = port.coerceIn(1024, 65535)
        val cleanName = workspaceName.trim().ifBlank {
            when (runtimeKind) {
                LocalhostRuntimeKind.CODE_SERVER_RUNTIME -> "VS Code Workspace :$targetPort"
                LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER -> "Termux code-server :$targetPort"
                else -> "Local Workspace :$targetPort"
            }
        }
        val cleanStack = stackHint.ifBlank { "HTML / Node.js / code-server" }
        val termuxInstalled = isTermuxInstalled(appContext)

        userManuallySelectedRemoteInSession = false
        hasHandledCurrentEntry = true
        isUserConnectionInProgress = true
        activeConnectJob?.cancel()
        geminiBootstrapJob?.cancel()

        activeConnectJob = viewModelScope.launch {
            connectionMutex.withLock {
                var draftId: Long = 0L
                var previousActiveId: Long? = null
                try {
                    _memorySnapshot.value = runtimeManager.checkMemoryBudget()
                    previousActiveId = activeProfile.value?.id
                    val existingDraft = existingDraftId?.let { profileRepository.getById(it) }
                    val slug = existingDraft?.workspaceSlug?.ifBlank { buildUniqueWorkspaceSlug(cleanName) }
                        ?: buildUniqueWorkspaceSlug(cleanName)
                    val termuxPassword = if (runtimeKind == LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER) {
                        TermuxRunCommand.generatePassword()
                    } else {
                        ""
                    }
                    val defaultCmd = if (termuxPassword.isNotBlank()) {
                        TermuxRunCommand.codeServerCommand(targetPort, termuxPassword, slug)
                    } else {
                        defaultTermuxCommandForPort(targetPort)
                    }

                    val draft = if (existingDraft != null) {
                        profileRepository.updateProvisioningState(
                            profileId = existingDraft.id,
                            state = WorkspaceProvisioningState.GENERATING,
                            lastError = "",
                        )
                        profileRepository.updateProfile(existingDraft.copy(
                            name = cleanName,
                            url = "http://127.0.0.1:$targetPort",
                            passwordHint = termuxPassword.ifBlank { existingDraft.passwordHint },
                            runtimeKind = runtimeKind,
                            workspaceSlug = slug,
                            provisioningState = WorkspaceProvisioningState.GENERATING,
                            lastError = "",
                        ))
                    } else {
                        profileRepository.createDraftProfile(
                            ServerProfile(
                                name = cleanName,
                                url = "http://127.0.0.1:$targetPort",
                                connectionMode = ConnectionMode.DIRECT_CODE_SERVER,
                                passwordHint = termuxPassword,
                                useDesktopUserAgent = false,
                                autoConnectOnLaunch = true,
                                useMasterAutoLogin = true,
                                isPinned = true,
                                isActive = false,
                                provisioningState = WorkspaceProvisioningState.GENERATING,
                                runtimeKind = runtimeKind,
                                workspaceSlug = slug,
                            ),
                        )
                    }
                    draftId = draft.id

                    terminalEmulator.info(
                        "LOCALHOST",
                        if (generateWithAi) {
                            "Generating starter files for \"$cleanName\" ($cleanStack)..."
                        } else {
                            "Initializing local workspace \"$cleanName\" ($cleanStack)..."
                        },
                    )
                    StartupDiagnostics.updateLastKnownState(
                        appContext,
                        "Provisioning draft #$draftId ($cleanName) [$runtimeKind]",
                    )
                    _localhostState.update {
                        it.copy(
                            isProvisioning = true,
                            provisioningState = WorkspaceProvisioningState.GENERATING,
                            runtimeKind = runtimeKind,
                            draftProfileId = draftId,
                            termuxInstalled = termuxInstalled,
                            pendingTargetPort = targetPort,
                            pendingTermuxCommand = defaultCmd,
                            canOfferOfflineFallback = false,
                            isMissingIdeBinary = false,
                            statusMessage = if (generateWithAi) {
                                "Generating workspace files with Gemini..."
                            } else {
                                "Preparing workspace files..."
                            },
                            geminiNotice = null,
                            errorMessage = null,
                        )
                    }

                    // Step 1: Optional Gemini bootstrap request or deterministic blank-workspace starter
                    val generatedPlan = if (generateWithAi) {
                        val geminiResult = withContext(Dispatchers.IO) {
                            geminiService.generateLocalhostBootstrap(
                                workspaceName = cleanName,
                                port = targetPort,
                                stackHint = cleanStack,
                            )
                        }
                        geminiResult.getOrElse { err ->
                            val classified = GeminiChatService.classifyGeminiFailure(err)
                            val errMsg = classified.message ?: "Gemini bootstrap failed (${classified.reason.name})"
                            terminalEmulator.error(
                                "GEMINI",
                                "Bootstrap failed [${classified.reason}]: $errMsg",
                            )
                            profileRepository.rollbackDraftProfile(
                                draftId = draftId,
                                previousActiveId = previousActiveId,
                                deleteDraft = false,
                                failureState = WorkspaceProvisioningState.FAILED,
                                errorMessage = errMsg,
                            )
                            _localhostState.update {
                                it.copy(
                                    isProvisioning = false,
                                    provisioningState = WorkspaceProvisioningState.FAILED,
                                    runtimeKind = runtimeKind,
                                    draftProfileId = draftId,
                                    termuxInstalled = termuxInstalled,
                                    pendingTargetPort = targetPort,
                                    pendingTermuxCommand = defaultCmd,
                                    canOfferOfflineFallback = true,
                                    isMissingIdeBinary = false,
                                    statusMessage = "Gemini generation failed (${classified.reason.name})",
                                    geminiNotice = null,
                                    errorMessage = errMsg,
                                )
                            }
                            return@withLock
                        }
                    } else {
                        buildDeterministicStarterPlan(cleanName, targetPort, cleanStack, defaultCmd)
                    }
                    val starterFiles = generatedPlan.starterFiles - "start-termux-codeserver.sh"
                    val termuxCommand = if (termuxPassword.isNotBlank()) {
                        TermuxRunCommand.codeServerCommand(
                            port = targetPort,
                            password = termuxPassword,
                            workspaceSlug = slug,
                            starterFiles = starterFiles,
                        )
                    } else {
                        ""
                    }
                    val plan = generatedPlan.copy(
                        termuxCommand = termuxCommand,
                        starterFiles = starterFiles,
                    )

                    // Step 2: Persist starter files inside unified SharedWorkspaceFilesystem & sync User Terminal
                    val workspaceDir = withContext(Dispatchers.IO) {
                        runtimeManager.switchWorkspace(slug, "create-workspace")
                    }
                    runtimeManager.userTerminal.syncWorkspaceDirectory(workspaceDir, slug)

                    val writtenCount = try {
                        withContext(Dispatchers.IO) {
                            runtimeManager.sharedFilesystem.writeFiles(
                                workspaceDir = workspaceDir,
                                files = plan.starterFiles,
                                overwriteExisting = false,
                                source = if (generateWithAi) "gemini-bootstrap" else "workspace-init",
                            )
                        }
                    } catch (ioErr: Throwable) {
                        val fileErr = GeminiBootstrapException(
                            GeminiFailureReason.FILE_WRITE_FAILURE,
                            "File write failure in ${workspaceDir.name}: ${ioErr.localizedMessage ?: ioErr.javaClass.simpleName}",
                            ioErr,
                        )
                        terminalEmulator.error("FILES", fileErr.message ?: "File write failure")
                        profileRepository.rollbackDraftProfile(
                            draftId = draftId,
                            previousActiveId = previousActiveId,
                            deleteDraft = false,
                            failureState = WorkspaceProvisioningState.FAILED,
                            errorMessage = fileErr.message.orEmpty(),
                        )
                        _localhostState.update {
                            it.copy(
                                isProvisioning = false,
                                provisioningState = WorkspaceProvisioningState.FAILED,
                                draftProfileId = draftId,
                                errorMessage = fileErr.message,
                                statusMessage = "Failed writing starter files",
                                canOfferOfflineFallback = true,
                            )
                        }
                        return@withLock
                    }

                    // Step 2b: Validate and execute optional setupCommand via User Terminal with exit verification
                    var setupNotice: String? = null
                    if (plan.setupCommand.isNotBlank()) {
                        val safety = CommandSafetyValidator.validate(plan.setupCommand)
                        when (safety.decision) {
                            CommandSafetyDecision.BLOCKED -> {
                                val msg = "Blocked unsafe setup command: ${safety.reason}"
                                terminalEmulator.warn("SECURITY", msg)
                                setupNotice = msg
                            }
                            CommandSafetyDecision.REQUIRES_CONFIRMATION -> {
                                runtimeManager.userTerminal.sendCommand(
                                    rawCommand = plan.setupCommand,
                                    bypassConfirmation = false,
                                    isSetupStep = true,
                                )
                                setupNotice = "Setup command requires confirmation in User Terminal."
                                terminalEmulator.warn("CONFIRM", setupNotice)
                            }
                            CommandSafetyDecision.ALLOWED -> {
                                val execRes = runtimeManager.userTerminal.executeCommandAndAwaitExit(
                                    rawCommand = plan.setupCommand,
                                    timeoutMs = 8_000L,
                                    bypassConfirmation = false,
                                    isSetupStep = true,
                                )
                                if (!execRes.succeeded) {
                                    setupNotice = execRes.errorMessage ?: "Setup command did not succeed"
                                    terminalEmulator.warn("USER-TERM", "Setup step warning: $setupNotice")
                                }
                            }
                        }
                    }

                    profileRepository.updateProvisioningState(
                        profileId = draftId,
                        state = WorkspaceProvisioningState.GENERATED,
                        lastError = "",
                    )
                    terminalEmulator.success(
                        if (generateWithAi) "GEMINI" else "WORKSPACE",
                        "Prepared ${plan.starterFiles.size} starter file(s) ($writtenCount new): ${plan.summary}",
                    )
                    val combinedNotice = listOfNotNull(plan.summary.takeIf { it.isNotBlank() }, setupNotice)
                        .joinToString(" • ")
                        .ifBlank { plan.summary }
                    _localhostState.update {
                        it.copy(
                            isProvisioning = true,
                            provisioningState = WorkspaceProvisioningState.GENERATED,
                            pendingTermuxCommand = plan.termuxCommand,
                            geminiNotice = combinedNotice,
                            statusMessage = "Starter files written ($writtenCount new)...",
                        )
                    }

                    // Step 3: Execute inside the single AI-managed runtime (or wait for external Termux)
                    if (runtimeKind == LocalhostRuntimeKind.CODE_SERVER_RUNTIME) {
                        _localhostState.update {
                            it.copy(
                                isProvisioning = true,
                                provisioningState = WorkspaceProvisioningState.STARTING,
                                statusMessage = "Starting AI-managed code-server runtime on 127.0.0.1:$targetPort...",
                            )
                        }
                        val snap = runtimeManager.aiRuntime.prepareAndStartWorkspace(
                            workspaceName = cleanName,
                            workspaceSlug = slug,
                            requestedPort = targetPort,
                            starterFiles = plan.starterFiles,
                            setupCommand = "",
                            termuxCommand = plan.termuxCommand,
                            overwriteStarterFiles = false,
                            preferGenuineCodeServer = true,
                            allowFallbackViewer = false,
                        )
                        if (snap.isRunning && snap.isVerifiedHealthy) {
                            val committed = profileRepository.commitReadyProfile(
                                profileId = draftId,
                                actualUrl = snap.url,
                                actualSlug = slug,
                                runtimeKind = LocalhostRuntimeKind.CODE_SERVER_RUNTIME,
                            )
                            val readyStatus = LocalhostServerStatus(
                                isRunning = true,
                                port = snap.port ?: targetPort,
                                url = snap.url,
                                isEmbeddedServer = false,
                                runtimeKind = LocalhostRuntimeKind.CODE_SERVER_RUNTIME,
                                sourceLabel = snap.statusMessage,
                                workspaceDirPath = snap.workspaceDirPath.ifBlank { workspaceDir.absolutePath },
                                termuxBootstrapCommand = plan.termuxCommand,
                            )
                            _localhostState.update {
                                it.copy(
                                    isProvisioning = false,
                                    provisioningState = WorkspaceProvisioningState.READY,
                                    runtimeKind = LocalhostRuntimeKind.CODE_SERVER_RUNTIME,
                                    activeStatus = readyStatus,
                                    draftProfileId = null,
                                    errorMessage = null,
                                    isMissingIdeBinary = false,
                                    canOfferOfflineFallback = false,
                                    statusMessage = snap.statusMessage,
                                    geminiNotice = plan.summary,
                                )
                            }
                            if (prefs.autoRebootWatchdogEnabled) {
                                armWatchdog(readyStatus, cleanName, slug)
                            } else {
                                CodeServerWatchdogService.stopMonitoring(appContext)
                            }
                            syncKeepAliveService(prefs, committed.name)
                            webController.loadProfileUrl(
                                profileId = committed.id,
                                rawUrl = snap.url,
                                desktopMode = false,
                                autoLoginPassword = resolveEffectivePassword(committed, prefs),
                                forceReload = true,
                            )
                            onLaunchedInApp()
                        } else {
                            val err = snap.errorMessage ?: snap.statusMessage
                            profileRepository.rollbackDraftProfile(
                                draftId = draftId,
                                previousActiveId = previousActiveId,
                                deleteDraft = false,
                                failureState = WorkspaceProvisioningState.FAILED,
                                errorMessage = err,
                            )
                            _localhostState.update {
                                it.copy(
                                    isProvisioning = false,
                                    provisioningState = WorkspaceProvisioningState.FAILED,
                                    runtimeKind = LocalhostRuntimeKind.CODE_SERVER_RUNTIME,
                                    draftProfileId = draftId,
                                    termuxInstalled = termuxInstalled,
                                    pendingTargetPort = targetPort,
                                    pendingTermuxCommand = plan.termuxCommand,
                                    canOfferOfflineFallback = true,
                                    isMissingIdeBinary = runtimeManager.vsCodeRuntime.status.value.backendKind == VsCodeIdeBackendKind.MISSING_BINARY,
                                    statusMessage = snap.statusMessage,
                                    geminiNotice = plan.summary,
                                    errorMessage = err,
                                )
                            }
                        }
                        return@withLock
                    }

                    if (runtimeKind == LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER) {
                        _localhostState.update {
                            it.copy(
                                isProvisioning = true,
                                provisioningState = WorkspaceProvisioningState.VERIFYING,
                                statusMessage = "Probing 127.0.0.1:$targetPort for running Termux code-server...",
                            )
                        }
                        var probe = localhostEngine.probeHttpIdentity(targetPort)
                        var autoStartWarning: String? = null
                        if (probe.kind != PortProbeKind.EXTERNAL_HTTP_SERVER && termuxInstalled) {
                            try {
                                TermuxRunCommand.startInBackground(appContext, plan.termuxCommand)
                                _localhostState.update {
                                    it.copy(statusMessage = "Installing Termux code-server and waiting for 127.0.0.1:$targetPort...")
                                }
                                val started = withTimeoutOrNull(120_000L) {
                                    while (!localhostEngine.isPortAcceptingConnections(targetPort)) {
                                        delay(1_000L)
                                    }
                                    true
                                } == true
                                if (!started) {
                                    autoStartWarning =
                                        "Termux accepted setup but code-server did not become ready within two minutes."
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Log.w(TAG, "Termux automatic setup was not accepted", error)
                                autoStartWarning =
                                    "Termux could not start automatically. Enable allow-external-apps and grant Run Commands in Termux, then use the displayed command."
                            }
                            probe = localhostEngine.probeHttpIdentity(targetPort)
                        }
                        if (probe.kind == PortProbeKind.EXTERNAL_HTTP_SERVER) {
                            val url = "http://127.0.0.1:$targetPort"
                            val committed = profileRepository.commitReadyProfile(
                                profileId = draftId,
                                actualUrl = url,
                                actualSlug = slug,
                                runtimeKind = LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER,
                            )
                            val extStatus = LocalhostServerStatus(
                                isRunning = true,
                                port = targetPort,
                                url = url,
                                isEmbeddedServer = false,
                                runtimeKind = LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER,
                                sourceLabel = "Termux / External HTTP Server (127.0.0.1:$targetPort)",
                                workspaceDirPath = workspaceDir.absolutePath,
                                termuxBootstrapCommand = plan.termuxCommand,
                            )
                            _localhostState.update {
                                it.copy(
                                    isProvisioning = false,
                                    provisioningState = WorkspaceProvisioningState.READY,
                                    runtimeKind = LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER,
                                    activeStatus = extStatus,
                                    draftProfileId = null,
                                    errorMessage = null,
                                    isMissingIdeBinary = false,
                                    canOfferOfflineFallback = false,
                                    statusMessage = extStatus.sourceLabel,
                                    geminiNotice = plan.summary,
                                )
                            }
                            if (prefs.autoRebootWatchdogEnabled) {
                                armWatchdog(extStatus, cleanName, slug)
                            } else {
                                CodeServerWatchdogService.stopMonitoring(appContext)
                            }
                            syncKeepAliveService(prefs, committed.name)
                            webController.loadProfileUrl(
                                profileId = committed.id,
                                rawUrl = url,
                                desktopMode = false,
                                autoLoginPassword = resolveEffectivePassword(committed, prefs),
                                forceReload = true,
                            )
                            onLaunchedInApp()
                        } else {
                            val waitReason = autoStartWarning ?: if (termuxInstalled) {
                                "Termux code-server is not running on 127.0.0.1:$targetPort yet. Open Termux, run the displayed password-protected command, then tap Verify & Connect."
                            } else {
                                "Termux (com.termux) is not installed on this device. Install Termux and run the displayed password-protected command, or start the Fallback Local File Viewer instead."
                            }
                            terminalEmulator.warn("TERMUX", waitReason)
                            profileRepository.rollbackDraftProfile(
                                draftId = draftId,
                                previousActiveId = previousActiveId,
                                deleteDraft = false,
                                failureState = WorkspaceProvisioningState.WAITING_FOR_INSTALLATION,
                                errorMessage = waitReason,
                            )
                            _localhostState.update {
                                it.copy(
                                    isProvisioning = false,
                                    provisioningState = WorkspaceProvisioningState.WAITING_FOR_INSTALLATION,
                                    runtimeKind = LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER,
                                    draftProfileId = draftId,
                                    termuxInstalled = termuxInstalled,
                                    pendingTargetPort = targetPort,
                                    pendingTermuxCommand = plan.termuxCommand,
                                    canOfferOfflineFallback = true,
                                    isMissingIdeBinary = false,
                                    statusMessage = "Waiting for Termux code-server on :$targetPort",
                                    geminiNotice = listOfNotNull(plan.summary, autoStartWarning).joinToString(" "),
                                    errorMessage = null,
                                )
                            }
                        }
                        return@withLock
                    }

                    // EMBEDDED_EDITOR: start and verify via the single AI-managed runtime coordinator
                    profileRepository.updateProvisioningState(
                        profileId = draftId,
                        state = WorkspaceProvisioningState.STARTING,
                        lastError = "",
                    )
                    _localhostState.update {
                        it.copy(
                            isProvisioning = true,
                            provisioningState = WorkspaceProvisioningState.STARTING,
                            statusMessage = "Starting & verifying AI-managed local workspace on 127.0.0.1:$targetPort...",
                        )
                    }

                    val snap = runtimeManager.aiRuntime.prepareAndStartWorkspace(
                        workspaceName = cleanName,
                        workspaceSlug = slug,
                        requestedPort = targetPort,
                        starterFiles = plan.starterFiles,
                        setupCommand = "",
                        termuxCommand = plan.termuxCommand,
                        overwriteStarterFiles = false,
                        preferGenuineCodeServer = false,
                        allowFallbackViewer = true,
                    )

                    if (!snap.isRunning || !snap.isVerifiedHealthy) {
                        val bindErr = snap.errorMessage?.ifBlank { snap.statusMessage }
                            ?: "AI-managed local workspace failed health verification on port $targetPort"
                        terminalEmulator.error("AI-RUNTIME", bindErr)
                        profileRepository.rollbackDraftProfile(
                            draftId = draftId,
                            previousActiveId = previousActiveId,
                            deleteDraft = false,
                            failureState = WorkspaceProvisioningState.FAILED,
                            errorMessage = bindErr,
                        )
                        _localhostState.update {
                            it.copy(
                                isProvisioning = false,
                                provisioningState = WorkspaceProvisioningState.FAILED,
                                draftProfileId = draftId,
                                errorMessage = bindErr,
                                statusMessage = bindErr,
                                canOfferOfflineFallback = false,
                            )
                        }
                        webController.reportConnectionError(bindErr)
                        return@withLock
                    }

                    val resolvedKind = when (snap.backendMode) {
                        AiManagedBackendMode.BUNDLED_CODE_SERVER -> LocalhostRuntimeKind.CODE_SERVER_RUNTIME
                        AiManagedBackendMode.EXTERNAL_CODE_SERVER -> LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER
                        else -> LocalhostRuntimeKind.EMBEDDED_EDITOR
                    }
                    val actualPort = snap.port ?: targetPort
                    val status = LocalhostServerStatus(
                        isRunning = true,
                        port = actualPort,
                        url = snap.url,
                        isEmbeddedServer = resolvedKind == LocalhostRuntimeKind.EMBEDDED_EDITOR,
                        runtimeKind = resolvedKind,
                        sourceLabel = snap.statusMessage,
                        workspaceDirPath = snap.workspaceDirPath.ifBlank { workspaceDir.absolutePath },
                        termuxBootstrapCommand = plan.termuxCommand,
                    )

                    val committed = profileRepository.commitReadyProfile(
                        profileId = draftId,
                        actualUrl = status.url,
                        actualSlug = slug,
                        runtimeKind = resolvedKind,
                    )

                    _localhostState.update {
                        it.copy(
                            isProvisioning = false,
                            provisioningState = WorkspaceProvisioningState.READY,
                            runtimeKind = resolvedKind,
                            statusMessage = status.sourceLabel,
                            activeStatus = status,
                            draftProfileId = null,
                            errorMessage = null,
                            isMissingIdeBinary = false,
                            canOfferOfflineFallback = false,
                            geminiNotice = plan.summary,
                        )
                    }

                    if (prefs.autoRebootWatchdogEnabled) {
                        armWatchdog(status, committed.name, slug)
                    } else {
                        CodeServerWatchdogService.stopMonitoring(appContext)
                    }
                    syncKeepAliveService(prefs, committed.name)
                    webController.loadProfileUrl(
                        profileId = committed.id,
                        rawUrl = status.url,
                        desktopMode = false,
                        autoLoginPassword = resolveEffectivePassword(committed, prefs),
                        forceReload = true,
                    )
                    onLaunchedInApp()
                } catch (ce: CancellationException) {
                    if (draftId > 0L) {
                        runCatching {
                            profileRepository.rollbackDraftProfile(
                                draftId = draftId,
                                previousActiveId = previousActiveId,
                                deleteDraft = false,
                                failureState = WorkspaceProvisioningState.STOPPED,
                                errorMessage = "Provisioning cancelled",
                            )
                        }
                    }
                    _localhostState.update {
                        it.copy(
                            isProvisioning = false,
                            provisioningState = WorkspaceProvisioningState.STOPPED,
                            statusMessage = "Provisioning cancelled",
                        )
                    }
                    throw ce
                } catch (t: Throwable) {
                    Log.e(TAG, "createAndStartTermuxLocalhost failed safely", t)
                    val errText = t.localizedMessage ?: t.javaClass.simpleName
                    terminalEmulator.error("LOCALHOST", "Provisioning error: $errText")
                    StartupDiagnostics.recordNonFatal(appContext, "createAndStartTermuxLocalhost", t)
                    if (draftId > 0L) {
                        runCatching {
                            profileRepository.rollbackDraftProfile(
                                draftId = draftId,
                                previousActiveId = previousActiveId,
                                deleteDraft = false,
                                failureState = WorkspaceProvisioningState.FAILED,
                                errorMessage = errText,
                            )
                        }
                    }
                    _localhostState.update {
                        it.copy(
                            isProvisioning = false,
                            provisioningState = WorkspaceProvisioningState.FAILED,
                            draftProfileId = if (draftId > 0L) draftId else it.draftProfileId,
                            statusMessage = errText,
                            errorMessage = errText,
                            canOfferOfflineFallback = true,
                        )
                    }
                    webController.reportConnectionError(errText)
                } finally {
                    isUserConnectionInProgress = false
                }
            }
        }
    }

    /**
     * Explicit user fallback when Gemini is unavailable or the user prefers launching
     * the built-in offline local editor instead of waiting for Termux installation.
     */
    fun startOfflineLocalEditorForDraft(
        prefs: AppPreferences,
        onReadyInApp: () -> Unit = {},
    ) {
        val cur = _localhostState.value
        val draftId = cur.draftProfileId
        val targetPort = cur.pendingTargetPort.coerceIn(1024, 65535)
        isUserConnectionInProgress = true
        activeConnectJob?.cancel()
        activeConnectJob = viewModelScope.launch {
            connectionMutex.withLock {
                try {
                    val previousActiveId = activeProfile.value?.id
                    val draft = draftId?.let { profileRepository.getById(it) }
                        ?: profileRepository.createDraftProfile(
                            ServerProfile(
                                name = "Local Workspace :$targetPort",
                                url = "http://127.0.0.1:$targetPort",
                                connectionMode = ConnectionMode.DIRECT_CODE_SERVER,
                                useDesktopUserAgent = false,
                                autoConnectOnLaunch = true,
                                useMasterAutoLogin = true,
                                isPinned = true,
                                isActive = false,
                                provisioningState = WorkspaceProvisioningState.STARTING,
                                runtimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
                                workspaceSlug = buildUniqueWorkspaceSlug("Local Workspace"),
                            ),
                        )
                    val slug = draft.workspaceSlug.ifBlank { buildUniqueWorkspaceSlug(draft.name) }
                    val port = extractLocalhostPort(draft.url) ?: targetPort
                    val ok = connectLocalEmbeddedWorkspace(
                        profile = draft.copy(runtimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR),
                        port = port,
                        slug = slug,
                        termuxCommand = cur.pendingTermuxCommand.ifBlank { defaultTermuxCommandForPort(port) },
                        prefs = prefs,
                        forceReload = true,
                        allowExternalAttach = false,
                        previousActiveId = previousActiveId,
                    )
                    if (ok) {
                        _localhostState.update {
                            it.copy(
                                geminiNotice = "Built-in Local Editor started offline (Gemini skipped).",
                            )
                        }
                        onReadyInApp()
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    val msg = t.localizedMessage ?: t.javaClass.simpleName
                    terminalEmulator.error("SERVER", "Offline local editor start failed: $msg")
                    _localhostState.update {
                        it.copy(
                            isProvisioning = false,
                            provisioningState = WorkspaceProvisioningState.FAILED,
                            errorMessage = msg,
                        )
                    }
                } finally {
                    isUserConnectionInProgress = false
                }
            }
        }
    }

    /**
     * Probing verification for Termux code-server drafts in WAITING_FOR_INSTALLATION state.
     * Only marks READY if a real HTTP server is verified listening on the target port.
     */
    fun verifyAndAttachExternalTermux(
        prefs: AppPreferences,
        onReadyInApp: () -> Unit = {},
    ) {
        val cur = _localhostState.value
        val draftId = cur.draftProfileId ?: activeProfile.value?.id ?: return
        isUserConnectionInProgress = true
        activeConnectJob?.cancel()
        activeConnectJob = viewModelScope.launch {
            connectionMutex.withLock {
                try {
                    val profile = profileRepository.getById(draftId) ?: return@withLock
                    val previousActiveId = activeProfile.value?.id
                    val port = extractLocalhostPort(profile.url) ?: cur.pendingTargetPort
                    val ok = connectProfileInternal(
                        profile = profile.copy(runtimeKind = LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER),
                        prefs = prefs,
                        forceReload = true,
                        previousActiveId = previousActiveId,
                    )
                    if (ok) {
                        onReadyInApp()
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    val msg = t.localizedMessage ?: t.javaClass.simpleName
                    terminalEmulator.error("TERMUX", "Verification failed: $msg")
                } finally {
                    isUserConnectionInProgress = false
                }
            }
        }
    }

    fun retryProvisioningDraft(
        prefs: AppPreferences,
        onLaunchedInApp: () -> Unit = {},
    ) {
        val cur = _localhostState.value
        val draftId = cur.draftProfileId
        viewModelScope.launch {
            val existing = draftId?.let { profileRepository.getById(it) }
            val port = existing?.let { extractLocalhostPort(it.url) } ?: cur.pendingTargetPort
            val name = existing?.name ?: "Local Workspace :$port"
            val runtime = existing?.runtimeKind ?: cur.runtimeKind
            _localhostState.update {
                it.copy(
                    isProvisioning = false,
                    provisioningState = WorkspaceProvisioningState.NOT_STARTED,
                )
            }
            createAndStartTermuxLocalhost(
                workspaceName = name,
                port = port,
                stackHint = "HTML / Node.js / code-server",
                runtimeKind = runtime,
                prefs = prefs,
                existingDraftId = existing?.id,
                onLaunchedInApp = onLaunchedInApp,
            )
        }
    }

    fun dismissDraftProvisioningState() {
        _localhostState.update {
            it.copy(
                isProvisioning = false,
                draftProfileId = null,
                errorMessage = null,
                isMissingIdeBinary = false,
                canOfferOfflineFallback = false,
                provisioningState = if (it.activeStatus?.isRunning == true) {
                    WorkspaceProvisioningState.READY
                } else {
                    WorkspaceProvisioningState.NOT_STARTED
                },
            )
        }
    }

    fun copyAndOpenTermuxIfInstalled(context: Context, command: String): String {
        runCatching {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Termux code-server command", command.take(8_192)))
        }
        val launchIntent = runCatching {
            context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE_NAME)
        }.getOrNull()
        return if (launchIntent != null) {
            runCatching {
                context.startActivity(launchIntent)
                "Copied Termux command & opened Termux — paste, run it, then return and tap Verify & Connect"
            }.getOrElse {
                "Copied Termux command to clipboard — open Termux and paste it"
            }
        } else {
            "Copied command to clipboard — Termux ($TERMUX_PACKAGE_NAME) is not installed on this device"
        }
    }

    fun requestChromeWithPermission(url: String) {
        webController.requestChromePermission(url)
    }

    fun confirmChromeWithPermission(
        context: Context,
        isDarkTheme: Boolean,
        toolbarColorArgb: Int,
        secondaryColorArgb: Int,
    ): Boolean {
        val url = sessionState.value.pendingChromePermissionUrl ?: return false
        webController.clearChromePermissionRequest()
        return customTabsManager.openWorkspaceOrAuth(
            context = context,
            rawUrl = url,
            isDarkTheme = isDarkTheme,
            toolbarColorArgb = toolbarColorArgb,
            secondaryColorArgb = secondaryColorArgb,
            isAuthFlow = true,
        )
    }

    fun dismissChromePermission() {
        webController.clearChromePermissionRequest()
    }

    fun togglePinProfile(profile: ServerProfile) {
        viewModelScope.launch {
            runCatching { profileRepository.togglePin(profile) }
        }
    }

    fun deleteProfile(profile: ServerProfile) {
        viewModelScope.launch {
            runCatching {
                profileRepository.deleteProfile(profile.id)
                if (profile.isActive) {
                    KeepAliveService.stop(appContext)
                    if (extractLocalhostPort(profile.url) != null) {
                        CodeServerWatchdogService.stopMonitoring(appContext)
                        runtimeManager.aiRuntime.stopRuntime()
                        _localhostState.update {
                            it.copy(
                                isProvisioning = false,
                                provisioningState = WorkspaceProvisioningState.STOPPED,
                                activeStatus = it.activeStatus?.copy(isRunning = false, sourceLabel = "Stopped"),
                                statusMessage = "Workspace profile deleted",
                            )
                        }
                    }
                }
                if (_localhostState.value.draftProfileId == profile.id) {
                    dismissDraftProvisioningState()
                }
            }
        }
    }

    private fun buildDeterministicStarterPlan(
        workspaceName: String,
        port: Int,
        stackHint: String,
        termuxCmd: String,
    ): com.example.ai.LocalhostBootstrapPlan {
        val isPython = stackHint.contains("python", ignoreCase = true) || stackHint.contains("fastapi", ignoreCase = true)
        val mainName = if (isPython) "app.py" else "server.js"
        val mainContent = if (isPython) {
            """
            # $workspaceName starter script
            from http.server import SimpleHTTPRequestHandler, HTTPServer

            if __name__ == "__main__":
                print("Run with Python or open inside code-server on 127.0.0.1:$port")
            """.trimIndent()
        } else {
            """
            // $workspaceName starter script
            console.log("Workspace '$workspaceName' ready on 127.0.0.1:$port");
            """.trimIndent()
        }
        val files = mapOf(
            "index.html" to """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width,initial-scale=1" />
                  <title>$workspaceName</title>
                </head>
                <body style="font-family:system-ui,sans-serif;background:#111827;color:#f3f4f6;padding:24px;">
                  <h1>$workspaceName</h1>
                  <p>Stack: $stackHint</p>
                </body>
                </html>
            """.trimIndent(),
            mainName to mainContent,
            "README.md" to "# $workspaceName\n\nStack: $stackHint\nTarget port: $port\n",
        )
        return com.example.ai.LocalhostBootstrapPlan(
            summary = "Offline starter workspace ready ($stackHint)",
            termuxCommand = termuxCmd,
            starterFiles = files,
            setupCommand = "ls -la",
            generatedByAi = false,
        )
    }

    override fun onCleared() {
        super.onCleared()
        activeConnectJob?.cancel()
        geminiBootstrapJob?.cancel()
        runCatching { runtimeManager.userTerminal.destroy() }
        if (!LocalhostRuntimeHost.watchdogState.value.isEnabled) {
            runCatching { runtimeManager.aiRuntime.destroy() }
        }
        webController.destroy()
        customTabsManager.unbind(appContext)
    }

    companion object {
        fun isTermuxInstalled(context: Context): Boolean {
            return runCatching {
                val pm = context.packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(TERMUX_PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(TERMUX_PACKAGE_NAME, 0)
                }
                true
            }.getOrDefault(false)
        }

        fun defaultTermuxCommandForPort(port: Int): String =
            "echo 'Create an external Termux workspace on port ${port.coerceIn(1024, 65535)} to generate its password-protected setup command.'"

        fun buildUniqueWorkspaceSlug(workspaceName: String, suffix: Long = System.currentTimeMillis()): String {
            val clean = workspaceName.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9_-]+"), "-")
                .trim('-')
                .ifBlank { "workspace" }
                .take(40)
            return "ws-$clean-$suffix"
        }

        fun workspaceSlugFor(profileId: Long, profileName: String): String =
            "profile-$profileId-$profileName"

        fun extractLocalhostPort(rawUrl: String): Int? {
            val normalized = ChromeCustomTabsManager.normalizeUrl(rawUrl)
            if (normalized.isBlank()) return null
            return runCatching {
                val uri = URI(normalized)
                val host = uri.host?.lowercase().orEmpty()
                if (host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0") {
                    if (uri.port in 1..65535) uri.port else 8080
                } else {
                    null
                }
            }.getOrNull()
        }
    }
}
