package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.example.data.model.ChatMessageEntity
import com.example.ui.components.mouseWheelHorizontalScroll
import com.example.ui.components.mouseWheelScroll
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.viewmodel.ChatRolePreset
import com.example.ui.viewmodel.ChatStreamState
import com.example.workspace.WorkspaceKeyboardShortcuts

@Composable
fun AiChatScreen(
    messages: List<ChatMessageEntity>,
    selectedRole: ChatRolePreset,
    streamState: ChatStreamState,
    activeServerLabel: String?,
    onSelectRole: (ChatRolePreset) -> Unit,
    onSendMessage: (String) -> Unit,
    onCancelGeneration: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onClearHistory: () -> Unit,
    onCopyToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val isGenerating = streamState is ChatStreamState.Loading || streamState is ChatStreamState.Streaming

    val isStreaming = streamState is ChatStreamState.Streaming
    LaunchedEffect(messages.size, isStreaming) {
        val totalItems = messages.size + if (isStreaming) 1 else 0
        if (totalItems > 0) {
            runCatching { listState.animateScrollToItem(totalItems - 1) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
                .padding(horizontal = 12.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Gemini Coding Assistant", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${selectedRole.title} (${selectedRole.modelId})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (messages.isNotEmpty()) {
                    IconButton(
                        onClick = onClearHistory,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("clear_chat_history_button"),
                    ) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = "Clear conversation",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            val rolesScrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rolesScrollState)
                    .mouseWheelHorizontalScroll(rolesScrollState),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChatRolePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = selectedRole == preset,
                        onClick = { onSelectRole(preset) },
                        label = { Text("${preset.title} · ${preset.shortBadge}") },
                        modifier = Modifier.testTag("chat_role_${preset.name}"),
                    )
                }
            }

            Text(
                text = selectedRole.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 3.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (messages.isEmpty() && streamState is ChatStreamState.Idle) {
                    EmptyChatStarter(
                        activeServerLabel = activeServerLabel,
                        onPromptSelected = { prompt ->
                            onSendMessage(prompt)
                        },
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .mouseWheelScroll(listState)
                            .testTag("chat_message_list"),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(messages, key = { it.id }) { msg ->
                        ChatMessageBubble(
                            isUser = msg.role == "user",
                            text = msg.text,
                            badge = if (msg.role == "user") "You" else "${msg.rolePresetName} · ${msg.modelName}",
                            onCopy = {
                                val copied = runCatching {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Gemini Reply", msg.text.take(96_000)))
                                    true
                                }.getOrDefault(false)
                                onCopyToast(if (copied) "Copied message to clipboard" else "Message too large to copy")
                            },
                        )
                    }

                    if (streamState is ChatStreamState.Loading) {
                        item(key = "chat_loading_indicator") {
                            Row(
                                modifier = Modifier.padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    text = streamState.statusNote,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (streamState is ChatStreamState.Streaming) {
                        item(key = "chat_streaming_bubble") {
                            ChatMessageBubble(
                                isUser = false,
                                text = streamState.partialText,
                                badge = "${selectedRole.title} · streaming",
                                onCopy = {},
                            )
                        }
                    }
                }
            }
        }

        if (streamState is ChatStreamState.Error) {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("chat_error_card"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = streamState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text("Retry") }
                    TextButton(onClick = onDismissError) { Text("Dismiss") }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Ask Gemini about code, terminal, or servers...") },
                textStyle = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                enabled = !isGenerating,
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { keyEvent ->
                        val native = keyEvent.nativeKeyEvent
                        val shouldSubmit = WorkspaceKeyboardShortcuts.shouldSubmitOnEnterKey(
                            keyCode = native.keyCode,
                            isKeyDown = keyEvent.type == KeyEventType.KeyDown,
                            repeatCount = native.repeatCount,
                            isShiftPressed = keyEvent.isShiftPressed,
                            isCtrlPressed = keyEvent.isCtrlPressed,
                            isAltPressed = keyEvent.isAltPressed,
                            isMetaPressed = keyEvent.isMetaPressed,
                        )
                        if (shouldSubmit && !isGenerating && inputText.isNotBlank()) {
                            val toSend = inputText
                            inputText = ""
                            onSendMessage(toSend)
                            true
                        } else {
                            false
                        }
                    }
                    .testTag("chat_input_field"),
            )

            if (isGenerating) {
                FilledTonalButton(
                    onClick = onCancelGeneration,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("chat_stop_button"),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop generating", modifier = Modifier.size(18.dp))
                }
            } else {
                Button(
                    onClick = {
                        val toSend = inputText
                        inputText = ""
                        onSendMessage(toSend)
                    },
                    enabled = inputText.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("chat_send_button"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message", modifier = Modifier.size(18.dp))
                }
            }
        }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    isUser: Boolean,
    text: String,
    badge: String,
    onCopy: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isUser) 12.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 12.dp,
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(modifier = Modifier.padding(9.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!isUser && text.isNotBlank()) {
                        IconButton(
                            onClick = onCopy,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy response",
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = if (!isUser && text.contains("```")) {
                            JetBrainsMonoFontFamily
                        } else {
                            MaterialTheme.typography.bodyMedium.fontFamily
                        },
                    ),
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyChatStarter(
    activeServerLabel: String?,
    onPromptSelected: (String) -> Unit,
) {
    val starters = listOf(
        "How do I start a GitHub VS Code Tunnel on my PC and connect here?",
        "Write a clean bash script to run code-server behind Tailscale Serve.",
        "Explain Python asyncio TaskGroup with a short practical example.",
        "How do Chrome Custom Tabs preserve Google/GitHub sign-in cookies safely?",
    )

    val emptyScrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(emptyScrollState)
            .mouseWheelScroll(emptyScrollState)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Text("Ask Gemini About Code or Servers", style = MaterialTheme.typography.titleMedium)
            if (!activeServerLabel.isNullOrBlank()) {
                Text(
                    text = "Active server: $activeServerLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            starters.forEach { prompt ->
                AssistChip(
                    onClick = { onPromptSelected(prompt) },
                    label = { Text(prompt, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
