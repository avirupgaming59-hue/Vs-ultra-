package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.example.data.model.CodeLanguage
import com.example.data.model.PhoneCodeFile
import com.example.data.model.PhoneStorageSource
import com.example.runtime.SharedWorkspaceFilesystem
import com.example.workspace.LocalhostRuntimeHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PhoneFileRepository(
    private val context: Context,
    private val sharedFilesystem: SharedWorkspaceFilesystem = LocalhostRuntimeHost.getRuntimeManager(context).sharedFilesystem,
) {

    private val localWorkspaceDir: File
        get() {
            val activeDir = sharedFilesystem.getActiveWorkspaceDir()
            migrateLegacyPhoneFilesIfNeeded(activeDir)
            return activeDir
        }

    private fun migrateLegacyPhoneFilesIfNeeded(targetDir: File) {
        val legacyPhoneDir = File(context.filesDir, "phone_workspace")
        if (!legacyPhoneDir.exists() || !legacyPhoneDir.isDirectory) return
        val legacyFiles = legacyPhoneDir.listFiles()?.filter { it.isFile }.orEmpty()
        for (f in legacyFiles) {
            val dest = File(targetDir, f.name)
            if (!dest.exists()) {
                runCatching { f.copyTo(dest, overwrite = false) }
            }
        }
    }

    suspend fun listFiles(linkedTreeUriString: String?): List<PhoneCodeFile> = withContext(Dispatchers.IO) {
        if (!linkedTreeUriString.isNullOrBlank()) {
            val treeUri = linkedTreeUriString.toUri()
            val safFiles = runCatching { listSafDirectory(treeUri) }.getOrNull()
            if (safFiles != null) {
                return@withContext safFiles
            }
        }
        listAppWorkspaceFiles()
    }

    private fun listAppWorkspaceFiles(): List<PhoneCodeFile> {
        val dir = localWorkspaceDir
        return sharedFilesystem.listFiles(dir, includeNested = true)
            .map { file ->
                val relPath = sharedFilesystem.relativePathOf(dir, file)
                PhoneCodeFile(
                    id = file.absolutePath,
                    displayName = relPath,
                    uri = Uri.fromFile(file),
                    language = CodeLanguage.fromFileName(file.name),
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    source = PhoneStorageSource.APP_WORKSPACE,
                    localPath = file.absolutePath,
                )
            }
            .sortedByDescending { it.lastModified }
    }

    private fun listSafDirectory(treeUri: Uri): List<PhoneCodeFile> {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        val result = mutableListOf<PhoneCodeFile>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeIdx) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val docId = cursor.getString(idIdx)
                val name = cursor.getString(nameIdx) ?: "untitled"
                val size = if (cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx)
                val modified = if (cursor.isNull(modIdx)) 0L else cursor.getLong(modIdx)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                result.add(
                    PhoneCodeFile(
                        id = docUri.toString(),
                        displayName = name,
                        uri = docUri,
                        language = CodeLanguage.fromFileName(name),
                        sizeBytes = size,
                        lastModified = modified,
                        source = PhoneStorageSource.SAF_FOLDER,
                    )
                )
            }
        }
        return result.sortedByDescending { it.lastModified }
    }

    suspend fun createFile(
        fileName: String,
        language: CodeLanguage,
        initialContent: String,
        linkedTreeUriString: String?,
    ): Result<PhoneCodeFile> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedName = ensureExtension(fileName.trim(), language)
            if (!linkedTreeUriString.isNullOrBlank()) {
                val treeUri = linkedTreeUriString.toUri()
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
                val createdUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocUri,
                    language.mimeType,
                    normalizedName,
                ) ?: error("Unable to create file in selected phone folder")

                writeBytesToSafUri(createdUri, initialContent.toByteArray(Charsets.UTF_8))
                return@runCatching PhoneCodeFile(
                    id = createdUri.toString(),
                    displayName = normalizedName,
                    uri = createdUri,
                    language = CodeLanguage.fromFileName(normalizedName),
                    sizeBytes = initialContent.toByteArray(Charsets.UTF_8).size.toLong(),
                    lastModified = System.currentTimeMillis(),
                    source = PhoneStorageSource.SAF_FOLDER,
                )
            }

            val workspaceRel = ensureWorkspaceRelativeExtension(fileName.trim(), language)
            val file = sharedFilesystem.resolveConfinedFile(localWorkspaceDir, workspaceRel)
                ?: error("Invalid workspace filename")
            file.parentFile?.mkdirs()
            file.writeText(initialContent, Charsets.UTF_8)
            val relDisplay = sharedFilesystem.relativePathOf(localWorkspaceDir, file)
            sharedFilesystem.notifyChanged("phone-files", sharedFilesystem.activeWorkspaceSlug.value, "Created $relDisplay")
            PhoneCodeFile(
                id = file.absolutePath,
                displayName = relDisplay,
                uri = Uri.fromFile(file),
                language = CodeLanguage.fromFileName(file.name),
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
                source = PhoneStorageSource.APP_WORKSPACE,
                localPath = file.absolutePath,
            )
        }
    }

    suspend fun importDocument(sourceUri: Uri, linkedTreeUriString: String?): Result<PhoneCodeFile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val displayName = resolveDisplayName(sourceUri)
                val bytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                    ?: error("Could not read selected file")
                val language = CodeLanguage.fromFileName(displayName)

                if (!linkedTreeUriString.isNullOrBlank()) {
                    val treeUri = linkedTreeUriString.toUri()
                    val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri),
                    )
                    val createdUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        parentDocUri,
                        language.mimeType,
                        displayName,
                    ) ?: error("Could not import into linked phone folder")
                    writeBytesToSafUri(createdUri, bytes)
                    return@runCatching PhoneCodeFile(
                        id = createdUri.toString(),
                        displayName = displayName,
                        uri = createdUri,
                        language = language,
                        sizeBytes = bytes.size.toLong(),
                        lastModified = System.currentTimeMillis(),
                        source = PhoneStorageSource.SAF_FOLDER,
                    )
                }

                val safeDisplayName = sanitizeFileName(displayName)
                val targetFile = resolveConfinedWorkspaceFile(safeDisplayName)
                    ?: error("Invalid imported filename")
                targetFile.writeBytes(bytes)
                sharedFilesystem.notifyChanged("phone-files", sharedFilesystem.activeWorkspaceSlug.value, "Imported ${targetFile.name}")
                PhoneCodeFile(
                    id = targetFile.absolutePath,
                    displayName = targetFile.name,
                    uri = Uri.fromFile(targetFile),
                    language = CodeLanguage.fromFileName(safeDisplayName),
                    sizeBytes = targetFile.length(),
                    lastModified = targetFile.lastModified(),
                    source = PhoneStorageSource.APP_WORKSPACE,
                    localPath = targetFile.absolutePath,
                )
            }
        }

    suspend fun readFileText(file: PhoneCodeFile): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = readFileBytes(file).getOrThrow()
            bytes.toString(Charsets.UTF_8)
        }
    }

    suspend fun readFileBytes(file: PhoneCodeFile): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            if (file.localPath != null) {
                val confined = verifyExistingLocalPath(file.localPath)
                confined.readBytes()
            } else {
                context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                    ?: error("Unable to open file stream")
            }
        }
    }

    suspend fun saveFileText(file: PhoneCodeFile, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (file.localPath != null) {
                val confined = verifyExistingLocalPath(file.localPath)
                confined.writeText(content, Charsets.UTF_8)
                sharedFilesystem.notifyChanged("phone-files", sharedFilesystem.activeWorkspaceSlug.value, "Saved ${confined.name}")
            } else {
                writeBytesToSafUri(file.uri, content.toByteArray(Charsets.UTF_8))
            }
        }
    }

    suspend fun deleteFile(file: PhoneCodeFile): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (file.localPath != null) {
                val confined = verifyExistingLocalPath(file.localPath)
                if (!confined.delete() && confined.exists()) {
                    error("Unable to delete ${confined.name}")
                }
                sharedFilesystem.notifyChanged("phone-files", sharedFilesystem.activeWorkspaceSlug.value, "Deleted ${confined.name}")
            } else {
                val deleted = DocumentsContract.deleteDocument(context.contentResolver, file.uri)
                if (!deleted) {
                    error("Unable to delete ${file.displayName} from phone storage")
                }
            }
            Unit
        }
    }

    private fun writeBytesToSafUri(uri: Uri, bytes: ByteArray) {
        val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: runCatching { context.contentResolver.openOutputStream(uri, "w") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri)
            ?: error("Unable to open output stream for phone storage file")
        stream.use { out ->
            out.write(bytes)
            out.flush()
        }
    }

    fun getFolderDisplayName(linkedTreeUriString: String?): String {
        if (linkedTreeUriString.isNullOrBlank()) {
            return "Shared Workspace (${sharedFilesystem.activeWorkspaceSlug.value})"
        }
        val uri = linkedTreeUriString.toUri()
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        return docId?.substringAfter(':', docId)?.ifBlank { "Linked Phone Folder" }
            ?: "Linked Phone Folder"
    }

    private fun resolveDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val value = c.getString(idx)
                    if (!value.isNullOrBlank()) return sanitizeFileName(value)
                }
            }
        }
        return sanitizeFileName(uri.lastPathSegment?.substringAfterLast('/') ?: "imported_file.txt")
    }

    private fun sanitizeFileName(raw: String): String {
        val noControl = raw.replace("\u0000", "").replace(Regex("[\\p{Cntrl}]"), "")
        val base = noControl
            .replace('\\', '/')
            .substringAfterLast('/')
            .replace("..", "")
            .trim()
            .trimStart('.')
        return base.ifBlank { "imported_file.txt" }.take(180)
    }

    private fun resolveConfinedWorkspaceFile(rawName: String): File? {
        val safeName = sharedFilesystem.sanitizeRelativePath(rawName)
            .ifBlank { sanitizeFileName(rawName) }
        return sharedFilesystem.resolveConfinedFile(localWorkspaceDir, safeName)
    }

    private fun verifyExistingLocalPath(rawPath: String): File {
        val root = sharedFilesystem.workspacesRootDir.canonicalFile
        val activeRoot = localWorkspaceDir.canonicalFile
        val candidate = File(rawPath).canonicalFile
        require(
            candidate.path.startsWith(activeRoot.path + File.separator) ||
                candidate.path.startsWith(root.path + File.separator),
        ) {
            "Access denied outside shared workspace directory"
        }
        return candidate
    }

    private fun ensureExtension(name: String, language: CodeLanguage): String {
        val clean = sanitizeFileName(name)
        if (clean.contains('.') || language == CodeLanguage.OTHER) return clean
        return "$clean.${language.primaryExtension}"
    }

    private fun ensureWorkspaceRelativeExtension(rawPath: String, language: CodeLanguage): String {
        val safeRel = sharedFilesystem.sanitizeRelativePath(rawPath)
        if (safeRel.isBlank()) return ensureExtension(rawPath, language)
        val leaf = safeRel.substringAfterLast('/')
        if (leaf.contains('.') || language == CodeLanguage.OTHER) return safeRel
        return "$safeRel.${language.primaryExtension}"
    }
}
