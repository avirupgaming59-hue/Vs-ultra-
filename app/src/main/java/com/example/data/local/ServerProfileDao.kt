package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.LocalhostRuntimeKind
import com.example.data.model.ServerProfile
import com.example.data.model.WorkspaceProvisioningState
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerProfileDao {
    @Query("SELECT * FROM server_profiles ORDER BY isActive DESC, isPinned DESC, lastConnectedAt DESC, createdAt DESC")
    fun observeAllProfiles(): Flow<List<ServerProfile>>

    @Query("SELECT * FROM server_profiles WHERE isActive = 1 LIMIT 1")
    fun observeActiveProfile(): Flow<ServerProfile?>

    @Query("SELECT COUNT(*) FROM server_profiles")
    suspend fun count(): Int

    @Query("SELECT * FROM server_profiles")
    suspend fun getAllOnce(): List<ServerProfile>

    @Query("SELECT * FROM server_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ServerProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ServerProfile): Long

    @Update
    suspend fun update(profile: ServerProfile)

    @Query("DELETE FROM server_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE server_profiles SET isActive = 0")
    suspend fun clearAllActive()

    @Query("UPDATE server_profiles SET isActive = 1, lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun markActive(id: Long, timestamp: Long)

    @Query(
        "UPDATE server_profiles SET provisioningState = :state, lastError = :lastError WHERE id = :id",
    )
    suspend fun updateProvisioningState(
        id: Long,
        state: WorkspaceProvisioningState,
        lastError: String,
    )

    @Query(
        "UPDATE server_profiles SET url = :url, workspaceSlug = :slug, runtimeKind = :runtimeKind, provisioningState = :state, lastError = '', isActive = 1, lastConnectedAt = :timestamp WHERE id = :id",
    )
    suspend fun markCommittedReady(
        id: Long,
        url: String,
        slug: String,
        runtimeKind: LocalhostRuntimeKind,
        state: WorkspaceProvisioningState,
        timestamp: Long,
    )

    @Query("UPDATE server_profiles SET provisioningState = :state, lastError = :lastError, isActive = 0 WHERE id = :id")
    suspend fun markDraftFailedOrWaiting(
        id: Long,
        state: WorkspaceProvisioningState,
        lastError: String,
    )

    @Transaction
    suspend fun activateProfile(id: Long, timestamp: Long = System.currentTimeMillis()) {
        clearAllActive()
        markActive(id, timestamp)
    }

    @Transaction
    suspend fun commitReadyProfile(
        id: Long,
        url: String,
        slug: String,
        runtimeKind: LocalhostRuntimeKind,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        clearAllActive()
        markCommittedReady(
            id = id,
            url = url,
            slug = slug,
            runtimeKind = runtimeKind,
            state = WorkspaceProvisioningState.READY,
            timestamp = timestamp,
        )
    }

    @Transaction
    suspend fun rollbackDraftAndRestore(
        draftId: Long,
        previousActiveId: Long?,
        deleteDraft: Boolean,
        failureState: WorkspaceProvisioningState,
        errorMessage: String,
        restoreTimestamp: Long = System.currentTimeMillis(),
    ) {
        if (deleteDraft) {
            deleteById(draftId)
        } else {
            markDraftFailedOrWaiting(
                id = draftId,
                state = failureState,
                lastError = errorMessage,
            )
        }
        clearAllActive()
        if (previousActiveId != null && previousActiveId > 0L && previousActiveId != draftId) {
            markActive(previousActiveId, restoreTimestamp)
        }
    }
}
