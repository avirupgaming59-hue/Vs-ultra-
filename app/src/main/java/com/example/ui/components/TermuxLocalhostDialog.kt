package com.example.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.LocalhostRuntimeKind

@Composable
fun TermuxLocalhostDialog(
    isCreating: Boolean = false,
    termuxInstalled: Boolean = false,
    hasBundledCodeServer: Boolean = false,
    onDismiss: () -> Unit,
    onCreateAndLaunch: (
        workspaceName: String,
        port: Int,
        stackHint: String,
        runtimeKind: LocalhostRuntimeKind,
        generateWithAi: Boolean,
    ) -> Unit,
) {
    var workspaceName by remember { mutableStateOf("Local Workspace") }
    var portText by remember { mutableStateOf("8080") }
    var stackHint by remember { mutableStateOf("HTML / Node.js / code-server") }
    var runtimeKind by remember { mutableStateOf(LocalhostRuntimeKind.EMBEDDED_EDITOR) }
    var generateWithAi by remember { mutableStateOf(true) }
    var portError by remember { mutableStateOf<String?>(null) }

    val stacks = listOf(
        "HTML / Node.js / code-server",
        "Python / FastAPI",
        "TypeScript / Vite",
        "Fullstack Web App",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "New Localhost Workspace",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            val dialogScroll = rememberScrollState()
            val runtimeChipScroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(dialogScroll)
                    .mouseWheelScroll(dialogScroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = when (runtimeKind) {
                        LocalhostRuntimeKind.CODE_SERVER_RUNTIME -> if (hasBundledCodeServer) {
                            "Starts the bundled native code-server inside VS Shell's single AI-managed runtime on 127.0.0.1 and verifies IDE readiness."
                        } else {
                            "Uses VS Shell's single AI-managed runtime to probe 127.0.0.1 for live code-server or bundled native binaries. Missing binaries are reported honestly with Managed Local Server fallback."
                        }
                        LocalhostRuntimeKind.EMBEDDED_EDITOR ->
                            "Starts VS Shell's single AI-managed local workspace server on 127.0.0.1 with HTTP readiness verification and file preservation."
                        LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER -> if (termuxInstalled) {
                            "Prepares starter files and a Termux code-server launch script. Paste & start it in Termux, then tap Verify & Connect."
                        } else {
                            "Termux (com.termux) was not detected. Starter files can still be created, or choose Managed Local Server for immediate execution."
                        }
                        LocalhostRuntimeKind.REMOTE_CLOUD_IDE -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Runtime Target", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(runtimeChipScroll)
                        .mouseWheelHorizontalScroll(runtimeChipScroll),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = runtimeKind == LocalhostRuntimeKind.EMBEDDED_EDITOR,
                        onClick = { runtimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR },
                        label = { Text("Managed Local Server") },
                        modifier = Modifier.testTag("runtime_chip_embedded"),
                    )
                    FilterChip(
                        selected = runtimeKind == LocalhostRuntimeKind.CODE_SERVER_RUNTIME,
                        onClick = { runtimeKind = LocalhostRuntimeKind.CODE_SERVER_RUNTIME },
                        label = { Text("AI Code-Server") },
                        modifier = Modifier.testTag("runtime_chip_vscode"),
                    )
                    FilterChip(
                        selected = runtimeKind == LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER,
                        onClick = { runtimeKind = LocalhostRuntimeKind.EXTERNAL_TERMUX_CODE_SERVER },
                        label = { Text("Termux code-server") },
                        modifier = Modifier.testTag("runtime_chip_termux"),
                    )
                }

                OutlinedTextField(
                    value = workspaceName,
                    onValueChange = { workspaceName = it },
                    label = { Text("Workspace Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("termux_localhost_name_input"),
                )

                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        portText = it.filter { ch -> ch.isDigit() }
                        portError = null
                    },
                    label = { Text("Localhost Port") },
                    isError = portError != null,
                    supportingText = portError?.let { msg -> { Text(msg) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("termux_localhost_port_input"),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { generateWithAi = !generateWithAi }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Generate starter with Gemini",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = if (generateWithAi) {
                                "Uses Gemini 3.8 Flash to build custom starter files"
                            } else {
                                "Creates offline starter files locally without AI"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = generateWithAi,
                        onCheckedChange = { generateWithAi = it },
                        modifier = Modifier.testTag("termux_localhost_ai_toggle"),
                    )
                }

                Text("Project Stack", style = MaterialTheme.typography.labelLarge)
                val stackScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(stackScroll)
                        .mouseWheelHorizontalScroll(stackScroll),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    stacks.forEach { preset ->
                        FilterChip(
                            selected = stackHint == preset,
                            onClick = { stackHint = preset },
                            label = { Text(preset) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isCreating,
                onClick = {
                    val port = portText.toIntOrNull()
                    if (port == null || port !in 1024..65535) {
                        portError = "Enter a port between 1024 and 65535"
                        return@Button
                    }
                    onCreateAndLaunch(workspaceName, port, stackHint, runtimeKind, generateWithAi)
                },
                modifier = Modifier.testTag("confirm_create_termux_localhost_button"),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text(if (isCreating) "Working..." else "Create & Start")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
