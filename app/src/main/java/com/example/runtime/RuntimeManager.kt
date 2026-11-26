package com.example.runtime

import android.app.ActivityManager
import android.content.Context
import com.example.workspace.LocalhostServerEngine
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Coordinates the canonical shared workspace, the single AI-managed localhost/IDE runtime,
 * and the independent user-facing interactive shell:
 * 1. [aiRuntime] — single dedicated AI-managed internal runtime (`AiManagedRuntimeCoordinator`)
 * 2. [userTerminal] — independent interactive shell lifecycle pinned to active workspace
 * 3. [sharedFilesystem] — single canonical workspace directory (`filesDir/workspaces/<slug>`)
 */
class RuntimeManager(
    private val appFilesDir: File,
    private val nativeLibDirPath: String?,
    private val context: Context? = null,
    shellExecutableOverride: String? = null,
) {
    val sharedFilesystem = SharedWorkspaceFilesystem(appFilesDir)

    val vsCodeRuntime = VsCodeRuntimeController(
        appFilesDir = appFilesDir,
        nativeLibDirPath = nativeLibDirPath,
    )

    val localhostEngine = LocalhostServerEngine(
        appFilesDir = appFilesDir,
        sharedFilesystem = sharedFilesystem,
    )

    val aiRuntime = AiManagedRuntimeCoordinator(
        appFilesDir = appFilesDir,
        sharedFilesystem = sharedFilesystem,
        vsCodeRuntime = vsCodeRuntime,
        localhostEngine = localhostEngine,
        shellExecutableOverride = shellExecutableOverride,
    )

    val userTerminal = UserTerminalSessionManager(
        appFilesDir = appFilesDir,
        sharedFilesystem = sharedFilesystem,
        shellExecutableOverride = shellExecutableOverride,
        binaryInventoryProvider = { vsCodeRuntime.refreshBinaryInventory() },
    )

    val activeWorkspaceSlug: StateFlow<String> = sharedFilesystem.activeWorkspaceSlug
    val activeWorkspaceDir: StateFlow<File> = sharedFilesystem.activeWorkspaceDir

    fun switchWorkspace(slug: String, source: String = "runtime-manager"): File {
        return sharedFilesystem.setActiveWorkspace(slug, source)
    }

    fun checkMemoryBudget(): MemorySnapshot {
        val ctx = context?.applicationContext
        if (ctx != null) {
            val am = runCatching { ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager }.getOrNull()
            if (am != null) {
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                val availMb = (info.availMem / (1024L * 1024L)).coerceAtLeast(0L)
                val totalMb = (info.totalMem / (1024L * 1024L)).coerceAtLeast(1L)
                return MemorySnapshot(
                    totalMb = totalMb,
                    availableMb = availMb,
                    lowMemory = info.lowMemory || availMb < 160L,
                )
            }
        }
        val rt = Runtime.getRuntime()
        val maxMb = (rt.maxMemory() / (1024L * 1024L)).coerceAtLeast(1L)
        val freeMb = ((rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024L * 1024L)).coerceAtLeast(0L)
        return MemorySnapshot(
            totalMb = maxMb,
            availableMb = freeMb,
            lowMemory = freeMb < 48L,
        )
    }

    /**
     * KeepAliveService should only remain active when a background workspace session is genuinely
     * running (either AI-managed runtime/viewer or live user shell session).
     */
    fun hasAnyActiveRuntime(fallbackServerRunning: Boolean): Boolean {
        return fallbackServerRunning ||
            aiRuntime.snapshot.value.isRunning ||
            vsCodeRuntime.status.value.isRunning ||
            userTerminal.status.value.isRunning
    }

    fun destroyAll() {
        runCatching { aiRuntime.destroy() }
        runCatching { userTerminal.destroy() }
    }
}
