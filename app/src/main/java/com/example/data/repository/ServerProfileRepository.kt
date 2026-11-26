package com.example.data.repository

import com.example.data.local.ActiveSessionDao
import com.example.data.local.ServerProfileDao
import com.example.data.model.ActiveSessionEntity
import com.example.data.model.ConnectionMode
import com.example.data.model.LocalhostRuntimeKind
import com.example.data.model.ServerProfile
import com.example.data.model.WorkspaceProvisioningState
import com.example.security.SecureVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ServerProfileRepository(
    private val dao: ServerProfileDao,
    private val activeSessionDao: ActiveSessionDao,
) {
    val profiles: Flow<List<ServerProfile>> = dao.observeAllProfiles()
        .map { list -> list.map(::decryptProfile) }
    val activeProfile: Flow<ServerProfile?> = dao.observeActiveProfile()
        .map { profile -> profile?.let(::decryptProfile) }
    val activeSession: Flow<ActiveSessionEntity?> = activeSessionDao.observeActiveSession()

    private fun encryptProfile(profile: ServerProfile): ServerProfile {
        val encryptedHint = if (profile.passwordHint.isBlank()) {
            ""
        } else {
            SecureVault.encryptSecret(profile.passwordHint)
        }
        return profile.copy(passwordHint = encryptedHint)
    }

    private fun decryptProfile(profile: ServerProfile): ServerProfile {
        if (profile.passwordHint.isBlank()) return profile
        return profile.copy(passwordHint = SecureVault.decryptSecret(profile.passwordHint))
    }

    suspend fun getById(id: Long): ServerProfile? = dao.getById(id)?.let(::decryptProfile)

    suspend fun migrateLegacyProfilesIfNeeded() {
        val existing = dao.getAllOnce()
        existing.forEach { profile ->
            val lowerUrl = profile.url.trim().lowercase()
            var updated = profile
            var changed = false
            if (lowerUrl.startsWith("https://stackblitz.com") || lowerUrl.startsWith("http://stackblitz.com")) {
                updated = updated.copy(
                    name = if (updated.name.contains("StackBlitz", ignoreCase = true)) {
                        "Project IDX / Cloud Studio"
                    } else {
                        updated.name
                    },
                    url = "https://idx.google.com",
                    useDesktopUserAgent = false,
                )
                changed = true
            }
            if (updated.passwordHint.isNotBlank() && !SecureVault.isEncryptedPayload(updated.passwordHint)) {
                updated = encryptProfile(updated)
                changed = true
            }
            if (changed) {
                dao.update(updated)
            }
        }
    }

    suspend fun seedDefaultProfilesIfNeeded(): ServerProfile? {
        if (dao.count() > 0) {
            migrateLegacyProfilesIfNeeded()
            val currentSession = activeSessionDao.getActiveSession()
            if (currentSession != null) {
                val matched = dao.getById(currentSession.activeProfileId)
                if (matched != null && matched.provisioningState != WorkspaceProvisioningState.FAILED) {
                    dao.activateProfile(matched.id)
                    return decryptProfile(matched)
                }
            }
            return null
        }

        val now = System.currentTimeMillis()
        val defaults = listOf(
            ServerProfile(
                name = "AI & Termux Localhost :8080",
                url = "http://127.0.0.1:8080",
                connectionMode = ConnectionMode.DIRECT_CODE_SERVER,
                useDesktopUserAgent = false,
                autoConnectOnLaunch = true,
                useMasterAutoLogin = true,
                isPinned = true,
                isActive = true,
                lastConnectedAt = now,
                createdAt = now,
                provisioningState = WorkspaceProvisioningState.NOT_STARTED,
                runtimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
            ),
            ServerProfile(
                name = "VS Code Web + GitHub Tunnel",
                url = "https://vscode.dev",
                connectionMode = ConnectionMode.CLOUD_IDE_WEB,
                useDesktopUserAgent = false,
                autoConnectOnLaunch = true,
                useMasterAutoLogin = true,
                isPinned = true,
                isActive = false,
                createdAt = now - 1_000,
                provisioningState = WorkspaceProvisioningState.READY,
                runtimeKind = LocalhostRuntimeKind.REMOTE_CLOUD_IDE,
            ),
            ServerProfile(
                name = "GitHub Codespaces (Full IDE)",
                url = "https://github.com/codespaces",
                connectionMode = ConnectionMode.CLOUD_IDE_WEB,
                useDesktopUserAgent = false,
                autoConnectOnLaunch = true,
                useMasterAutoLogin = true,
                isPinned = false,
                isActive = false,
                createdAt = now - 2_000,
                provisioningState = WorkspaceProvisioningState.READY,
                runtimeKind = LocalhostRuntimeKind.REMOTE_CLOUD_IDE,
            ),
            ServerProfile(
                name = "Project IDX / Cloud Studio",
                url = "https://idx.google.com",
                connectionMode = ConnectionMode.CLOUD_IDE_WEB,
                useDesktopUserAgent = false,
                autoConnectOnLaunch = true,
                useMasterAutoLogin = true,
                isPinned = false,
                isActive = false,
                createdAt = now - 3_000,
                provisioningState = WorkspaceProvisioningState.READY,
                runtimeKind = LocalhostRuntimeKind.REMOTE_CLOUD_IDE,
            ),
        )
        var activeSaved: ServerProfile? = null
        defaults.forEach { profile ->
            val id = dao.insert(encryptProfile(profile))
            if (profile.isActive) {
                activeSaved = profile.copy(id = id)
            }
        }
        activeSaved?.let { active ->
            activeSessionDao.upsert(
                ActiveSessionEntity(
                    activeProfileId = active.id,
                    lastUrl = active.url,
                    isDesktopMode = active.useDesktopUserAgent,
                    isBackgroundKeepAlive = true,
                )
            )
        }
        return activeSaved
    }

    suspend fun createDraftProfile(profile: ServerProfile): ServerProfile {
        val draft = profile.copy(isActive = false)
        val id = dao.insert(encryptProfile(draft))
        return decryptProfile(dao.getById(id) ?: draft.copy(id = id))
    }

    suspend fun updateProfile(profile: ServerProfile): ServerProfile {
        require(profile.id > 0L) { "A profile must have a persisted ID" }
        dao.update(encryptProfile(profile))
        return decryptProfile(dao.getById(profile.id) ?: error("Profile ${profile.id} disappeared"))
    }

    suspend fun updateProvisioningState(
        profileId: Long,
        state: WorkspaceProvisioningState,
        lastError: String = "",
    ) {
        if (profileId <= 0L) return
        dao.updateProvisioningState(profileId, state, lastError)
    }

    suspend fun commitReadyProfile(
        profileId: Long,
        actualUrl: String,
        actualSlug: String,
        runtimeKind: LocalhostRuntimeKind,
    ): ServerProfile {
        dao.commitReadyProfile(
            id = profileId,
            url = actualUrl,
            slug = actualSlug,
            runtimeKind = runtimeKind,
        )
        val committed = decryptProfile(
            dao.getById(profileId) ?: error("Profile $profileId disappeared during commit"),
        )
        activeSessionDao.upsert(
            ActiveSessionEntity(
                activeProfileId = committed.id,
                lastUrl = committed.url,
                isDesktopMode = committed.useDesktopUserAgent,
                isBackgroundKeepAlive = true,
            ),
        )
        return committed
    }

    suspend fun rollbackDraftProfile(
        draftId: Long,
        previousActiveId: Long?,
        deleteDraft: Boolean,
        failureState: WorkspaceProvisioningState = WorkspaceProvisioningState.FAILED,
        errorMessage: String = "",
    ) {
        dao.rollbackDraftAndRestore(
            draftId = draftId,
            previousActiveId = previousActiveId,
            deleteDraft = deleteDraft,
            failureState = failureState,
            errorMessage = errorMessage,
        )
        val restored = if (previousActiveId != null && previousActiveId > 0L && previousActiveId != draftId) {
            dao.getById(previousActiveId)
        } else {
            null
        }
        if (restored != null) {
            activeSessionDao.upsert(
                ActiveSessionEntity(
                    activeProfileId = restored.id,
                    lastUrl = restored.url,
                    isDesktopMode = restored.useDesktopUserAgent,
                    isBackgroundKeepAlive = true,
                ),
            )
        } else if (deleteDraft) {
            val cur = activeSessionDao.getActiveSession()
            if (cur?.activeProfileId == draftId) {
                activeSessionDao.clear()
            }
        }
    }

    suspend fun ensureLocalhostProfileAndActivate(port: Int = 8080): ServerProfile {
        val all = dao.getAllOnce()
        val existingLocal = all.firstOrNull { profile ->
            val lower = profile.url.lowercase()
            lower.contains("127.0.0.1") || lower.contains("localhost")
        }
        if (existingLocal != null) {
            dao.activateProfile(existingLocal.id)
            val refreshed = decryptProfile(dao.getById(existingLocal.id) ?: existingLocal.copy(isActive = true))
            activeSessionDao.upsert(
                ActiveSessionEntity(
                    activeProfileId = refreshed.id,
                    lastUrl = refreshed.url,
                    isDesktopMode = refreshed.useDesktopUserAgent,
                    isBackgroundKeepAlive = true,
                )
            )
            return refreshed
        }
        return saveAndActivate(
            ServerProfile(
                name = "AI & Termux Localhost :$port",
                url = "http://127.0.0.1:$port",
                connectionMode = ConnectionMode.DIRECT_CODE_SERVER,
                useDesktopUserAgent = false,
                autoConnectOnLaunch = true,
                useMasterAutoLogin = true,
                isPinned = true,
                isActive = true,
                provisioningState = WorkspaceProvisioningState.NOT_STARTED,
                runtimeKind = LocalhostRuntimeKind.EMBEDDED_EDITOR,
            )
        )
    }

    suspend fun saveAndActivate(profile: ServerProfile): ServerProfile {
        val encrypted = encryptProfile(profile)
        val insertedId = if (profile.id == 0L) {
            dao.insert(encrypted)
        } else {
            dao.update(encrypted)
            profile.id
        }
        dao.activateProfile(insertedId)
        val activated = decryptProfile(dao.getById(insertedId) ?: encrypted.copy(id = insertedId, isActive = true))
        activeSessionDao.upsert(
            ActiveSessionEntity(
                activeProfileId = activated.id,
                lastUrl = activated.url,
                isDesktopMode = activated.useDesktopUserAgent,
                isBackgroundKeepAlive = true,
            )
        )
        return decryptProfile(activated)
    }

    suspend fun activateProfile(id: Long) {
        dao.activateProfile(id)
        dao.getById(id)?.let { profile ->
            activeSessionDao.upsert(
                ActiveSessionEntity(
                    activeProfileId = profile.id,
                    lastUrl = profile.url,
                    isDesktopMode = profile.useDesktopUserAgent,
                    isBackgroundKeepAlive = true,
                )
            )
        }
    }

    suspend fun persistSessionUrl(profileId: Long, currentUrl: String, isDesktopMode: Boolean) {
        if (profileId <= 0L || currentUrl.isBlank()) return
        activeSessionDao.upsert(
            ActiveSessionEntity(
                activeProfileId = profileId,
                lastUrl = currentUrl,
                isDesktopMode = isDesktopMode,
                isBackgroundKeepAlive = true,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun togglePin(profile: ServerProfile) {
        dao.update(encryptProfile(profile.copy(isPinned = !profile.isPinned)))
    }

    suspend fun deleteProfile(id: Long) {
        val currentSession = activeSessionDao.getActiveSession()
        if (currentSession?.activeProfileId == id) {
            activeSessionDao.clear()
        }
        dao.deleteById(id)
    }

    private fun decryptProfile(profile: ServerProfile): ServerProfile =
        profile.copy(passwordHint = SecureVault.decryptSecret(profile.passwordHint))
}
