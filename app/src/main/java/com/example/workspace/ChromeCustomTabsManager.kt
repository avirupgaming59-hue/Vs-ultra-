package com.example.workspace

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "ChromeCustomTabs"

data class CustomTabsSessionState(
    val customTabsPackage: String? = null,
    val isBound: Boolean = false,
    val isWarmedUp: Boolean = false,
    val lastLaunchedUrl: String = "",
    val lastLaunchedAt: Long = 0L,
    val launchCount: Int = 0,
    val lastAuthUrl: String = "",
)

/**
 * Manages AndroidX Chrome Custom Tabs binding, service warmup, speculative URL preloading,
 * and themed CustomTabsIntent launches for all web-based workspaces and OAuth authentication flows.
 */
class ChromeCustomTabsManager(private val appContext: Context) {

    private val _state = MutableStateFlow(CustomTabsSessionState())
    val state: StateFlow<CustomTabsSessionState> = _state.asStateFlow()

    private var customTabsClient: CustomTabsClient? = null
    private var customTabsSession: CustomTabsSession? = null
    private var serviceConnection: CustomTabsServiceConnection? = null
    private var pendingPreloadUrls: List<String> = emptyList()

    fun bindAndWarmup(context: Context = appContext) {
        if (serviceConnection != null && customTabsClient != null) {
            return
        }
        val packageName = resolveCustomTabsPackage(context)
        if (packageName.isNullOrBlank()) {
            Log.w(TAG, "No Custom Tabs provider found; will fall back to default browser.")
            _state.update { it.copy(customTabsPackage = null, isBound = false, isWarmedUp = false) }
            return
        }

        val connection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                customTabsClient = client
                val warmedUp = runCatching { client.warmup(0L) }.getOrDefault(false)
                customTabsSession = client.newSession(object : CustomTabsCallback() {
                    override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
                        Log.d(TAG, "Custom Tab navigation event: $navigationEvent")
                    }
                })
                _state.update {
                    it.copy(
                        customTabsPackage = packageName,
                        isBound = true,
                        isWarmedUp = warmedUp,
                    )
                }
                if (pendingPreloadUrls.isNotEmpty()) {
                    mayLaunchUrls(pendingPreloadUrls)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                customTabsClient = null
                customTabsSession = null
                serviceConnection = null
                _state.update { it.copy(isBound = false, isWarmedUp = false) }
            }
        }

        val bound = runCatching {
            CustomTabsClient.bindCustomTabsService(context.applicationContext, packageName, connection)
        }.getOrDefault(false)

        if (bound) {
            serviceConnection = connection
            _state.update { it.copy(customTabsPackage = packageName) }
        } else {
            Log.w(TAG, "Failed to bind CustomTabsService for $packageName")
        }
    }

    fun mayLaunchUrls(rawUrls: List<String>) {
        val normalized = rawUrls.map { normalizeUrl(it) }.filter { it.isNotBlank() }.distinct()
        if (normalized.isEmpty()) return
        pendingPreloadUrls = normalized

        val session = customTabsSession ?: return
        val primaryUri = normalized.first().toUri()
        val otherBundles = normalized.drop(1).take(5).map { url ->
            Bundle().apply {
                putParcelable(CustomTabsService.KEY_URL, url.toUri())
            }
        }
        runCatching {
            session.mayLaunchUrl(primaryUri, null, otherBundles)
        }
    }

    fun openWorkspaceOrAuth(
        context: Context,
        rawUrl: String,
        isDarkTheme: Boolean,
        toolbarColorArgb: Int,
        secondaryColorArgb: Int,
        isAuthFlow: Boolean = false,
    ): Boolean {
        val normalized = normalizeUrl(rawUrl)
        if (normalized.isBlank()) return false

        if (customTabsClient == null) {
            bindAndWarmup(context)
        }

        val colorParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(toolbarColorArgb)
            .setNavigationBarColor(toolbarColorArgb)
            .setSecondaryToolbarColor(secondaryColorArgb)
            .build()

        val builder = CustomTabsIntent.Builder(customTabsSession)
            .setDefaultColorSchemeParams(colorParams)
            .setColorScheme(
                if (isDarkTheme) CustomTabsIntent.COLOR_SCHEME_DARK
                else CustomTabsIntent.COLOR_SCHEME_LIGHT
            )
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setInstantAppsEnabled(false)

        val customTabsIntent = builder.build()
        val resolvedPkg = _state.value.customTabsPackage ?: resolveCustomTabsPackage(context)
        if (!resolvedPkg.isNullOrBlank()) {
            customTabsIntent.intent.setPackage(resolvedPkg)
        }
        if (context !is Activity) {
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val targetUri = Uri.parse(normalized)
        val scheme = targetUri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") {
            Log.w(TAG, "Blocked unsafe URL scheme for Custom Tabs launch")
            return false
        }

        val launched = runCatching {
            customTabsIntent.launchUrl(context, targetUri)
            true
        }.getOrElse { err ->
            Log.w(TAG, "Custom Tab launch failed; trying generic VIEW intent", err)
            runCatching {
                val fallback = Intent(Intent.ACTION_VIEW, targetUri).apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            }.getOrDefault(false)
        }

        if (launched) {
            _state.update {
                it.copy(
                    lastLaunchedUrl = if (isAuthFlow) it.lastLaunchedUrl else normalized,
                    lastLaunchedAt = System.currentTimeMillis(),
                    launchCount = it.launchCount + 1,
                    lastAuthUrl = if (isAuthFlow) normalized else it.lastAuthUrl,
                )
            }
        }
        return launched
    }

    fun unbind(context: Context = appContext) {
        val conn = serviceConnection ?: return
        runCatching {
            context.applicationContext.unbindService(conn)
        }
        serviceConnection = null
        customTabsClient = null
        customTabsSession = null
        _state.update { it.copy(isBound = false, isWarmedUp = false) }
    }

    private fun resolveCustomTabsPackage(context: Context): String? {
        val preferred = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.sec.android.app.sbrowser",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
        )
        return CustomTabsClient.getPackageName(context, preferred, false)
            ?: CustomTabsClient.getPackageName(context, null)
    }

    companion object {
        const val AUTH_GITHUB_URL = "https://github.com/login"
        const val AUTH_GOOGLE_URL = "https://accounts.google.com"
        const val AUTH_MICROSOFT_URL = "https://login.microsoftonline.com"

        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return ""
            if (trimmed.any { it.isISOControl() } || trimmed.startsWith("//")) return ""

            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                val hostPart = trimmed.substringAfter("://").substringBefore('/').substringBefore('?')
                return if (hostPart.isBlank()) "" else trimmed
            }

            // Reject dangerous or non-HTTP schemes (javascript:, data:, file:, content:, intent:, vbscript:, about:, etc.)
            // while still allowing scheme-less host:port inputs like localhost:8080 or [::1]:8080.
            val colonIdx = trimmed.indexOf(':')
            val slashIdx = trimmed.indexOf('/')
            val queryIdx = trimmed.indexOf('?')
            val firstDelimiter = listOf(slashIdx, queryIdx).filter { it >= 0 }.minOrNull() ?: Int.MAX_VALUE
            if (colonIdx >= 0 && colonIdx < firstDelimiter && !trimmed.startsWith("[")) {
                val afterColon = trimmed.substring(colonIdx + 1)
                val isNumericPort = afterColon.isNotEmpty() && afterColon.first().isDigit()
                if (!isNumericPort) {
                    return ""
                }
            }

            return "https://$trimmed"
        }
    }
}
