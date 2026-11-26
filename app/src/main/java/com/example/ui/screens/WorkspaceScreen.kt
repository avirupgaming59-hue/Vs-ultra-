package com.example.ui.screens

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.data.model.ServerProfile
import com.example.data.model.WorkspaceProvisioningState
import com.example.data.repository.AppPreferences
import com.example.ui.components.DeveloperShortcutBar
import com.example.ui.components.mouseWheelHorizontalScroll
import com.example.ui.components.mouseWheelScroll
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.viewmodel.LocalhostUiState
import com.example.workspace.ConnectionStatus
import com.example.workspace.InAppWebWorkspaceController
import com.example.workspace.WebViewSessionState
import com.example.workspace.WorkspaceKeyboardShortcuts

@Composable
fun WorkspaceScreen(
    activeProfile: ServerProfile?,
    allProfiles: List<ServerProfile>,
    preferences: AppPreferences,
    webState: WebViewSessionState,
    localhostState: LocalhostUiState,
    webController: InAppWebWorkspaceController,
    onAddServerClick: () -> Unit,
    onNewTermuxLocalhostClick: () -> Unit,
    onSelectProfile: (ServerProfile) -> Unit,
    onBackToServers: () -> Unit,
    onOpenUserTerminal: () -> Unit,
    onOpenPhoneFiles: () -> Unit,
    onOpenAiChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onRecreateWebView: () -> Unit,
    onToggleDevBar: () -> Unit,
    onRequestChromeWithPermission: (String) -> Unit,
    onOpenTermuxWithCommand: (String) -> Unit,
    onVerifyExternalTermux: () -> Unit,
    onStartOfflineLocalEditor: () -> Unit,
    onRetryProvisioning: () -> Unit,
    onDismissDraftError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webBackgroundArgb = MaterialTheme.colorScheme.background.toArgb()
    var showToolbar by remember(preferences.immersiveFullscreen) {
        mutableStateOf(!preferences.immersiveFullscreen)
    }
    var showTermuxCommandDialog by remember { mutableStateOf(false) }

    val hasActiveDraftOverlay = localhostState.draftProfileId != null && (
        localhostState.isProvisioning ||
            localhostState.provisioningState == WorkspaceProvisioningState.WAITING_FOR_INSTALLATION ||
            localhostState.provisioningState == WorkspaceProvisioningState.FAILED
        )

    Box(modifier = modifier.fillMaxSize()) {
        if (activeProfile == null && !hasActiveDraftOverlay) {
            WorkspaceOnboardingEmptyState(
                profiles = allProfiles,
                onAddServerClick = onAddServerClick,
                onNewTermuxLocalhostClick = onNewTermuxLocalhostClick,
                onSelectProfile = onSelectProfile,
            )
        } else if (activeProfile == null) {
            // Draft workspace being provisioned or awaiting runtime setup before activation
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            )
        } else {
            val effectivePassword = activeProfile.passwordHint.ifBlank {
                if (activeProfile.useMasterAutoLogin) preferences.masterPasswordOrToken else ""
            }

            Column(modifier = Modifier.fillMaxSize()) {
                if (showToolbar) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 2.dp,
                    ) {
                        androidx.compose.foundation.layout.BoxWithConstraints(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val maxActionStripWidth = (maxWidth * 0.58f).coerceAtLeast(140.dp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = onBackToServers,
                                    modifier = Modifier.testTag("workspace_back_to_servers_button"),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back to servers",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 4.dp),
                                ) {
                                    Text(
                                        text = activeProfile.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = if (activeProfile.runtimeKind != com.example.data.model.LocalhostRuntimeKind.REMOTE_CLOUD_IDE) {
                                            localhostState.activeStatus?.sourceLabel
                                                ?: webState.currentUrl.ifBlank { activeProfile.url }
                                        } else {
                                            webState.currentUrl.ifBlank { activeProfile.url }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                val actionStripScroll = rememberScrollState()
                                Row(
                                    modifier = Modifier
                                        .widthIn(max = maxActionStripWidth)
                                        .horizontalScroll(actionStripScroll)
                                        .mouseWheelHorizontalScroll(actionStripScroll),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (webState.zoomPercent != WorkspaceKeyboardShortcuts.DEFAULT_ZOOM_PERCENT) {
                                        AssistChip(
                                            onClick = { webController.resetZoom() },
                                            label = { Text("${webState.zoomPercent}%") },
                                            modifier = Modifier
                                                .padding(end = 4.dp)
                                                .testTag("workspace_zoom_reset_chip"),
                                        )
                                    }
                                    IconButton(
                                        onClick = { webController.reload() },
                                        modifier = Modifier.testTag("workspace_reload_button"),
                                    ) {
                                        Icon(Icons.Filled.Refresh, contentDescription = "Reload", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = { webController.setDesktopMode(!webState.isDesktopMode) },
                                        modifier = Modifier.testTag("workspace_desktop_ua_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Computer,
                                            contentDescription = "Toggle desktop mode",
                                            modifier = Modifier.size(18.dp),
                                            tint = if (webState.isDesktopMode) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                    if (effectivePassword.isNotBlank()) {
                                        IconButton(
                                            onClick = { webController.autofillPassword(effectivePassword) },
                                            modifier = Modifier.testTag("workspace_autofill_button"),
                                        ) {
                                            Icon(Icons.Filled.Key, contentDescription = "Autofill password", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    if (localhostState.activeStatus != null) {
                                        IconButton(
                                            onClick = { showTermuxCommandDialog = true },
                                            modifier = Modifier.testTag("workspace_termux_cmd_button"),
                                        ) {
                                            Icon(Icons.Filled.Terminal, contentDescription = "Termux script", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    IconButton(
                                        onClick = onToggleDevBar,
                                        modifier = Modifier.testTag("workspace_toggle_devbar_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Keyboard,
                                            contentDescription = "Toggle shortcut keys",
                                            modifier = Modifier.size(18.dp),
                                            tint = if (preferences.showDevShortcutBar) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                    IconButton(
                                        onClick = onOpenUserTerminal,
                                        modifier = Modifier.testTag("workspace_user_terminal_button"),
                                    ) {
                                        Icon(Icons.Filled.Terminal, contentDescription = "User Terminal", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = onOpenPhoneFiles,
                                        modifier = Modifier.testTag("workspace_phone_files_button"),
                                    ) {
                                        Icon(Icons.Filled.FolderCopy, contentDescription = "Phone files", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = onOpenAiChat,
                                        modifier = Modifier.testTag("workspace_ai_chat_button"),
                                    ) {
                                        Icon(Icons.Filled.AutoAwesome, contentDescription = "Gemini AI", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = onOpenSettings,
                                        modifier = Modifier.testTag("workspace_settings_button"),
                                    ) {
                                        Icon(Icons.Filled.Settings, contentDescription = "Settings", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            onRequestChromeWithPermission(
                                                webState.currentUrl.ifBlank { activeProfile.url },
                                            )
                                        },
                                        modifier = Modifier.testTag("workspace_chrome_permission_button"),
                                    ) {
                                        Icon(Icons.Filled.OpenInBrowser, contentDescription = "Open in Chrome (asks permission)", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    key(webState.webViewSessionSeq) {
                        AndroidView(
                            factory = { ctx ->
                                webController.getOrCreateMainView(
                                    context = ctx,
                                    backgroundColorArgb = webBackgroundArgb,
                                ).also { view ->
                                    (view.parent as? ViewGroup)?.removeView(view)
                                }
                            },
                            update = { view ->
                                webController.updateHostContext(context)
                                view.setBackgroundColor(webBackgroundArgb)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("code_server_webview"),
                        )
                    }

                    if (!showToolbar) {
                        FilledTonalIconButton(
                            onClick = { showToolbar = true },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .testTag("workspace_show_toolbar_button"),
                        ) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = "Show workspace controls",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = !localhostState.isProvisioning &&
                            webState.status == ConnectionStatus.LOADING &&
                            !webState.isDomVerified,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        WorkspaceLoadingOverlay(
                            serverName = activeProfile.name,
                            statusLabel = if (webState.progress >= 95) {
                                "Verifying rendered IDE workspace…"
                            } else {
                                "Loading workspace inside app (${webState.progress.coerceIn(0, 100)}%)…"
                            },
                            url = webState.currentUrl.ifBlank { activeProfile.url },
                            progress = webState.progress,
                            isProvisioning = false,
                            onBackToServers = onBackToServers,
                        )
                    }

                    if (webState.status == ConnectionStatus.LOADING && webState.progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { webState.progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .align(Alignment.TopCenter),
                        )
                    }

                    val hasLocalhostError = !localhostState.isProvisioning &&
                        !localhostState.errorMessage.isNullOrBlank() &&
                        !hasActiveDraftOverlay
                    if (webState.status == ConnectionStatus.ERROR || hasLocalhostError) {
                        ConnectionErrorOverlay(
                            url = webState.currentUrl.ifBlank { activeProfile.url },
                            errorMessage = webState.errorMessage
                                ?: localhostState.errorMessage
                                ?: "Unable to reach server",
                            canRecreateWebView = webState.isBlankScreenDetected || webState.rendererCrashCount > 0,
                            onRetry = {
                                if (hasLocalhostError && localhostState.activeStatus?.isRunning != true) {
                                    onSelectProfile(activeProfile)
                                } else {
                                    webController.reload()
                                }
                            },
                            onRecreateWebView = onRecreateWebView,
                            onOpenUserTerminal = onOpenUserTerminal,
                            onRequestChrome = {
                                onRequestChromeWithPermission(
                                    webState.currentUrl.ifBlank { activeProfile.url },
                                )
                            },
                            onBackToServers = onBackToServers,
                        )
                    }
                }

                if (preferences.showDevShortcutBar) {
                    DeveloperShortcutBar(
                        onDispatchKey = { key, ctrl, alt, shift ->
                            webController.dispatchDevKey(key, ctrl, alt, shift)
                        },
                        onHideBar = onToggleDevBar,
                    )
                }
            }
        }

        // Truthful Provisioning State Machine Overlays (GENERATING/STARTING/VERIFYING, WAITING_FOR_INSTALLATION, FAILED)
        if (localhostState.isProvisioning) {
            val stepProgress = when (localhostState.provisioningState) {
                WorkspaceProvisioningState.GENERATING -> 25
                WorkspaceProvisioningState.GENERATED -> 50
                WorkspaceProvisioningState.STARTING -> 70
                WorkspaceProvisioningState.VERIFYING -> 90
                else -> 40
            }
            WorkspaceLoadingOverlay(
                serverName = "${localhostState.runtimeKind.label} (${localhostState.provisioningState.label})",
                statusLabel = localhostState.statusMessage.ifBlank { "Provisioning localhost workspace..." },
                url = "http://127.0.0.1:${localhostState.pendingTargetPort}",
                progress = stepProgress,
                isProvisioning = true,
                onBackToServers = {
                    onDismissDraftError()
                    onBackToServers()
                },
            )
        } else if (localhostState.provisioningState == WorkspaceProvisioningState.WAITING_FOR_INSTALLATION) {
            WaitingForTermuxInstallOverlay(
                port = localhostState.pendingTargetPort,
                termuxInstalled = localhostState.termuxInstalled,
                termuxCommand = localhostState.pendingTermuxCommand.ifBlank {
                    "echo 'Create an external Termux workspace to generate its password-protected setup command.'"
                },
                notice = localhostState.geminiNotice,
                onCopyAndOpenTermux = {
                    onOpenTermuxWithCommand(
                        localhostState.pendingTermuxCommand.ifBlank {
                            "echo 'Create an external Termux workspace to generate its password-protected setup command.'"
                        },
                    )
                },
                onVerifyAndConnect = onVerifyExternalTermux,
                onSwitchToOfflineLocalEditor = onStartOfflineLocalEditor,
                onBackToServers = {
                    onDismissDraftError()
                    onBackToServers()
                },
            )
        } else if (localhostState.provisioningState == WorkspaceProvisioningState.FAILED &&
            !localhostState.errorMessage.isNullOrBlank() &&
            localhostState.draftProfileId != null
        ) {
            ProvisioningFailedOverlay(
                errorMessage = localhostState.errorMessage,
                canOfferOfflineFallback = localhostState.canOfferOfflineFallback,
                onRetryGemini = onRetryProvisioning,
                onStartOfflineLocalEditor = onStartOfflineLocalEditor,
                onBackToServers = {
                    onDismissDraftError()
                    onBackToServers()
                },
            )
        }
    }

    if (showTermuxCommandDialog && localhostState.activeStatus != null) {
        val status = localhostState.activeStatus
        AlertDialog(
            onDismissRequest = { showTermuxCommandDialog = false },
            title = { Text("Termux code-server Command") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!localhostState.geminiNotice.isNullOrBlank()) {
                        Text(
                            text = localhostState.geminiNotice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = "If Termux did not start automatically, enable allow-external-apps in Termux and grant VS Shell the Run Commands in Termux permission. Then copy and run this password-protected code-server setup:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = status.termuxBootstrapCommand,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTermuxCommandDialog = false
                        onOpenTermuxWithCommand(status.termuxBootstrapCommand)
                    },
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy & Open Termux")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTermuxCommandDialog = false }) {
                    Text("Close")
                }
            },
        )
    }
}

@Composable
private fun WorkspaceLoadingOverlay(
    serverName: String,
    statusLabel: String,
    url: String,
    progress: Int,
    isProvisioning: Boolean,
    onBackToServers: () -> Unit,
) {
    val overlayScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.90f))
            .pointerInput(Unit) {}
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 400.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(overlayScroll)
                    .mouseWheelScroll(overlayScroll)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isProvisioning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        text = serverName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { (progress.coerceIn(5, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onBackToServers,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Back to Servers")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceOnboardingEmptyState(
    profiles: List<ServerProfile>,
    onAddServerClick: () -> Unit,
    onNewTermuxLocalhostClick: () -> Unit,
    onSelectProfile: (ServerProfile) -> Unit,
) {
    val onboardingScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(onboardingScroll)
            .mouseWheelScroll(onboardingScroll)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 540.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column {
                    Image(
                        painter = painterResource(id = R.drawable.img_workspace_hero),
                        contentDescription = "VS Shell workspace illustration",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(118.dp),
                    )
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "In-App Code-Server Workspace",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            text = "Launch Termux localhost, built-in local code server, Tailscale, or VS Code Web strictly inside the app—never opening Chrome without your permission.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onNewTermuxLocalhostClick,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_new_localhost_button"),
                        ) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("New Termux Localhost (Gemini)")
                        }
                        OutlinedButton(
                            onClick = onAddServerClick,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_add_server_button"),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Custom Server")
                        }
                    }
                }
            }

            if (profiles.isNotEmpty()) {
                Text("Saved Servers", style = MaterialTheme.typography.titleMedium)
                profiles.forEach { profile ->
                    Card(
                        onClick = { onSelectProfile(profile) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    profile.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    profile.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Filled.Terminal, contentDescription = "Launch in app", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionErrorOverlay(
    url: String,
    errorMessage: String,
    canRecreateWebView: Boolean,
    onRetry: () -> Unit,
    onRecreateWebView: () -> Unit,
    onOpenUserTerminal: () -> Unit,
    onRequestChrome: () -> Unit,
    onBackToServers: () -> Unit,
) {
    val errorScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
            .pointerInput(Unit) {}
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 420.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(errorScroll)
                    .mouseWheelScroll(errorScroll)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Workspace Connection Issue",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$errorMessage You can retry inside the app, recreate the WebView, or open User Terminal.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Retry In App", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (canRecreateWebView) {
                        OutlinedButton(
                            onClick = onRecreateWebView,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("recreate_webview_button"),
                        ) {
                            Text("Recreate WebView", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onRequestChrome,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Ask Chrome", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onOpenUserTerminal,
                        modifier = Modifier.testTag("error_open_user_terminal_button"),
                    ) {
                        Text("User Terminal")
                    }
                    AssistChip(
                        onClick = onBackToServers,
                        label = { Text("Back to Servers") },
                    )
                }
            }
        }
    }
}

@Composable
private fun WaitingForTermuxInstallOverlay(
    port: Int,
    termuxInstalled: Boolean,
    termuxCommand: String,
    notice: String?,
    onCopyAndOpenTermux: () -> Unit,
    onVerifyAndConnect: () -> Unit,
    onSwitchToOfflineLocalEditor: () -> Unit,
    onBackToServers: () -> Unit,
) {
    val waitScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
            .pointerInput(Unit) {}
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 440.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(waitScroll)
                    .mouseWheelScroll(waitScroll)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Termux code-server Not Running Yet (:$port)",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!notice.isNullOrBlank()) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = if (termuxInstalled) {
                        "Starter files and command are ready. Termux requires manual execution: copy and run the command below in Termux, then return and tap Verify & Connect."
                    } else {
                        "Starter files and command are ready, but Termux (com.termux) is not installed on this device. Install Termux to run code-server on :$port, or switch to the Built-in Local Editor."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = termuxCommand,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(
                        onClick = onVerifyAndConnect,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("verify_termux_connect_button"),
                    ) {
                        Text("Verify & Connect", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onCopyAndOpenTermux,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("copy_open_termux_button"),
                    ) {
                        Text(if (termuxInstalled) "Open Termux" else "Copy Command", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onSwitchToOfflineLocalEditor,
                        modifier = Modifier.testTag("switch_offline_local_editor_button"),
                    ) {
                        Text("Fallback File Viewer")
                    }
                    TextButton(onClick = onBackToServers) {
                        Text("Back to Servers")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProvisioningFailedOverlay(
    errorMessage: String,
    canOfferOfflineFallback: Boolean,
    onRetryGemini: () -> Unit,
    onStartOfflineLocalEditor: () -> Unit,
    onBackToServers: () -> Unit,
) {
    val failedScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
            .pointerInput(Unit) {}
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 440.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(failedScroll)
                    .mouseWheelScroll(failedScroll)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Workspace Provisioning Failed",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(
                        onClick = onRetryGemini,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("retry_gemini_provisioning_button"),
                    ) {
                        Text("Retry", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (canOfferOfflineFallback) {
                        OutlinedButton(
                            onClick = onStartOfflineLocalEditor,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("start_offline_local_editor_button"),
                        ) {
                            Text("Fallback File Viewer", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                AssistChip(
                    onClick = onBackToServers,
                    label = { Text("Back to Servers") },
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}
