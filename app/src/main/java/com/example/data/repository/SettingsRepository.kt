package com.example.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.security.SecureVault
import com.example.ui.theme.ThemePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vsshell_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

data class AppPreferences(
    val themePreset: ThemePreset = ThemePreset.VSCODE_DARK,
    val immersiveFullscreen: Boolean = true,
    val keepScreenAwake: Boolean = true,
    val keepAliveServiceEnabled: Boolean = false,
    val autoLaunchBackground: Boolean = true,
    val aiAutoCodeServerOnLaunch: Boolean = true,
    val autoRebootWatchdogEnabled: Boolean = true,
    val showDevShortcutBar: Boolean = false,
    val linkedFolderUri: String? = null,
    val hasSeededDefaults: Boolean = false,
    val masterAccountLabel: String = "",
    val masterPasswordOrToken: String = "",
    val githubSessionSignedIn: Boolean = false,
    val screenPrivacyShieldEnabled: Boolean = false,
    val appPinEnabled: Boolean = false,
    val appPinHash: String = "",
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val THEME_PRESET = stringPreferencesKey("theme_preset")
        val IMMERSIVE_FULLSCREEN = booleanPreferencesKey("immersive_fullscreen")
        val KEEP_SCREEN_AWAKE = booleanPreferencesKey("keep_screen_awake")
        val KEEP_ALIVE_SERVICE = booleanPreferencesKey("keep_alive_service")
        val AUTO_LAUNCH_BACKGROUND = booleanPreferencesKey("auto_launch_background")
        val AI_AUTO_CODE_SERVER_ON_LAUNCH = booleanPreferencesKey("ai_auto_code_server_on_launch")
        val AUTO_REBOOT_WATCHDOG = booleanPreferencesKey("auto_reboot_watchdog")
        val SHOW_DEV_SHORTCUT_BAR = booleanPreferencesKey("show_dev_shortcut_bar")
        val LINKED_FOLDER_URI = stringPreferencesKey("linked_folder_uri")
        val HAS_SEEDED_DEFAULTS = booleanPreferencesKey("has_seeded_defaults")
        val MASTER_ACCOUNT_LABEL = stringPreferencesKey("master_account_label")
        val MASTER_PASSWORD_OR_TOKEN = stringPreferencesKey("master_password_or_token")
        val GITHUB_SESSION_SIGNED_IN = booleanPreferencesKey("github_session_signed_in")
        val SCREEN_PRIVACY_SHIELD = booleanPreferencesKey("screen_privacy_shield")
        val APP_PIN_ENABLED = booleanPreferencesKey("app_pin_enabled")
        val APP_PIN_HASH = stringPreferencesKey("app_pin_hash")
    }

    val preferences: Flow<AppPreferences> = context.dataStore.data
        .catch { exception ->
            Log.w("SettingsRepository", "DataStore read warning, using defaults", exception)
            emit(emptyPreferences())
        }
        .map { prefs ->
            val themeName = prefs[Keys.THEME_PRESET] ?: ThemePreset.VSCODE_DARK.name
            val preset = runCatching { ThemePreset.valueOf(themeName) }
                .getOrDefault(ThemePreset.VSCODE_DARK)
            val rawMaster = prefs[Keys.MASTER_PASSWORD_OR_TOKEN] ?: ""
            val decryptedMaster = SecureVault.decryptSecret(rawMaster)
            AppPreferences(
                themePreset = preset,
                immersiveFullscreen = prefs[Keys.IMMERSIVE_FULLSCREEN] ?: true,
                keepScreenAwake = prefs[Keys.KEEP_SCREEN_AWAKE] ?: true,
                keepAliveServiceEnabled = prefs[Keys.KEEP_ALIVE_SERVICE] ?: false,
                autoLaunchBackground = prefs[Keys.AUTO_LAUNCH_BACKGROUND] ?: true,
                aiAutoCodeServerOnLaunch = prefs[Keys.AI_AUTO_CODE_SERVER_ON_LAUNCH] ?: true,
                autoRebootWatchdogEnabled = prefs[Keys.AUTO_REBOOT_WATCHDOG] ?: true,
                showDevShortcutBar = prefs[Keys.SHOW_DEV_SHORTCUT_BAR] ?: false,
                linkedFolderUri = prefs[Keys.LINKED_FOLDER_URI],
                hasSeededDefaults = prefs[Keys.HAS_SEEDED_DEFAULTS] ?: false,
                masterAccountLabel = prefs[Keys.MASTER_ACCOUNT_LABEL] ?: "",
                masterPasswordOrToken = decryptedMaster,
                githubSessionSignedIn = prefs[Keys.GITHUB_SESSION_SIGNED_IN] ?: false,
                screenPrivacyShieldEnabled = prefs[Keys.SCREEN_PRIVACY_SHIELD] ?: false,
                appPinEnabled = prefs[Keys.APP_PIN_ENABLED] ?: false,
                appPinHash = prefs[Keys.APP_PIN_HASH] ?: "",
            )
        }

    suspend fun setThemePreset(preset: ThemePreset) {
        context.dataStore.edit { it[Keys.THEME_PRESET] = preset.name }
    }

    suspend fun setImmersiveFullscreen(enabled: Boolean) {
        context.dataStore.edit { it[Keys.IMMERSIVE_FULLSCREEN] = enabled }
    }

    suspend fun setKeepScreenAwake(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_AWAKE] = enabled }
    }

    suspend fun setKeepAliveServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_ALIVE_SERVICE] = enabled }
    }

    suspend fun setAutoLaunchBackground(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_LAUNCH_BACKGROUND] = enabled }
    }

    suspend fun setAiAutoCodeServerOnLaunch(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AI_AUTO_CODE_SERVER_ON_LAUNCH] = enabled }
    }

    suspend fun setAutoRebootWatchdogEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_REBOOT_WATCHDOG] = enabled }
    }

    suspend fun setShowDevShortcutBar(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_DEV_SHORTCUT_BAR] = enabled }
    }

    suspend fun setLinkedFolderUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri.isNullOrBlank()) {
                prefs.remove(Keys.LINKED_FOLDER_URI)
            } else {
                prefs[Keys.LINKED_FOLDER_URI] = uri
            }
        }
    }

    suspend fun setHasSeededDefaults(seeded: Boolean) {
        context.dataStore.edit { it[Keys.HAS_SEEDED_DEFAULTS] = seeded }
    }

    suspend fun setMasterAccount(label: String, passwordOrToken: String) {
        val encrypted = SecureVault.encryptSecret(passwordOrToken)
        context.dataStore.edit { prefs ->
            prefs[Keys.MASTER_ACCOUNT_LABEL] = label.trim()
            prefs[Keys.MASTER_PASSWORD_OR_TOKEN] = encrypted
        }
    }

    suspend fun setGithubSessionSignedIn(signedIn: Boolean) {
        context.dataStore.edit { it[Keys.GITHUB_SESSION_SIGNED_IN] = signedIn }
    }

    suspend fun setScreenPrivacyShieldEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SCREEN_PRIVACY_SHIELD] = enabled }
    }

    suspend fun enableAppPin(pin: String) {
        val saltedHash = SecureVault.hashPin(pin)
        context.dataStore.edit { prefs ->
            prefs[Keys.APP_PIN_HASH] = saltedHash
            prefs[Keys.APP_PIN_ENABLED] = true
        }
    }

    suspend fun disableAppPin() {
        context.dataStore.edit { prefs ->
            prefs[Keys.APP_PIN_ENABLED] = false
            prefs.remove(Keys.APP_PIN_HASH)
        }
    }
}
