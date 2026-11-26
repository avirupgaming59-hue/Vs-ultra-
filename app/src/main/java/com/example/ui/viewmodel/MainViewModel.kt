package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.CodeLanguage
import com.example.data.model.PhoneCodeFile
import com.example.data.repository.AppPreferences
import com.example.data.repository.PhoneFileRepository
import com.example.data.repository.SettingsRepository
import com.example.service.KeepAliveService
import com.example.ui.theme.ThemePreset
import com.example.workspace.InAppWebWorkspaceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppTab {
    WORKSPACE,
    USER_TERMINAL,
    PHONE_FILES,
    PROFILES,
    AI_CHAT,
    SETTINGS,
}

data class PhoneFilesUiState(
    val files: List<PhoneCodeFile> = emptyList(),
    val selectedLanguage: CodeLanguage? = null,
    val searchQuery: String = "",
    val folderDisplayName: String = "Phone Workspace (App Storage)",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val editingFile: PhoneCodeFile? = null,
    val editingContent: String = "",
    val isSavingFile: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val settingsRepository = SettingsRepository(appContext)
    private val sharedFilesystem = com.example.workspace.LocalhostRuntimeHost.getRuntimeManager(appContext).sharedFilesystem
    private val phoneFileRepository = PhoneFileRepository(appContext, sharedFilesystem)

    private val _currentTab = MutableStateFlow(AppTab.WORKSPACE)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    private val _tabBackStack = MutableStateFlow<List<AppTab>>(emptyList())
    val canNavigateBack: StateFlow<Boolean> = _tabBackStack
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val preferences: StateFlow<AppPreferences> = settingsRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())

    private val _phoneFilesState = MutableStateFlow(PhoneFilesUiState())
    val phoneFilesState: StateFlow<PhoneFilesUiState> = _phoneFilesState.asStateFlow()

    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()

    private val _coldStartPinCheckDone = MutableStateFlow<Boolean?>(null)
    val coldStartPinRequired: StateFlow<Boolean?> = _coldStartPinCheckDone.asStateFlow()

    init {
        viewModelScope.launch {
            val initialPrefs = settingsRepository.preferences.first()
            _coldStartPinCheckDone.value = initialPrefs.appPinEnabled && initialPrefs.appPinHash.isNotBlank()
        }
        viewModelScope.launch {
            preferences
                .map { it.linkedFolderUri }
                .distinctUntilChanged()
                .collect { linkedUri ->
                    refreshPhoneFiles(linkedUri)
                }
        }
        viewModelScope.launch {
            sharedFilesystem.changes.collect {
                if (preferences.value.linkedFolderUri.isNullOrBlank()) {
                    refreshPhoneFiles(null)
                }
            }
        }
    }

    fun selectTab(tab: AppTab, pushHistory: Boolean = true) {
        val current = _currentTab.value
        if (current == tab) return
        if (pushHistory) {
            _tabBackStack.update { stack ->
                val filtered = if (stack.lastOrNull() == current) stack else (stack + current)
                filtered.takeLast(12)
            }
        }
        _currentTab.value = tab
    }

    fun navigateBack(): Boolean {
        val stack = _tabBackStack.value
        if (stack.isEmpty()) return false
        val previous = stack.last()
        _tabBackStack.value = stack.dropLast(1)
        _currentTab.value = previous
        return true
    }

    fun navigateBackOrProfiles(): Boolean {
        if (navigateBack()) return true
        if (_currentTab.value != AppTab.PROFILES) {
            _currentTab.value = AppTab.PROFILES
            return true
        }
        return false
    }

    fun saveMasterCredentials(label: String, passwordOrToken: String) {
        viewModelScope.launch {
            settingsRepository.setMasterAccount(label, passwordOrToken)
            _toastMessages.tryEmit("Master password/token saved for in-app autofill")
        }
    }

    fun setThemePreset(preset: ThemePreset) {
        viewModelScope.launch { settingsRepository.setThemePreset(preset) }
    }

    fun setImmersiveFullscreen(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setImmersiveFullscreen(enabled) }
    }

    fun setKeepScreenAwake(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepScreenAwake(enabled) }
    }

    fun setKeepAliveServiceEnabled(enabled: Boolean, activeServerName: String?) {
        viewModelScope.launch {
            settingsRepository.setKeepAliveServiceEnabled(enabled)
            if (enabled) {
                val manager = com.example.workspace.LocalhostRuntimeHost.getRuntimeManager(appContext)
                val fallbackRunning = com.example.workspace.LocalhostRuntimeHost.getEngine(appContext).isEmbeddedServerRunning()
                if (manager.hasAnyActiveRuntime(fallbackRunning)) {
                    KeepAliveService.start(appContext, activeServerName ?: "Local Workspace")
                } else {
                    KeepAliveService.stop(appContext)
                    _toastMessages.tryEmit("Keep-Alive armed — activates when a local runtime or shell runs")
                }
            } else {
                KeepAliveService.stop(appContext)
            }
        }
    }

    fun setAutoLaunchBackground(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoLaunchBackground(enabled) }
    }

    fun setAiAutoCodeServerOnLaunch(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAiAutoCodeServerOnLaunch(enabled) }
    }

    fun setAutoRebootWatchdogEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoRebootWatchdogEnabled(enabled) }
    }

    fun setShowDevShortcutBar(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShowDevShortcutBar(enabled) }
    }

    fun setScreenPrivacyShieldEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setScreenPrivacyShieldEnabled(enabled)
            _toastMessages.tryEmit(
                if (enabled) "Screen Privacy Shield enabled (screenshots & recents hidden)"
                else "Screen Privacy Shield disabled",
            )
        }
    }

    fun setAppPin(pin: String) {
        val trimmed = pin.trim()
        if (trimmed.length !in 4..12 || !trimmed.all { it.isDigit() }) {
            _toastMessages.tryEmit("PIN must be 4 to 12 digits")
            return
        }
        viewModelScope.launch {
            settingsRepository.enableAppPin(trimmed)
            _toastMessages.tryEmit("App PIN Lock enabled & stored with PBKDF2")
        }
    }

    fun disableAppPin() {
        viewModelScope.launch {
            settingsRepository.disableAppPin()
            _toastMessages.tryEmit("App PIN Lock removed")
        }
    }

    fun onLinkedFolderPicked(uri: Uri?) {
        viewModelScope.launch {
            if (uri != null) {
                runCatching {
                    appContext.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                settingsRepository.setLinkedFolderUri(uri.toString())
                refreshPhoneFiles(uri.toString())
                _toastMessages.tryEmit("Linked phone folder")
            } else {
                settingsRepository.setLinkedFolderUri(null)
                refreshPhoneFiles(null)
                _toastMessages.tryEmit("Switched to App Phone Workspace")
            }
        }
    }

    fun refreshPhoneFiles(linkedUri: String? = preferences.value.linkedFolderUri) {
        viewModelScope.launch {
            _phoneFilesState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    folderDisplayName = phoneFileRepository.getFolderDisplayName(linkedUri),
                )
            }
            try {
                val files = phoneFileRepository.listFiles(linkedUri)
                _phoneFilesState.update { it.copy(files = files, isLoading = false, errorMessage = null) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _phoneFilesState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = t.localizedMessage ?: "Unable to load phone workspace files",
                    )
                }
            }
        }
    }

    fun setLanguageFilter(language: CodeLanguage?) {
        _phoneFilesState.update { it.copy(selectedLanguage = language) }
    }

    fun setFileSearchQuery(query: String) {
        _phoneFilesState.update { it.copy(searchQuery = query) }
    }

    fun createPhoneFile(name: String, language: CodeLanguage) {
        viewModelScope.launch {
            val res = phoneFileRepository.createFile(
                fileName = name,
                language = language,
                initialContent = language.starterTemplate,
                linkedTreeUriString = preferences.value.linkedFolderUri,
            )
            res.onSuccess { created ->
                refreshPhoneFiles()
                openFileInLocalEditor(created)
                _toastMessages.tryEmit("Created ${created.displayName} on phone")
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "Failed to create file")
            }
        }
    }

    fun importPhoneDocument(uri: Uri) {
        viewModelScope.launch {
            val res = phoneFileRepository.importDocument(uri, preferences.value.linkedFolderUri)
            res.onSuccess { imported ->
                refreshPhoneFiles()
                _toastMessages.tryEmit("Added ${imported.displayName} (${imported.language.displayName})")
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "Could not import file")
            }
        }
    }

    fun openFileInLocalEditor(file: PhoneCodeFile) {
        viewModelScope.launch {
            if (!file.isEditableText) {
                _toastMessages.tryEmit("File too large to edit locally; use upload picker in workspace")
                return@launch
            }
            phoneFileRepository.readFileText(file).onSuccess { text ->
                _phoneFilesState.update {
                    it.copy(editingFile = file, editingContent = text)
                }
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "Cannot read file")
            }
        }
    }

    fun updateEditingContent(content: String) {
        _phoneFilesState.update { it.copy(editingContent = content) }
    }

    fun closeLocalEditor() {
        _phoneFilesState.update { it.copy(editingFile = null, editingContent = "") }
    }

    fun saveEditingFile(onSaved: (() -> Unit)? = null) {
        val file = _phoneFilesState.value.editingFile ?: return
        val content = _phoneFilesState.value.editingContent
        viewModelScope.launch {
            _phoneFilesState.update { it.copy(isSavingFile = true) }
            phoneFileRepository.saveFileText(file, content).onSuccess {
                refreshPhoneFiles()
                _toastMessages.tryEmit("Saved ${file.displayName} to phone")
                onSaved?.invoke()
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "Failed to save file")
            }
            _phoneFilesState.update { it.copy(isSavingFile = false) }
        }
    }

    fun deletePhoneFile(file: PhoneCodeFile) {
        viewModelScope.launch {
            phoneFileRepository.deleteFile(file).onSuccess {
                if (_phoneFilesState.value.editingFile?.id == file.id) {
                    closeLocalEditor()
                }
                refreshPhoneFiles()
                _toastMessages.tryEmit("Deleted ${file.displayName}")
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "Unable to delete ${file.displayName}")
            }
        }
    }

    fun sendPhoneFileToVsCode(
        file: PhoneCodeFile,
        webController: InAppWebWorkspaceController,
        onReadyInWorkspace: () -> Unit,
    ) {
        viewModelScope.launch {
            val bytesResult = phoneFileRepository.readFileBytes(file)
            bytesResult.onSuccess { bytes ->
                val textPreview = if (bytes.size <= 200_000) bytes.toString(Charsets.UTF_8) else null
                webController.sendPhoneFileToWorkspace(file.displayName, textPreview) { msg ->
                    _toastMessages.tryEmit(msg)
                }
                onReadyInWorkspace()
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "Failed to read phone file")
            }
        }
    }

    fun notifyMessage(message: String) {
        _toastMessages.tryEmit(message)
    }
}
