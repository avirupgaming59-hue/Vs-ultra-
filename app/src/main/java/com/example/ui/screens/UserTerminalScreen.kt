package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.example.runtime.MemorySnapshot
import com.example.runtime.PendingCommandConfirmation
import com.example.runtime.RuntimeBinaryInventory
import com.example.runtime.RuntimeInstanceStatus
import com.example.runtime.RuntimeLifecycleState
import com.example.terminal.TerminalLine
import com.example.terminal.TerminalTone
import com.example.ui.components.mouseWheelHorizontalScroll
import com.example.ui.components.mouseWheelScroll
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.workspace.WorkspaceKeyboardShortcuts

@Composable
fun UserTerminalScreen(
    userTerminalState: RuntimeInstanceStatus,
    terminalLines: List<TerminalLine>,
    commandHistory: List<String>,
    pendingConfirmation: PendingCommandConfirmation?,
    vsCodeRuntimeState: RuntimeInstanceStatus,
    vsCodeRuntimeLogs: List<TerminalLine>,
    binaryInventory: RuntimeBinaryInventory,
    memorySnapshot: MemorySnapshot,
    onStartUserTerminal: () -> Unit,
    onStopUserTerminal: () -> Unit,
    onRestartUserTerminal: () -> Unit,
    onInterruptUserTerminal: () -> Unit,
    onClearUserTerminalLogs: () -> Unit,
    onExecuteCommand: (String) -> Unit,
    onConfirmPendingCommand: () -> Unit,
    onDismissPendingCommand: () -> Unit,
    onStartVsCodeRuntime: () -> Unit,
    onStopVsCodeRuntime: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var commandInput by remember { mutableStateOf("") }
    var showVsCodeLogs by remember { mutableStateOf(false) }
    val terminalListState = rememberLazyListState()

    val displayedLines = remember(showVsCodeLogs, terminalLines, vsCodeRuntimeLogs) {
        if (showVsCodeLogs) vsCodeRuntimeLogs else terminalLines
    }

    LaunchedEffect(displayedLines.size) {
        if (displayedLines.isNotEmpty()) {
            terminalListState.animateScrollToItem(displayedLines.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 820.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Card 1: Independent Built-in User Terminal Lifecycle Header
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                userTerminalState.isRunning -> Color(0xFF4CAF50)
                                                userTerminalState.state == RuntimeLifecycleState.FAILED -> MaterialTheme.colorScheme.error
                                                else -> Color(0xFFFFB300)
                                            },
                                        ),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Built-in User Terminal (${userTerminalState.state.label})",
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "cwd: ${userTerminalState.workspacePath.ifBlank { "workspaces/default" }}" +
                                            (userTerminalState.pid?.let { " • PID $it" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Text(
                                text = "RAM ${memorySnapshot.availableMb}MB",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (memorySnapshot.lowMemory) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }

                        val headerActionsScroll = rememberScrollState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(headerActionsScroll)
                                .mouseWheelHorizontalScroll(headerActionsScroll),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (!userTerminalState.isRunning) {
                                FilledTonalButton(
                                    onClick = onStartUserTerminal,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("user_terminal_start_button"),
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Start Shell")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onStopUserTerminal,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("user_terminal_stop_button"),
                                ) {
                                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Stop")
                                }
                                OutlinedButton(
                                    onClick = onInterruptUserTerminal,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("user_terminal_interrupt_button"),
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("^C Reset")
                                }
                            }
                            OutlinedButton(
                                onClick = onRestartUserTerminal,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("user_terminal_restart_button"),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Restart")
                            }
                            IconButton(
                                onClick = onClearUserTerminalLogs,
                                modifier = Modifier.testTag("user_terminal_clear_button"),
                            ) {
                                Icon(Icons.Filled.ClearAll, contentDescription = "Clear terminal output")
                            }
                            IconButton(
                                onClick = {
                                    val text = displayedLines.joinToString("\n") { it.plainText }
                                    runCatching {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("User Terminal output", text.take(96_000)))
                                        onNotify("Copied terminal output to clipboard")
                                    }.onFailure {
                                        onNotify("Unable to copy terminal output")
                                    }
                                },
                                modifier = Modifier.testTag("user_terminal_copy_button"),
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy terminal output")
                            }
                        }

                        Text(
                            text = "Tools (${binaryInventory.systemShellPath.ifBlank { "no sh" }}): " +
                                binaryInventory.availableSystemTools.joinToString(", ").ifBlank { "none detected" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Card 2: Independent Dedicated VS Code Runtime Controller Card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Build,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (vsCodeRuntimeState.isRunning) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "AI-Managed Runtime (Internal): ${vsCodeRuntimeState.state.label} (${vsCodeRuntimeState.backendKind.label})",
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = vsCodeRuntimeState.errorMessage?.takeIf { it.isNotBlank() }
                                            ?: vsCodeRuntimeState.statusMessage,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (!vsCodeRuntimeState.errorMessage.isNullOrBlank()) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        val runtimeActionsScroll = rememberScrollState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(runtimeActionsScroll)
                                .mouseWheelHorizontalScroll(runtimeActionsScroll),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AssistChip(
                                onClick = onStartVsCodeRuntime,
                                label = { Text("Ensure AI Runtime") },
                                modifier = Modifier.testTag("vscode_runtime_start_button"),
                            )
                            if (vsCodeRuntimeState.isRunning) {
                                AssistChip(
                                    onClick = onStopVsCodeRuntime,
                                    label = { Text("Stop AI Runtime") },
                                    modifier = Modifier.testTag("vscode_runtime_stop_button"),
                                )
                                AssistChip(
                                    onClick = onOpenWorkspace,
                                    label = { Text("Open Workspace") },
                                )
                            }
                            AssistChip(
                                onClick = { showVsCodeLogs = !showVsCodeLogs },
                                label = { Text(if (showVsCodeLogs) "Show User Shell Log" else "Show AI Runtime Log") },
                                modifier = Modifier.testTag("toggle_runtime_log_view_button"),
                            )
                        }
                    }
                }

                // Quick Command & Persistent History Bar
                val quickCmdScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(quickCmdScroll)
                        .mouseWheelHorizontalScroll(quickCmdScroll),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val quickCmds = listOf("ws-files", "ws-tools", "pwd", "ls -la", "help")
                    quickCmds.forEach { cmd ->
                        AssistChip(
                            onClick = { onExecuteCommand(cmd) },
                            label = { Text(cmd, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    commandHistory.asReversed().distinct().take(6).forEach { prevCmd ->
                        if (prevCmd !in quickCmds) {
                            AssistChip(
                                onClick = { commandInput = prevCmd },
                                label = {
                                    Text(
                                        text = prevCmd.take(24),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                }

                // Live Terminal Output Buffer
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("user_terminal_output"),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF141820),
                ) {
                    SelectionContainer {
                        LazyColumn(
                            state = terminalListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .mouseWheelScroll(terminalListState),
                            contentPadding = PaddingValues(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            items(displayedLines, key = { it.id }) { line ->
                                val annotated = remember(line.id, line.spans) {
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(color = Color(0xFF7D8590))) {
                                            append("${line.timestamp} ")
                                        }
                                        withStyle(SpanStyle(color = Color(0xFF58A6FF), fontWeight = FontWeight.SemiBold)) {
                                            append("[${line.source}] ")
                                        }
                                        line.spans.forEach { span ->
                                            val toneColor = when (span.tone) {
                                                TerminalTone.DEFAULT -> Color(0xFFDDE3EC)
                                                TerminalTone.INFO -> Color(0xFF56D4DD)
                                                TerminalTone.SUCCESS -> Color(0xFF3FB950)
                                                TerminalTone.WARN -> Color(0xFFD29922)
                                                TerminalTone.ERROR -> Color(0xFFF85149)
                                                TerminalTone.MUTED -> Color(0xFF8B949E)
                                            }
                                            withStyle(
                                                SpanStyle(
                                                    color = toneColor,
                                                    fontWeight = if (span.bold) FontWeight.Bold else FontWeight.Normal,
                                                ),
                                            ) {
                                                append(span.text)
                                            }
                                        }
                                    }
                                }
                                Text(
                                    text = annotated,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                }

                // Command Input Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = commandInput,
                        onValueChange = { commandInput = it },
                        placeholder = { Text("Enter shell command (e.g. mkdir src && ls)...") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Terminal,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val cmd = commandInput.trim()
                                if (cmd.isNotEmpty()) {
                                    commandInput = ""
                                    onExecuteCommand(cmd)
                                }
                            },
                        ),
                        modifier = Modifier
                            .weight(1f)
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
                                    val cmd = commandInput.trim()
                                    if (cmd.isNotEmpty()) {
                                        commandInput = ""
                                        onExecuteCommand(cmd)
                                    }
                                    true
                                } else {
                                    false
                                }
                            }
                            .testTag("user_terminal_command_input"),
                    )
                    Button(
                        enabled = commandInput.isNotBlank(),
                        onClick = {
                            val cmd = commandInput.trim()
                            if (cmd.isNotEmpty()) {
                                commandInput = ""
                                onExecuteCommand(cmd)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        modifier = Modifier.testTag("user_terminal_run_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Run command",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = onDismissPendingCommand,
            title = { Text("Confirm Terminal Command") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = pending.reason,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            text = pending.command,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmPendingCommand,
                    modifier = Modifier.testTag("confirm_terminal_command_button"),
                ) {
                    Text("Run Command")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissPendingCommand,
                    modifier = Modifier.testTag("dismiss_terminal_command_button"),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}
