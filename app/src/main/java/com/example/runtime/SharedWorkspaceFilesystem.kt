package com.example.runtime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Single canonical workspace storage coordinator shared across:
 * - Built-in VS Code Runtime
 * - Built-in User Terminal (working directory)
 * - Fallback Local File Viewer
 * - Phone Files tab (when no external SAF tree URI is linked)
 * - Gemini project generator
 *
 * All relative paths are confined inside the workspace root via canonical path verification.
 */
class SharedWorkspaceFilesystem(private val appFilesDir: File) {

    val workspacesRootDir: File = File(appFilesDir, "workspaces")
    private val legacyWorkspacesDir: File = File(appFilesDir, "localhost_workspaces")

    private val revisionCounter = AtomicLong(1L)
    private val fsLock = Any()

    private val _activeWorkspaceSlug = MutableStateFlow("default")
    val activeWorkspaceSlug: StateFlow<String> = _activeWorkspaceSlug.asStateFlow()

    private val _activeWorkspaceDir = MutableStateFlow(resolveOrPrepareWorkspaceDir("default"))
    val activeWorkspaceDir: StateFlow<File> = _activeWorkspaceDir.asStateFlow()

    private val _revision = MutableStateFlow(1L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _changes = MutableSharedFlow<WorkspaceChangeEvent>(extraBufferCapacity = 32)
    val changes: SharedFlow<WorkspaceChangeEvent> = _changes.asSharedFlow()

    fun sanitizeSlug(raw: String): String {
        return raw.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .ifBlank { "default" }
            .take(64)
    }

    fun getActiveWorkspaceDir(): File {
        val current = _activeWorkspaceDir.value
        if (!current.exists()) {
            current.mkdirs()
        }
        return current
    }

    fun setActiveWorkspace(slug: String, source: String = "workspace-switch"): File {
        val safeSlug = sanitizeSlug(slug)
        val dir = resolveWorkspaceDir(safeSlug)
        val changed = _activeWorkspaceSlug.value != safeSlug || _activeWorkspaceDir.value.absolutePath != dir.absolutePath
        _activeWorkspaceSlug.value = safeSlug
        _activeWorkspaceDir.value = dir
        if (changed) {
            notifyChanged(source = source, slug = safeSlug, summary = "Switched active workspace to $safeSlug")
        }
        return dir
    }

    /**
     * Resolves canonical workspace directory `filesDir/workspaces/<slug>`.
     * If a legacy directory exists under `filesDir/localhost_workspaces/<slug>`, its files are
     * non-destructively synced into `workspaces/<slug>` when target has no conflicting user edits.
     * If the path is explicitly blocked by a regular file, returns that blocked handle so callers
     * surface an honest [IOException] rather than silently discarding writes.
     */
    fun resolveWorkspaceDir(slug: String): File = synchronized(fsLock) {
        val safeSlug = sanitizeSlug(slug)
        val legacyDir = File(legacyWorkspacesDir, safeSlug)
        if (legacyDir.exists() && !legacyDir.isDirectory) {
            return@synchronized legacyDir
        }
        val canonicalTarget = File(workspacesRootDir, safeSlug)
        if (canonicalTarget.exists() && !canonicalTarget.isDirectory) {
            return@synchronized canonicalTarget
        }
        if (!canonicalTarget.exists()) {
            canonicalTarget.mkdirs()
        }
        if (legacyDir.exists() && legacyDir.isDirectory) {
            copyDirectoryNonDestructive(legacyDir, canonicalTarget)
        }
        return@synchronized canonicalTarget
    }

    private fun resolveOrPrepareWorkspaceDir(slug: String): File {
        return runCatching { resolveWorkspaceDir(slug) }
            .getOrElse { File(workspacesRootDir, sanitizeSlug(slug)).apply { mkdirs() } }
    }

    private fun copyDirectoryNonDestructive(sourceDir: File, targetDir: File) {
        val children = sourceDir.listFiles() ?: return
        for (child in children) {
            val dest = File(targetDir, child.name)
            if (child.isDirectory) {
                if (!dest.exists()) dest.mkdirs()
                if (dest.isDirectory) {
                    copyDirectoryNonDestructive(child, dest)
                }
            } else if (child.isFile && !dest.exists()) {
                runCatching {
                    dest.parentFile?.mkdirs()
                    child.copyTo(dest, overwrite = false)
                }
            }
        }
    }

    /**
     * Resolves [rawRelativePath] strictly inside [workspaceDir], supporting clean relative
     * subdirectories (`src/app.js`) while rejecting path traversal (`..`), leading `/`, null bytes,
     * and symlink escapes outside [workspaceDir].
     */
    fun resolveConfinedFile(workspaceDir: File, rawRelativePath: String): File? {
        val cleanRel = sanitizeRelativePath(rawRelativePath)
        if (cleanRel.isBlank()) return null
        return runCatching {
            val canonicalRoot = workspaceDir.canonicalFile
            val candidate = File(canonicalRoot, cleanRel).canonicalFile
            val rootPrefix = canonicalRoot.path + File.separator
            if (candidate.path.startsWith(rootPrefix)) candidate else null
        }.getOrNull()
    }

    fun sanitizeRelativePath(raw: String): String {
        val noControl = raw.replace("\u0000", "").replace(Regex("[\\p{Cntrl}]"), "")
        val normalized = noControl.replace('\\', '/').trim()
        if (normalized.isBlank() || normalized.startsWith('/')) return ""
        val segments = normalized.split('/').filter { it.isNotBlank() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) return ""
        return segments.joinToString("/").take(240)
    }

    fun writeFiles(
        workspaceDir: File,
        files: Map<String, String>,
        overwriteExisting: Boolean = false,
        source: String = "writer",
    ): Int = synchronized(fsLock) {
        if (workspaceDir.exists() && !workspaceDir.isDirectory) {
            throw IOException("Workspace path ${workspaceDir.name} is blocked by a non-directory file")
        }
        if (!workspaceDir.exists() && !workspaceDir.mkdirs()) {
            throw IOException("Unable to create workspace directory ${workspaceDir.absolutePath}")
        }
        var writtenCount = 0
        files.forEach { (rawPath, content) ->
            val target = resolveConfinedFile(workspaceDir, rawPath) ?: return@forEach
            if (!overwriteExisting && target.exists() && target.length() > 0L) {
                return@forEach
            }
            val parent = target.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw IOException("Unable to create parent folder for $rawPath")
            }
            target.writeText(content, StandardCharsets.UTF_8)
            writtenCount++
        }
        if (writtenCount > 0) {
            notifyChanged(
                source = source,
                slug = workspaceDir.name,
                summary = "Wrote $writtenCount file(s)",
            )
        }
        writtenCount
    }

    fun writeSingleFile(
        workspaceDir: File,
        relativePath: String,
        content: String,
        source: String = "editor",
    ): File = synchronized(fsLock) {
        val target = resolveConfinedFile(workspaceDir, relativePath)
            ?: throw SecurityException("Path escapes workspace boundary: $relativePath")
        target.parentFile?.mkdirs()
        target.writeText(content, StandardCharsets.UTF_8)
        notifyChanged(source = source, slug = workspaceDir.name, summary = "Updated $relativePath")
        target
    }

    fun deleteFile(workspaceDir: File, relativePath: String, source: String = "editor"): Boolean = synchronized(fsLock) {
        val target = resolveConfinedFile(workspaceDir, relativePath)
            ?: throw SecurityException("Path escapes workspace boundary: $relativePath")
        val deleted = if (target.exists()) target.delete() else false
        if (deleted) {
            notifyChanged(source = source, slug = workspaceDir.name, summary = "Deleted $relativePath")
        }
        deleted
    }

    fun listFiles(workspaceDir: File, includeNested: Boolean = true): List<File> {
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) return emptyList()
        val result = mutableListOf<File>()
        fun walk(dir: File) {
            val entries = dir.listFiles() ?: return
            for (entry in entries) {
                if (entry.isFile) {
                    result.add(entry)
                } else if (includeNested && entry.isDirectory && !entry.name.startsWith(".git")) {
                    walk(entry)
                }
            }
        }
        walk(workspaceDir)
        return result.sortedBy { relativePathOf(workspaceDir, it).lowercase(Locale.US) }
    }

    fun relativePathOf(workspaceDir: File, file: File): String {
        val rootPath = runCatching { workspaceDir.canonicalPath }.getOrDefault(workspaceDir.absolutePath)
        val filePath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        return filePath.removePrefix(rootPath + File.separator).replace(File.separatorChar, '/')
    }

    fun notifyChanged(source: String, slug: String = _activeWorkspaceSlug.value, summary: String = "") {
        val rev = revisionCounter.incrementAndGet()
        _revision.value = rev
        val dir = runCatching { resolveWorkspaceDir(slug) }.getOrDefault(_activeWorkspaceDir.value)
        _changes.tryEmit(
            WorkspaceChangeEvent(
                slug = sanitizeSlug(slug),
                workspacePath = dir.absolutePath,
                source = source,
                summary = summary,
                revision = rev,
            ),
        )
    }
}
