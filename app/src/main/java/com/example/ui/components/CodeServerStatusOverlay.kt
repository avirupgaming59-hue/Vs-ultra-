package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ServerProfile
import com.example.data.model.WorkspaceProvisioningState
import com.example.service.WatchdogHealthStatus
import com.example.terminal.TerminalLine
import com.example.terminal.TerminalTone
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StatusConnectedGreen
import com.example.ui.theme.StatusConnectingAmber
import com.example.ui.theme.StatusOfflineRed
import com.example.ui.viewmodel.LocalhostUiState
import com.example.workspace.ConnectionStatus
import com.example.workspace.WatchdogUiState
import com.example.workspace.WebViewSessionState
import com.example.workspace.WorkspaceKeyboardShortcuts

@Composable
fun CodeServerStatusOverlay(
    activeProfile: ServerProfile?,
    localhostState: LocalhostUiState,
    watchdogState: WatchdogUiState = WatchdogUiState(),
    webState: WebViewSessionState,
    terminalLines: List<TerminalLine>,
    isTerminalExpanded: Boolean,
    onToggleTerminal: () -> Unit,
    onRestartServer: () -> Unit,
    onExecuteCommand: (String) -> Unit,
    onClearLogs: () -> Unit,
    onCopyLogs: () -> Unit,
    onExportLogs: () -> Unit,
    onShareLogs: () -> Unit,
    bottomClearanceDp: Dp = 10.dp,
    onTerminalInputFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val activeLocal = localhostState.activeStatus
    val pState = localhostState.provisioningState
    val isWatchdogBackoff = watchdogState.status == WatchdogHealthStatus.WAITING_BACKOFF ||
        watchdogState.status == WatchdogHealthStatus.REBOOTING
    val isCircuitOpen = watchdogState.status == WatchdogHealthStatus.CIRCUIT_OPEN
    val isConnecting = localhostState.isProvisioning ||
        isWatchdogBackoff ||
        pState == WorkspaceProvisioningState.WAITING_FOR_INSTALLATION ||
        webState.status == ConnectionStatus.LOADING
    val isError = isCircuitOpen ||
        pState == WorkspaceProvisioningState.FAILED ||
        (!isWatchdogBackoff && (webState.status == ConnectionStatus.ERROR || (activeLocal != null && !activeLocal.isRunning && pState != WorkspaceProvisioningState.STOPPED)))
    val isConnected = !isError && !isConnecting && pState != WorkspaceProvisioningState.STOPPED && (
        activeLocal?.isRunning == true || webState.status == ConnectionStatus.CONNECTED
    )

    val statusColor = when {
        isError -> StatusOfflineRed
        isConnecting -> StatusConnectingAmber
        isConnected -> StatusConnectedGreen
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusTitle = when {
        watchdogState.status == WatchdogHealthStatus.WAITING_BACKOFF ->
            "Reboot in ${watchdogState.nextRetryInSec}s"
        watchdogState.status == WatchdogHealthStatus.REBOOTING -> "Rebooting..."
        isCircuitOpen -> "Crash-Loop Guard"
        pState == WorkspaceProvisioningState.GENERATING -> "Gemini Generating..."
        pState == WorkspaceProvisioningState.STARTING -> "Starting Editor..."
        pState == WorkspaceProvisioningState.VERIFYING -> "Verifying HTTP..."
        pState == WorkspaceProvisioningState.WAITING_FOR_INSTALLATION -> "Waiting Termux :${localhostState.pendingTargetPort}"
        pState == WorkspaceProvisioningState.STOPPED -> "Stopped"
        pState == WorkspaceProvisioningState.FAILED -> "Provisioning Failed"
        isError -> "Disconnected"
        activeLocal?.isRunning == true && !activeLocal.isEmbeddedServer -> "code-server :${activeLocal.port}"
        activeLocal?.isRunning == true -> "AI Server :${activeLocal.port}"
        webState.status == ConnectionStatus.LOADING -> "Connecting..."
        webState.status == ConnectionStatus.CONNECTED -> activeProfile?.name ?: "Connected"
        else -> "Standby"
    }

    var commandInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val chipsScrollState = rememberScrollState()

    LaunchedEffect(terminalLines.size, isTerminalExpanded) {
        if (isTerminalExpanded && terminalLines.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(terminalLines.lastIndex) }
        }
    }

    AnimatedVisibility(
        visible = isTerminalExpanded,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)),
        exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)),
    ) {
        // Full-screen modal backdrop when expanded so outside taps close the drawer and WebView doesn't bleed focus
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleTerminal,
                )
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(14.dp),
                            )
                            .testTag("terminal_emulator_panel"),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "AI Runtime Terminal & Logs",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = if (watchdogState.isEnabled) {
                                            "${activeLocal?.sourceLabel ?: "Localhost"} • Watchdog: ${watchdogState.message}"
                                        } else {
                                            activeLocal?.sourceLabel
                                                ?: localhostState.statusMessage.ifBlank { "AI-managed runtime output" }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(
                                    onClick = onExportLogs,
                                    modifier = Modifier.testTag("terminal_export_logs_button"),
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = "Export logs as text file")
                                }
                                IconButton(
                                    onClick = onCopyLogs,
                                    modifier = Modifier.testTag("terminal_copy_logs_button"),
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy terminal logs")
                                }
                                IconButton(
                                    onClick = onClearLogs,
                                    modifier = Modifier.testTag("terminal_clear_logs_button"),
                                ) {
                                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear terminal logs")
                                }
                                IconButton(
                                    onClick = onToggleTerminal,
                                    modifier = Modifier.testTag("terminal_close_button"),
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close terminal")
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(chipsScrollState)
                                    .mouseWheelHorizontalScroll(chipsScrollState),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AssistChip(
                                    onClick = onRestartServer,
                                    label = { Text("Restart") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    modifier = Modifier.testTag("terminal_chip_restart"),
                                )
                                AssistChip(
                                    onClick = onExportLogs,
                                    label = { Text("Export .txt") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    modifier = Modifier.testTag("terminal_chip_export"),
                                )
                                AssistChip(
                                    onClick = onShareLogs,
                                    label = { Text("Share") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    modifier = Modifier.testTag("terminal_chip_share"),
                                )
                                AssistChip(
                                    onClick = { onExecuteCommand("crash-log") },
                                    label = { Text("Crash Log") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    modifier = Modifier.testTag("terminal_chip_crash_log"),
                                )
                                AssistChip(
                                    onClick = { onExecuteCommand("status") },
                                    label = { Text("Status") },
                                    modifier = Modifier.testTag("terminal_chip_status"),
                                )
                                AssistChip(
                                    onClick = { onExecuteCommand("files") },
                                    label = { Text("Files") },
                                    modifier = Modifier.testTag("terminal_chip_files"),
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF0B0E14),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 130.dp, max = 230.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                            ) {
                                if (terminalLines.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "Waiting for AI runtime logs...",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color(0xFF8B949E),
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .mouseWheelScroll(listState)
                                            .padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        items(terminalLines, key = { it.id }) { line ->
                                            TerminalLineRow(line = line)
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OutlinedTextField(
                                    value = commandInput,
                                    onValueChange = { commandInput = it },
                                    placeholder = {
                                        Text("status, restart, files, crash-log, help")
                                    },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                    ),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(
                                        onSend = {
                                            if (commandInput.isNotBlank()) {
                                                onExecuteCommand(commandInput)
                                                commandInput = ""
                                            }
                                        },
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .onFocusChanged { onTerminalInputFocusChanged(it.isFocused) }
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
                                            if (submit && commandInput.isNotBlank()) {
                                                onExecuteCommand(commandInput)
                                                commandInput = ""
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                        .testTag("terminal_command_input"),
                                )
                                IconButton(
                                    onClick = {
                                        if (commandInput.isNotBlank()) {
                                            onExecuteCommand(commandInput)
                                            commandInput = ""
                                        }
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("terminal_command_send_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Run terminal command",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }

                CompactStatusPill(
                    statusColor = statusColor,
                    statusTitle = statusTitle,
                    isTerminalExpanded = true,
                    onToggleTerminal = onToggleTerminal,
                    onRestartServer = onRestartServer,
                )
            }
        }
    }

    if (!isTerminalExpanded) {
        // Collapsed floating pill docked cleanly at BottomEnd so WorkspaceScreen's TopStart Back button & toolbar are never overlapped
        Box(
            modifier = modifier
                .navigationBarsPadding()
                .padding(end = 10.dp, bottom = bottomClearanceDp),
        ) {
            CompactStatusPill(
                statusColor = statusColor,
                statusTitle = statusTitle,
                isTerminalExpanded = false,
                onToggleTerminal = onToggleTerminal,
                onRestartServer = onRestartServer,
            )
        }
    }
}

@Composable
private fun CompactStatusPill(
    statusColor: Color,
    statusTitle: String,
    isTerminalExpanded: Boolean,
    onToggleTerminal: () -> Unit,
    onRestartServer: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = statusColor.copy(alpha = 0.45f),
                shape = RoundedCornerShape(18.dp),
            )
            .testTag("code_server_status_overlay_pill"),
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 210.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onToggleTerminal)
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .testTag("overlay_toggle_terminal_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = "Toggle server terminal logs",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = statusTitle,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (isTerminalExpanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.Filled.KeyboardArrowUp
                    },
                    contentDescription = if (isTerminalExpanded) "Collapse logs" else "Expand logs",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FilledTonalButton(
                onClick = onRestartServer,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("overlay_restart_server_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Restart local server",
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text("Restart", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TerminalLineRow(line: TerminalLine) {
    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(color = Color(0xFF6E7681))) {
            append("${line.timestamp} ")
        }
        withStyle(SpanStyle(color = Color(0xFF58A6FF), fontWeight = FontWeight.SemiBold)) {
            append("[${line.source}] ")
        }
        line.spans.forEach { span ->
            val color = when (span.tone) {
                TerminalTone.DEFAULT -> Color(0xFFE6EDF3)
                TerminalTone.INFO -> Color(0xFF56D4DD)
                TerminalTone.SUCCESS -> Color(0xFF3FB950)
                TerminalTone.WARN -> Color(0xFFD29922)
                TerminalTone.ERROR -> Color(0xFFF85149)
                TerminalTone.MUTED -> Color(0xFF8B949E)
            }
            withStyle(
                SpanStyle(
                    color = color,
                    fontWeight = if (span.bold) FontWeight.Bold else FontWeight.Normal,
                ),
            ) {
                append(span.text)
            }
        }
    }
    Text(
        text = annotated,
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
    )
}
