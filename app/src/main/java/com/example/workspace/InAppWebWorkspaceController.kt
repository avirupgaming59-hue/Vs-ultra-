package com.example.workspace

import android.app.Activity
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.security.SecureVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.URI

private const val TAG = "InAppWebWorkspace"
private const val MAX_CLIPBOARD_CHARS = 96_000
private const val BLANK_SCREEN_WATCHDOG_MS = 4_500L

enum class ConnectionStatus {
    IDLE,
    LOADING,
    CONNECTED,
    ERROR,
}

enum class JsDialogKind {
    ALERT,
    CONFIRM,
    PROMPT,
}

data class PendingJsDialog(
    val id: Long,
    val kind: JsDialogKind,
    val message: String,
    val defaultValue: String = "",
)

data class DomReadinessEvaluation(
    val isReady: Boolean,
    val isBlank: Boolean,
    val detail: String,
)

data class WebViewSessionState(
    val activeProfileId: Long = 0L,
    val currentUrl: String = "",
    val pageTitle: String = "",
    val status: ConnectionStatus = ConnectionStatus.IDLE,
    val progress: Int = 0,
    val errorMessage: String? = null,
    val httpErrorCode: Int? = null,
    val rendererCrashCount: Int = 0,
    val isCodespacesSession: Boolean = false,
    val isDomVerified: Boolean = false,
    val isBlankScreenDetected: Boolean = false,
    val ideReadinessDetail: String = "",
    val consoleErrors: List<String> = emptyList(),
    val canGoBack: Boolean = false,
    val isDesktopMode: Boolean = false,
    val hasPopupWindow: Boolean = false,
    val popupSessionId: Long = 0L,
    val popupUrl: String = "",
    val sslWarningHost: String? = null,
    val pendingChromePermissionUrl: String? = null,
    val pendingJsDialog: PendingJsDialog? = null,
    val zoomPercent: Int = WorkspaceKeyboardShortcuts.DEFAULT_ZOOM_PERCENT,
    val isWorkspaceInteractive: Boolean = true,
    val webViewSessionSeq: Long = 1L,
)

/**
 * Controls the persistent in-app WebView workspace and in-app OAuth popup WebView.
 * Never opens Chrome or an external browser automatically; external browser handoff always
 * stages [WebViewSessionState.pendingChromePermissionUrl] for explicit user approval.
 * Hardened against rapid navigation, popup lifecycle churn, invalid file MIME types,
 * and WindowManager BadTokenException on JS dialogs.
 */
class InAppWebWorkspaceController(private val appContext: Context) {

    private val webContextWrapper = MutableContextWrapper(appContext.applicationContext)
    private val _state = MutableStateFlow(WebViewSessionState())
    val state: StateFlow<WebViewSessionState> = _state.asStateFlow()

    private data class PendingLoadRequest(
        val profileId: Long,
        val url: String,
        val desktopMode: Boolean,
        val autoLoginPassword: String,
        val forceReload: Boolean,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val zoomPrefs = appContext.getSharedPreferences("vsshell_webview_zoom", Context.MODE_PRIVATE)
    private var mainWebView: WorkspaceWebView? = null
    private var popupWebView: WebView? = null
    private var pendingLoadRequest: PendingLoadRequest? = null
    private var pendingSslHandler: SslErrorHandler? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingJsResult: JsResult? = null
    private var pendingJsPromptResult: JsPromptResult? = null
    private var lastAutoFilledUrl: String = ""
    private var pendingAutoLoginPassword: String = ""
    private var pendingAutoLoginHost: String = ""
    private var nextPopupSeq: Long = 1L
    private var nextJsDialogSeq: Long = 1L
    private var appliedZoomPercent: Int = WorkspaceKeyboardShortcuts.DEFAULT_ZOOM_PERCENT

    @Volatile
    private var isWorkspaceVisible: Boolean = true

    @Volatile
    private var isWorkspaceForegroundInteractive: Boolean = true

    @Volatile
    private var isComposeTextFieldFocused: Boolean = false

    @Volatile
    private var currentNavigationHadError: Boolean = false

    @Volatile
    private var isDestroyed: Boolean = false

    private var onRequestFilePicker: ((mimeTypes: Array<String>, allowMultiple: Boolean) -> Unit)? = null
    private var onDownloadSavedCallback: ((String) -> Unit)? = null
    private var onConsoleErrorCallback: ((String) -> Unit)? = null
    private var blankScreenWatchdogRunnable: Runnable? = null

    fun setCallbacks(
        requestFilePicker: (mimeTypes: Array<String>, allowMultiple: Boolean) -> Unit,
        onDownloadSaved: (String) -> Unit,
        onConsoleError: ((String) -> Unit)? = null,
    ) {
        onRequestFilePicker = requestFilePicker
        onDownloadSavedCallback = onDownloadSaved
        onConsoleErrorCallback = onConsoleError
    }

    fun updateHostContext(context: Context) {
        val safeContext = if (context is Activity && (context.isDestroyed || context.isFinishing)) {
            appContext.applicationContext
        } else {
            context
        }
        webContextWrapper.baseContext = safeContext
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    fun getOrCreateMainView(context: Context, backgroundColorArgb: Int): View {
        return getOrCreateMainWebView(context, backgroundColorArgb) ?: View(context).apply {
            setBackgroundColor(backgroundColorArgb)
        }
    }

    fun getOrCreateMainWebView(context: Context, backgroundColorArgb: Int): WebView? {
        updateHostContext(context)
        isDestroyed = false
        val existing = mainWebView
        if (existing != null) {
            runCatching { existing.setBackgroundColor(backgroundColorArgb) }
            val pending = pendingLoadRequest
            if (pending != null) {
                pendingLoadRequest = null
                existing.post {
                    if (!isDestroyed) {
                        loadProfileUrl(
                            profileId = pending.profileId,
                            rawUrl = pending.url,
                            desktopMode = pending.desktopMode,
                            autoLoginPassword = pending.autoLoginPassword,
                            forceReload = pending.forceReload,
                        )
                    }
                }
            }
            return existing
        }

        val created = try {
            WorkspaceWebView(webContextWrapper).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(backgroundColorArgb)
                configureWebSettings(this, _state.value.isDesktopMode)
                webViewClient = createMainWebViewClient()
                webChromeClient = createMainWebChromeClient()
                visibility = if (isWorkspaceVisible) View.VISIBLE else View.INVISIBLE
                isFocusable = isWorkspaceForegroundInteractive
                isFocusableInTouchMode = isWorkspaceForegroundInteractive
                onScopedKeyShortcut = { keyEvent ->
                    handleScopedHardwareKey(
                        keyCode = keyEvent.keyCode,
                        isKeyDown = keyEvent.action == android.view.KeyEvent.ACTION_DOWN,
                        repeatCount = keyEvent.repeatCount,
                        isCtrlPressed = keyEvent.isCtrlPressed,
                        isAltPressed = keyEvent.isAltPressed,
                        isMetaPressed = keyEvent.isMetaPressed,
                    )
                }
                onWheelZoom = { zoomIn ->
                    if (!isWorkspaceForegroundInteractive || isComposeTextFieldFocused) {
                        false
                    } else if (zoomIn) {
                        zoomIn()
                    } else {
                        zoomOut()
                    }
                }
                setDownloadListener { url, _, _, _, _ ->
                    onDownloadSavedCallback?.invoke("Download link ready: ${SecureVault.sanitizeUrlForLogs(url)}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create WebView safely", t)
            _state.update {
                it.copy(
                    status = ConnectionStatus.ERROR,
                    errorMessage = t.localizedMessage ?: "Android WebView unavailable on this device",
                )
            }
            return null
        }
        appliedZoomPercent = WorkspaceKeyboardShortcuts.DEFAULT_ZOOM_PERCENT
        mainWebView = created
        val pending = pendingLoadRequest
        if (pending != null) {
            pendingLoadRequest = null
            created.post {
                if (!isDestroyed) {
                    loadProfileUrl(
                        profileId = pending.profileId,
                        rawUrl = pending.url,
                        desktopMode = pending.desktopMode,
                        autoLoginPassword = pending.autoLoginPassword,
                        forceReload = pending.forceReload,
                    )
                }
            }
        }
        return created
    }

    fun getPopupWebView(): WebView? = popupWebView

    fun updateInteractionGate(
        isWorkspaceInteractive: Boolean,
        composeTextFieldFocused: Boolean = this.isComposeTextFieldFocused,
    ) {
        this.isWorkspaceForegroundInteractive = isWorkspaceInteractive
        this.isComposeTextFieldFocused = composeTextFieldFocused
        _state.update { it.copy(isWorkspaceInteractive = isWorkspaceInteractive) }
    }

    fun setWorkspaceVisibilityAndFocus(isVisible: Boolean, isInteractive: Boolean) {
        isWorkspaceVisible = isVisible
        isWorkspaceForegroundInteractive = isInteractive
        _state.update { it.copy(isWorkspaceInteractive = isInteractive) }
        runOnMainThread {
            if (isDestroyed) return@runOnMainThread
            val wv = mainWebView ?: return@runOnMainThread
            wv.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
            wv.isFocusable = isInteractive
            wv.isFocusableInTouchMode = isInteractive
            if (!isInteractive && wv.hasFocus()) {
                wv.clearFocus()
            }
        }
    }

    fun handleScopedHardwareKey(
        keyCode: Int,
        isKeyDown: Boolean,
        repeatCount: Int,
        isCtrlPressed: Boolean,
        isAltPressed: Boolean,
        isMetaPressed: Boolean,
    ): Boolean {
        return when (
            WorkspaceKeyboardShortcuts.resolveScopedWebViewKeyAction(
                keyCode = keyCode,
                isKeyDown = isKeyDown,
                repeatCount = repeatCount,
                isCtrlPressed = isCtrlPressed,
                isAltPressed = isAltPressed,
                isMetaPressed = isMetaPressed,
                isWorkspaceForegroundFocused = isWorkspaceForegroundInteractive && isWorkspaceVisible,
                isComposeTextFieldFocused = isComposeTextFieldFocused,
            )
        ) {
            WorkspaceKeyAction.ZOOM_IN -> zoomIn()
            WorkspaceKeyAction.ZOOM_OUT -> zoomOut()
            WorkspaceKeyAction.ZOOM_RESET -> resetZoom()
            WorkspaceKeyAction.NONE -> false
        }
    }

    fun zoomIn(): Boolean =
        setZoomPercent(WorkspaceKeyboardShortcuts.nextZoomInPercent(_state.value.zoomPercent))

    fun zoomOut(): Boolean =
        setZoomPercent(WorkspaceKeyboardShortcuts.nextZoomOutPercent(_state.value.zoomPercent))

    fun resetZoom(): Boolean =
        setZoomPercent(WorkspaceKeyboardShortcuts.DEFAULT_ZOOM_PERCENT)

    fun setZoomPercent(targetPercent: Int): Boolean {
        if (isDestroyed) return false
        val clamped = WorkspaceKeyboardShortcuts.clampZoomPercent(targetPercent)
        val profileId = _state.value.activeProfileId
        saveZoomPercent(profileId, clamped)
        _state.update { it.copy(zoomPercent = clamped) }
        runOnMainThread {
            if (isDestroyed) return@runOnMainThread
            val wv = mainWebView ?: return@runOnMainThread
            applyZoomToWebViewLocked(wv, clamped)
        }
        return true
    }

    private fun applyZoomToWebViewLocked(wv: WebView, targetPercent: Int) {
        if (appliedZoomPercent == targetPercent) return
        val factor = (targetPercent.toFloat() / appliedZoomPercent.coerceAtLeast(10).toFloat())
            .coerceIn(0.25f, 4.0f)
        val zoomed = runCatching {
            wv.zoomBy(factor)
            true
        }.getOrDefault(false)
        if (zoomed) {
            appliedZoomPercent = targetPercent
        }
    }

    private fun getSavedZoomPercent(profileId: Long): Int {
        if (profileId <= 0L) return WorkspaceKeyboardShortcuts.DEFAULT_ZOOM_PERCENT
        val raw = zoomPrefs.getInt("zoom_profile_$profileId", WorkspaceKeyboardShortcuts.DEFAULT_ZOOM_PERCENT)
        return WorkspaceKeyboardShortcuts.clampZoomPercent(raw)
    }

    private fun saveZoomPercent(profileId: Long, percent: Int) {
        if (profileId <= 0L) return
        runCatching {
            zoomPrefs.edit().putInt("zoom_profile_$profileId", percent).apply()
        }
    }

    fun loadProfileUrl(
        profileId: Long,
        rawUrl: String,
        desktopMode: Boolean,
        autoLoginPassword: String = "",
        forceReload: Boolean = false,
    ) {
        if (isDestroyed) return
        val normalized = ChromeCustomTabsManager.normalizeUrl(rawUrl)
        if (normalized.isBlank()) {
            reportConnectionError("Invalid or unsupported server URL: ${SecureVault.sanitizeUrlForLogs(rawUrl)}")
            return
        }

        runOnMainThread {
            if (isDestroyed) return@runOnMainThread
            pendingAutoLoginPassword = autoLoginPassword
            pendingAutoLoginHost = extractHost(normalized)
            if (forceReload) {
                lastAutoFilledUrl = ""
            }

            // Wait until Activity context is attached before constructing WebView for the first time
            if (mainWebView == null && webContextWrapper.baseContext !is Activity) {
                pendingLoadRequest = PendingLoadRequest(
                    profileId = profileId,
                    url = normalized,
                    desktopMode = desktopMode,
                    autoLoginPassword = autoLoginPassword,
                    forceReload = forceReload,
                )
                _state.update {
                    it.copy(
                        activeProfileId = profileId,
                        currentUrl = normalized,
                        isDesktopMode = desktopMode,
                        errorMessage = null,
                        status = ConnectionStatus.LOADING,
                        progress = 10,
                    )
                }
                return@runOnMainThread
            }

            val wv = getOrCreateMainWebView(webContextWrapper, 0xFF1E1E1E.toInt()) ?: return@runOnMainThread
            if (_state.value.isDesktopMode != desktopMode) {
                runCatching { applyUserAgent(wv, desktopMode) }
            }

            val isLoopback = isLoopbackHost(extractHost(normalized))
            runCatching {
                wv.settings.cacheMode = if (isLoopback) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
                if (isLoopback && forceReload) {
                    wv.clearCache(true)
                }
            }

            val sameTarget = _state.value.activeProfileId == profileId &&
                _state.value.currentUrl == normalized &&
                !forceReload &&
                _state.value.status == ConnectionStatus.CONNECTED

            if (!sameTarget) {
                currentNavigationHadError = false
                cancelBlankScreenWatchdog()
            }

            val targetZoom = getSavedZoomPercent(profileId)
            _state.update {
                it.copy(
                    activeProfileId = profileId,
                    currentUrl = normalized,
                    isDesktopMode = desktopMode,
                    zoomPercent = targetZoom,
                    errorMessage = null,
                    httpErrorCode = null,
                    isCodespacesSession = isCodespacesUrl(normalized),
                    isDomVerified = if (sameTarget) it.isDomVerified else false,
                    isBlankScreenDetected = false,
                    ideReadinessDetail = if (sameTarget) it.ideReadinessDetail else "Loading page...",
                    consoleErrors = if (sameTarget) it.consoleErrors else emptyList(),
                    status = if (sameTarget) it.status else ConnectionStatus.LOADING,
                    progress = if (sameTarget) 100 else 10,
                )
            }

            if (!sameTarget) {
                appliedZoomPercent = WorkspaceKeyboardShortcuts.DEFAULT_ZOOM_PERCENT
                runCatching { wv.loadUrl(normalized) }
                    .onFailure { err ->
                        Log.w(TAG, "WebView loadUrl failed: ${err.message}")
                        currentNavigationHadError = true
                        _state.update {
                            it.copy(
                                status = ConnectionStatus.ERROR,
                                errorMessage = err.localizedMessage ?: "Failed to load URL",
                            )
                        }
                    }
            }
        }
    }

    fun recreateMainWebView() {
        if (isDestroyed) return
        runOnMainThread {
            if (isDestroyed) return@runOnMainThread
            cancelBlankScreenWatchdog()
            currentNavigationHadError = false
            val old = mainWebView
            mainWebView = null
            destroyWebViewSafely(old)
            val snap = _state.value
            _state.update {
                it.copy(
                    status = if (snap.currentUrl.isNotBlank()) ConnectionStatus.LOADING else ConnectionStatus.IDLE,
                    errorMessage = null,
                    isDomVerified = false,
                    isBlankScreenDetected = false,
                    consoleErrors = emptyList(),
                    progress = if (snap.currentUrl.isNotBlank()) 10 else 0,
                    webViewSessionSeq = it.webViewSessionSeq + 1L,
                )
            }
            if (snap.currentUrl.isNotBlank()) {
                loadProfileUrl(
                    profileId = snap.activeProfileId,
                    rawUrl = snap.currentUrl,
                    desktopMode = snap.isDesktopMode,
                    autoLoginPassword = pendingAutoLoginPassword,
                    forceReload = true,
                )
            }
        }
    }

    fun reportConnectionError(message: String) {
        if (isDestroyed) return
        cancelBlankScreenWatchdog()
        val cleanMsg = message.trim().ifBlank { "Unable to connect to workspace" }
        currentNavigationHadError = true
        _state.update {
            it.copy(
                status = ConnectionStatus.ERROR,
                errorMessage = cleanMsg,
                isDomVerified = false,
                progress = 0,
            )
        }
    }

    fun reload() {
        if (isDestroyed) return
        runOnMainThread {
            if (isDestroyed) return@runOnMainThread
            lastAutoFilledUrl = ""
            currentNavigationHadError = false
            val wv = mainWebView
            val snap = _state.value
            val isLoopback = isLoopbackHost(extractHost(snap.currentUrl))
            if (wv != null) {
                val loadedUrl = runCatching { wv.url.orEmpty() }.getOrDefault("")
                if (isLoopback) {
                    runCatching {
                        wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        wv.clearCache(true)
                    }
                }
                val needsExplicitUrlLoad = loadedUrl.isBlank() ||
                    loadedUrl.equals("about:blank", ignoreCase = true) ||
                    loadedUrl.startsWith("chrome-error://", ignoreCase = true) ||
                    loadedUrl.startsWith("data:", ignoreCase = true)
                if (needsExplicitUrlLoad && snap.currentUrl.isNotBlank()) {
                    _state.update { it.copy(status = ConnectionStatus.LOADING, errorMessage = null, progress = 15) }
                    runCatching { wv.loadUrl(snap.currentUrl) }
                        .onFailure { err ->
                            currentNavigationHadError = true
                            _state.update {
                                it.copy(
                                    status = ConnectionStatus.ERROR,
                                    errorMessage = err.localizedMessage ?: "Failed to reload URL",
                                )
                            }
                        }
                } else {
                    _state.update { it.copy(status = ConnectionStatus.LOADING, errorMessage = null, progress = 15) }
                    runCatching { wv.reload() }
                }
            } else if (snap.currentUrl.isNotBlank()) {
                loadProfileUrl(
                    profileId = snap.activeProfileId,
                    rawUrl = snap.currentUrl,
                    desktopMode = snap.isDesktopMode,
                    autoLoginPassword = pendingAutoLoginPassword,
                    forceReload = true,
                )
            }
        }
    }

    fun goBack() {
        if (isDestroyed) return
        mainWebView?.let { wv ->
            runCatching {
                if (wv.canGoBack()) {
                    wv.goBack()
                }
            }
        }
    }

    fun setDesktopMode(enabled: Boolean) {
        if (isDestroyed) return
        _state.update { it.copy(isDesktopMode = enabled) }
        mainWebView?.let { wv ->
            runCatching {
                applyUserAgent(wv, enabled)
                wv.reload()
            }
        }
    }

    fun openInAppAuthPopup(authUrl: String) {
        if (isDestroyed) return
        val normalized = ChromeCustomTabsManager.normalizeUrl(authUrl)
        if (normalized.isBlank()) return

        val oldPopup = popupWebView
        popupWebView = null

        val popup = try {
            WebView(webContextWrapper).apply {
                configureWebSettings(this, _state.value.isDesktopMode)
                webViewClient = createPopupWebViewClient()
                webChromeClient = createPopupWebChromeClient()
                loadUrl(normalized)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create popup WebView safely", t)
            return
        }
        popupWebView = popup
        val sessionId = nextPopupSeq++
        _state.update {
            it.copy(
                hasPopupWindow = true,
                popupSessionId = sessionId,
                popupUrl = normalized,
            )
        }
        destroyWebViewSafely(oldPopup)
    }

    fun closePopupWindow() {
        val oldPopup = popupWebView
        popupWebView = null
        runCatching { CookieManager.getInstance().flush() }
        _state.update {
            it.copy(
                hasPopupWindow = false,
                popupUrl = "",
            )
        }
        destroyWebViewSafely(oldPopup)
    }

    fun requestChromePermission(rawUrl: String) {
        val normalized = ChromeCustomTabsManager.normalizeUrl(rawUrl)
        if (!normalized.startsWith("http://", ignoreCase = true) &&
            !normalized.startsWith("https://", ignoreCase = true)
        ) {
            return
        }
        _state.update { it.copy(pendingChromePermissionUrl = normalized) }
    }

    fun clearChromePermissionRequest() {
        _state.update { it.copy(pendingChromePermissionUrl = null) }
    }

    fun proceedWithSslWarning() {
        runCatching { pendingSslHandler?.proceed() }
        pendingSslHandler = null
        _state.update { it.copy(sslWarningHost = null) }
    }

    fun cancelSslWarning() {
        runCatching { pendingSslHandler?.cancel() }
        pendingSslHandler = null
        _state.update {
            it.copy(
                sslWarningHost = null,
                status = ConnectionStatus.ERROR,
                errorMessage = "SSL certificate rejected by user",
            )
        }
    }

    fun confirmJsDialog(inputValue: String? = null) {
        val dialog = _state.value.pendingJsDialog
        val jsResult = pendingJsResult
        val promptResult = pendingJsPromptResult
        pendingJsResult = null
        pendingJsPromptResult = null
        _state.update { it.copy(pendingJsDialog = null) }

        runCatching {
            when (dialog?.kind) {
                JsDialogKind.PROMPT -> promptResult?.confirm(inputValue.orEmpty())
                JsDialogKind.ALERT, JsDialogKind.CONFIRM -> jsResult?.confirm()
                null -> Unit
            }
        }
    }

    fun cancelJsDialog() {
        val jsResult = pendingJsResult
        val promptResult = pendingJsPromptResult
        pendingJsResult = null
        pendingJsPromptResult = null
        _state.update { it.copy(pendingJsDialog = null) }
        runCatching { promptResult?.cancel() }
        runCatching { jsResult?.cancel() }
    }

    fun deliverFilePickerResult(uris: Array<Uri>?) {
        val cb = filePathCallback
        filePathCallback = null
        runCatching { cb?.onReceiveValue(uris) }
    }

    fun autofillPassword(password: String) {
        if (isDestroyed || password.isBlank()) return
        val wv = mainWebView ?: return
        val quoted = JSONObject.quote(password)
        val js = """
            (function() {
              var pwd = $quoted;
              var inputs = document.querySelectorAll('input[type="password"], input[name="password"]');
              if (!inputs || inputs.length === 0) return false;
              inputs.forEach(function(el) {
                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                if (setter) setter.call(el, pwd); else el.value = pwd;
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
              });
              var form = inputs[0].closest('form');
              var btn = form ? form.querySelector('button[type="submit"], input[type="submit"]') : null;
              if (btn) btn.click();
              return true;
            })();
        """.trimIndent()
        runCatching { wv.evaluateJavascript(js, null) }
    }

    fun dispatchDevKey(key: String, ctrl: Boolean, alt: Boolean, shift: Boolean) {
        if (isDestroyed) return
        val wv = mainWebView ?: return
        val quotedKey = JSONObject.quote(key)
        val js = """
            (function() {
              var target = document.activeElement || document.body;
              if (!target) return;
              ['keydown', 'keyup'].forEach(function(type) {
                target.dispatchEvent(new KeyboardEvent(type, {
                  key: $quotedKey,
                  ctrlKey: $ctrl,
                  altKey: $alt,
                  shiftKey: $shift,
                  bubbles: true,
                  cancelable: true
                }));
              });
            })();
        """.trimIndent()
        runCatching { wv.evaluateJavascript(js, null) }
    }

    fun sendPhoneFileToWorkspace(
        fileName: String,
        textPreview: String?,
        onDone: (String) -> Unit,
    ) {
        if (textPreview != null) {
            val safeText = if (textPreview.length > MAX_CLIPBOARD_CHARS) {
                textPreview.take(MAX_CLIPBOARD_CHARS)
            } else {
                textPreview
            }
            val copied = runCatching {
                val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(fileName, safeText))
                true
            }.getOrDefault(false)
            if (copied) {
                val truncatedNote = if (textPreview.length > MAX_CLIPBOARD_CHARS) " (first 96K chars)" else ""
                onDone("Copied $fileName$truncatedNote to clipboard — paste inside workspace")
            } else {
                onDone("$fileName ready — use upload picker inside the workspace")
            }
        } else {
            onDone("$fileName ready — use upload picker inside the workspace")
        }
    }

    fun clearAllCookiesAndCache() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            mainWebView?.clearCache(true)
            mainWebView?.clearHistory()
        }
    }

    fun onResume() {
        if (isDestroyed) return
        runCatching {
            mainWebView?.onResume()
            popupWebView?.onResume()
        }
    }

    fun onPause() {
        if (isDestroyed) return
        runCatching {
            CookieManager.getInstance().flush()
            mainWebView?.onPause()
            popupWebView?.onPause()
        }
    }

    fun destroy() {
        isDestroyed = true
        cancelBlankScreenWatchdog()
        deliverFilePickerResult(null)
        cancelJsDialog()
        runCatching { pendingSslHandler?.cancel() }
        pendingSslHandler = null
        val oldPopup = popupWebView
        popupWebView = null
        val oldMain = mainWebView
        mainWebView = null
        destroyWebViewSafely(oldPopup)
        destroyWebViewSafely(oldMain)
    }

    private fun cancelBlankScreenWatchdog() {
        blankScreenWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        blankScreenWatchdogRunnable = null
    }

    private fun scheduleBlankScreenWatchdog(view: WebView?, url: String) {
        cancelBlankScreenWatchdog()
        val wv = view ?: return
        val stage1 = Runnable {
            if (isDestroyed || mainWebView !== wv || currentNavigationHadError) return@Runnable
            if (!_state.value.isDomVerified) {
                inspectWebViewDomReadiness(wv, url, isWatchdogCheck = false)
            }
        }
        val stage2 = Runnable {
            if (isDestroyed || mainWebView !== wv || currentNavigationHadError) return@Runnable
            if (!_state.value.isDomVerified) {
                inspectWebViewDomReadiness(wv, url, isWatchdogCheck = false)
            }
        }
        val finalWatchdog = Runnable {
            if (isDestroyed || mainWebView !== wv || currentNavigationHadError) return@Runnable
            inspectWebViewDomReadiness(wv, url, isWatchdogCheck = true)
        }
        blankScreenWatchdogRunnable = finalWatchdog
        mainHandler.postDelayed(stage1, 650L)
        mainHandler.postDelayed(stage2, 1_800L)
        mainHandler.postDelayed(finalWatchdog, BLANK_SCREEN_WATCHDOG_MS)
    }

    private fun inspectWebViewDomReadiness(
        view: WebView?,
        url: String,
        isWatchdogCheck: Boolean,
    ) {
        if (view == null || isDestroyed) return
        val script = """
            (function() {
              try {
                var body = document.body;
                var textLen = body && body.innerText ? body.innerText.trim().length : 0;
                var children = body ? body.children.length : 0;
                var height = body ? (body.scrollHeight || body.clientHeight || 0) : 0;
                var mediaCount = document.querySelectorAll('canvas, svg, img, video, iframe').length;
                var hasWorkbench = !!document.querySelector('.monaco-workbench, #editor, textarea#editor');
                return JSON.stringify({
                  readyState: document.readyState || '',
                  href: location.href || '',
                  textLen: textLen,
                  children: children,
                  height: height,
                  mediaCount: mediaCount,
                  hasWorkbench: hasWorkbench
                });
              } catch (e) {
                return JSON.stringify({ error: String(e) });
              }
            })();
        """.trimIndent()

        runCatching {
            view.evaluateJavascript(script) { rawResult ->
                if (isDestroyed || currentNavigationHadError) return@evaluateJavascript
                val eval = classifyDomInspectionResult(rawResult.orEmpty(), url)
                if (eval.isReady) {
                    cancelBlankScreenWatchdog()
                    _state.update {
                        it.copy(
                            isDomVerified = true,
                            isBlankScreenDetected = false,
                            ideReadinessDetail = eval.detail,
                            status = ConnectionStatus.CONNECTED,
                            progress = 100,
                        )
                    }
                } else if (eval.isBlank && isWatchdogCheck) {
                    currentNavigationHadError = true
                    _state.update {
                        it.copy(
                            status = ConnectionStatus.ERROR,
                            isDomVerified = false,
                            isBlankScreenDetected = true,
                            ideReadinessDetail = eval.detail,
                            errorMessage = "Blank screen detected: ${eval.detail}. Check runtime logs or tap Recreate WebView.",
                        )
                    }
                }
            }
        }
    }

    private fun destroyWebViewSafely(wv: WebView?) {
        if (wv == null) return
        runCatching {
            wv.stopLoading()
            wv.webChromeClient = null
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebSettings(wv: WebView, desktopMode: Boolean) {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
        applyUserAgent(wv, desktopMode)
    }

    private fun applyUserAgent(wv: WebView, desktopMode: Boolean) {
        wv.settings.userAgentString = if (desktopMode) {
            DESKTOP_UA
        } else {
            runCatching { WebSettings.getDefaultUserAgent(appContext) }.getOrNull()
        }
    }

    private fun createPopupWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val target = request?.url?.toString().orEmpty().trim()
            if (target.startsWith("http://", ignoreCase = true) ||
                target.startsWith("https://", ignoreCase = true) ||
                target.equals("about:blank", ignoreCase = true)
            ) {
                return false
            }
            if (isBlockedUnsafeScheme(target)) {
                Log.w(TAG, "Blocked unsafe navigation scheme in popup WebView")
                return true
            }
            requestChromePermission(target)
            return true
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            runCatching { CookieManager.getInstance().flush() }
            _state.update { it.copy(popupUrl = url.orEmpty()) }
        }

        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean {
            Log.w(TAG, "Popup WebView render process gone (didCrash=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detail?.didCrash() else null})")
            closePopupWindow()
            return true
        }
    }

    private fun createPopupWebChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onCloseWindow(window: WebView?) {
            closePopupWindow()
        }
    }

    private fun createMainWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val target = request?.url?.toString().orEmpty().trim()
            if (target.startsWith("http://", ignoreCase = true) ||
                target.startsWith("https://", ignoreCase = true) ||
                target.equals("about:blank", ignoreCase = true)
            ) {
                return false
            }
            if (isBlockedUnsafeScheme(target)) {
                Log.w(TAG, "Blocked unsafe navigation scheme in main WebView")
                return true
            }
            if (target.startsWith("vscode://", ignoreCase = true) ||
                target.startsWith("vscode-insiders://", ignoreCase = true)
            ) {
                onDownloadSavedCallback?.invoke("Desktop VS Code URI blocked — use Web IDE in VS Shell or open Codespaces in browser")
                return true
            }
            requestChromePermission(target)
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            currentNavigationHadError = false
            cancelBlankScreenWatchdog()
            val rawUrl = url.orEmpty()
            val usableUrl = rawUrl.takeIf {
                it.isNotBlank() &&
                    !it.startsWith("chrome-error://", ignoreCase = true) &&
                    !it.equals("about:blank", ignoreCase = true)
            }
            _state.update {
                it.copy(
                    currentUrl = usableUrl ?: it.currentUrl,
                    status = ConnectionStatus.LOADING,
                    errorMessage = null,
                    httpErrorCode = null,
                    isDomVerified = false,
                    isBlankScreenDetected = false,
                    ideReadinessDetail = "Connecting...",
                    isCodespacesSession = isCodespacesUrl(usableUrl ?: it.currentUrl),
                    progress = 15,
                )
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            runCatching { CookieManager.getInstance().flush() }
            val rawUrl = url.orEmpty()
            val isInternalErrorPage = rawUrl.startsWith("chrome-error://", ignoreCase = true) ||
                rawUrl.equals("about:blank", ignoreCase = true)
            val usableUrl = rawUrl.takeIf { it.isNotBlank() && !isInternalErrorPage }
            if (isInternalErrorPage && _state.value.currentUrl.isNotBlank()) {
                currentNavigationHadError = true
            }
            _state.update {
                it.copy(
                    currentUrl = usableUrl ?: it.currentUrl,
                    pageTitle = if (currentNavigationHadError) it.pageTitle else runCatching { view?.title.orEmpty() }.getOrDefault(""),
                    isCodespacesSession = isCodespacesUrl(usableUrl ?: it.currentUrl),
                    status = if (currentNavigationHadError) {
                        ConnectionStatus.ERROR
                    } else if (it.isDomVerified) {
                        ConnectionStatus.CONNECTED
                    } else {
                        ConnectionStatus.LOADING
                    },
                    errorMessage = if (rawUrl.equals("about:blank", ignoreCase = true) && it.errorMessage.isNullOrBlank() && it.currentUrl.isNotBlank()) {
                        "WebView remained on about:blank instead of rendering ${it.currentUrl}"
                    } else {
                        it.errorMessage
                    },
                    isBlankScreenDetected = rawUrl.equals("about:blank", ignoreCase = true) && it.currentUrl.isNotBlank(),
                    progress = 100,
                    canGoBack = runCatching { view?.canGoBack() == true }.getOrDefault(false),
                )
            }
            if (!currentNavigationHadError && !isInternalErrorPage) {
                val desiredZoom = _state.value.zoomPercent
                if (view != null && desiredZoom != appliedZoomPercent) {
                    applyZoomToWebViewLocked(view, desiredZoom)
                }
                inspectWebViewDomReadiness(view, usableUrl ?: _state.value.currentUrl, isWatchdogCheck = false)
                scheduleBlankScreenWatchdog(view, usableUrl ?: _state.value.currentUrl)
            }
            if (!currentNavigationHadError &&
                pendingAutoLoginPassword.isNotBlank() &&
                !usableUrl.isNullOrBlank() &&
                usableUrl != lastAutoFilledUrl &&
                isAllowedAutofillTarget(usableUrl, pendingAutoLoginHost)
            ) {
                lastAutoFilledUrl = usableUrl
                autofillPassword(pendingAutoLoginPassword)
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame == true) {
                currentNavigationHadError = true
                val desc = runCatching { error?.description?.toString().orEmpty() }
                    .getOrDefault("")
                    .ifBlank { "Unable to reach server" }
                val reqUrl = request.url?.toString().orEmpty()
                val enriched = if (isCodespacesUrl(reqUrl)) {
                    "GitHub Codespaces navigation failed ($desc). Check connection or sign in with GitHub."
                } else {
                    desc
                }
                Log.w(TAG, "Main frame WebView error: $enriched")
                _state.update {
                    it.copy(
                        status = ConnectionStatus.ERROR,
                        errorMessage = enriched,
                        canGoBack = runCatching { view?.canGoBack() == true }.getOrDefault(false),
                    )
                }
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            if (request?.isForMainFrame == true) {
                val statusCode = errorResponse?.statusCode ?: return
                if (statusCode >= 400) {
                    val reqUrl = request.url?.toString().orEmpty()
                    val msg = classifyHttpErrorMessage(
                        url = reqUrl,
                        statusCode = statusCode,
                        reasonPhrase = errorResponse.reasonPhrase.orEmpty(),
                    )
                    currentNavigationHadError = true
                    Log.w(TAG, "Main frame HTTP $statusCode: $msg")
                    _state.update {
                        it.copy(
                            status = ConnectionStatus.ERROR,
                            httpErrorCode = statusCode,
                            errorMessage = msg,
                            canGoBack = runCatching { view?.canGoBack() == true }.getOrDefault(false),
                        )
                    }
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?,
        ) {
            runCatching { pendingSslHandler?.cancel() }
            pendingSslHandler = handler
            val host = runCatching { error?.url?.toUri()?.host.orEmpty() }
                .getOrDefault("")
                .ifBlank { "localhost" }
            _state.update { it.copy(sslWarningHost = host) }
        }

        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean {
            val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { detail?.didCrash() == true }.getOrDefault(true)
            } else {
                true
            }
            Log.e(TAG, "Main WebView render process gone (didCrash=$crashed); cleaning up and recovering safely.")
            currentNavigationHadError = true
            pendingLoadRequest = null
            deliverFilePickerResult(null)
            cancelJsDialog()
            runCatching { pendingSslHandler?.cancel() }
            pendingSslHandler = null
            val old = mainWebView
            mainWebView = null
            destroyWebViewSafely(old)
            _state.update {
                it.copy(
                    status = ConnectionStatus.ERROR,
                    errorMessage = if (crashed) {
                        "Workspace WebView renderer crashed — tap Retry In App"
                    } else {
                        "Workspace WebView renderer stopped (low memory) — tap Retry In App"
                    },
                    rendererCrashCount = it.rendererCrashCount + 1,
                    webViewSessionSeq = it.webViewSessionSeq + 1L,
                )
            }
            return true
        }
    }

    private fun createMainWebChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            _state.update {
                it.copy(
                    progress = newProgress,
                    status = when {
                        currentNavigationHadError -> ConnectionStatus.ERROR
                        newProgress >= 100 && it.isDomVerified -> ConnectionStatus.CONNECTED
                        else -> ConnectionStatus.LOADING
                    },
                    canGoBack = runCatching { view?.canGoBack() == true }.getOrDefault(false),
                )
            }
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            if (consoleMessage != null && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                val msg = "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} ${consoleMessage.message()}"
                val safeMsg = SecureVault.redactSensitiveText(msg).take(220)
                _state.update { cur ->
                    cur.copy(consoleErrors = (cur.consoleErrors + safeMsg).takeLast(8))
                }
                onConsoleErrorCallback?.invoke(safeMsg)
            }
            return super.onConsoleMessage(consoleMessage)
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            _state.update { it.copy(pageTitle = title.orEmpty()) }
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            deliverFilePickerResult(null)
            this@InAppWebWorkspaceController.filePathCallback = filePathCallback
            val mimes = normalizeAcceptMimeTypes(fileChooserParams?.acceptTypes)
            val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
            val picker = onRequestFilePicker
            if (picker == null) {
                deliverFilePickerResult(null)
                return false
            }
            return runCatching {
                picker.invoke(mimes, allowMultiple)
                true
            }.getOrElse { err ->
                Log.w(TAG, "Failed to invoke file picker", err)
                deliverFilePickerResult(null)
                false
            }
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            if (isDestroyed) return false
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val oldPopup = popupWebView
            popupWebView = null

            val popup = try {
                WebView(webContextWrapper).apply {
                    configureWebSettings(this, _state.value.isDesktopMode)
                    webViewClient = createPopupWebViewClient()
                    webChromeClient = createPopupWebChromeClient()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create child WebView safely", t)
                return false
            }
            popupWebView = popup
            transport.webView = popup
            resultMsg.sendToTarget()
            val sessionId = nextPopupSeq++
            _state.update {
                it.copy(
                    hasPopupWindow = true,
                    popupSessionId = sessionId,
                    popupUrl = "In-App Sign-In",
                )
            }
            destroyWebViewSafely(oldPopup)
            return true
        }

        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?,
        ): Boolean {
            cancelJsDialog()
            pendingJsResult = result
            _state.update {
                it.copy(
                    pendingJsDialog = PendingJsDialog(
                        id = nextJsDialogSeq++,
                        kind = JsDialogKind.ALERT,
                        message = message.orEmpty().ifBlank { "Notice" },
                    ),
                )
            }
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?,
        ): Boolean {
            cancelJsDialog()
            pendingJsResult = result
            _state.update {
                it.copy(
                    pendingJsDialog = PendingJsDialog(
                        id = nextJsDialogSeq++,
                        kind = JsDialogKind.CONFIRM,
                        message = message.orEmpty().ifBlank { "Confirm" },
                    ),
                )
            }
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?,
        ): Boolean {
            cancelJsDialog()
            pendingJsPromptResult = result
            _state.update {
                it.copy(
                    pendingJsDialog = PendingJsDialog(
                        id = nextJsDialogSeq++,
                        kind = JsDialogKind.PROMPT,
                        message = message.orEmpty().ifBlank { "Input" },
                        defaultValue = defaultValue.orEmpty(),
                    ),
                )
            }
            return true
        }
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        internal fun extractHost(rawUrl: String): String {
            return runCatching { URI(rawUrl.trim()).host.orEmpty().lowercase() }.getOrDefault("")
        }

        internal fun isLoopbackHost(host: String): Boolean {
            val h = host.trim().lowercase()
            return h == "127.0.0.1" || h == "localhost" || h == "::1" || h == "[::1]"
        }

        internal fun classifyDomInspectionResult(
            rawJsEvalResult: String,
            expectedUrl: String,
        ): DomReadinessEvaluation {
            val trimmed = rawJsEvalResult.trim()
            if (trimmed.isBlank() || trimmed == "null") {
                return DomReadinessEvaluation(
                    isReady = false,
                    isBlank = true,
                    detail = "WebView JS runtime returned empty DOM result",
                )
            }
            val unwrapped = runCatching {
                if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                    JSONObject("""{"v":$trimmed}""").getString("v")
                } else {
                    trimmed
                }
            }.getOrDefault(trimmed)

            val json = runCatching { JSONObject(unwrapped) }.getOrNull()
                ?: return DomReadinessEvaluation(
                    isReady = false,
                    isBlank = true,
                    detail = "Unreadable DOM evaluation response",
                )

            if (json.has("error")) {
                return DomReadinessEvaluation(
                    isReady = false,
                    isBlank = true,
                    detail = "JS boot error: ${json.optString("error")}",
                )
            }

            val href = json.optString("href")
            if (href.equals("about:blank", ignoreCase = true) && expectedUrl.isNotBlank()) {
                return DomReadinessEvaluation(
                    isReady = false,
                    isBlank = true,
                    detail = "Page stayed on about:blank",
                )
            }
            val textLen = json.optInt("textLen", 0)
            val children = json.optInt("children", 0)
            val height = json.optInt("height", 0)
            val mediaCount = json.optInt("mediaCount", 0)
            val hasWorkbench = json.optBoolean("hasWorkbench", false)
            val readyState = json.optString("readyState")

            val hasVisibleContent = textLen > 0 || mediaCount > 0
            val painted = hasWorkbench || (children > 0 && hasVisibleContent)
            return if (painted) {
                DomReadinessEvaluation(
                    isReady = true,
                    isBlank = false,
                    detail = if (hasWorkbench) "IDE DOM ready ($readyState)" else "DOM painted ($children nodes, text=$textLen)",
                )
            } else {
                DomReadinessEvaluation(
                    isReady = false,
                    isBlank = true,
                    detail = "Rendered body has no visible content (children=$children, height=$height, textLen=$textLen, media=$mediaCount)",
                )
            }
        }

        internal fun isBlockedUnsafeScheme(rawUrl: String): Boolean {
            val t = rawUrl.trim()
            if (t.isBlank()) return true
            return t.startsWith("javascript:", ignoreCase = true) ||
                t.startsWith("data:", ignoreCase = true) ||
                t.startsWith("file:", ignoreCase = true) ||
                t.startsWith("vbscript:", ignoreCase = true) ||
                t.startsWith("content:", ignoreCase = true) ||
                (t.startsWith("about:", ignoreCase = true) && !t.equals("about:blank", ignoreCase = true))
        }

        internal fun isCodespacesUrl(rawUrl: String): Boolean {
            val parsed = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
            val host = parsed.host?.lowercase().orEmpty()
            val path = parsed.path?.lowercase().orEmpty()
            return host.endsWith(".github.dev") ||
                host.endsWith(".app.github.dev") ||
                (host == "github.com" && path.startsWith("/codespaces"))
        }

        internal fun classifyHttpErrorMessage(
            url: String,
            statusCode: Int,
            reasonPhrase: String = "",
        ): String {
            val isCodespaces = isCodespacesUrl(url)
            val reason = reasonPhrase.trim().takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            return when {
                isCodespaces && (statusCode == 401 || statusCode == 403) ->
                    "GitHub Codespaces authentication required or denied (HTTP $statusCode). Sign in with GitHub In-App and retry."
                isCodespaces && (statusCode == 404 || statusCode == 502 || statusCode == 503 || statusCode == 504) ->
                    "GitHub Codespace is stopped, starting up, or unreachable (HTTP $statusCode). Start the Codespace from your dashboard or retry."
                statusCode >= 500 ->
                    "Remote workspace server error (HTTP $statusCode$reason)."
                else ->
                    "Workspace returned HTTP $statusCode$reason."
            }
        }

        internal fun isAllowedAutofillTarget(currentUrl: String, expectedHost: String): Boolean {
            val parsed = runCatching { URI(currentUrl.trim()) }.getOrNull() ?: return false
            val scheme = parsed.scheme?.lowercase().orEmpty()
            val host = parsed.host?.lowercase().orEmpty()
            if (host.isBlank()) return false
            val transportSafe = scheme == "https" || (scheme == "http" && isLoopbackHost(host))
            if (!transportSafe) return false
            return expectedHost.isBlank() || host == expectedHost.lowercase()
        }

        internal fun normalizeAcceptMimeTypes(rawTypes: Array<String>?): Array<String> {
            if (rawTypes.isNullOrEmpty()) return arrayOf("*/*")
            val valid = rawTypes
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { entry ->
                    when {
                        entry.contains('/') -> entry
                        entry.startsWith('.') -> {
                            val ext = entry.removePrefix(".").lowercase()
                            when (ext) {
                                "html", "htm" -> "text/html"
                                "css" -> "text/css"
                                "js", "mjs" -> "text/javascript"
                                "json" -> "application/json"
                                "xml" -> "application/xml"
                                "txt", "md", "py", "kt", "kts", "java", "c", "cpp", "h", "hpp",
                                "cs", "go", "rs", "swift", "php", "rb", "sh", "yaml", "yml",
                                -> "text/plain"
                                else -> null
                            }
                        }
                        else -> null
                    }
                }
                .distinct()
            return if (valid.isEmpty()) arrayOf("*/*") else valid.toTypedArray()
        }
    }
}
