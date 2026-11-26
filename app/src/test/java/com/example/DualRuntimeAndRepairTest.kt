package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiChatService
import com.example.ai.GeminiFailureReason
import com.example.data.model.CodeLanguage
import com.example.data.model.LocalhostRuntimeKind
import com.example.data.repository.PhoneFileRepository
import com.example.runtime.CommandSafetyDecision
import com.example.runtime.CommandSafetyValidator
import com.example.runtime.RuntimeLifecycleState
import com.example.runtime.SharedWorkspaceFilesystem
import com.example.runtime.UserTerminalSessionManager
import com.example.runtime.VsCodeIdeBackendKind
import com.example.runtime.VsCodeRuntimeController
import com.example.workspace.InAppWebWorkspaceController
import com.example.workspace.LocalhostRuntimeHost
import com.example.workspace.LocalhostServerEngine
import com.example.workspace.WatchdogTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DualRuntimeAndRepairTest {

    @Test
    fun `vscode runtime reports missing binaries honestly and rejects fallback viewer`() = runTest {
        val tempDir = createTempDirectory("vsrt_test").toFile()
        val sharedFs = SharedWorkspaceFilesystem(tempDir)
        val wsDir = sharedFs.resolveWorkspaceDir("alpha")
        val fallbackEngine = LocalhostServerEngine(tempDir, sharedFs)
        val controller = VsCodeRuntimeController(tempDir, nativeLibDirPath = null)
        try {
            val inv = controller.refreshBinaryInventory()
            assertFalse(inv.isCodeServerRunnableInApp)
            assertTrue(inv.missingIdeBinaries.contains("libnode.so"))
            assertTrue(inv.missingIdeBinaries.contains("libcodeserver.so"))

            // Fallback File Viewer on loopback must NOT be accepted as genuine code-server
            val fallbackStatus = fallbackEngine.ensureLocalhostReady(
                requestedPort = 0,
                workspaceName = "Fallback",
                workspaceSlug = "alpha",
            )
            assertTrue(fallbackStatus.isRunning)
            val fallbackVerification = VsCodeRuntimeController.verifyCodeServerHttpEndpoint(fallbackStatus.port)
            assertFalse(fallbackVerification.isGenuineCodeServer)
            assertTrue(fallbackVerification.detail.contains("Fallback Local File Viewer", ignoreCase = true))

            // Attempting to start VS Code Runtime when port serves Fallback Viewer must fail honestly
            val rtFail = controller.startRuntime(
                workspaceDir = wsDir,
                workspaceSlug = "alpha",
                requestedPort = fallbackStatus.port,
                allowExternalLoopbackAttach = true,
                allowPortFallback = false,
            )
            assertFalse(rtFail.isRunning)
            assertEquals(RuntimeLifecycleState.FAILED, rtFail.state)
            assertEquals(VsCodeIdeBackendKind.MISSING_BINARY, rtFail.backendKind)
            assertTrue(rtFail.errorMessage.orEmpty().contains("Android 11+", ignoreCase = true))
        } finally {
            controller.destroy()
            fallbackEngine.stopSuspending()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `vscode runtime verifies and attaches to genuine code-server HTTP endpoint`() = runTest {
        val tempDir = createTempDirectory("vsrt_genuine").toFile()
        val sharedFs = SharedWorkspaceFilesystem(tempDir)
        val wsDir = sharedFs.resolveWorkspaceDir("beta")
        val controller = VsCodeRuntimeController(tempDir, nativeLibDirPath = null)
        val mockCodeServer = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val acceptJob = launch(Dispatchers.IO) {
            while (!mockCodeServer.isClosed) {
                runCatching {
                    mockCodeServer.accept().use { sock ->
                        val reader = sock.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty()) {
                            line = reader.readLine()
                        }
                        val body = "<!doctype html><html><body><div class=\"monaco-workbench\">code-server</div></body></html>"
                        val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nServer: code-server\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                        sock.getOutputStream().write(resp.toByteArray(StandardCharsets.UTF_8))
                        sock.getOutputStream().flush()
                    }
                }
            }
        }
        try {
            val probe = withContext(Dispatchers.IO) {
                VsCodeRuntimeController.verifyCodeServerHttpEndpoint(mockCodeServer.localPort)
            }
            assertTrue(probe.isGenuineCodeServer)

            val attached = controller.startRuntime(
                workspaceDir = wsDir,
                workspaceSlug = "beta",
                requestedPort = mockCodeServer.localPort,
                allowExternalLoopbackAttach = true,
                allowPortFallback = false,
            )
            assertTrue(attached.isRunning)
            assertEquals(RuntimeLifecycleState.READY, attached.state)
            assertEquals(VsCodeIdeBackendKind.EXTERNAL_LOOPBACK_CODE_SERVER, attached.backendKind)
            assertEquals("http://127.0.0.1:${mockCodeServer.localPort}", attached.url)

            controller.stopRuntime()
            assertEquals(RuntimeLifecycleState.STOPPED, controller.status.value.state)
        } finally {
            acceptJob.cancel()
            runCatching { mockCodeServer.close() }
            controller.destroy()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `user terminal runs real shell commands records history and syncs nested workspace files`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = createTempDirectory("user_term_test").toFile()
        val sharedFs = SharedWorkspaceFilesystem(tempDir)
        val wsDir = sharedFs.setActiveWorkspace("proj-one", "test")
        val terminalMgr = UserTerminalSessionManager(tempDir, sharedFs)
        val phoneRepo = PhoneFileRepository(context, sharedFs)
        val fallbackEngine = LocalhostServerEngine(tempDir, sharedFs)

        try {
            val started = terminalMgr.startShell(wsDir, "proj-one")
            assertTrue(started.isRunning)
            assertEquals(RuntimeLifecycleState.READY, started.state)

            // Create nested file via real POSIX shell command and verify exit code 0
            val mkResult = terminalMgr.executeCommandAndAwaitExit(
                "mkdir -p src && printf 'print(\"hello from terminal\")\\n' > src/main.py",
                timeoutMs = 5_000L,
            )
            assertTrue("Expected shell command to succeed: ${mkResult.errorMessage}", mkResult.succeeded)
            assertEquals(0, mkResult.exitCode)

            // Verify non-zero exit code is captured accurately
            val failResult = terminalMgr.executeCommandAndAwaitExit("ls non_existent_file_xyz", timeoutMs = 5_000L)
            assertFalse(failResult.succeeded)
            assertTrue((failResult.exitCode ?: 0) != 0)

            // File created in User Terminal appears immediately in SharedWorkspaceFilesystem, PhoneFileRepository, and Fallback HTTP /api/files
            val createdFile = File(wsDir, "src/main.py")
            assertTrue(createdFile.exists())
            assertTrue(sharedFs.listFiles(wsDir).any { sharedFs.relativePathOf(wsDir, it) == "src/main.py" })

            val phoneFiles = phoneRepo.listFiles(null)
            assertTrue(phoneFiles.any { it.displayName == "src/main.py" && it.language == CodeLanguage.PYTHON })

            val serverStatus = fallbackEngine.ensureLocalhostReady(
                requestedPort = 0,
                workspaceName = "Proj One",
                workspaceSlug = "proj-one",
                overwriteStarterFiles = false,
            )
            assertTrue(serverStatus.isRunning)

            val rootHtml = sendRawHttp(
                serverStatus.port,
                "GET / HTTP/1.1\r\nHost: 127.0.0.1:${serverStatus.port}\r\nConnection: close\r\n\r\n",
            )
            val token = Regex("""const SESSION_TOKEN = "([a-f0-9]+)";""").find(rootHtml)?.groupValues?.get(1).orEmpty()
            assertTrue(token.length == 64)

            val filesResp = sendRawHttp(
                serverStatus.port,
                "GET /api/files HTTP/1.1\r\nHost: 127.0.0.1:${serverStatus.port}\r\nX-VSShell-Token: $token\r\nConnection: close\r\n\r\n",
            )
            assertTrue(filesResp.contains("src/main.py"))

            // Path traversal remains strictly blocked
            assertNull(fallbackEngine.resolveConfinedFile(wsDir, "../outside.txt"))
            assertNull(sharedFs.resolveConfinedFile(wsDir, "../../etc/passwd"))

            // History persists across manager re-instantiation
            val reloadedMgr = UserTerminalSessionManager(tempDir, sharedFs)
            try {
                assertTrue(reloadedMgr.commandHistory.value.any { it.contains("src/main.py") })
            } finally {
                reloadedMgr.destroy()
            }

            // Safety validator gates dangerous / confirmable commands
            val blocked = CommandSafetyValidator.validate("rm -rf /")
            assertEquals(CommandSafetyDecision.BLOCKED, blocked.decision)
            val confirmable = terminalMgr.executeCommandAndAwaitExit("pip install fastapi")
            assertTrue(confirmable.requiresConfirmation)
            assertNotNull(terminalMgr.pendingConfirmation.value)
            terminalMgr.dismissPendingCommand()

            terminalMgr.stopShell()
            assertEquals(RuntimeLifecycleState.STOPPED, terminalMgr.status.value.state)
        } finally {
            terminalMgr.destroy()
            fallbackEngine.stopSuspending()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `webview dom inspection rejects empty full height container and detects blank or codespace errors`() {
        val emptySpaRoot = """"{\"readyState\":\"complete\",\"href\":\"http://127.0.0.1:8080/\",\"textLen\":0,\"children\":1,\"height\":900,\"mediaCount\":0,\"hasWorkbench\":false}""""
        val blankEval = InAppWebWorkspaceController.classifyDomInspectionResult(emptySpaRoot, "http://127.0.0.1:8080/")
        assertFalse(blankEval.isReady)
        assertTrue(blankEval.isBlank)

        val workbenchReady = """"{\"readyState\":\"complete\",\"href\":\"http://127.0.0.1:8080/\",\"textLen\":0,\"children\":1,\"height\":900,\"mediaCount\":0,\"hasWorkbench\":true}""""
        val ideEval = InAppWebWorkspaceController.classifyDomInspectionResult(workbenchReady, "http://127.0.0.1:8080/")
        assertTrue(ideEval.isReady)
        assertFalse(ideEval.isBlank)

        val aboutBlank = """"{\"readyState\":\"complete\",\"href\":\"about:blank\",\"textLen\":0,\"children\":0,\"height\":0,\"mediaCount\":0,\"hasWorkbench\":false}""""
        val blankPageEval = InAppWebWorkspaceController.classifyDomInspectionResult(aboutBlank, "https://github.com/codespaces")
        assertFalse(blankPageEval.isReady)
        assertTrue(blankPageEval.isBlank)

        val cs401 = InAppWebWorkspaceController.classifyHttpErrorMessage("https://abc-123.app.github.dev/", 401)
        assertTrue(cs401.contains("Sign in with GitHub", ignoreCase = true))
        val cs503 = InAppWebWorkspaceController.classifyHttpErrorMessage("https://abc-123.app.github.dev/", 503)
        assertTrue(cs503.contains("stopped", ignoreCase = true))
    }

    @Test
    fun `gemini failure classification handles 429 quota and 404 model not found accurately`() {
        val quotaErr = GeminiChatService.classifyGeminiFailure(IllegalStateException("HTTP 429 RESOURCE_EXHAUSTED quota"))
        assertEquals(GeminiFailureReason.TIMEOUT, quotaErr.reason)
        assertTrue(quotaErr.message.orEmpty().contains("429"))

        val notFoundErr = GeminiChatService.classifyGeminiFailure(IllegalStateException("HTTP 404 NOT_FOUND unknown model"))
        assertEquals(GeminiFailureReason.API_PERMISSION_DENIED, notFoundErr.reason)
        assertFalse(GeminiChatService.isRetryableError(IllegalStateException("HTTP 404 NOT_FOUND")))
        assertTrue(GeminiChatService.isRetryableError(IllegalStateException("HTTP 429 RESOURCE_EXHAUSTED")))
    }

    @Test
    fun `localhost runtime host persists external termux flag and runtimeKind across loads`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = WatchdogTarget(
            port = 8443,
            workspaceName = "VS Code Watchdog",
            workspaceSlug = "ws-code",
            termuxCommand = "",
            isExternalTermux = true,
            runtimeKind = LocalhostRuntimeKind.CODE_SERVER_RUNTIME,
        )
        LocalhostRuntimeHost.updateTarget(context, target, enabled = true)
        try {
            // Force reload from SharedPreferences by testing persistence round-trip
            val (enabled, loaded) = LocalhostRuntimeHost.loadTarget(context)
            assertTrue(enabled)
            assertNotNull(loaded)
            assertEquals(8443, loaded!!.port)
            assertTrue(loaded.isExternalTermux)
            assertEquals(LocalhostRuntimeKind.CODE_SERVER_RUNTIME, loaded.runtimeKind)
        } finally {
            LocalhostRuntimeHost.clearTarget(context)
        }
    }

    @Test
    fun `user terminal enforces workspace sibling boundary supports built-in await exposes usr-bin PATH and phone repo fails honestly on non-empty dir delete`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = createTempDirectory("user_term_bounds").toFile()
        val sharedFs = SharedWorkspaceFilesystem(tempDir)
        val wsDir = sharedFs.setActiveWorkspace("proj-one", "test")
        val siblingDir = File(wsDir.parentFile, "${wsDir.name}2").apply { mkdirs() }
        val terminalMgr = UserTerminalSessionManager(tempDir, sharedFs)
        val phoneRepo = PhoneFileRepository(context, sharedFs)

        try {
            val started = terminalMgr.startShell(wsDir, "proj-one")
            assertTrue(started.isRunning)

            val builtInRes = terminalMgr.executeCommandAndAwaitExit("ws-info", timeoutMs = 3_000L)
            assertTrue(builtInRes.succeeded)
            assertTrue(builtInRes.outputLines.any { it.contains("workspace=proj-one") })

            val pathRes = terminalMgr.executeCommandAndAwaitExit("printf '%s' \"\$PATH\"", timeoutMs = 3_000L)
            assertTrue(pathRes.succeeded)
            assertTrue(pathRes.outputLines.joinToString("\n").contains("${tempDir.absolutePath}/usr/bin"))

            // Attempting to cd into a sibling directory whose name shares the workspace prefix must NOT update tracked workspacePath
            val cdRes = terminalMgr.executeCommandAndAwaitExit("cd ${siblingDir.absolutePath} && pwd", timeoutMs = 3_000L)
            assertTrue(cdRes.succeeded)
            assertEquals(wsDir.canonicalPath, File(terminalMgr.status.value.workspacePath).canonicalPath)

            // Non-empty subdirectory inside workspace cannot be deleted via single-file delete(); PhoneFileRepository must fail loudly
            val nonEmptySubdir = File(wsDir, "locked_dir").apply { mkdirs() }
            File(nonEmptySubdir, "keep.txt").writeText("keep")
            val dirAsPhoneFile = com.example.data.model.PhoneCodeFile(
                id = nonEmptySubdir.absolutePath,
                displayName = "locked_dir",
                uri = android.net.Uri.fromFile(nonEmptySubdir),
                language = CodeLanguage.OTHER,
                sizeBytes = 0L,
                lastModified = nonEmptySubdir.lastModified(),
                source = com.example.data.model.PhoneStorageSource.APP_WORKSPACE,
                localPath = nonEmptySubdir.absolutePath,
            )
            val delResult = phoneRepo.deleteFile(dirAsPhoneFile)
            assertTrue("Deleting non-empty directory should return Result.failure instead of false success", delResult.isFailure)
        } finally {
            terminalMgr.destroy()
            siblingDir.deleteRecursively()
            tempDir.deleteRecursively()
        }
    }

    private fun sendRawHttp(port: Int, request: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            return socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readText()
        }
    }
}
