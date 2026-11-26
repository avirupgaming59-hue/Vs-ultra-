package com.example

import android.app.Application
import java.io.File

class VsShellApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        StartupDiagnostics.installUncaughtExceptionHandler(this)
        sanitizeWebViewCrashpadState()
    }

    private fun sanitizeWebViewCrashpadState() {
        runCatching {
            val cacheCrashpadDir = File(cacheDir, "WebView/Crashpad")
            val candidateRoots = listOfNotNull(
                cacheCrashpadDir,
                filesDir.parentFile?.let { File(it, "app_webview/Crashpad") },
            )
            // Ensure any pending/completed/new Crashpad report UUID has a matching attachments/<uuid>
            // directory under its own Crashpad root so Chromium's DirectoryReaderPosix never fails with ENOENT.
            candidateRoots.forEach { root ->
                val attachmentsDir = File(root, "attachments")
                if (!attachmentsDir.exists()) {
                    attachmentsDir.mkdirs()
                }
                listOf("pending", "completed", "new").forEach { bucket ->
                    File(root, bucket).listFiles()?.forEach { file ->
                        val uuid = file.name.substringBefore('.').trim()
                        if (uuid.length >= 32) {
                            val uuidAttachDir = File(attachmentsDir, uuid)
                            if (!uuidAttachDir.exists()) {
                                uuidAttachDir.mkdirs()
                            }
                        }
                    }
                }
            }
        }
    }
}
