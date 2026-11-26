package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.example.ai.configureAppCheck
import com.example.data.local.AppDatabase
import com.example.data.model.ServerProfile
import com.example.data.model.WorkspaceProvisioningState
import com.example.security.SecureVault
import com.example.ui.components.CodeServerStatusOverlay
import com.example.ui.components.ServerProfileDialog
import com.example.ui.components.TermuxLocalhostDialog
import com.example.ui.screens.AiChatScreen
import com.example.ui.screens.PhoneFilesScreen
import com.example.ui.screens.ProfilesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StartupPhase
import com.example.ui.screens.StartupRecoveryView
import com.example.ui.screens.StartupSplashView
import com.example.ui.screens.UserTerminalScreen
import com.example.ui.screens.WorkspaceScreen
import com.example.ui.theme.ThemePreset
import com.example.ui.theme.VsShellTheme
import com.example.ui.viewmodel.AppTab
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.WorkspaceViewModel
import com.example.workspace.JsDialogKind
import com.example.workspace.WorkspaceKeyboardShortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runCatching { configureAppCheck(this, intent) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { StartupDiagnostics.installUncaughtExceptionHandler(applicationContext) }
        // Initialize Firebase App Check safely before any UI or Firebase calls
        runCatching { configureAppCheck(this, intent) }
            .onFailure { StartupDiagnostics.recordNonFatal(this, "MainActivity.onCreate AppCheck", it) }
        runCatching { enableEdgeToEdge() }

        setContent {
            var startupAttempt by remember { mutableIntStateOf(0) }
            var forceSafeMode by remember { mutableStateOf(false) }
            var bypassPendingCrashCheck by remember { mutableStateOf(false) }
            var lastFailureWasDatabase by remember { mutableStateOf(false) }
            var recoveryExportFeedback by remember { mutableStateOf<String?>(null) }
            var pendingExportRuntimeLogs by remember { mutableStateOf("") }
            var onExportCompleteFeedback by remember { mutableStateOf<((String) -> Unit)?>(null) }
            val rootScope = rememberCoroutineScope()
            var startupPhase by remember {
                mutableStateOf<StartupPhase>(
                    StartupPhase.Initializing("Initializing VS Shell & AI Code Server..."),
                )
            }

            val exportCrashLogLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("text/plain"),
            ) { uri ->
                val runtimeLogsSnapshot = pendingExportRuntimeLogs
                val feedbackCallback = onExportCompleteFeedback
                if (uri != null) {
                    rootScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            StartupDiagnostics.exportReportToUri(
                                context = this@MainActivity,
                                destinationUri = uri,
                                extraRuntimeLogs = runtimeLogsSnapshot,
                            )
                        }
                        val msg = result.fold(
                            onSuccess = { bytes -> "Exported diagnostic log (${bytes} bytes) as text file." },
                            onFailure = { err -> "Failed to export log: ${err.localizedMessage ?: "Write error"}" },
                        )
                        recoveryExportFeedback = msg
                        feedbackCallback?.invoke(msg)
                    }
                }
            }

            fun triggerLogExport(extraLogs: String, onFeedback: ((String) -> Unit)? = null) {
                pendingExportRuntimeLogs = extraLogs
                onExportCompleteFeedback = onFeedback
                val launched = runCatching {
                    exportCrashLogLauncher.launch(StartupDiagnostics.defaultExportFileName())
                    true
                }.getOrDefault(false)
                if (!launched) {
                    rootScope.launch {
                        val fallbackFile = withContext(Dispatchers.IO) {
                            StartupDiagnostics.prepareShareableReportFile(this@MainActivity, extraLogs)
                        }
                        val msg = fallbackFile.fold(
                            onSuccess = { file -> "File picker unavailable. Saved to ${file.absolutePath}" },
                            onFailure = { err -> "Unable to export log: ${err.localizedMessage}" },
                        )
                        recoveryExportFeedback = msg
                        onFeedback?.invoke(msg)
                    }
                }
            }

            fun triggerLogShare(extraLogs: String, onFeedback: ((String) -> Unit)? = null) {
                rootScope.launch {
                    val intentResult = withContext(Dispatchers.IO) {
                        StartupDiagnostics.createShareCrashLogIntent(this@MainActivity, extraLogs)
                    }
                    intentResult.onSuccess { shareIntent ->
                        val opened = runCatching {
                            startActivity(Intent.createChooser(shareIntent, "Share VS Shell Crash Log"))
                            true
                        }.getOrDefault(false)
                        if (!opened) {
                            val msg = "No compatible share app found on device."
                            recoveryExportFeedback = msg
                            onFeedback?.invoke(msg)
                        }
                    }.onFailure { err ->
                        val msg = "Unable to prepare crash log share: ${err.localizedMessage}"
                        recoveryExportFeedback = msg
                        onFeedback?.invoke(msg)
                    }
                }
            }

            LaunchedEffect(startupAttempt) {
                startupPhase = StartupPhase.Initializing("Initializing VS Shell & AI Code Server...")
                val pendingCrash = if (bypassPendingCrashCheck) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        StartupDiagnostics.getPendingCrashReport(this@MainActivity)
                    }
                }

                if (pendingCrash != null) {
                    lastFailureWasDatabase = pendingCrash.message.contains("migration", ignoreCase = true) ||
                        pendingCrash.message.contains("SQLite", ignoreCase = true) ||
                        pendingCrash.stackTrace.contains("AppDatabase", ignoreCase = true)
                    startupPhase = StartupPhase.Recovery(
                        title = "VS Shell Recovered Safely",
                        message = "${pendingCrash.stage}: ${pendingCrash.message}",
                        stackTrace = pendingCrash.stackTrace,
                        lastKnownState = pendingCrash.lastKnownState,
                        logFilePath = pendingCrash.logFilePath,
                        fromPreviousCrash = true,
                    )
                    return@LaunchedEffect
                }

                var phaseStage = "Startup Initialization"
                try {
                    StartupDiagnostics.updateLastKnownState(
                        this@MainActivity,
                        "Initializing database & ViewModels (attempt=$startupAttempt)",
                        persistToPrefs = true,
                    )
                    phaseStage = "Database Initialization"
                    withContext(Dispatchers.IO) {
                        val db = AppDatabase.getInstance(applicationContext)
                        db.openHelper.writableDatabase
                    }
                    phaseStage = "ViewModel Initialization"
                    val provider = ViewModelProvider(this@MainActivity)
                    val mainVm = provider[MainViewModel::class.java]
                    val workspaceVm = provider[WorkspaceViewModel::class.java]
                    val chatVm = provider[ChatViewModel::class.java]

                    lastFailureWasDatabase = false
                    startupPhase = StartupPhase.Ready(
                        mainViewModel = mainVm,
                        workspaceViewModel = workspaceVm,
                        chatViewModel = chatVm,
                        safeMode = forceSafeMode,
                    )
                } catch (t: Throwable) {
                    lastFailureWasDatabase = phaseStage == "Database Initialization"
                    StartupDiagnostics.recordStartupFailure(this@MainActivity, phaseStage, t)
                    startupPhase = StartupPhase.Recovery(
                        title = "Startup Initialization Failed",
                        message = t.localizedMessage ?: t.javaClass.simpleName,
                        stackTrace = t.stackTraceToString().take(8_192),
                        lastKnownState = StartupDiagnostics.getLastKnownState(),
                        logFilePath = StartupDiagnostics.getCrashLogPath(this@MainActivity),
                        fromPreviousCrash = false,
                    )
                }
            }

            VsShellTheme {
                when (val phase = startupPhase) {
                    is StartupPhase.Initializing -> {
                        StartupSplashView(statusText = phase.statusText)
                    }

                    is StartupPhase.Recovery -> {
                        StartupRecoveryView(
                            title = phase.title,
                            message = phase.message,
                            stackTrace = phase.stackTrace,
                            lastKnownState = phase.lastKnownState,
                            logFilePath = phase.logFilePath,
                            fromPreviousCrash = phase.fromPreviousCrash,
                            exportFeedback = recoveryExportFeedback,
                            onContinueSafeMode = {
                                StartupDiagnostics.clearCrashCounter(this@MainActivity)
                                forceSafeMode = true
                                bypassPendingCrashCheck = true
                                startupAttempt++
                            },
                            onRetryNormal = {
                                StartupDiagnostics.clearCrashCounter(this@MainActivity)
                                forceSafeMode = false
                                bypassPendingCrashCheck = true
                                startupAttempt++
                            },
                            onResetCachesAndRetry = {
                                rootScope.launch {
                                    withContext(Dispatchers.IO) {
                                        StartupDiagnostics.resetLocalCachesSafely(
                                            context = this@MainActivity,
                                            resetCorruptedDatabase = lastFailureWasDatabase,
                                        )
                                    }
                                    lastFailureWasDatabase = false
                                    forceSafeMode = false
                                    bypassPendingCrashCheck = true
                                    startupAttempt++
                                }
                            },
                            onExportLog = {
                                triggerLogExport(extraLogs = "")
                            },
                            onShareLog = {
                                triggerLogShare(extraLogs = "")
                            },
                        )
                    }

                    is StartupPhase.Ready -> {
                        LaunchedEffect(phase) {
                            delay(2_500L)
                            StartupDiagnostics.markStartupHealthy(this@MainActivity)
                        }
                        VsShellMainContent(
                            activity = this@MainActivity,
                            viewModel = phase.mainViewModel,
                            workspaceViewModel = phase.workspaceViewModel,
                            chatViewModel = phase.chatViewModel,
                            safeMode = phase.safeMode,
                            onExportLogs = { extraLogs, feedback ->
                                triggerLogExport(extraLogs = extraLogs, onFeedback = feedback)
                            },
                            onShareLogs = { extraLogs, feedback ->
                                triggerLogShare(extraLogs = extraLogs, onFeedback = feedback)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VsShellMainContent(
    activity: ComponentActivity,
    viewModel: MainViewModel,
    workspaceViewModel: WorkspaceViewModel,
    chatViewModel: ChatViewModel,
    safeMode: Boolean,
    onExportLogs: (extraLogs: String, onFeedback: (String) -> Unit) -> Unit,
    onShareLogs: (extraLogs: String, onFeedback: (String) -> Unit) -> Unit,
) {
            val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
            val profiles by workspaceViewModel.profiles.collectAsStateWithLifecycle()
            val activeProfile by workspaceViewModel.activeProfile.collectAsStateWithLifecycle()
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()
            val webState by workspaceViewModel.sessionState.collectAsStateWithLifecycle()
            val localhostState by workspaceViewModel.localhostState.collectAsStateWithLifecycle()
            val watchdogState by workspaceViewModel.watchdogState.collectAsStateWithLifecycle()
            val terminalLines by workspaceViewModel.terminalLines.collectAsStateWithLifecycle()
            val userTerminalState by workspaceViewModel.userTerminalState.collectAsStateWithLifecycle()
            val userTerminalLines by workspaceViewModel.userTerminalLines.collectAsStateWithLifecycle()
            val userCommandHistory by workspaceViewModel.userCommandHistory.collectAsStateWithLifecycle()
            val pendingCommandConfirmation by workspaceViewModel.pendingCommandConfirmation.collectAsStateWithLifecycle()
            val vsCodeRuntimeState by workspaceViewModel.vsCodeRuntimeState.collectAsStateWithLifecycle()
            val vsCodeRuntimeLogs by workspaceViewModel.vsCodeRuntimeLogs.collectAsStateWithLifecycle()
            val binaryInventory by workspaceViewModel.binaryInventory.collectAsStateWithLifecycle()
            val memorySnapshot by workspaceViewModel.memorySnapshot.collectAsStateWithLifecycle()
            val phoneFilesState by viewModel.phoneFilesState.collectAsStateWithLifecycle()

            val chatMessages by chatViewModel.messages.collectAsStateWithLifecycle()
            val selectedChatRole by chatViewModel.selectedRole.collectAsStateWithLifecycle()
            val chatStreamState by chatViewModel.streamState.collectAsStateWithLifecycle()

            val context = LocalContext.current
            val snackbarHostState = remember { SnackbarHostState() }

            var showServerDialog by remember { mutableStateOf(false) }
            var showTermuxLocalhostDialog by remember { mutableStateOf(false) }
            var isTerminalOverlayExpanded by remember { mutableStateOf(false) }
            var isOverlayTerminalInputFocused by remember { mutableStateOf(false) }
            var editingProfile by remember { mutableStateOf<ServerProfile?>(null) }

            val canNavigateBack by viewModel.canNavigateBack.collectAsStateWithLifecycle()
            val coldStartPinRequired by viewModel.coldStartPinRequired.collectAsStateWithLifecycle()
            var handledColdStartPin by remember { mutableStateOf(false) }
            var isAppLocked by remember { mutableStateOf(false) }

            LaunchedEffect(coldStartPinRequired) {
                val req = coldStartPinRequired
                if (req != null && !handledColdStartPin) {
                    handledColdStartPin = true
                    isAppLocked = req
                }
            }

            LaunchedEffect(preferences.appPinEnabled) {
                if (!preferences.appPinEnabled) {
                    isAppLocked = false
                }
            }

            LaunchedEffect(
                currentTab,
                activeProfile?.name,
                localhostState.activeStatus?.isRunning,
                localhostState.activeStatus?.port,
                webState.status,
                webState.currentUrl,
            ) {
                val summary = buildString {
                    append("tab=").append(currentTab.name)
                    append(", profile=").append(activeProfile?.name ?: "none")
                    append(", serverRunning=").append(localhostState.activeStatus?.isRunning ?: false)
                    append(", port=").append(localhostState.activeStatus?.port ?: 0)
                    append(", webStatus=").append(webState.status.name)
                    if (webState.currentUrl.isNotBlank()) {
                        append(", url=").append(SecureVault.sanitizeUrlForLogs(webState.currentUrl))
                    }
                }
                StartupDiagnostics.updateLastKnownState(activity, summary)
            }

            val folderPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                if (uri != null) {
                    viewModel.onLinkedFolderPicked(uri)
                }
            }

            val importFileLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    viewModel.importPhoneDocument(uri)
                }
            }

            val webUploadMultiLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenMultipleDocuments(),
            ) { uris ->
                workspaceViewModel.webController.deliverFilePickerResult(
                    if (uris.isEmpty()) null else uris.toTypedArray(),
                )
            }

            val webUploadSingleLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                workspaceViewModel.webController.deliverFilePickerResult(
                    if (uri == null) null else arrayOf(uri),
                )
            }

            val notificationPermLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { granted ->
                viewModel.setKeepAliveServiceEnabled(granted, activeProfile?.name)
                if (!granted) {
                    viewModel.notifyMessage("Notification permission needed for background keep-alive")
                }
            }

            DisposableEffect(context) {
                workspaceViewModel.webController.updateHostContext(context)
                onDispose {
                    workspaceViewModel.webController.updateHostContext(context.applicationContext)
                }
            }

            LaunchedEffect(Unit) {
                workspaceViewModel.webController.setCallbacks(
                    requestFilePicker = { mimeTypes, allowMultiple ->
                        val launched = runCatching {
                            if (allowMultiple) {
                                webUploadMultiLauncher.launch(mimeTypes)
                            } else {
                                webUploadSingleLauncher.launch(mimeTypes)
                            }
                            true
                        }.getOrDefault(false)
                        if (!launched) {
                            workspaceViewModel.webController.deliverFilePickerResult(null)
                            viewModel.notifyMessage("No file picker available on this device")
                        }
                    },
                    onDownloadSaved = { msg ->
                        viewModel.notifyMessage(msg)
                    },
                )
            }

            val latestPreferences by rememberUpdatedState(preferences)
            val latestActiveProfile by rememberUpdatedState(activeProfile)
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, safeMode) {
                val observer = LifecycleEventObserver { _, event ->
                    runCatching {
                        when (event) {
                            Lifecycle.Event.ON_START -> {
                                workspaceViewModel.onAppEntered(
                                    prefs = latestPreferences,
                                    safeMode = safeMode,
                                ) {
                                    if (latestPreferences.aiAutoCodeServerOnLaunch) {
                                        viewModel.selectTab(AppTab.WORKSPACE)
                                    }
                                }
                            }
                            Lifecycle.Event.ON_RESUME -> {
                                workspaceViewModel.webController.onResume()
                                if (!safeMode) {
                                    workspaceViewModel.syncKeepAliveService(
                                        prefs = latestPreferences,
                                        activeServerName = latestActiveProfile?.name,
                                    )
                                }
                            }
                            Lifecycle.Event.ON_PAUSE -> workspaceViewModel.webController.onPause()
                            Lifecycle.Event.ON_STOP -> {
                                workspaceViewModel.onAppLeftForeground()
                                if (latestPreferences.appPinEnabled && latestPreferences.appPinHash.isNotBlank()) {
                                    isAppLocked = true
                                }
                            }
                            else -> Unit
                        }
                    }.onFailure { err ->
                        StartupDiagnostics.recordNonFatal(activity, "MainActivity $event", err)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            LaunchedEffect(safeMode) {
                if (safeMode) {
                    viewModel.selectTab(AppTab.PROFILES)
                }
            }

            LaunchedEffect(currentTab) {
                if (currentTab == AppTab.WORKSPACE) {
                    workspaceViewModel.webController.onResume()
                }
            }

            LaunchedEffect(Unit) {
                viewModel.toastMessages.collect { message ->
                    snackbarHostState.showSnackbar(message)
                }
            }

            val isPureFullscreenWorkspace = currentTab == AppTab.WORKSPACE
            val hideSystemBars = isPureFullscreenWorkspace && preferences.immersiveFullscreen

            val hasBlockingOverlayOrDialog = isTerminalOverlayExpanded ||
                showServerDialog ||
                showTermuxLocalhostDialog ||
                isAppLocked ||
                webState.hasPopupWindow ||
                webState.pendingChromePermissionUrl != null ||
                webState.pendingJsDialog != null ||
                webState.sslWarningHost != null ||
                localhostState.isProvisioning ||
                localhostState.provisioningState == WorkspaceProvisioningState.WAITING_FOR_INSTALLATION ||
                (localhostState.provisioningState == WorkspaceProvisioningState.FAILED &&
                    !localhostState.errorMessage.isNullOrBlank() &&
                    localhostState.draftProfileId != null)
            val isWorkspaceInteractive = isPureFullscreenWorkspace && !hasBlockingOverlayOrDialog && activeProfile != null

            LaunchedEffect(isPureFullscreenWorkspace, isWorkspaceInteractive, isOverlayTerminalInputFocused) {
                workspaceViewModel.webController.setWorkspaceVisibilityAndFocus(
                    isVisible = isPureFullscreenWorkspace,
                    isInteractive = isWorkspaceInteractive,
                )
                workspaceViewModel.webController.updateInteractionGate(
                    isWorkspaceInteractive = isWorkspaceInteractive,
                    composeTextFieldFocused = isOverlayTerminalInputFocused,
                )
            }

            LaunchedEffect(preferences.keepScreenAwake, preferences.screenPrivacyShieldEnabled, hideSystemBars) {
                runCatching {
                    val win = activity.window
                    if (preferences.keepScreenAwake) {
                        win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }

                    if (preferences.screenPrivacyShieldEnabled) {
                        win.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        win.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }

                    val controller = WindowCompat.getInsetsController(win, win.decorView)
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (hideSystemBars) {
                        controller.hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        controller.show(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }

            BackHandler(
                enabled = isTerminalOverlayExpanded ||
                    webState.hasPopupWindow ||
                    webState.pendingChromePermissionUrl != null ||
                    webState.pendingJsDialog != null ||
                    webState.sslWarningHost != null ||
                    (currentTab == AppTab.WORKSPACE && webState.canGoBack) ||
                    canNavigateBack ||
                    currentTab != AppTab.PROFILES,
            ) {
                when {
                    webState.hasPopupWindow -> workspaceViewModel.webController.closePopupWindow()
                    webState.pendingChromePermissionUrl != null -> workspaceViewModel.dismissChromePermission()
                    webState.pendingJsDialog != null -> workspaceViewModel.webController.cancelJsDialog()
                    webState.sslWarningHost != null -> workspaceViewModel.webController.cancelSslWarning()
                    isTerminalOverlayExpanded -> isTerminalOverlayExpanded = false
                    currentTab == AppTab.WORKSPACE && webState.canGoBack -> {
                        workspaceViewModel.webController.goBack()
                    }
                    else -> viewModel.navigateBackOrProfiles()
                }
            }

            val systemDark = isSystemInDarkTheme()
            val isDarkTheme = when (preferences.themePreset) {
                ThemePreset.VSCODE_LIGHT -> false
                ThemePreset.MATERIAL_DYNAMIC -> systemDark
                else -> true
            }

            VsShellTheme(themePreset = preferences.themePreset) {
                val toolbarColorArgb = MaterialTheme.colorScheme.surfaceContainer.toArgb()
                val secondaryColorArgb = MaterialTheme.colorScheme.primaryContainer.toArgb()

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { keyEvent ->
                            val native = keyEvent.nativeKeyEvent
                            val isKeyDown = keyEvent.type == KeyEventType.KeyDown
                            if (isKeyDown &&
                                native.repeatCount == 0 &&
                                native.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE
                            ) {
                                when {
                                    isAppLocked -> false
                                    webState.hasPopupWindow -> {
                                        workspaceViewModel.webController.closePopupWindow()
                                        true
                                    }
                                    webState.pendingChromePermissionUrl != null -> {
                                        workspaceViewModel.dismissChromePermission()
                                        true
                                    }
                                    webState.pendingJsDialog != null -> {
                                        workspaceViewModel.webController.cancelJsDialog()
                                        true
                                    }
                                    webState.sslWarningHost != null -> {
                                        workspaceViewModel.webController.cancelSslWarning()
                                        true
                                    }
                                    isTerminalOverlayExpanded -> {
                                        isTerminalOverlayExpanded = false
                                        true
                                    }
                                    showServerDialog -> {
                                        showServerDialog = false
                                        true
                                    }
                                    showTermuxLocalhostDialog -> {
                                        showTermuxLocalhostDialog = false
                                        true
                                    }
                                    else -> false
                                }
                            } else {
                                workspaceViewModel.webController.handleScopedHardwareKey(
                                    keyCode = native.keyCode,
                                    isKeyDown = isKeyDown,
                                    repeatCount = native.repeatCount,
                                    isCtrlPressed = keyEvent.isCtrlPressed,
                                    isAltPressed = keyEvent.isAltPressed,
                                    isMetaPressed = keyEvent.isMetaPressed,
                                )
                            }
                        },
                    contentWindowInsets = if (isPureFullscreenWorkspace) {
                        WindowInsets(0, 0, 0, 0)
                    } else {
                        WindowInsets.safeDrawing
                    },
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    bottomBar = {
                        if (!isPureFullscreenWorkspace) {
                            CompactIconBottomNavBar(
                                currentTab = currentTab,
                                onSelectTab = viewModel::selectTab,
                            )
                        }
                    },
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        // Layer 0: Persistent In-App WebView Workspace (never takes user to Chrome)
                        WorkspaceScreen(
                            activeProfile = activeProfile,
                            allProfiles = profiles,
                            preferences = preferences,
                            webState = webState,
                            localhostState = localhostState,
                            webController = workspaceViewModel.webController,
                            onAddServerClick = {
                                editingProfile = null
                                showServerDialog = true
                            },
                            onNewTermuxLocalhostClick = { showTermuxLocalhostDialog = true },
                            onSelectProfile = { profile ->
                                workspaceViewModel.connectProfileInApp(profile, preferences) {
                                    viewModel.selectTab(AppTab.WORKSPACE)
                                }
                            },
                            onBackToServers = { viewModel.selectTab(AppTab.PROFILES) },
                            onOpenUserTerminal = { viewModel.selectTab(AppTab.USER_TERMINAL) },
                            onOpenPhoneFiles = { viewModel.selectTab(AppTab.PHONE_FILES) },
                            onOpenAiChat = { viewModel.selectTab(AppTab.AI_CHAT) },
                            onOpenSettings = { viewModel.selectTab(AppTab.SETTINGS) },
                            onRecreateWebView = {
                                workspaceViewModel.recreateWorkspaceWebView()
                            },
                            onToggleDevBar = {
                                viewModel.setShowDevShortcutBar(!preferences.showDevShortcutBar)
                            },
                            onRequestChromeWithPermission = workspaceViewModel::requestChromeWithPermission,
                            onOpenTermuxWithCommand = { cmd ->
                                val feedback = workspaceViewModel.copyAndOpenTermuxIfInstalled(context, cmd)
                                viewModel.notifyMessage(feedback)
                            },
                            onVerifyExternalTermux = {
                                workspaceViewModel.verifyAndAttachExternalTermux(preferences)
                            },
                            onStartOfflineLocalEditor = {
                                workspaceViewModel.startOfflineLocalEditorForDraft(preferences) {
                                    viewModel.selectTab(AppTab.WORKSPACE)
                                }
                            },
                            onRetryProvisioning = {
                                workspaceViewModel.retryProvisioningDraft(preferences)
                            },
                            onDismissDraftError = {
                                workspaceViewModel.dismissDraftProvisioningState()
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(0f),
                        )

                        // Floating Code-Server Status Pill, Restart Recovery Button & Terminal Emulator Overlay
                        if (currentTab == AppTab.WORKSPACE || isTerminalOverlayExpanded) {
                            CodeServerStatusOverlay(
                                activeProfile = activeProfile,
                                localhostState = localhostState,
                                watchdogState = watchdogState,
                                webState = webState,
                                terminalLines = terminalLines,
                                isTerminalExpanded = isTerminalOverlayExpanded,
                                onToggleTerminal = {
                                    isTerminalOverlayExpanded = !isTerminalOverlayExpanded
                                    if (!isTerminalOverlayExpanded) {
                                        isOverlayTerminalInputFocused = false
                                    }
                                },
                                onRestartServer = {
                                    workspaceViewModel.restartLocalCodeServer(preferences) {
                                        viewModel.notifyMessage("Restarted local AI server")
                                    }
                                },
                                onExecuteCommand = { command ->
                                    workspaceViewModel.executeTerminalCommand(command, preferences)
                                },
                                onClearLogs = workspaceViewModel::clearTerminalLogs,
                                onCopyLogs = {
                                    val text = workspaceViewModel.terminalEmulator.dumpPlainText()
                                    runCatching {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("VS Shell logs", text.take(96_000)))
                                        viewModel.notifyMessage("Copied terminal logs to clipboard")
                                    }.onFailure {
                                        viewModel.notifyMessage("Unable to copy logs")
                                    }
                                },
                                onExportLogs = {
                                    val runtimeDump = workspaceViewModel.terminalEmulator.dumpPlainText()
                                    onExportLogs(runtimeDump, viewModel::notifyMessage)
                                },
                                onShareLogs = {
                                    val runtimeDump = workspaceViewModel.terminalEmulator.dumpPlainText()
                                    onShareLogs(runtimeDump, viewModel::notifyMessage)
                                },
                                bottomClearanceDp = if (preferences.showDevShortcutBar && currentTab == AppTab.WORKSPACE) {
                                    56.dp
                                } else {
                                    10.dp
                                },
                                onTerminalInputFocusChanged = { focused ->
                                    isOverlayTerminalInputFocused = focused
                                },
                                modifier = Modifier
                                    .align(if (isTerminalOverlayExpanded) Alignment.Center else Alignment.BottomEnd)
                                    .zIndex(2f),
                            )
                        }

                        // Layer 1: Profiles, Phone Files, AI Chat, and Settings
                        if (currentTab != AppTab.WORKSPACE) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(1f)
                                    .pointerInput(Unit) {},
                                color = MaterialTheme.colorScheme.background,
                            ) {
                                when (currentTab) {
                                    AppTab.USER_TERMINAL -> UserTerminalScreen(
                                        userTerminalState = userTerminalState,
                                        terminalLines = userTerminalLines,
                                        commandHistory = userCommandHistory,
                                        pendingConfirmation = pendingCommandConfirmation,
                                        vsCodeRuntimeState = vsCodeRuntimeState,
                                        vsCodeRuntimeLogs = vsCodeRuntimeLogs,
                                        binaryInventory = binaryInventory,
                                        memorySnapshot = memorySnapshot,
                                        onStartUserTerminal = workspaceViewModel::startUserTerminal,
                                        onStopUserTerminal = workspaceViewModel::stopUserTerminal,
                                        onRestartUserTerminal = workspaceViewModel::restartUserTerminal,
                                        onInterruptUserTerminal = workspaceViewModel::interruptUserTerminal,
                                        onClearUserTerminalLogs = workspaceViewModel::clearUserTerminalLogs,
                                        onExecuteCommand = workspaceViewModel::executeUserTerminalCommand,
                                        onConfirmPendingCommand = workspaceViewModel::confirmPendingUserCommand,
                                        onDismissPendingCommand = workspaceViewModel::dismissPendingUserCommand,
                                        onStartVsCodeRuntime = {
                                            workspaceViewModel.startVsCodeRuntime(preferences)
                                        },
                                        onStopVsCodeRuntime = workspaceViewModel::stopVsCodeRuntime,
                                        onOpenWorkspace = { viewModel.selectTab(AppTab.WORKSPACE) },
                                        onNotify = viewModel::notifyMessage,
                                    )

                                    AppTab.PHONE_FILES -> PhoneFilesScreen(
                                        uiState = phoneFilesState,
                                        hasLinkedSafFolder = !preferences.linkedFolderUri.isNullOrBlank(),
                                        onPickPhoneFolder = {
                                            runCatching { folderPickerLauncher.launch(null) }
                                                .onFailure { viewModel.notifyMessage("Folder picker unavailable") }
                                        },
                                        onResetToAppWorkspace = { viewModel.onLinkedFolderPicked(null) },
                                        onImportPhoneFile = {
                                            runCatching { importFileLauncher.launch(arrayOf("*/*")) }
                                                .onFailure { viewModel.notifyMessage("File picker unavailable") }
                                        },
                                        onSelectLanguage = viewModel::setLanguageFilter,
                                        onSearchQueryChange = viewModel::setFileSearchQuery,
                                        onCreateFile = viewModel::createPhoneFile,
                                        onOpenFile = viewModel::openFileInLocalEditor,
                                        onSendToVsCode = { file ->
                                            viewModel.sendPhoneFileToVsCode(
                                                file = file,
                                                webController = workspaceViewModel.webController,
                                            ) {
                                                viewModel.selectTab(AppTab.WORKSPACE)
                                            }
                                        },
                                        onDeleteFile = viewModel::deletePhoneFile,
                                        onUpdateEditingContent = viewModel::updateEditingContent,
                                        onSaveEditingFile = { viewModel.saveEditingFile() },
                                        onSaveAndSendEditingFile = {
                                            val file = phoneFilesState.editingFile
                                            viewModel.saveEditingFile {
                                                if (file != null) {
                                                    viewModel.closeLocalEditor()
                                                    viewModel.sendPhoneFileToVsCode(
                                                        file = file,
                                                        webController = workspaceViewModel.webController,
                                                    ) {
                                                        viewModel.selectTab(AppTab.WORKSPACE)
                                                    }
                                                }
                                            }
                                        },
                                        onCloseEditor = viewModel::closeLocalEditor,
                                    )

                                    AppTab.PROFILES -> ProfilesScreen(
                                        profiles = profiles,
                                        preferences = preferences,
                                        isProvisioningLocalhost = localhostState.isProvisioning,
                                        onAddProfile = {
                                            editingProfile = null
                                            showServerDialog = true
                                        },
                                        onNewTermuxLocalhost = {
                                            if (!localhostState.isProvisioning) {
                                                showTermuxLocalhostDialog = true
                                            }
                                        },
                                        onConnectProfileInApp = { profile ->
                                            workspaceViewModel.connectProfileInApp(profile, preferences) {
                                                viewModel.selectTab(AppTab.WORKSPACE)
                                            }
                                        },
                                        onEditProfile = { profile ->
                                            editingProfile = profile
                                            showServerDialog = true
                                        },
                                        onTogglePin = workspaceViewModel::togglePinProfile,
                                        onDeleteProfile = workspaceViewModel::deleteProfile,
                                        onRequestChromeWithPermission = workspaceViewModel::requestChromeWithPermission,
                                        onSignInGithubInApp = {
                                            workspaceViewModel.webController.openInAppAuthPopup("https://github.com/login")
                                        },
                                        onSaveMasterCredentials = viewModel::saveMasterCredentials,
                                    )

                                    AppTab.AI_CHAT -> AiChatScreen(
                                        messages = chatMessages,
                                        selectedRole = selectedChatRole,
                                        streamState = chatStreamState,
                                        activeServerLabel = activeProfile?.let { "${it.name} (${it.url})" },
                                        onSelectRole = chatViewModel::selectRole,
                                        onSendMessage = { prompt ->
                                            val serverCtx = activeProfile?.let { "${it.name} - ${it.url}" }
                                            chatViewModel.sendMessage(prompt, serverCtx)
                                        },
                                        onCancelGeneration = chatViewModel::cancelGeneration,
                                        onRetry = chatViewModel::retryLastMessage,
                                        onDismissError = chatViewModel::dismissError,
                                        onClearHistory = chatViewModel::clearHistory,
                                        onCopyToast = viewModel::notifyMessage,
                                    )

                                    AppTab.SETTINGS -> SettingsScreen(
                                        preferences = preferences,
                                        onSelectTheme = viewModel::setThemePreset,
                                        onToggleAutoLaunch = viewModel::setAutoLaunchBackground,
                                        onToggleAiAutoCodeServer = viewModel::setAiAutoCodeServerOnLaunch,
                                        onToggleAutoRebootWatchdog = { enabled ->
                                            viewModel.setAutoRebootWatchdogEnabled(enabled)
                                            workspaceViewModel.syncWatchdogPreference(
                                                prefs = preferences.copy(autoRebootWatchdogEnabled = enabled),
                                                safeMode = safeMode,
                                            )
                                        },
                                        onToggleKeepAliveService = { enabled ->
                                            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                val granted = ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.POST_NOTIFICATIONS,
                                                ) == PackageManager.PERMISSION_GRANTED
                                                if (granted) {
                                                    viewModel.setKeepAliveServiceEnabled(true, activeProfile?.name)
                                                } else {
                                                    val launched = runCatching {
                                                        notificationPermLauncher.launch(
                                                            Manifest.permission.POST_NOTIFICATIONS,
                                                        )
                                                        true
                                                    }.getOrDefault(false)
                                                    if (!launched) {
                                                        viewModel.setKeepAliveServiceEnabled(true, activeProfile?.name)
                                                    }
                                                }
                                            } else {
                                                viewModel.setKeepAliveServiceEnabled(enabled, activeProfile?.name)
                                            }
                                        },
                                        onToggleImmersiveFullscreen = viewModel::setImmersiveFullscreen,
                                        onToggleKeepScreenAwake = viewModel::setKeepScreenAwake,
                                        onToggleDevShortcutBar = viewModel::setShowDevShortcutBar,
                                        onToggleScreenPrivacyShield = viewModel::setScreenPrivacyShieldEnabled,
                                        onSetAppPin = viewModel::setAppPin,
                                        onDisableAppPin = viewModel::disableAppPin,
                                        onLockAppNow = {
                                            if (preferences.appPinEnabled && preferences.appPinHash.isNotBlank()) {
                                                isAppLocked = true
                                            }
                                        },
                                        onClearCookiesAndCache = {
                                            workspaceViewModel.webController.clearAllCookiesAndCache()
                                            viewModel.notifyMessage("Cleared in-app cookies & cache")
                                        },
                                        onExportDiagnosticLogs = {
                                            val runtimeDump = workspaceViewModel.terminalEmulator.dumpPlainText()
                                            onExportLogs(runtimeDump, viewModel::notifyMessage)
                                        },
                                        onShareDiagnosticLogs = {
                                            val runtimeDump = workspaceViewModel.terminalEmulator.dumpPlainText()
                                            onShareLogs(runtimeDump, viewModel::notifyMessage)
                                        },
                                    )

                                    AppTab.WORKSPACE -> Unit
                                }
                            }
                        }
                    }
                }

                if (showServerDialog) {
                    ServerProfileDialog(
                        initialProfile = editingProfile,
                        hasMasterPassword = preferences.masterPasswordOrToken.isNotBlank(),
                        onSignInGithubInApp = {
                            workspaceViewModel.webController.openInAppAuthPopup("https://github.com/login")
                        },
                        onDismiss = { showServerDialog = false },
                        onSaveAndConnect = { id, name, url, mode, passwordHint, desktopUa, autoConnect, masterAutoLogin ->
                            showServerDialog = false
                            workspaceViewModel.saveAndConnectInApp(
                                existingId = id,
                                name = name,
                                url = url,
                                mode = mode,
                                passwordHint = passwordHint,
                                useDesktopUa = desktopUa,
                                autoConnect = autoConnect,
                                useMasterAutoLogin = masterAutoLogin,
                                prefs = preferences,
                            ) {
                                viewModel.selectTab(AppTab.WORKSPACE)
                            }
                        },
                    )
                }

                if (showTermuxLocalhostDialog) {
                    TermuxLocalhostDialog(
                        isCreating = localhostState.isProvisioning,
                        termuxInstalled = localhostState.termuxInstalled,
                        hasBundledCodeServer = binaryInventory.isCodeServerRunnableInApp,
                        onDismiss = { showTermuxLocalhostDialog = false },
                        onCreateAndLaunch = { wsName, port, stackHint, runtimeKind, generateWithAi ->
                            showTermuxLocalhostDialog = false
                            viewModel.selectTab(AppTab.WORKSPACE)
                            workspaceViewModel.createAndStartTermuxLocalhost(
                                workspaceName = wsName,
                                port = port,
                                stackHint = stackHint,
                                runtimeKind = runtimeKind,
                                prefs = preferences,
                                generateWithAi = generateWithAi,
                            )
                        },
                    )
                }

                if (webState.hasPopupWindow) {
                    Dialog(
                        onDismissRequest = { workspaceViewModel.webController.closePopupWindow() },
                        properties = DialogProperties(usePlatformDefaultWidth = false),
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceContainer)
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "In-App Sign-In",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            text = webState.popupUrl,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val target = webState.popupUrl.ifBlank { "https://github.com/login" }
                                            workspaceViewModel.requestChromeWithPermission(target)
                                        },
                                        modifier = Modifier.testTag("open_oauth_chrome_permission_button"),
                                    ) {
                                        Icon(Icons.Filled.OpenInBrowser, contentDescription = "Ask permission to open Chrome")
                                    }
                                    IconButton(
                                        onClick = { workspaceViewModel.webController.closePopupWindow() },
                                        modifier = Modifier.testTag("close_oauth_popup_button"),
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = "Done signing in")
                                    }
                                }
                                key(webState.popupSessionId) {
                                    AndroidView(
                                        factory = { ctx ->
                                            val wv = workspaceViewModel.webController.getPopupWebView()
                                            if (wv != null) {
                                                (wv.parent as? ViewGroup)?.removeView(wv)
                                                wv
                                            } else {
                                                View(ctx)
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }

                // Explicit Chrome Permission Gate — app NEVER takes user to Chrome without permission
                webState.pendingChromePermissionUrl?.let { targetUrl ->
                    AlertDialog(
                        onDismissRequest = workspaceViewModel::dismissChromePermission,
                        title = { Text("Open in Chrome?") },
                        text = {
                            Text(
                                "VS Shell stays inside the app by default. Do you want to grant permission to open \"$targetUrl\" externally in Chrome?",
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val ok = workspaceViewModel.confirmChromeWithPermission(
                                        context = context,
                                        isDarkTheme = isDarkTheme,
                                        toolbarColorArgb = toolbarColorArgb,
                                        secondaryColorArgb = secondaryColorArgb,
                                    )
                                    if (!ok) {
                                        viewModel.notifyMessage("Could not open Chrome for $targetUrl")
                                    }
                                },
                                modifier = Modifier.testTag("confirm_open_chrome_button"),
                            ) {
                                Text("Allow Chrome")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = workspaceViewModel::dismissChromePermission,
                                modifier = Modifier.testTag("deny_open_chrome_button"),
                            ) {
                                Text("Stay in App")
                            }
                        },
                    )
                }

                webState.pendingJsDialog?.let { jsDialog ->
                    var promptInput by remember(jsDialog.id) { mutableStateOf(jsDialog.defaultValue) }
                    AlertDialog(
                        onDismissRequest = { workspaceViewModel.webController.cancelJsDialog() },
                        title = {
                            Text(
                                when (jsDialog.kind) {
                                    JsDialogKind.ALERT -> "Workspace Notice"
                                    JsDialogKind.CONFIRM -> "Confirm Action"
                                    JsDialogKind.PROMPT -> "Workspace Input"
                                },
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(jsDialog.message)
                                if (jsDialog.kind == JsDialogKind.PROMPT) {
                                    OutlinedTextField(
                                        value = promptInput,
                                        onValueChange = { promptInput = it },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("web_js_prompt_input"),
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    workspaceViewModel.webController.confirmJsDialog(
                                        if (jsDialog.kind == JsDialogKind.PROMPT) promptInput else null,
                                    )
                                },
                                modifier = Modifier.testTag("web_js_dialog_confirm"),
                            ) {
                                Text("OK")
                            }
                        },
                        dismissButton = if (jsDialog.kind != JsDialogKind.ALERT) {
                            {
                                TextButton(
                                    onClick = { workspaceViewModel.webController.cancelJsDialog() },
                                    modifier = Modifier.testTag("web_js_dialog_cancel"),
                                ) {
                                    Text("Cancel")
                                }
                            }
                        } else {
                            null
                        },
                    )
                }

                webState.sslWarningHost?.let { host ->
                    AlertDialog(
                        onDismissRequest = { workspaceViewModel.webController.cancelSslWarning() },
                        title = { Text("Untrusted SSL Certificate") },
                        text = {
                            Text(
                                "The server '$host' presented a self-signed or untrusted certificate. " +
                                    "Only proceed if this is your own Tailscale or local development machine.",
                            )
                        },
                        confirmButton = {
                            Button(onClick = { workspaceViewModel.webController.proceedWithSslWarning() }) {
                                Text("Trust & Continue")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { workspaceViewModel.webController.cancelSslWarning() }) {
                                Text("Cancel")
                            }
                        },
                    )
                }

                if (isAppLocked && preferences.appPinEnabled && preferences.appPinHash.isNotBlank()) {
                    AppPinLockOverlay(
                        storedPinHash = preferences.appPinHash,
                        onUnlocked = { isAppLocked = false },
                    )
                }
            }
}

@Composable
private fun AppPinLockOverlay(
    storedPinHash: String,
    onUnlocked: () -> Unit,
) {
    var pinEntry by remember { mutableStateOf("") }
    var failedAttempts by remember { mutableIntStateOf(0) }
    var lockoutRemainingSec by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(lockoutRemainingSec) {
        if (lockoutRemainingSec > 0) {
            delay(1_000L)
            lockoutRemainingSec -= 1
        }
    }

    BackHandler(enabled = true) {
        // Consume back presses while locked so protected workspace views remain inaccessible
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f)
            .pointerInput(Unit) {},
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "VS Shell Locked",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    text = "VS Shell Locked",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "Enter your PIN to unlock your servers, encrypted credentials, and workspace.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                OutlinedTextField(
                    value = pinEntry,
                    onValueChange = {
                        pinEntry = it.filter(Char::isDigit).take(12)
                        errorMessage = null
                    },
                    label = { Text("Enter PIN") },
                    singleLine = true,
                    enabled = lockoutRemainingSec == 0,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { keyEvent ->
                            val native = keyEvent.nativeKeyEvent
                            val submit = WorkspaceKeyboardShortcuts.shouldSubmitOnEnterKey(
                                keyCode = native.keyCode,
                                isKeyDown = keyEvent.type == KeyEventType.KeyDown,
                                repeatCount = native.repeatCount,
                                isShiftPressed = keyEvent.isShiftPressed,
                                isCtrlPressed = keyEvent.isCtrlPressed,
                                isAltPressed = keyEvent.isAltPressed,
                                isMetaPressed = keyEvent.isMetaPressed,
                            )
                            if (submit && lockoutRemainingSec == 0 && pinEntry.length >= 4) {
                                if (SecureVault.verifyPin(pinEntry, storedPinHash)) {
                                    pinEntry = ""
                                    failedAttempts = 0
                                    errorMessage = null
                                    onUnlocked()
                                } else {
                                    failedAttempts += 1
                                    pinEntry = ""
                                    if (failedAttempts >= 5) {
                                        lockoutRemainingSec = 15
                                        errorMessage = "Too many wrong PIN attempts."
                                    } else {
                                        errorMessage = "Incorrect PIN (${5 - failedAttempts} attempts before cooldown)."
                                    }
                                }
                                true
                            } else {
                                false
                            }
                        }
                        .testTag("app_pin_unlock_input"),
                )
                if (lockoutRemainingSec > 0) {
                    Text(
                        text = "Too many attempts. Try again in ${lockoutRemainingSec}s.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    enabled = lockoutRemainingSec == 0 && pinEntry.length >= 4,
                    onClick = {
                        if (SecureVault.verifyPin(pinEntry, storedPinHash)) {
                            pinEntry = ""
                            failedAttempts = 0
                            errorMessage = null
                            onUnlocked()
                        } else {
                            failedAttempts += 1
                            pinEntry = ""
                            if (failedAttempts >= 5) {
                                lockoutRemainingSec = 15
                                errorMessage = "Too many wrong PIN attempts."
                            } else {
                                errorMessage = "Incorrect PIN (${5 - failedAttempts} attempts before cooldown)."
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("app_pin_unlock_button"),
                ) {
                    Text("Unlock VS Shell")
                }
            }
        }
    }
}

private data class NavTabSpec(
    val tab: AppTab,
    val icon: ImageVector,
    val labelResId: Int,
    val testTag: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactIconBottomNavBar(
    currentTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navItems = remember {
        listOf(
            NavTabSpec(AppTab.PROFILES, Icons.Filled.Dns, R.string.nav_profiles, "nav_tab_profiles"),
            NavTabSpec(AppTab.USER_TERMINAL, Icons.Filled.Terminal, R.string.nav_user_terminal, "nav_tab_user_terminal"),
            NavTabSpec(AppTab.PHONE_FILES, Icons.Filled.FolderCopy, R.string.nav_phone_files, "nav_tab_phone_files"),
            NavTabSpec(AppTab.AI_CHAT, Icons.Filled.AutoAwesome, R.string.nav_ai_chat, "nav_tab_ai_chat"),
            NavTabSpec(AppTab.SETTINGS, Icons.Filled.Settings, R.string.nav_settings, "nav_tab_settings"),
            NavTabSpec(AppTab.WORKSPACE, Icons.Filled.Code, R.string.nav_workspace, "nav_tab_workspace"),
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("main_bottom_nav"),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .height(52.dp)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navItems.forEach { item ->
                    val label = stringResource(item.labelResId)
                    val isSelected = item.tab != AppTab.WORKSPACE && currentTab == item.tab
                    val interactionSource = remember(item.tab) { MutableInteractionSource() }

                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(label) } },
                        state = rememberTooltipState(),
                    ) {
                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0f)
                                    },
                                )
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = ripple(bounded = true),
                                    role = Role.Tab,
                                    onClick = { onSelectTab(item.tab) },
                                )
                                .semantics {
                                    selected = isSelected
                                    contentDescription = label
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag(item.testTag),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = label,
                                modifier = Modifier.size(20.dp),
                                tint = if (isSelected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
