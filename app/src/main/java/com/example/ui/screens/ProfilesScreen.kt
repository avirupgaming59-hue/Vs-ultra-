package com.example.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.LocalhostRuntimeKind
import com.example.data.model.ServerProfile
import com.example.data.model.WorkspaceProvisioningState
import com.example.data.repository.AppPreferences
import com.example.ui.components.mouseWheelScroll

@Composable
fun ProfilesScreen(
    profiles: List<ServerProfile>,
    preferences: AppPreferences,
    isProvisioningLocalhost: Boolean = false,
    onAddProfile: () -> Unit,
    onNewTermuxLocalhost: () -> Unit,
    onConnectProfileInApp: (ServerProfile) -> Unit,
    onEditProfile: (ServerProfile) -> Unit,
    onTogglePin: (ServerProfile) -> Unit,
    onDeleteProfile: (ServerProfile) -> Unit,
    onRequestChromeWithPermission: (String) -> Unit,
    onSignInGithubInApp: () -> Unit,
    onSaveMasterCredentials: (label: String, passwordOrToken: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMasterVaultDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            SmallFloatingActionButton(
                onClick = onAddProfile,
                modifier = Modifier.testTag("add_server_profile_fab"),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Add server profile",
                    modifier = Modifier.size(18.dp),
                )
            }
        },
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
                    .widthIn(max = 760.dp)
                    .padding(horizontal = 12.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Server Profiles", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "All workspaces and code-servers open directly inside VS Shell.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Quick Termux Localhost Auto-Creator + In-App Vault Card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Instant Localhost & In-App Vault",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Create a local workspace with Gemini 3.8 Flash starter files, probe code-server on 127.0.0.1, or open in the built-in Terminal and Fallback File Viewer.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onNewTermuxLocalhost,
                            enabled = !isProvisioningLocalhost,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("new_termux_localhost_button"),
                        ) {
                            if (isProvisioningLocalhost) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isProvisioningLocalhost) {
                                    "Starting Localhost..."
                                } else {
                                    "New Termux Localhost (Auto Gemini)"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedButton(
                                onClick = onSignInGithubInApp,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("master_github_signin_button"),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("GitHub In-App", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = { showMasterVaultDialog = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("master_password_vault_button"),
                            ) {
                                Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    if (preferences.masterPasswordOrToken.isNotBlank()) "Pass Saved" else "Master Pass",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (profiles.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            modifier = Modifier.widthIn(max = 320.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text("No Server Profiles Saved", style = MaterialTheme.typography.titleMedium)
                            Button(
                                onClick = onAddProfile,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Text("Add Server")
                            }
                        }
                    }
                } else {
                    val profilesListState = rememberLazyListState()
                    LazyColumn(
                        state = profilesListState,
                        modifier = Modifier
                            .weight(1f)
                            .mouseWheelScroll(profilesListState),
                        contentPadding = PaddingValues(bottom = 72.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(profiles, key = { it.id }) { profile ->
                            ServerProfileCard(
                                profile = profile,
                                onConnectInApp = { onConnectProfileInApp(profile) },
                                onEdit = { onEditProfile(profile) },
                                onTogglePin = { onTogglePin(profile) },
                                onDelete = { onDeleteProfile(profile) },
                                onRequestChrome = { onRequestChromeWithPermission(profile.url) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showMasterVaultDialog) {
        MasterCredentialsDialog(
            initialLabel = preferences.masterAccountLabel,
            initialPassword = preferences.masterPasswordOrToken,
            onDismiss = { showMasterVaultDialog = false },
            onSave = { label, pwd ->
                showMasterVaultDialog = false
                onSaveMasterCredentials(label, pwd)
            },
        )
    }
}

@Composable
private fun MasterCredentialsDialog(
    initialLabel: String,
    initialPassword: String,
    onDismiss: () -> Unit,
    onSave: (label: String, passwordOrToken: String) -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var password by remember { mutableStateOf(initialPassword) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Master Auto-Login Vault") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Any server with Master Auto-Login enabled will automatically fill this password inside the in-app workspace.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Account Label (Optional)") },
                    placeholder = { Text("github-username or email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Default code-server Password / Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("master_vault_password_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(label, password) },
                modifier = Modifier.testTag("save_master_vault_button"),
            ) {
                Text("Save Vault")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ServerProfileCard(
    profile: ServerProfile,
    onConnectInApp: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onRequestChrome: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (profile.isActive) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    text = "Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (profile.runtimeKind != LocalhostRuntimeKind.REMOTE_CLOUD_IDE ||
                            profile.provisioningState != WorkspaceProvisioningState.READY
                        ) {
                            val badgeColor = when (profile.provisioningState) {
                                WorkspaceProvisioningState.READY -> MaterialTheme.colorScheme.secondaryContainer
                                WorkspaceProvisioningState.FAILED -> MaterialTheme.colorScheme.errorContainer
                                WorkspaceProvisioningState.WAITING_FOR_INSTALLATION -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                            val badgeTextColor = when (profile.provisioningState) {
                                WorkspaceProvisioningState.READY -> MaterialTheme.colorScheme.onSecondaryContainer
                                WorkspaceProvisioningState.FAILED -> MaterialTheme.colorScheme.onErrorContainer
                                WorkspaceProvisioningState.WAITING_FOR_INSTALLATION -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Surface(
                                color = badgeColor,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    text = profile.provisioningState.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeTextColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = profile.url,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${profile.runtimeKind.label} • ${profile.connectionMode.shortLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (profile.lastError.isNotBlank() &&
                        (profile.provisioningState == WorkspaceProvisioningState.FAILED ||
                            profile.provisioningState == WorkspaceProvisioningState.WAITING_FOR_INSTALLATION)
                    ) {
                        Text(
                            text = profile.lastError,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (profile.provisioningState == WorkspaceProvisioningState.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    IconButton(onClick = onTogglePin, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = if (profile.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "Pin profile",
                            modifier = Modifier.size(18.dp),
                            tint = if (profile.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit profile", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete profile", modifier = Modifier.size(18.dp))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = onConnectInApp,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1.2f)
                        .testTag("connect_profile_${profile.id}"),
                ) {
                    Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (profile.isActive) "Open In App" else "Launch In App",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                OutlinedButton(
                    onClick = onRequestChrome,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(0.85f),
                ) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Chrome?", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
