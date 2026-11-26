package com.example

import android.app.Application
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.example.runtime.AiManagedBackendMode
import com.example.runtime.AiManagedRuntimeCoordinator
import com.example.runtime.RuntimeLifecycleState
import com.example.runtime.SharedWorkspaceFilesystem
import com.example.runtime.VsCodeRuntimeController
import com.example.ui.viewmodel.AppTab
import com.example.ui.viewmodel.MainViewModel
import com.example.workspace.InAppWebWorkspaceController
import com.example.workspace.LocalhostServerEngine
import com.example.workspace.WorkspaceKeyAction
import com.example.workspace.WorkspaceKeyboardShortcuts
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiRuntimeAndInputTest {

    @Test
    fun `single AI runtime verifies readiness reuses healthy instance preserves user files and cleans stale metadata`() = runBlocking {
        val tempDir = createTempDirectory("ai_rt_test").toFile()
        val sharedFs = SharedWorkspaceFilesystem(tempDir)
        val vsCodeRuntime = VsCodeRuntimeController(tempDir, nativeLibDirPath = null)
        val localhostEngine = LocalhostServerEngine(tempDir, sharedFs)
        val coordinator = AiManagedRuntimeCoordinator(
            appFilesDir = tempDir,
            sharedFilesystem = sharedFs,
            vsCodeRuntime = vsCodeRuntime,
            localhostEngine = localhostEngine,
        )

        // Pick a guaranteed free ephemeral port
        val freePort = ServerSocket(0).use { it.localPort }

        try {
            // Write fake stale metadata pointing to a dead port before initialization
            val metaDir = File(tempDir, "ai_runtime").apply { mkdirs() }
            val metaFile = File(metaDir, "runtime_metadata.json")
            metaFile.writeText(
                """{"workspaceSlug":"stale-ws","workspaceName":"Stale","port":$freePort,"url":"http://127.0.0.1:$freePort","backendMode":"FALLBACK_LOCAL_VIEWER","pid":999999}""",
            )
            val cleaned = coordinator.inspectAndCleanupStaleMetadata()
            assertEquals(AiManagedBackendMode.NONE, cleaned?.backendMode)
            assertEquals("", cleaned?.url)

            // Seed an existing user file to verify preservation
            val wsDir = sharedFs.resolveWorkspaceDir("my-ai-app")
            val userFile = File(wsDir, "index.html")
            userFile.writeText("<h1>User Owned</h1>")

            // First start: creates server, verifies HTTP readiness, persists metadata
            val first = coordinator.prepareAndStartWorkspace(
                workspaceName = "My AI App",
                workspaceSlug = "my-ai-app",
                requestedPort = freePort,
                starterFiles = mapOf(
                    "index.html" to "<h1>Should Not Clobber</h1>",
                    "app.js" to "console.log('ready');",
                ),
                setupCommand = "echo setup-ok",
                overwriteStarterFiles = false,
                preferGenuineCodeServer = false,
                allowFallbackViewer = true,
            )

            assertTrue(first.isRunning)
            assertTrue(first.isVerifiedHealthy)
            assertFalse(first.reusedExistingInstance)
            assertEquals(RuntimeLifecycleState.READY, first.state)
            assertEquals("<h1>User Owned</h1>", userFile.readText())
            assertTrue(File(wsDir, "app.js").exists())
            assertTrue(coordinator.isHealthy())
            assertTrue(metaFile.exists())

            val boundPort = first.port!!

            // Second call on same slug & app entry: MUST reuse healthy running server instead of spawning duplicate
            val entryReuse = coordinator.ensureRuntimeForAppEntry(
                preferredPort = boundPort,
                preferredSlug = "my-ai-app",
                allowAutoStart = true,
            )
            assertTrue(entryReuse.isRunning)
            assertTrue(entryReuse.reusedExistingInstance)
            assertEquals(boundPort, entryReuse.port)

            // Calling prepareAndStartWorkspace again on same slug also reuses healthy instance cleanly
            val secondSame = coordinator.prepareAndStartWorkspace(
                workspaceName = "My AI App",
                workspaceSlug = "my-ai-app",
                requestedPort = boundPort,
                overwriteStarterFiles = false,
            )
            assertTrue(secondSame.isRunning)
            assertTrue(secondSame.reusedExistingInstance)
            assertEquals(boundPort, secondSame.port)

            // Stop runtime cleans state & metadata
            val stopped = coordinator.stopRuntime()
            assertFalse(stopped.isRunning)
            assertEquals(RuntimeLifecycleState.STOPPED, stopped.state)
            assertFalse(coordinator.isHealthy())
        } finally {
            coordinator.destroy()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `main view model tracks tab back stack without duplicates and falls back to profiles`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        vm.selectTab(AppTab.PROFILES, pushHistory = false)
        assertEquals(AppTab.PROFILES, vm.currentTab.value)
        assertFalse(vm.navigateBack())

        vm.selectTab(AppTab.AI_CHAT)
        vm.selectTab(AppTab.AI_CHAT) // consecutive duplicate ignored
        vm.selectTab(AppTab.SETTINGS)
        assertEquals(AppTab.SETTINGS, vm.currentTab.value)

        assertTrue(vm.navigateBackOrProfiles())
        assertEquals(AppTab.AI_CHAT, vm.currentTab.value)

        assertTrue(vm.navigateBackOrProfiles())
        assertEquals(AppTab.PROFILES, vm.currentTab.value)

        assertFalse(vm.navigateBackOrProfiles())
    }

    @Test
    fun `scoped hardware keyboard zoom shortcuts respect foreground focus AltGr Bengali typing and text fields`() {
        // Active, unobstructed workspace WebView accepts Ctrl++, Ctrl+=, Ctrl+-, Ctrl+0
        assertEquals(
            WorkspaceKeyAction.ZOOM_IN,
            WorkspaceKeyboardShortcuts.resolveScopedWebViewKeyAction(
                keyCode = KeyEvent.KEYCODE_EQUALS,
                isKeyDown = true,
                repeatCount = 0,
                isCtrlPressed = true,
                isAltPressed = false,
                isMetaPressed = false,
                isWorkspaceForegroundFocused = true,
                isComposeTextFieldFocused = false,
            ),
        )
        assertEquals(
            WorkspaceKeyAction.ZOOM_IN,
            WorkspaceKeyboardShortcuts.resolveScopedWebViewKeyAction(
                keyCode = KeyEvent.KEYCODE_PLUS,
                isKeyDown = true,
                repeatCount = 0,
                isCtrlPressed = true,
                isAltPressed = false,
                isMetaPressed = false,
                isWorkspaceForegroundFocused = true,
                isComposeTextFieldFocused = false,
            ),
        )
        assertEquals(
            WorkspaceKeyAction.ZOOM_OUT,
            WorkspaceKeyboardShortcuts.resolveScopedWebViewKeyAction(
                keyCode = KeyEvent.KEYCODE_MINUS,
                isKeyDown = true,
                repeatCount = 0,
                isCtrlPressed = true,
                isAltPressed = false,
                isMetaPressed = false,
                isWorkspaceForegroundFocused = true,
                isComposeTextFieldFocused = false,
            ),
        )
        assertEquals(
            WorkspaceKeyAction.ZOOM_RESET,
            WorkspaceKeyboardShortcuts.resolveScopedWebViewKeyAction(
                keyCode = KeyEvent.KEYCODE_0,
                isKeyDown = true,
                repeatCount = 0,
                isCtrlPressed = true,
                isAltPressed = false,
                isMetaPressed = false,
                isWorkspaceForegroundFocused = true,
                isComposeTextFieldFocused = false,
            ),
        )

        // AltGr (Ctrl+Alt) must NEVER be intercepted so Bengali/international IME layouts work
        assertEquals(
            WorkspaceKeyAction.NONE,
            WorkspaceKeyboardShortcuts.resolveScopedWebViewKeyAction(
                keyCode = KeyEvent.KEYCODE_EQUALS,
                isKeyDown = true,
                repeatCount = 0,
                isCtrlPressed = true,
                isAltPressed = true,
                isMetaPressed = false,
                isWorkspaceForegroundFocused = true,
                isComposeTextFieldFocused = false,
            ),
        )

        // Compose text field focus blocks WebView zoom shortcut
        assertEquals(
            WorkspaceKeyAction.NONE,
            WorkspaceKeyboardShortcuts.resolveScopedWebViewKeyAction(
                keyCode = KeyEvent.KEYCODE_EQUALS,
                isKeyDown = true,
                repeatCount = 0,
                isCtrlPressed = true,
                isAltPressed = false,
                isMetaPressed = false,
                isWorkspaceForegroundFocused = true,
                isComposeTextFieldFocused = true,
            ),
        )

        // Inactive / background workspace tab blocks zoom shortcut
        assertEquals(
            WorkspaceKeyAction.NONE,
            WorkspaceKeyboardShortcuts.resolveScopedWebViewKeyAction(
                keyCode = KeyEvent.KEYCODE_MINUS,
                isKeyDown = true,
                repeatCount = 0,
                isCtrlPressed = true,
                isAltPressed = false,
                isMetaPressed = false,
                isWorkspaceForegroundFocused = false,
                isComposeTextFieldFocused = false,
            ),
        )

        // Standard editing & navigation keys pass untouched
        for (key in listOf(
            KeyEvent.KEYCODE_C,
            KeyEvent.KEYCODE_V,
            KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DEL,
        )) {
            assertEquals(
                WorkspaceKeyAction.NONE,
                WorkspaceKeyboardShortcuts.resolveScopedWebViewKeyAction(
                    keyCode = key,
                    isKeyDown = true,
                    repeatCount = 0,
                    isCtrlPressed = true,
                    isAltPressed = false,
                    isMetaPressed = false,
                    isWorkspaceForegroundFocused = true,
                    isComposeTextFieldFocused = false,
                ),
            )
        }

        // Clamping & step checks
        assertEquals(110, WorkspaceKeyboardShortcuts.nextZoomInPercent(100))
        assertEquals(90, WorkspaceKeyboardShortcuts.nextZoomOutPercent(100))
        assertEquals(250, WorkspaceKeyboardShortcuts.nextZoomInPercent(250))
        assertEquals(50, WorkspaceKeyboardShortcuts.nextZoomOutPercent(50))

        // Enter submits chat while Shift+Enter inserts newline
        assertTrue(
            WorkspaceKeyboardShortcuts.shouldSubmitOnEnterKey(
                keyCode = KeyEvent.KEYCODE_ENTER,
                isKeyDown = true,
                repeatCount = 0,
                isShiftPressed = false,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
            ),
        )
        assertFalse(
            WorkspaceKeyboardShortcuts.shouldSubmitOnEnterKey(
                keyCode = KeyEvent.KEYCODE_ENTER,
                isKeyDown = true,
                repeatCount = 0,
                isShiftPressed = true,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
            ),
        )
    }

    @Test
    fun `web controller updates scoped zoom bounds and interaction gate safely`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val controller = InAppWebWorkspaceController(app)
        try {
            assertEquals(100, controller.state.value.zoomPercent)
            controller.updateInteractionGate(isWorkspaceInteractive = true, composeTextFieldFocused = false)
            assertTrue(
                controller.handleScopedHardwareKey(
                    keyCode = KeyEvent.KEYCODE_EQUALS,
                    isKeyDown = true,
                    repeatCount = 0,
                    isCtrlPressed = true,
                    isAltPressed = false,
                    isMetaPressed = false,
                ),
            )
            assertEquals(110, controller.state.value.zoomPercent)

            assertTrue(
                controller.handleScopedHardwareKey(
                    keyCode = KeyEvent.KEYCODE_MINUS,
                    isKeyDown = true,
                    repeatCount = 0,
                    isCtrlPressed = true,
                    isAltPressed = false,
                    isMetaPressed = false,
                ),
            )
            assertEquals(100, controller.state.value.zoomPercent)

            controller.setZoomPercent(260)
            assertEquals(250, controller.state.value.zoomPercent)
            controller.resetZoom()
            assertEquals(100, controller.state.value.zoomPercent)

            controller.updateInteractionGate(isWorkspaceInteractive = false, composeTextFieldFocused = false)
            assertFalse(
                controller.handleScopedHardwareKey(
                    keyCode = KeyEvent.KEYCODE_PLUS,
                    isKeyDown = true,
                    repeatCount = 0,
                    isCtrlPressed = true,
                    isAltPressed = false,
                    isMetaPressed = false,
                ),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `AI runtime switches instead of reusing mismatched workspace slug on app entry`() = runBlocking {
        val tempDir = createTempDirectory("ai_rt_slug_switch").toFile()
        val sharedFs = SharedWorkspaceFilesystem(tempDir)
        val vsCodeRuntime = VsCodeRuntimeController(tempDir, nativeLibDirPath = null)
        val localhostEngine = LocalhostServerEngine(tempDir, sharedFs)
        val coordinator = AiManagedRuntimeCoordinator(
            appFilesDir = tempDir,
            sharedFilesystem = sharedFs,
            vsCodeRuntime = vsCodeRuntime,
            localhostEngine = localhostEngine,
        )
        val freePort = ServerSocket(0).use { it.localPort }

        try {
            val alpha = coordinator.prepareAndStartWorkspace(
                workspaceName = "Alpha",
                workspaceSlug = "ws-alpha",
                requestedPort = freePort,
                allowFallbackViewer = true,
            )
            assertTrue(alpha.isRunning)
            assertEquals("ws-alpha", alpha.workspaceSlug)

            val beta = coordinator.ensureRuntimeForAppEntry(
                preferredPort = alpha.port!!,
                preferredSlug = "ws-beta",
                workspaceName = "Beta",
                allowAutoStart = true,
            )
            assertTrue(beta.isRunning)
            assertEquals("ws-beta", beta.workspaceSlug)
            assertFalse("Should not report reusedExistingInstance for different slug", beta.reusedExistingInstance)
        } finally {
            coordinator.destroy()
            tempDir.deleteRecursively()
        }
    }
}
