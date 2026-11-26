package com.example

import com.example.ai.GeminiBootstrapException
import com.example.ai.GeminiChatService
import com.example.ai.GeminiFailureReason
import com.example.ai.RetryPolicy
import com.example.data.local.ActiveSessionDao
import com.example.data.local.AppDatabase
import com.example.data.local.ProfileTypeConverters
import com.example.data.local.ServerProfileDao
import com.example.data.model.ActiveSessionEntity
import com.example.data.model.ChatMessageEntity
import com.example.data.model.CodeLanguage
import com.example.data.model.ConnectionMode
import com.example.data.model.LocalhostRuntimeKind
import com.example.data.model.ServerProfile
import com.example.data.model.WorkspaceProvisioningState
import com.example.data.repository.ServerProfileRepository
import com.example.security.SecureVault
import com.example.service.RebootBackoffTracker
import com.example.service.RebootDecision
import com.example.service.WatchdogSnapshot
import com.example.terminal.TerminalEmulator
import com.example.terminal.TerminalTone
import com.example.ui.theme.AppTypography
import com.example.ui.viewmodel.ChatRolePreset
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.LocalhostUiState
import com.example.ui.viewmodel.PhoneFilesUiState
import com.example.ui.viewmodel.WorkspaceViewModel
import com.example.util.SimpleJson
import com.example.workspace.ChromeCustomTabsManager
import com.example.workspace.InAppWebWorkspaceController
import com.example.workspace.LocalhostServerEngine
import com.example.workspace.PortProbeKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.crypto.AEADBadTagException
import kotlin.io.path.createTempDirectory

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun extractLocalhostPort_detectsLocalhostAndIgnoresRemoteUrls() {
        assertEquals(8080, WorkspaceViewModel.extractLocalhostPort("http://127.0.0.1:8080"))
        assertEquals(3000, WorkspaceViewModel.extractLocalhostPort("localhost:3000"))
        assertEquals(8080, WorkspaceViewModel.extractLocalhostPort("http://localhost"))
        assertNull(WorkspaceViewModel.extractLocalhostPort("https://vscode.dev"))
        assertNull(WorkspaceViewModel.extractLocalhostPort("https://idx.google.com"))
        assertEquals("profile-7-My Local App", WorkspaceViewModel.workspaceSlugFor(7L, "My Local App"))
    }

    @Test
    fun allChatPresets_targetConfiguredGeminiModels() {
        assertEquals("gemini-3.8-flash", GeminiChatService.TARGET_MODEL_ID)
        assertEquals("gemini-3.5-flash-lite", GeminiChatService.FALLBACK_MODEL_ID)
        ChatRolePreset.entries.forEach { preset ->
            val expectedModel = if (preset == ChatRolePreset.FAST_FLASH_LITE) {
                GeminiChatService.FALLBACK_MODEL_ID
            } else {
                GeminiChatService.TARGET_MODEL_ID
            }
            assertEquals(expectedModel, preset.modelId)
        }
    }

    @Test
    fun computeBackoffDelayMs_scalesExponentiallyAndCapsAtMax() {
        val policy = RetryPolicy(
            initialDelayMs = 1_000L,
            backoffMultiplier = 2.0,
            maxDelayMs = 4_000L,
            jitterRatio = 0.0,
        )
        assertEquals(1_000L, GeminiChatService.computeBackoffDelayMs(0, policy, randomUnit = 0.5))
        assertEquals(2_000L, GeminiChatService.computeBackoffDelayMs(1, policy, randomUnit = 0.5))
        assertEquals(4_000L, GeminiChatService.computeBackoffDelayMs(2, policy, randomUnit = 0.5))
        assertEquals(4_000L, GeminiChatService.computeBackoffDelayMs(3, policy, randomUnit = 0.5))
    }

    @Test
    fun isRetryableError_distinguishesTransientFromPermanentFailures() {
        assertTrue(GeminiChatService.isRetryableError(SocketTimeoutException("Read timed out")))
        assertTrue(GeminiChatService.isRetryableError(IOException("Connection reset")))
        assertTrue(GeminiChatService.isRetryableError(IllegalStateException("HTTP 503 Service Unavailable")))
        assertTrue(GeminiChatService.isRetryableError(IllegalStateException("HTTP 429 Too Many Requests")))

        assertFalse(GeminiChatService.isRetryableError(IllegalStateException("HTTP 403 AppCheck failed")))
        assertFalse(GeminiChatService.isRetryableError(IllegalArgumentException("HTTP 400 INVALID_ARGUMENT")))
    }

    @Test
    fun executeWithExponentialBackoff_retriesTransientErrorsAndRecovers() = runTest {
        val policy = RetryPolicy(
            maxAttempts = 4,
            initialDelayMs = 500L,
            backoffMultiplier = 2.0,
            maxDelayMs = 4_000L,
            jitterRatio = 0.0,
        )
        val recordedDelays = mutableListOf<Long>()
        var attempts = 0

        val result = GeminiChatService.executeWithExponentialBackoff(
            policy = policy,
            delayFn = { ms -> recordedDelays.add(ms) },
        ) {
            attempts++
            if (attempts < 3) {
                throw SocketTimeoutException("Simulated timeout #$attempts")
            }
            "gemini-3.8-flash-ok"
        }

        assertEquals("gemini-3.8-flash-ok", result)
        assertEquals(3, attempts)
        assertEquals(listOf(500L, 1_000L), recordedDelays)
    }

    @Test
    fun buildValidAlternatingHistory_stripsTrailingUserAndDuplicates() {
        val raw = listOf(
            ChatMessageEntity(role = "model", text = "orphan model", modelName = GeminiChatService.TARGET_MODEL_ID, rolePresetName = "General"),
            ChatMessageEntity(role = "user", text = "first user", modelName = GeminiChatService.TARGET_MODEL_ID, rolePresetName = "General"),
            ChatMessageEntity(role = "user", text = "duplicate user", modelName = GeminiChatService.TARGET_MODEL_ID, rolePresetName = "General"),
            ChatMessageEntity(role = "model", text = "first model", modelName = GeminiChatService.TARGET_MODEL_ID, rolePresetName = "General"),
            ChatMessageEntity(role = "user", text = "pending failed user", modelName = GeminiChatService.TARGET_MODEL_ID, rolePresetName = "General"),
        )
        val result = ChatViewModel.buildValidAlternatingHistory(raw)
        assertEquals(2, result.size)
        assertEquals("user", result[0].role)
        assertEquals("model", result[1].role)
    }

    @Test
    fun codeLanguage_detectsExtensionsAccurately() {
        assertEquals(CodeLanguage.PYTHON, CodeLanguage.fromFileName("train_model.py"))
        assertEquals(CodeLanguage.TYPESCRIPT, CodeLanguage.fromFileName("App.tsx"))
        assertEquals(CodeLanguage.KOTLIN, CodeLanguage.fromFileName("MainActivity.kt"))
        assertEquals(CodeLanguage.OTHER, CodeLanguage.fromFileName("Makefile"))
    }

    @Test
    fun chromeCustomTabsManager_normalizesWorkspaceAndAuthUrls() {
        assertEquals("https://vscode.dev", ChromeCustomTabsManager.normalizeUrl("  vscode.dev "))
        assertEquals("http://127.0.0.1:8080", ChromeCustomTabsManager.normalizeUrl("http://127.0.0.1:8080"))
        assertEquals("https://accounts.google.com", ChromeCustomTabsManager.normalizeUrl("https://accounts.google.com"))
        assertEquals("", ChromeCustomTabsManager.normalizeUrl("   "))
    }

    @Test
    fun normalizeAcceptMimeTypes_handlesEmptyAndExtensionsSafely() {
        assertArrayEquals(arrayOf("*/*"), InAppWebWorkspaceController.normalizeAcceptMimeTypes(null))
        assertArrayEquals(arrayOf("*/*"), InAppWebWorkspaceController.normalizeAcceptMimeTypes(arrayOf("", "  ")))
        assertArrayEquals(
            arrayOf("application/json", "text/plain"),
            InAppWebWorkspaceController.normalizeAcceptMimeTypes(arrayOf("application/json, .py")),
        )
    }

    @Test
    fun autoLoginOrigin_mustMatchProfileOrigin() {
        assertTrue(
            InAppWebWorkspaceController.hasSameOrigin(
                "https://example.com/workspace",
                "https://EXAMPLE.com/login",
            ),
        )
        assertFalse(
            InAppWebWorkspaceController.hasSameOrigin(
                "https://example.com",
                "https://accounts.example.com",
            ),
        )
        assertFalse(
            InAppWebWorkspaceController.hasSameOrigin(
                "https://example.com",
                "http://example.com",
            ),
        )
        assertFalse(
            InAppWebWorkspaceController.hasSameOrigin(
                "https://example.com",
                "https://example.com:8443",
            ),
        )
    }

    @Test
    fun termuxBootstrap_requiresPasswordAuthAndBindsOnlyToLoopback() {
        val password = TermuxRunCommand.generatePassword()
        val command = TermuxRunCommand.codeServerCommand(
            port = 4321,
            password = password,
            workspaceSlug = "My workspace",
            starterFiles = mapOf(
                "index.html" to "<h1>safe starter</h1>",
                "../startup.sh" to "unsafe",
            ),
        )

        assertTrue(command.contains("--bind-addr 127.0.0.1:4321"))
        assertTrue(command.contains("--auth password"))
        assertFalse(command.contains("--auth none"))
        assertTrue(command.contains("PASSWORD='$password'"))
        assertTrue(command.contains("workspaces/my-workspace/index.html"))
        assertFalse(command.contains("unsafe"))
        assertFalse(command.contains("workspaces/my-workspace/../"))
    }

    @Test
    fun termuxBootstrap_rejectsInvalidPortsAndPasswords() {
        val password = TermuxRunCommand.generatePassword()
        assertEquals(48, password.length)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            TermuxRunCommand.codeServerCommand(80, password, "test")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            TermuxRunCommand.codeServerCommand(8080, "unsafe; command", "test")
        }
    }

    @Test
    fun localhostServerEngine_handlesConcurrentStartAndAbruptClientCloseWithoutCrashing() = runTest {
        val tempDir: File = createTempDirectory("vsshell_test").toFile()
        val engine = LocalhostServerEngine(tempDir)
        try {
            val results = (1..4).map { idx ->
                async {
                    engine.ensureLocalhostReady(
                        requestedPort = 0,
                        workspaceName = "Test WS $idx",
                        workspaceSlug = "test-ws",
                    )
                }
            }.awaitAll()
            val status = results.last()
            assertTrue(status.isRunning)
            assertTrue(status.port > 0)

            Socket("127.0.0.1", status.port).use { socket ->
                socket.getOutputStream().write(
                    "GET $accessPath HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(),
                )
                socket.getOutputStream().flush()
            }
            val verifyStatus = engine.ensureLocalhostReady(
                requestedPort = status.port,
                workspaceName = "Test WS",
                workspaceSlug = "test-ws",
            )
            assertTrue(verifyStatus.isRunning)
        } finally {
            engine.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun terminalEmulator_parsesAnsiColorsAndCapsBufferSize() {
        val terminal = TerminalEmulator(maxLines = 3)
        terminal.appendAnsi("SERVER", "\u001B[1;32mReady\u001B[0m on \u001B[36m127.0.0.1:8080\u001B[0m")
        terminal.warn("WARN", "line 2")
        terminal.error("ERR", "line 3")
        terminal.info("INFO", "line 4")

        val lines = terminal.lines.value
        assertEquals(3, lines.size)
        assertEquals("WARN", lines[0].source)
        assertEquals("ERR", lines[1].source)
        assertEquals("INFO", lines[2].source)
        assertEquals(TerminalTone.INFO, lines[2].spans.first().tone)

        val parsed = TerminalEmulator.parseAnsiSpans("\u001B[1;32mOK\u001B[0m plain")
        assertEquals("OK", parsed[0].text)
        assertEquals(TerminalTone.SUCCESS, parsed[0].tone)
        assertTrue(parsed[0].bold)
        assertEquals(" plain", parsed[1].text)
        assertEquals(TerminalTone.DEFAULT, parsed[1].tone)
    }

    @Test
    fun localhostServerEngine_emitsStartupLogsToSink() = runTest {
        val tempDir: File = createTempDirectory("vsshell_logs").toFile()
        val engine = LocalhostServerEngine(tempDir)
        val terminal = TerminalEmulator()
        engine.setLogSink { source, msg -> terminal.appendAnsi(source, msg) }
        try {
            val status = engine.ensureLocalhostReady(
                requestedPort = 0,
                workspaceName = "Log Test",
                workspaceSlug = "log-test",
            )
            assertTrue(status.isRunning)
            val dump = terminal.dumpPlainText()
            assertTrue(dump.contains("[SERVER]"))
            assertTrue(dump.contains("127.0.0.1:${status.port}"))
        } finally {
            engine.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun localhostServerEngine_rejectsInvalidPortProbeAndAnswersHeadRequest() = runTest {
        val tempDir: File = createTempDirectory("vsshell_probe").toFile()
        val engine = LocalhostServerEngine(tempDir)
        try {
            assertFalse(engine.isPortAcceptingConnections(0))
            assertFalse(engine.isPortAcceptingConnections(-1))
            assertFalse(engine.isPortAcceptingConnections(70000))

            val status = engine.ensureLocalhostReady(
                requestedPort = 0,
                workspaceName = "Probe Test",
                workspaceSlug = "probe-test",
            )
            assertTrue(status.isRunning)

            Socket("127.0.0.1", status.port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write("HEAD / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                val headerLine = socket.getInputStream().bufferedReader().readLine()
                assertTrue(headerLine.orEmpty().contains("200 OK"))
            }
        } finally {
            engine.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun startupDiagnostics_formatsExportableDiagnosticTextFileReport() {
        val fileName = StartupDiagnostics.defaultExportFileName(1_700_000_000_000L)
        assertTrue(fileName.startsWith("vsshell-crash-log-"))
        assertTrue(fileName.endsWith(".txt"))

        val report = StartupDiagnostics.formatDiagnosticReport(
            exportedAtMs = 1_700_000_000_000L,
            packageName = "com.aistudio.vsshell.krmxvp",
            versionName = "1.0.3",
            versionCode = 3L,
            deviceLabel = "Pixel 9",
            androidRelease = "15",
            sdkInt = 35,
            lastKnownState = "Testing localhost 127.0.0.1:8080",
            lastCrashLog = "IllegalStateException: Test failure",
            crashHistoryLog = "Previous crash entry",
            extraRuntimeLogs = "[SERVER] Ready on 127.0.0.1:8080",
        )
        assertTrue(report.contains("VS SHELL DIAGNOSTIC & CRASH LOG EXPORT"))
        assertTrue(report.contains("com.aistudio.vsshell.krmxvp"))
        assertTrue(report.contains("Testing localhost 127.0.0.1:8080"))
        assertTrue(report.contains("IllegalStateException: Test failure"))
        assertTrue(report.contains("Previous crash entry"))
        assertTrue(report.contains("[SERVER] Ready on 127.0.0.1:8080"))
    }

    @Test
    fun rebootBackoffTracker_appliesExponentialBackoffAndCircuitBreaker() {
        val tracker = RebootBackoffTracker(
            initialBackoffMs = 2_000L,
            backoffMultiplier = 2.0,
            maxBackoffMs = 60_000L,
            maxConsecutiveFailures = 5,
            healthyResetWindowMs = 60_000L,
        )

        val d1 = tracker.onProcessDied(10_000L) as RebootDecision.ScheduleReboot
        val d2 = tracker.onProcessDied(12_000L) as RebootDecision.ScheduleReboot
        val d3 = tracker.onProcessDied(16_000L) as RebootDecision.ScheduleReboot
        val d4 = tracker.onProcessDied(24_000L) as RebootDecision.ScheduleReboot
        val d5 = tracker.onProcessDied(40_000L) as RebootDecision.ScheduleReboot

        assertEquals(2_000L, d1.delayMs)
        assertEquals(4_000L, d2.delayMs)
        assertEquals(8_000L, d3.delayMs)
        assertEquals(16_000L, d4.delayMs)
        assertEquals(32_000L, d5.delayMs)
        assertEquals(5, d5.attemptNumber)

        val d6 = tracker.onProcessDied(45_000L)
        assertTrue(d6 is RebootDecision.CircuitOpen)
        assertEquals(6, (d6 as RebootDecision.CircuitOpen).consecutiveFailures)

        tracker.reset(nowMs = 50_000L)
        val afterReset = tracker.onProcessDied(52_000L) as RebootDecision.ScheduleReboot
        assertEquals(1, afterReset.attemptNumber)
        assertEquals(2_000L, afterReset.delayMs)
        assertEquals(6, tracker.snapshot().totalReboots)
    }

    @Test
    fun rebootBackoffTracker_resetsConsecutiveFailuresAfterHealthyWindow() {
        val tracker = RebootBackoffTracker(
            initialBackoffMs = 2_000L,
            maxConsecutiveFailures = 3,
            healthyResetWindowMs = 60_000L,
        )
        tracker.restore(WatchdogSnapshot(consecutiveFailures = 2, totalReboots = 2))
        tracker.recordHealthyHeartbeat(nowMs = 100_000L)
        assertEquals(2, tracker.snapshot().consecutiveFailures)

        tracker.recordHealthyHeartbeat(nowMs = 165_000L)
        assertEquals(0, tracker.snapshot().consecutiveFailures)

        val decision = tracker.onProcessDied(170_000L) as RebootDecision.ScheduleReboot
        assertEquals(1, decision.attemptNumber)
        assertEquals(2_000L, decision.delayMs)
    }

    @Test
    fun localhostServerEngine_healthProbeReflectsStartAndStopLifecycle() = runTest {
        val tempDir: File = createTempDirectory("vsshell_watchdog_probe").toFile()
        val engine = LocalhostServerEngine(tempDir)
        try {
            val status = engine.ensureLocalhostReady(
                requestedPort = 0,
                workspaceName = "Watchdog Probe",
                workspaceSlug = "watchdog-probe",
            )
            assertTrue(status.isRunning)
            assertTrue(engine.isEmbeddedServerHealthy(status.port))
            assertTrue(engine.probeLocalhostHttp(status.port))

            engine.stop()
            assertFalse(engine.isEmbeddedServerHealthy(status.port))
            assertFalse(engine.probeLocalhostHttp(status.port))
        } finally {
            engine.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun isModelUnavailableError_identifies404AndThinkingErrorsButExcludes403AndGeneric400() {
        assertTrue(GeminiChatService.isModelUnavailableError(IllegalStateException("HTTP 404 NOT_FOUND: model gemini-3.8-flash not found")))
        assertTrue(GeminiChatService.isModelUnavailableError(IllegalArgumentException("HTTP 400 INVALID_ARGUMENT: thinkingLevel not supported")))
        assertFalse(GeminiChatService.isModelUnavailableError(IllegalArgumentException("HTTP 400 INVALID_ARGUMENT: malformed content payload")))
        assertFalse(GeminiChatService.isModelUnavailableError(IllegalStateException("HTTP 403 AppCheck token invalid")))
        assertFalse(GeminiChatService.isModelUnavailableError(IllegalStateException("PERMISSION_DENIED")))
        assertFalse(GeminiChatService.isModelUnavailableError(SocketTimeoutException("Read timed out")))
    }

    @Test
    fun secureVault_aesGcmRoundTripTamperCheckAndPinVerification() {
        val key = SecureVault.generateRawAes256KeyForTests()
        val secretBytes = "super-secret-token-value".toByteArray(Charsets.UTF_8)
        val encrypted = SecureVault.encryptAesGcmBase64(secretBytes, key)
        val decrypted = SecureVault.decryptAesGcmBase64(encrypted, key)
        assertArrayEquals(secretBytes, decrypted)

        val tamperedChars = encrypted.toCharArray()
        val tamperIdx = tamperedChars.size / 2
        tamperedChars[tamperIdx] = if (tamperedChars[tamperIdx] == 'A') 'B' else 'A'
        var failedTamper = false
        try {
            SecureVault.decryptAesGcmBase64(String(tamperedChars), key)
        } catch (_: AEADBadTagException) {
            failedTamper = true
        }
        assertTrue(failedTamper)

        val hash = SecureVault.hashPin("482910")
        assertTrue(hash.startsWith("pbkdf2:sha256:"))
        assertTrue(SecureVault.verifyPin("482910", hash))
        assertFalse(SecureVault.verifyPin("000000", hash))
        assertFalse(SecureVault.verifyPin("48291", hash))

        assertEquals(
            "https://vscode.dev/auth?[redacted]",
            SecureVault.sanitizeUrlForLogs("https://vscode.dev/auth?token=secret123#frag"),
        )
        val redacted = SecureVault.redactSensitiveText("Authorization: Bearer abc.def-123 password=mysecret")
        assertFalse(redacted.contains("abc.def-123"))
        assertFalse(redacted.contains("mysecret"))
    }

    @Test
    fun localhostServerEngine_enforcesSessionTokenHostOriginAndPathConfinement() = runTest {
        val tempDir: File = createTempDirectory("vsshell_sec_test").toFile()
        val engine = LocalhostServerEngine(tempDir)
        try {
            val status = engine.ensureLocalhostReady(
                requestedPort = 0,
                workspaceName = "Sec Test",
                workspaceSlug = "sec-test",
            )
            assertTrue(status.isRunning)

            val unauthResp = sendRawHttp(
                port = status.port,
                request = "GET /api/files HTTP/1.1\r\nHost: 127.0.0.1:${status.port}\r\nConnection: close\r\n\r\n",
            )
            assertTrue(unauthResp.startsWith("HTTP/1.1 403 Forbidden"))

            val wrongTokenResp = sendRawHttp(
                port = status.port,
                request = "GET /api/files HTTP/1.1\r\nHost: 127.0.0.1:${status.port}\r\nX-VSShell-Token: 0000\r\nConnection: close\r\n\r\n",
            )
            assertTrue(wrongTokenResp.startsWith("HTTP/1.1 403 Forbidden"))

            val badHostResp = sendRawHttp(
                port = status.port,
                request = "GET / HTTP/1.1\r\nHost: evil.attacker.example\r\nConnection: close\r\n\r\n",
            )
            assertTrue(badHostResp.startsWith("HTTP/1.1 403 Forbidden"))

            val badOriginResp = sendRawHttp(
                port = status.port,
                request = "GET /api/files HTTP/1.1\r\nHost: 127.0.0.1:${status.port}\r\nOrigin: https://evil.attacker.example\r\nConnection: close\r\n\r\n",
            )
            assertTrue(badOriginResp.startsWith("HTTP/1.1 403 Forbidden"))

            assertNull(engine.resolveConfinedFile(tempDir, "../outside.txt"))
            assertNull(engine.resolveConfinedFile(tempDir, "..\\outside.txt"))

            val rootResp = sendRawHttp(
                port = status.port,
                request = "GET / HTTP/1.1\r\nHost: 127.0.0.1:${status.port}\r\nConnection: close\r\n\r\n",
            )
            assertTrue(rootResp.contains("sandbox=\"allow-scripts allow-forms\""))
            val match = Regex("""const SESSION_TOKEN = "([a-f0-9]+)";""").find(rootResp)
            val token = match?.groupValues?.getOrNull(1).orEmpty()
            assertEquals(64, token.length)

            val authDeleteResp = sendRawHttp(
                port = status.port,
                request = "DELETE /api/file?name=nonexistent.txt HTTP/1.1\r\nHost: 127.0.0.1:${status.port}\r\nX-VSShell-Token: $token\r\nConnection: close\r\n\r\n",
            )
            assertTrue(authDeleteResp.startsWith("HTTP/1.1 200 OK"))
            assertTrue(authDeleteResp.contains("X-Content-Type-Options: nosniff"))
            assertTrue(authDeleteResp.contains("Cache-Control: no-store, no-cache"))
            assertTrue(authDeleteResp.contains("Pragma: no-cache"))

            val infoBefore = sendRawHttp(
                port = status.port,
                request = "GET /api/info HTTP/1.1\r\nHost: 127.0.0.1:${status.port}\r\nX-VSShell-Token: $token\r\nConnection: close\r\n\r\n",
            )
            assertTrue(infoBefore.contains("\"slug\":\"sec-test\""))
            val revBefore = Regex(""""revision":(\d+)""").find(infoBefore)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            assertTrue(revBefore > 0L)

            engine.writeStarterFiles(engine.getWorkspaceDir("sec-test"), mapOf("ai-test.html" to "<h1>Hi</h1>"))
            val infoAfter = sendRawHttp(
                port = status.port,
                request = "GET /api/info HTTP/1.1\r\nHost: 127.0.0.1:${status.port}\r\nX-VSShell-Token: $token\r\nConnection: close\r\n\r\n",
            )
            val revAfter = Regex(""""revision":(\d+)""").find(infoAfter)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            assertTrue(revAfter > revBefore)

            val previewResp = sendRawHttp(
                port = status.port,
                request = "GET /preview/index.html HTTP/1.1\r\nHost: 127.0.0.1:${status.port}\r\nConnection: close\r\n\r\n",
            )
            assertTrue(previewResp.startsWith("HTTP/1.1 200 OK"))
            assertTrue(previewResp.contains("Content-Security-Policy:"))
            assertTrue(previewResp.contains("connect-src 'none'"))
        } finally {
            engine.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun urlAndWebViewAutofillGuards_blockDangerousSchemesAndCrossHostAutofill() {
        assertEquals("", ChromeCustomTabsManager.normalizeUrl("javascript:alert(1)"))
        assertEquals("", ChromeCustomTabsManager.normalizeUrl("file:///data/data/com.aistudio.vsshell.krmxvp/shared_prefs/a.xml"))
        assertEquals("", ChromeCustomTabsManager.normalizeUrl("data:text/html,<script>alert(1)</script>"))
        assertEquals("", ChromeCustomTabsManager.normalizeUrl("intent://scan/#Intent;end"))
        assertEquals("https://localhost:8080", ChromeCustomTabsManager.normalizeUrl("localhost:8080"))

        assertTrue(InAppWebWorkspaceController.isBlockedUnsafeScheme("javascript:alert(1)"))
        assertTrue(InAppWebWorkspaceController.isBlockedUnsafeScheme("file:///etc/hosts"))
        assertFalse(InAppWebWorkspaceController.isBlockedUnsafeScheme("about:blank"))

        assertTrue(InAppWebWorkspaceController.isAllowedAutofillTarget("https://coder.example.com/login", "coder.example.com"))
        assertTrue(InAppWebWorkspaceController.isAllowedAutofillTarget("http://127.0.0.1:8080/", "127.0.0.1"))
        assertFalse(InAppWebWorkspaceController.isAllowedAutofillTarget("https://evil.example.com/login", "coder.example.com"))
        assertFalse(InAppWebWorkspaceController.isAllowedAutofillTarget("http://coder.example.com/login", "coder.example.com"))
    }

    @Test
    fun localhostAndPhoneFilesUiStates_exposeCleanErrorAndEdgeCaseDefaults() {
        val localDefault = LocalhostUiState()
        assertFalse(localDefault.isProvisioning)
        assertNull(localDefault.errorMessage)
        assertNull(localDefault.activeStatus)

        val localWithError = localDefault.copy(
            isProvisioning = false,
            errorMessage = "Port 8080 refused",
        )
        assertEquals("Port 8080 refused", localWithError.errorMessage)

        val filesDefault = PhoneFilesUiState()
        assertFalse(filesDefault.isLoading)
        assertNull(filesDefault.errorMessage)
        assertNull(filesDefault.editingFile)

        assertEquals(8080, WorkspaceViewModel.extractLocalhostPort("http://0.0.0.0"))
        assertEquals(9090, WorkspaceViewModel.extractLocalhostPort("http://0.0.0.0:9090"))
        assertNull(WorkspaceViewModel.extractLocalhostPort(""))
        assertNull(WorkspaceViewModel.extractLocalhostPort("javascript:alert(1)"))
        assertNull(WorkspaceViewModel.extractLocalhostPort("not a valid url !!!"))
    }

    @Test
    fun compactTypography_staysWithinCompactScaleBounds() {
        assertTrue(AppTypography.headlineMedium.fontSize.value <= 18f)
        assertTrue(AppTypography.titleLarge.fontSize.value <= 16f)
        assertTrue(AppTypography.titleMedium.fontSize.value <= 14f)
        assertTrue(AppTypography.bodyLarge.fontSize.value <= 13f)
        assertTrue(AppTypography.bodyMedium.fontSize.value <= 12f)
        assertTrue(AppTypography.labelSmall.fontSize.value <= 10f)
    }

    // -------------------------------------------------------------------------
    // 12 MANDATORY LOCALHOST, GEMINI BOOTSTRAP & CODESPACES TEST CASES
    // -------------------------------------------------------------------------

    @Test // Scenario 1: Successful Gemini bootstrap + file creation + embedded server startup + HTTP readiness + DB registration
    fun scenario01_successfulGeminiBootstrapAndEmbeddedServerReadyCommitsProfile() = runTest {
        val tempDir = createTempDirectory("vsshell_s01").toFile()
        val engine = LocalhostServerEngine(tempDir)
        val dao = FakeServerProfileDao()
        val sessionDao = FakeActiveSessionDao()
        val repo = ServerProfileRepository(dao, sessionDao)

        try {
            val geminiPayload = """
                {
                  "summary": "Node/HTML starter ready",
                  "termuxCommand": "pkg install -y nodejs-lts code-server && code-server --bind-addr 127.0.0.1:8080 --auth none",
                  "indexHtml": "<!doctype html><html><body><h1>Gemini App</h1></body></html>",
                  "mainScriptName": "server.js",
                  "mainScriptContent": "console.log('ready');",
                  "readmeMd": "# Gemini Starter"
                }
            """.trimIndent()
            val plan = GeminiChatService.parseLocalhostBootstrapJson(geminiPayload, "My Gemini App", 8080)
            assertTrue(plan.generatedByAi)
            assertEquals(3, plan.starterFiles.size)
            assertEquals("", plan.termuxCommand)
            assertFalse(plan.starterFiles.containsKey("start-termux-codeserver.sh"))

            val draft = repo.createDraftProfile(
                ServerProfile(
                    name = "My Gemini App",
                    url = "http://127.0.0.1:8080",
                    connectionMode = ConnectionMode.DIRECT_CODE_SERVER,
                    provisioningState = WorkspaceProvisioningState.GENERATING,
                    runtimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
                    workspaceSlug = "ws-my-gemini-app",
                ),
            )
            assertFalse(draft.isActive)

            val status = engine.ensureLocalhostReady(
                requestedPort = 0,
                workspaceName = draft.name,
                workspaceSlug = draft.workspaceSlug,
                termuxCommand = plan.termuxCommand,
                starterFiles = plan.starterFiles,
                allowExternalAttach = false,
                allowPortFallback = true,
            )
            assertTrue(status.isRunning)
            assertTrue(engine.probeLocalhostHttp(status.port))

            val committed = repo.commitReadyProfile(
                profileId = draft.id,
                actualUrl = status.url,
                actualSlug = draft.workspaceSlug,
                runtimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
            )
            assertTrue(committed.isActive)
            assertEquals(WorkspaceProvisioningState.READY, committed.provisioningState)
            assertEquals(status.url, committed.url)
            assertEquals(committed.id, sessionDao.getActiveSession()?.activeProfileId)
            assertTrue(File(status.workspaceDirPath, "server.js").readText().contains("ready"))
        } finally {
            engine.stopSuspending()
            tempDir.deleteRecursively()
        }
    }

    @Test // Scenario 2: Gemini network timeout / unavailable API error + honest categorized failure state
    fun scenario02_geminiTimeoutAndPermissionErrorsClassifyHonestlyWithoutFakeSuccess() {
        val timeoutErr = GeminiChatService.classifyGeminiFailure(SocketTimeoutException("Read timed out"))
        assertEquals(GeminiFailureReason.TIMEOUT, timeoutErr.reason)
        assertTrue(timeoutErr.message.orEmpty().contains("timed out", ignoreCase = true))

        val networkErr = GeminiChatService.classifyGeminiFailure(UnknownHostException("Unable to resolve host"))
        assertEquals(GeminiFailureReason.NETWORK_UNAVAILABLE, networkErr.reason)

        val permErr = GeminiChatService.classifyGeminiFailure(IllegalStateException("HTTP 403 AppCheck token rejected"))
        assertEquals(GeminiFailureReason.API_PERMISSION_DENIED, permErr.reason)

        val firebaseErr = GeminiChatService.classifyGeminiFailure(IllegalStateException("Default FirebaseApp is not initialized"))
        assertEquals(GeminiFailureReason.FIREBASE_NOT_INITIALIZED, firebaseErr.reason)
    }

    @Test // Scenario 3: Gemini returning invalid/empty JSON + rollback / non-registration
    fun scenario03_geminiInvalidOrEmptyResponseThrowsCategorizedErrorAndRollsBackDraft() = runTest {
        var emptyThrown: GeminiBootstrapException? = null
        try {
            GeminiChatService.parseLocalhostBootstrapJson(
                rawJson = """{"summary":"empty","termuxCommand":"echo hi","indexHtml":"","mainScriptContent":""}""",
                workspaceName = "EmptyWS",
                port = 8080,
            )
        } catch (e: GeminiBootstrapException) {
            emptyThrown = e
        }
        assertEquals(GeminiFailureReason.EMPTY_GENERATED_FILES, emptyThrown?.reason)

        var invalidThrown: GeminiBootstrapException? = null
        try {
            GeminiChatService.parseLocalhostBootstrapJson(
                rawJson = "not-json-at-all {{{",
                workspaceName = "BrokenWS",
                port = 8080,
            )
        } catch (e: GeminiBootstrapException) {
            invalidThrown = e
        }
        assertEquals(GeminiFailureReason.INVALID_RESPONSE, invalidThrown?.reason)

        val dao = FakeServerProfileDao()
        val sessionDao = FakeActiveSessionDao()
        val repo = ServerProfileRepository(dao, sessionDao)
        val existing = repo.saveAndActivate(
            ServerProfile(
                name = "Previous Active",
                url = "https://vscode.dev",
                provisioningState = WorkspaceProvisioningState.READY,
                runtimeKind = LocalhostRuntimeKind.REMOTE_CLOUD_IDE,
            ),
        )
        val failedDraft = repo.createDraftProfile(
            ServerProfile(
                name = "BrokenWS",
                url = "http://127.0.0.1:8080",
                provisioningState = WorkspaceProvisioningState.GENERATING,
            ),
        )
        repo.rollbackDraftProfile(
            draftId = failedDraft.id,
            previousActiveId = existing.id,
            deleteDraft = false,
            failureState = WorkspaceProvisioningState.FAILED,
            errorMessage = emptyThrown?.message.orEmpty(),
        )
        val restored = repo.getById(existing.id)
        val draftRow = repo.getById(failedDraft.id)
        assertTrue(restored?.isActive == true)
        assertFalse(draftRow?.isActive == true)
        assertEquals(WorkspaceProvisioningState.FAILED, draftRow?.provisioningState)
    }

    @Test // Scenario 4: Starter file write failure returns honest status without marking running
    fun scenario04_starterFileWriteFailureReturnsNonRunningFailureStatus() = runTest {
        val tempDir = createTempDirectory("vsshell_s04").toFile()
        val engine = LocalhostServerEngine(tempDir)
        try {
            val slug = "write-fail-ws"
            val blockedFile = File(tempDir, "localhost_workspaces/$slug")
            blockedFile.parentFile?.mkdirs()
            // Make workspace root path a regular file instead of directory so writing inside fails
            blockedFile.writeText("blocker")

            val status = engine.ensureLocalhostReady(
                requestedPort = 0,
                workspaceName = "Blocked",
                workspaceSlug = slug,
                starterFiles = mapOf("index.html" to "<h1>Hi</h1>"),
            )
            assertFalse(status.isRunning)
            assertTrue(status.failureDetail.contains("write failure", ignoreCase = true))
        } finally {
            engine.stopSuspending()
            tempDir.deleteRecursively()
        }
    }

    @Test // Scenario 5: Port already in use — rejects non-HTTP TCP socket, detects HTTP server, falls back & returns actual port
    fun scenario05_portConflictRejectsRawTcpSocketAndFallsBackToFreePort() = runTest {
        val tempDir = createTempDirectory("vsshell_s05").toFile()
        val engine = LocalhostServerEngine(tempDir)
        // Open a non-HTTP silent TCP listener
        val nonHttpSocket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val acceptThread = Thread {
            runCatching {
                nonHttpSocket.accept().use { s ->
                    s.getOutputStream().write("RAW_BANNER_NOT_HTTP\r\n".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }.apply { start() }

        try {
            val nonHttpPort = nonHttpSocket.localPort
            val probe = engine.probeHttpIdentity(nonHttpPort)
            assertEquals(PortProbeKind.OCCUPIED_NON_HTTP_OR_UNRESPONSIVE, probe.kind)
            assertFalse(engine.isPortAcceptingConnections(nonHttpPort))

            // Attempting to bind nonHttpPort with fallback enabled must bind a free port and report actual port
            val status = engine.ensureLocalhostReady(
                requestedPort = nonHttpPort,
                workspaceName = "Fallback WS",
                workspaceSlug = "fallback-ws",
                allowExternalAttach = true,
                allowPortFallback = true,
            )
            assertTrue(status.isRunning)
            assertNotEquals(nonHttpPort, status.port)
            assertEquals("http://127.0.0.1:${status.port}", status.url)
            assertTrue(engine.probeLocalhostHttp(status.port))
        } finally {
            runCatching { nonHttpSocket.close() }
            runCatching { acceptThread.join(1000) }
            engine.stopSuspending()
            tempDir.deleteRecursively()
        }
    }

    @Test // Scenario 6: Server startup failure when port busy and fallback disabled reports honest error
    fun scenario06_serverStartupFailureWithoutFallbackReportsHonestFailure() = runTest {
        val tempDir = createTempDirectory("vsshell_s06").toFile()
        val engine = LocalhostServerEngine(tempDir)
        val blocker = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        try {
            val status = engine.ensureLocalhostReady(
                requestedPort = blocker.localPort,
                workspaceName = "Strict Port WS",
                workspaceSlug = "strict-port-ws",
                allowExternalAttach = false,
                allowPortFallback = false,
            )
            assertFalse(status.isRunning)
            assertTrue(status.failureDetail.contains("Failed to bind", ignoreCase = true))
        } finally {
            runCatching { blocker.close() }
            engine.stopSuspending()
            tempDir.deleteRecursively()
        }
    }

    @Test // Scenario 7: Workspace registration only after HTTP readiness (no premature active registration)
    fun scenario07_workspaceRegistrationOnlyActiveAfterVerifiedCommit() = runTest {
        val dao = FakeServerProfileDao()
        val sessionDao = FakeActiveSessionDao()
        val repo = ServerProfileRepository(dao, sessionDao)

        val draft = repo.createDraftProfile(
            ServerProfile(
                name = "Pending WS",
                url = "http://127.0.0.1:8080",
                provisioningState = WorkspaceProvisioningState.GENERATING,
                workspaceSlug = "ws-pending",
            ),
        )
        assertNull(dao.observeActiveProfile().first())
        assertFalse(draft.isActive)

        repo.updateProvisioningState(draft.id, WorkspaceProvisioningState.VERIFYING, "")
        assertNull(dao.observeActiveProfile().first())

        val ready = repo.commitReadyProfile(
            profileId = draft.id,
            actualUrl = "http://127.0.0.1:41234",
            actualSlug = "ws-pending",
            runtimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
        )
        assertTrue(ready.isActive)
        assertEquals(WorkspaceProvisioningState.READY, ready.provisioningState)
        assertEquals("http://127.0.0.1:41234", dao.observeActiveProfile().first()?.url)
    }

    @Test // Scenario 8: Termux external server probe requires real HTTP server (not missing/refused socket)
    fun scenario08_termuxExternalVerificationDetectsStoppedVsRunningHttpServer() = runTest {
        val tempDir = createTempDirectory("vsshell_s08").toFile()
        val engine = LocalhostServerEngine(tempDir)
        // Find a guaranteed free port first
        val freePort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        assertEquals(PortProbeKind.FREE, engine.probeHttpIdentity(freePort).kind)
        assertFalse(engine.isPortAcceptingConnections(freePort))

        // Now start a minimal external HTTP server simulating Termux code-server
        val mockTermux = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            runCatching {
                while (!mockTermux.isClosed) {
                    mockTermux.accept().use { s ->
                        s.soTimeout = 1000
                        s.getInputStream().bufferedReader().readLine()
                        s.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nServer: code-server\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray(),
                        )
                        s.getOutputStream().flush()
                    }
                }
            }
        }.apply { start() }

        try {
            val probe = engine.probeHttpIdentity(mockTermux.localPort)
            assertEquals(PortProbeKind.EXTERNAL_HTTP_SERVER, probe.kind)
            assertTrue(engine.isPortAcceptingConnections(mockTermux.localPort))
        } finally {
            runCatching { mockTermux.close() }
            runCatching { t.join(1000) }
            tempDir.deleteRecursively()
        }
    }

    @Test // Scenario 9: Reopening existing workspace preserves user-modified files on disk
    fun scenario09_reopeningExistingWorkspacePreservesModifiedUserFiles() = runTest {
        val tempDir = createTempDirectory("vsshell_s09").toFile()
        val engine = LocalhostServerEngine(tempDir)
        try {
            val first = engine.ensureLocalhostReady(
                requestedPort = 0,
                workspaceName = "Preserve WS",
                workspaceSlug = "preserve-ws",
                starterFiles = mapOf(
                    "index.html" to "<h1>Initial</h1>",
                    "app.js" to "console.log('initial');",
                ),
            )
            assertTrue(first.isRunning)
            val userHtml = File(first.workspaceDirPath, "index.html")
            userHtml.writeText("<h1>User Custom Work</h1>")

            // Reopen / retry without overwriteExisting -> custom edits MUST remain intact
            val written = engine.writeStarterFiles(
                File(first.workspaceDirPath),
                mapOf(
                    "index.html" to "<h1>Template Clobber</h1>",
                    "new_file.txt" to "fresh",
                ),
                overwriteExisting = false,
            )
            assertEquals(1, written)
            assertEquals("<h1>User Custom Work</h1>", userHtml.readText())
            assertEquals("fresh", File(first.workspaceDirPath, "new_file.txt").readText())
        } finally {
            engine.stopSuspending()
            tempDir.deleteRecursively()
        }
    }

    @Test // Scenario 10: Unique workspace slugs & state-guard prevent duplicate concurrent creation
    fun scenario10_uniqueSlugsAndStateGuardsPreventDuplicateCreations() {
        val slug1 = WorkspaceViewModel.buildUniqueWorkspaceSlug("My App", suffix = 1001L)
        val slug2 = WorkspaceViewModel.buildUniqueWorkspaceSlug("My App", suffix = 1002L)
        assertNotEquals(slug1, slug2)
        assertTrue(slug1.startsWith("ws-my-app-"))

        val inProgressState = LocalhostUiState(
            isProvisioning = true,
            provisioningState = WorkspaceProvisioningState.GENERATING,
        )
        assertTrue(inProgressState.isProvisioning)
        assertEquals(WorkspaceProvisioningState.GENERATING, inProgressState.provisioningState)
    }

    @Test // Scenario 11: GitHub Codespaces URL routing + HTTP auth / stopped / gateway error classification
    fun scenario11_githubCodespacesUrlsAndHttpErrorsAreClassifiedProperly() {
        assertTrue(InAppWebWorkspaceController.isCodespacesUrl("https://github.com/codespaces"))
        assertTrue(InAppWebWorkspaceController.isCodespacesUrl("https://laughing-space-goggles-8080.app.github.dev/"))
        assertTrue(InAppWebWorkspaceController.isCodespacesUrl("https://obscure-cod.github.dev/?folder=/workspaces/demo"))
        assertFalse(InAppWebWorkspaceController.isCodespacesUrl("http://127.0.0.1:8080"))

        val unauthMsg = InAppWebWorkspaceController.classifyHttpErrorMessage("https://github.com/codespaces", 401)
        assertTrue(unauthMsg.contains("Sign in with GitHub", ignoreCase = true))

        val stoppedMsg = InAppWebWorkspaceController.classifyHttpErrorMessage("https://demo.app.github.dev", 502)
        assertTrue(stoppedMsg.contains("stopped", ignoreCase = true))

        val notFoundMsg = InAppWebWorkspaceController.classifyHttpErrorMessage("https://demo.app.github.dev", 404)
        assertTrue(notFoundMsg.contains("404"))
    }

    @Test // Scenario 12: Server stop + restart recovery preserves workspace & saves JSON edits via SimpleJson
    fun scenario12_serverStopAndRestartRecoveryWithSimpleJsonSaveWorksCleanly() = runTest {
        val tempDir = createTempDirectory("vsshell_s12").toFile()
        val engine = LocalhostServerEngine(tempDir)
        try {
            val initial = engine.ensureLocalhostReady(
                requestedPort = 0,
                workspaceName = "Recover WS",
                workspaceSlug = "recover-ws",
            )
            assertTrue(initial.isRunning)

            // Save a file via HTTP /api/save using SimpleJson backend
            val rootHtml = sendRawHttp(
                port = initial.port,
                request = "GET / HTTP/1.1\r\nHost: 127.0.0.1:${initial.port}\r\nConnection: close\r\n\r\n",
            )
            val token = Regex("""const SESSION_TOKEN = "([a-f0-9]+)";""").find(rootHtml)?.groupValues?.get(1).orEmpty()
            assertTrue(token.length == 64)

            val saveBody = """{"name":"notes.txt","content":"line1\nline2"}"""
            val saveResp = sendRawHttp(
                port = initial.port,
                request = "POST /api/save HTTP/1.1\r\nHost: 127.0.0.1:${initial.port}\r\nX-VSShell-Token: $token\r\nContent-Type: application/json\r\nContent-Length: ${saveBody.toByteArray().size}\r\nConnection: close\r\n\r\n$saveBody",
            )
            assertTrue(saveResp.startsWith("HTTP/1.1 200 OK"))

            // Stop server completely and verify it is down
            engine.stopSuspending()
            assertFalse(engine.probeLocalhostHttp(initial.port))

            // Restart server on same workspace and verify saved file survived & HTTP is healthy
            val recovered = engine.ensureLocalhostReady(
                requestedPort = initial.port,
                workspaceName = "Recover WS",
                workspaceSlug = "recover-ws",
                overwriteStarterFiles = false,
            )
            assertTrue(recovered.isRunning)
            assertTrue(engine.probeLocalhostHttp(recovered.port))
            assertEquals("line1\nline2", File(recovered.workspaceDirPath, "notes.txt").readText())

            // Verify SimpleJson roundtrip parsing handles nested objects and arrays cleanly
            val parsed = SimpleJson.parseObject("""{"ok":true,"items":[{"name":"a"}],"title":"notes"}""")
            assertEquals(true, parsed["ok"])
            assertEquals("notes", SimpleJson.stringValue(parsed, "title"))
            assertEquals("a", SimpleJson.stringValue(SimpleJson.objectListValue(parsed, "items").first(), "name"))
        } finally {
            engine.stopSuspending()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun secureVault_pureKotlinBase64RoundTripsAllPaddingLengthsAndRejectsMalformedInput() {
        listOf(0, 1, 2, 3, 4, 15, 16, 17, 64).forEach { len ->
            val raw = ByteArray(len) { idx -> ((idx * 37 + 13) and 0xFF).toByte() }
            val encoded = SecureVault.encodeBase64(raw)
            val decoded = SecureVault.decodeBase64(encoded)
            assertArrayEquals("Failed Base64 round-trip for length $len", raw, decoded)
        }

        // Standard RFC 4648 test vectors + whitespace/URL-safe handling
        assertEquals("", SecureVault.encodeBase64(ByteArray(0)))
        assertEquals("Zg==", SecureVault.encodeBase64("f".toByteArray(Charsets.UTF_8)))
        assertEquals("Zm8=", SecureVault.encodeBase64("fo".toByteArray(Charsets.UTF_8)))
        assertEquals("Zm9v", SecureVault.encodeBase64("foo".toByteArray(Charsets.UTF_8)))
        assertEquals("Zm9vYmFy", SecureVault.encodeBase64("foobar".toByteArray(Charsets.UTF_8)))
        assertArrayEquals(
            "foobar".toByteArray(Charsets.UTF_8),
            SecureVault.decodeBase64(" Zm9v\nYmFy "),
        )

        var badCharFailed = false
        try {
            SecureVault.decodeBase64("not*valid!")
        } catch (_: IllegalArgumentException) {
            badCharFailed = true
        }
        assertTrue(badCharFailed)

        var badLenFailed = false
        try {
            SecureVault.decodeBase64("A")
        } catch (_: IllegalArgumentException) {
            badLenFailed = true
        }
        assertTrue(badLenFailed)
    }

    @Test
    fun appDatabase_registersAllLegacyToV4MigrationsAndFallbackConverters() {
        assertEquals(1, AppDatabase.MIGRATION_1_4.startVersion)
        assertEquals(4, AppDatabase.MIGRATION_1_4.endVersion)
        assertEquals(2, AppDatabase.MIGRATION_2_4.startVersion)
        assertEquals(4, AppDatabase.MIGRATION_2_4.endVersion)
        assertEquals(3, AppDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, AppDatabase.MIGRATION_3_4.endVersion)

        val converters = ProfileTypeConverters()
        assertEquals(ConnectionMode.TAILSCALE_CUSTOM, converters.toConnectionMode("UNKNOWN_FUTURE_MODE"))
        assertEquals(WorkspaceProvisioningState.NOT_STARTED, converters.toProvisioningState("UNKNOWN_STATE"))
        assertEquals(LocalhostRuntimeKind.EMBEDDED_EDITOR, converters.toRuntimeKind("UNKNOWN_RUNTIME"))
        assertEquals("READY", converters.fromProvisioningState(WorkspaceProvisioningState.READY))
    }

    @Test
    fun secureVault_stripsUserInfoFromUrlsAndRedactsTokensAndKeys() {
        val sanitized = SecureVault.sanitizeUrlForLogs(
            "https://alice:supersecret@dev.example.com:8443/workspace?token=abc#frag",
        )
        assertFalse(sanitized.contains("alice"))
        assertFalse(sanitized.contains("supersecret"))
        assertFalse(sanitized.contains("abc"))
        assertEquals("https://dev.example.com:8443/workspace?[redacted]", sanitized)

        val redacted = SecureVault.redactSensitiveText(
            "pat=github_pat_11ABCDEFGH_1234567890 ghp=ghp_1234567890abcdef key=AIzaSyA12345678901234567890 auth=Basic dXNlcjpwYXNz url=https://bob:pass123@git.example.com/repo.git",
        )
        assertFalse(redacted.contains("github_pat_11ABCDEFGH"))
        assertFalse(redacted.contains("ghp_1234567890abcdef"))
        assertFalse(redacted.contains("AIzaSyA12345678901234567890"))
        assertFalse(redacted.contains("dXNlcjpwYXNz"))
        assertFalse(redacted.contains("bob:pass123"))
    }

    @Test
    fun vsCodeRuntime_verifyEndpointHandlesKeepAliveWithoutTimeoutStall() {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            runCatching {
                server.accept().use { sock ->
                    val input = sock.getInputStream()
                    val headerBuf = ByteArray(1024)
                    input.read(headerBuf)
                    val body = """<!doctype html><html><body><div class="monaco-workbench">code-server</div></body></html>"""
                    val bodyBytes = body.toByteArray(Charsets.UTF_8)
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: keep-alive\r\nContent-Length: ${bodyBytes.size}\r\n\r\n"
                    sock.getOutputStream().write(resp.toByteArray(Charsets.UTF_8) + bodyBytes)
                    sock.getOutputStream().flush()
                    // Deliberately keep socket open longer than client soTimeout (1200ms)
                    Thread.sleep(2_000)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            val startMs = System.currentTimeMillis()
            val probe = com.example.runtime.VsCodeRuntimeController.verifyCodeServerHttpEndpoint(server.localPort)
            val elapsedMs = System.currentTimeMillis() - startMs
            assertTrue("Expected keep-alive code-server to verify cleanly: ${probe.detail}", probe.isGenuineCodeServer)
            assertTrue("Expected bounded reader to return immediately (<800ms), took ${elapsedMs}ms", elapsedMs < 800L)
        } finally {
            runCatching { server.close() }
            runCatching { worker.interrupt() }
        }
    }

    private fun sendRawHttp(port: Int, request: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            return socket.getInputStream().bufferedReader().readText()
        }
    }
}

private class FakeServerProfileDao : ServerProfileDao {
    private val rows = linkedMapOf<Long, ServerProfile>()
    private val flow = MutableStateFlow<List<ServerProfile>>(emptyList())
    private var nextId = 1L

    private fun emit() {
        flow.value = rows.values.sortedWith(
            compareByDescending<ServerProfile> { it.isActive }
                .thenByDescending { it.isPinned }
                .thenByDescending { it.lastConnectedAt }
                .thenByDescending { it.createdAt },
        )
    }

    override fun observeAllProfiles(): Flow<List<ServerProfile>> = flow

    override fun observeActiveProfile(): Flow<ServerProfile?> = flow.map { list -> list.firstOrNull { it.isActive } }

    override suspend fun count(): Int = rows.size

    override suspend fun getAllOnce(): List<ServerProfile> = rows.values.toList()

    override suspend fun getById(id: Long): ServerProfile? = rows[id]

    override suspend fun insert(profile: ServerProfile): Long {
        val id = if (profile.id == 0L) nextId++ else profile.id
        rows[id] = profile.copy(id = id)
        emit()
        return id
    }

    override suspend fun update(profile: ServerProfile) {
        if (profile.id > 0L) {
            rows[profile.id] = profile
            emit()
        }
    }

    override suspend fun deleteById(id: Long) {
        rows.remove(id)
        emit()
    }

    override suspend fun clearAllActive() {
        rows.keys.toList().forEach { key ->
            rows[key]?.let { rows[key] = it.copy(isActive = false) }
        }
        emit()
    }

    override suspend fun markActive(id: Long, timestamp: Long) {
        rows[id]?.let { rows[id] = it.copy(isActive = true, lastConnectedAt = timestamp) }
        emit()
    }

    override suspend fun updateProvisioningState(id: Long, state: WorkspaceProvisioningState, lastError: String) {
        rows[id]?.let { rows[id] = it.copy(provisioningState = state, lastError = lastError) }
        emit()
    }

    override suspend fun markCommittedReady(
        id: Long,
        url: String,
        slug: String,
        runtimeKind: LocalhostRuntimeKind,
        state: WorkspaceProvisioningState,
        timestamp: Long,
    ) {
        rows[id]?.let {
            rows[id] = it.copy(
                url = url,
                workspaceSlug = slug,
                runtimeKind = runtimeKind,
                provisioningState = state,
                lastError = "",
                isActive = true,
                lastConnectedAt = timestamp,
            )
        }
        emit()
    }

    override suspend fun markDraftFailedOrWaiting(id: Long, state: WorkspaceProvisioningState, lastError: String) {
        rows[id]?.let {
            rows[id] = it.copy(
                provisioningState = state,
                lastError = lastError,
                isActive = false,
            )
        }
        emit()
    }
}

private class FakeActiveSessionDao : ActiveSessionDao {
    private val flow = MutableStateFlow<ActiveSessionEntity?>(null)

    override fun observeActiveSession(): Flow<ActiveSessionEntity?> = flow

    override suspend fun getActiveSession(): ActiveSessionEntity? = flow.value

    override suspend fun upsert(session: ActiveSessionEntity) {
        flow.value = session
    }

    override suspend fun clear() {
        flow.value = null
    }
}
