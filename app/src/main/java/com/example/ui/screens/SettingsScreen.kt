package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.repository.AppPreferences
import com.example.ui.components.mouseWheelScroll
import com.example.ui.theme.ThemePreset

@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    onSelectTheme: (ThemePreset) -> Unit,
    onToggleAutoLaunch: (Boolean) -> Unit,
    onToggleAiAutoCodeServer: (Boolean) -> Unit,
    onToggleAutoRebootWatchdog: (Boolean) -> Unit,
    onToggleKeepAliveService: (Boolean) -> Unit,
    onToggleImmersiveFullscreen: (Boolean) -> Unit,
    onToggleKeepScreenAwake: (Boolean) -> Unit,
    onToggleDevShortcutBar: (Boolean) -> Unit,
    onToggleScreenPrivacyShield: (Boolean) -> Unit,
    onSetAppPin: (String) -> Unit,
    onDisableAppPin: () -> Unit,
    onLockAppNow: () -> Unit,
    onClearCookiesAndCache: () -> Unit,
    onExportDiagnosticLogs: () -> Unit,
    onShareDiagnosticLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }

    val verticalScrollState = rememberScrollState()
    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(verticalScrollState)
            .mouseWheelScroll(verticalScrollState),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Settings & Themes", style = MaterialTheme.typography.headlineMedium)

            Text("App & IDE Theme", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemePreset.entries.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pair.forEach { preset ->
                            ThemePresetCard(
                                preset = preset,
                                selected = preferences.themePreset == preset,
                                onClick = { onSelectTheme(preset) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Text("Security & App Protection", style = MaterialTheme.typography.titleMedium)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = "Active security safeguards",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Keystore AES-256 Vault • Token-Protected Localhost • HTTPS Enforced • Backup Excluded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    SettingsSwitchRow(
                        title = "Screen Privacy Shield (FLAG_SECURE)",
                        subtitle = "Hide workspace contents from screenshots, screen recorders, and the system task switcher",
                        checked = preferences.screenPrivacyShieldEnabled,
                        onCheckedChange = onToggleScreenPrivacyShield,
                        testTag = "setting_screen_privacy_switch",
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("App Lock PIN", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = if (preferences.appPinEnabled) {
                                        "Enabled (salted PBKDF2-SHA256) — locks when leaving app"
                                    } else {
                                        "Optional 4–12 digit PIN to protect stored servers & workspace code"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = if (preferences.appPinEnabled) Icons.Filled.Lock else Icons.Filled.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (preferences.appPinEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedButton(
                                onClick = { showPinDialog = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("setting_configure_pin_button"),
                            ) {
                                Text(
                                    if (preferences.appPinEnabled) "Change PIN" else "Set App PIN",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (preferences.appPinEnabled) {
                                OutlinedButton(
                                    onClick = onLockAppNow,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("setting_lock_app_now_button"),
                                ) {
                                    Text("Lock Now", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(
                                    onClick = onDisableAppPin,
                                    modifier = Modifier.testTag("setting_disable_pin_button"),
                                ) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }

            Text("In-App Workspace & Background Session", style = MaterialTheme.typography.titleMedium)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsSwitchRow(
                        title = "Auto-launch in Background",
                        subtitle = "Restore active workspace inside app on launch without leaving VS Shell",
                        checked = preferences.autoLaunchBackground,
                        onCheckedChange = onToggleAutoLaunch,
                        testTag = "setting_auto_launch_switch",
                    )
                    SettingsSwitchRow(
                        title = "AI Auto Runtime on Enter",
                        subtitle = "Automatically inspect, reuse healthy localhost instances, or start the single AI-managed internal workspace runtime on app entry",
                        checked = preferences.aiAutoCodeServerOnLaunch,
                        onCheckedChange = onToggleAiAutoCodeServer,
                        testTag = "setting_ai_auto_server_switch",
                    )
                    SettingsSwitchRow(
                        title = "Auto-Reboot Watchdog Service",
                        subtitle = "Detect background code-server death and cleanly reboot with exponential backoff to avoid crash loops",
                        checked = preferences.autoRebootWatchdogEnabled,
                        onCheckedChange = onToggleAutoRebootWatchdog,
                        testTag = "setting_auto_reboot_watchdog_switch",
                    )
                    SettingsSwitchRow(
                        title = "Keep-Alive Background Service",
                        subtitle = "Keep local code-server & WebSocket sessions alive when switching apps",
                        checked = preferences.keepAliveServiceEnabled,
                        onCheckedChange = onToggleKeepAliveService,
                        testTag = "setting_keep_alive_switch",
                    )
                    SettingsSwitchRow(
                        title = "Immersive Fullscreen",
                        subtitle = "Hide top controls and system bars inside Workspace for full-screen coding",
                        checked = preferences.immersiveFullscreen,
                        onCheckedChange = onToggleImmersiveFullscreen,
                        testTag = "setting_immersive_switch",
                    )
                    SettingsSwitchRow(
                        title = "Keep Screen Awake",
                        subtitle = "Prevent display sleep while coding in Workspace",
                        checked = preferences.keepScreenAwake,
                        onCheckedChange = onToggleKeepScreenAwake,
                        testTag = "setting_awake_switch",
                    )
                    SettingsSwitchRow(
                        title = "Developer Shortcut Key Bar",
                        subtitle = "Show Ctrl, Alt, Esc, Tab, and Arrow keys above keyboard",
                        checked = preferences.showDevShortcutBar,
                        onCheckedChange = onToggleDevShortcutBar,
                        testTag = "setting_dev_bar_switch",
                    )
                }
            }

            OutlinedButton(
                onClick = { showClearDialog = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clear_cookies_button"),
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Clear In-App Cookies & Cache")
            }

            Text("Crash & Error Diagnostics", style = MaterialTheme.typography.titleMedium)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Export locally saved crash stack traces, last-known state, and code-server logs as a plain text file for debugging or sharing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = onExportDiagnosticLogs,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_export_logs_button"),
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Export .txt", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onShareDiagnosticLogs,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_share_logs_button"),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Share Logs", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Cookies & Cache?") },
            text = { Text("This will clear saved cookies and cached data inside VS Shell's in-app workspace.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        onClearCookiesAndCache()
                    },
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showPinDialog) {
        var pinInput by remember { mutableStateOf("") }
        var confirmPinInput by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text(if (preferences.appPinEnabled) "Change App Lock PIN" else "Set App Lock PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter a 4–12 digit PIN. It is stored locally as a salted PBKDF2-SHA256 hash.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { val filtered = it.filter(Char::isDigit).take(12); pinInput = filtered; pinError = null },
                        label = { Text("New PIN (4–12 digits)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_pin_input"),
                    )
                    OutlinedTextField(
                        value = confirmPinInput,
                        onValueChange = { val filtered = it.filter(Char::isDigit).take(12); confirmPinInput = filtered; pinError = null },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_pin_confirm_input"),
                    )
                    if (!pinError.isNullOrBlank()) {
                        Text(
                            text = pinError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            pinInput.length < 4 -> pinError = "Enter at least 4 digits."
                            pinInput != confirmPinInput -> pinError = "PIN entries do not match."
                            else -> {
                                showPinDialog = false
                                onSetAppPin(pinInput)
                            }
                        }
                    },
                    modifier = Modifier.testTag("settings_pin_save_button"),
                ) {
                    Text("Save PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ThemePresetCard(
    preset: ThemePreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(width = if (selected) 1.5.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("theme_card_${preset.name}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(preset.previewBg)
                    .border(1.5.dp, preset.previewAccent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected theme",
                        tint = preset.previewAccent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    preset.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    preset.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .scale(0.85f)
                .testTag(testTag),
        )
    }
}
