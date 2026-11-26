package com.example.workspace

import android.content.Context
import android.content.Intent
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale

object TermuxRunCommand {
    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

    fun generatePassword(): String {
        val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun codeServerCommand(
        port: Int,
        password: String,
        workspaceSlug: String,
        starterFiles: Map<String, String> = emptyMap(),
    ): String {
        require(port in 1024..65535) { "Port must be between 1024 and 65535" }
        require(password.matches(Regex("[a-f0-9]{48}"))) { "Password must be a generated URL-safe secret" }
        val safeSlug = workspaceSlug.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .ifBlank { "workspace" }
        val workspacePath = "\$HOME/workspaces/$safeSlug"
        val createFiles = starterFiles.mapNotNull { (name, content) ->
            val safeName = sanitizeRelativeFileName(name) ?: return@mapNotNull null
            val target = "$workspacePath/$safeName"
            val encodedContent = Base64.encodeToString(
                content.toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP,
            )
            "mkdir -p \"\$(dirname '$target')\" && " +
                "printf '%s' '$encodedContent' | base64 -d > '$target'"
        }.joinToString(" && ")
        val setupFiles = if (createFiles.isBlank()) "" else " && $createFiles"
        return "pkg update -y && pkg install -y nodejs-lts code-server && " +
            "mkdir -p '$workspacePath'$setupFiles && cd '$workspacePath' && " +
            "PASSWORD='$password' code-server --bind-addr 127.0.0.1:$port --auth password"
    }

    private fun sanitizeRelativeFileName(raw: String): String? {
        val parts = raw.replace('\\', '/').split('/')
        if (parts.isEmpty() || parts.any { it.isBlank() || it == "." || it == ".." }) return null
        if (parts.any { !it.matches(Regex("[A-Za-z0-9._-]+")) }) return null
        return parts.joinToString("/")
    }

    fun startInBackground(context: Context, command: String) {
        val intent = Intent(RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", command))
            putExtra(EXTRA_WORKDIR, "/data/data/com.termux/files/home")
            putExtra(EXTRA_BACKGROUND, true)
        }
        check(context.startService(intent) != null) {
            "Termux did not accept the background command"
        }
    }
}
