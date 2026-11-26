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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.model.ConnectionMode
import com.example.data.model.ServerProfile
import com.example.workspace.ChromeCustomTabsManager

@Composable
fun ServerProfileDialog(
    initialProfile: ServerProfile?,
    hasMasterPassword: Boolean,
    onSignInGithubInApp: () -> Unit,
    onDismiss: () -> Unit,
    onSaveAndConnect: (
        id: Long,
        name: String,
        url: String,
        mode: ConnectionMode,
        passwordHint: String,
        useDesktopUa: Boolean,
        autoConnect: Boolean,
        useMasterAutoLogin: Boolean,
    ) -> Unit,
) {
    var name by remember(initialProfile) {
        mutableStateOf(initialProfile?.name ?: "My VS Code Workspace")
    }
    var url by remember(initialProfile) {
        mutableStateOf(initialProfile?.url ?: "http://127.0.0.1:8080")
    }
    var mode by remember(initialProfile) {
        mutableStateOf(initialProfile?.connectionMode ?: ConnectionMode.DIRECT_CODE_SERVER)
    }
    var passwordHint by remember(initialProfile) {
        mutableStateOf(initialProfile?.passwordHint ?: "")
    }
    var useDesktopUa by remember(initialProfile) {
        mutableStateOf(initialProfile?.useDesktopUserAgent ?: false)
    }
    var autoConnect by remember(initialProfile) {
        mutableStateOf(initialProfile?.autoConnectOnLaunch ?: true)
    }
    var useMasterAutoLogin by remember(initialProfile) {
        mutableStateOf(initialProfile?.useMasterAutoLogin ?: true)
    }
    var urlError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialProfile == null) "Add Code-Server Profile" else "Edit Server Profile",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            val dialogScroll = rememberScrollState()
            val modeScroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(dialogScroll)
                    .mouseWheelScroll(dialogScroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Connection Mode",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(modeScroll)
                        .mouseWheelHorizontalScroll(modeScroll),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ConnectionMode.entries.forEach { item ->
                        FilterChip(
                            selected = mode == item,
                            onClick = { mode = item },
                            label = { Text(item.shortLabel) },
                            modifier = Modifier.testTag("mode_chip_${item.name}"),
                        )
                    }
                }

                Text(
                    text = mode.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_name_input"),
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        urlError = null
                    },
                    label = { Text("Server URL (Localhost / Tailscale / HTTPS)") },
                    isError = urlError != null,
                    supportingText = urlError?.let { msg -> { Text(msg) } },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_url_input"),
                )

                val quickPresetsScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(quickPresetsScroll)
                        .mouseWheelHorizontalScroll(quickPresetsScroll),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = {
                            mode = ConnectionMode.DIRECT_CODE_SERVER
                            name = "Termux Localhost :8080"
                            url = "http://127.0.0.1:8080"
                            urlError = null
                        },
                        label = { Text("Termux :8080") },
                    )
                    AssistChip(
                        onClick = {
                            mode = ConnectionMode.CLOUD_IDE_WEB
                            name = "VS Code Web + Tunnel"
                            url = "https://vscode.dev"
                            urlError = null
                        },
                        label = { Text("VS Code Web") },
                    )
                    AssistChip(
                        onClick = {
                            mode = ConnectionMode.CLOUD_IDE_WEB
                            name = "GitHub Codespaces"
                            url = "https://github.com/codespaces"
                            urlError = null
                        },
                        label = { Text("Codespaces") },
                    )
                    AssistChip(
                        onClick = {
                            mode = ConnectionMode.CLOUD_IDE_WEB
                            name = "Project IDX / Studio"
                            url = "https://idx.google.com"
                            urlError = null
                        },
                        label = { Text("Project IDX") },
                    )
                    AssistChip(
                        onClick = {
                            mode = ConnectionMode.TAILSCALE_CUSTOM
                            name = "Tailscale PC"
                            url = "https://pc-name.tailnet.ts.net"
                            urlError = null
                        },
                        label = { Text("Tailscale") },
                    )
                }

                OutlinedButton(
                    onClick = onSignInGithubInApp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_github_signin_button"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sign in GitHub (In-App Popup)")
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { useMasterAutoLogin = !useMasterAutoLogin }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Master Auto-Login", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (hasMasterPassword) {
                                "Autofills saved master password in app"
                            } else {
                                "Set a default password in Servers > Master Pass"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = useMasterAutoLogin,
                        onCheckedChange = { useMasterAutoLogin = it },
                        modifier = Modifier
                            .scale(0.85f)
                            .testTag("master_auto_login_switch"),
                    )
                }

                OutlinedTextField(
                    value = passwordHint,
                    onValueChange = { passwordHint = it },
                    label = { Text("Server-Specific Password (Optional)") },
                    placeholder = { Text("Leave blank to use Master Vault") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_password_input"),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { useDesktopUa = !useDesktopUa }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Desktop User-Agent", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Requests desktop layout inside in-app WebView",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = useDesktopUa,
                        onCheckedChange = { useDesktopUa = it },
                        modifier = Modifier
                            .scale(0.85f)
                            .testTag("desktop_ua_switch"),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { autoConnect = !autoConnect }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-start in Background", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Reconnect automatically inside app on startup",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoConnect,
                        onCheckedChange = { autoConnect = it },
                        modifier = Modifier
                            .scale(0.85f)
                            .testTag("auto_connect_switch"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanUrl = url.trim()
                    if (cleanUrl.isBlank() || ChromeCustomTabsManager.normalizeUrl(cleanUrl).isBlank()) {
                        urlError = "Enter a valid HTTP/HTTPS server URL"
                        return@Button
                    }
                    onSaveAndConnect(
                        initialProfile?.id ?: 0L,
                        name,
                        cleanUrl,
                        mode,
                        passwordHint,
                        useDesktopUa,
                        autoConnect,
                        useMasterAutoLogin,
                    )
                },
                modifier = Modifier.testTag("save_connect_server_button"),
            ) {
                Text("Save & Launch In App")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
