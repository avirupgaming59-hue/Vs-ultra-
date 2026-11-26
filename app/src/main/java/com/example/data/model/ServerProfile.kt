package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ConnectionMode(
    val displayName: String,
    val shortLabel: String,
    val description: String,
) {
    CLOUD_IDE_WEB(
        displayName = "Cloud VS Code / GitHub",
        shortLabel = "Cloud IDE",
        description = "vscode.dev, GitHub Codespaces, Project IDX & Remote Tunnels with shared GitHub login",
    ),
    TAILSCALE_CUSTOM(
        displayName = "Tailscale / Custom HTTPS",
        shortLabel = "Tailscale",
        description = "MagicDNS (*.ts.net), Tailscale Serve/Funnel, or custom reverse proxy URL",
    ),
    DIRECT_CODE_SERVER(
        displayName = "Direct code-server",
        shortLabel = "code-server",
        description = "Direct connection to coder/code-server (PC, VPS, or local Termux :8080)",
    ),
    VS_CODE_SHELL_PROXY(
        displayName = "VS Code Shell + Proxy",
        shortLabel = "VS Shell",
        description = "VS Code Shell login app with httpOnly session cookie and proxied code-server",
    ),
}

enum class WorkspaceProvisioningState(val label: String) {
    NOT_STARTED("Not Started"),
    GENERATING("Generating"),
    GENERATED("Files Generated"),
    WAITING_FOR_INSTALLATION("Waiting for Termux Install"),
    INSTALLING("Installing"),
    STARTING("Starting"),
    VERIFYING("Verifying HTTP"),
    READY("Ready"),
    FAILED("Failed"),
    STOPPED("Stopped"),
}

enum class LocalhostRuntimeKind(val label: String) {
    CODE_SERVER_RUNTIME("Built-in VS Code Runtime"),
    EMBEDDED_EDITOR("Fallback File Viewer (Not VS Code)"),
    EXTERNAL_TERMUX_CODE_SERVER("Termux code-server"),
    REMOTE_CLOUD_IDE("Remote / Cloud IDE"),
}

@Entity(tableName = "server_profiles")
data class ServerProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val connectionMode: ConnectionMode = ConnectionMode.CLOUD_IDE_WEB,
    val passwordHint: String = "",
    val useDesktopUserAgent: Boolean = false,
    val autoConnectOnLaunch: Boolean = true,
    val useMasterAutoLogin: Boolean = true,
    val isPinned: Boolean = false,
    val isActive: Boolean = false,
    val lastConnectedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val provisioningState: WorkspaceProvisioningState = WorkspaceProvisioningState.NOT_STARTED,
    val runtimeKind: LocalhostRuntimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
    val lastError: String = "",
    val workspaceSlug: String = "",
)
