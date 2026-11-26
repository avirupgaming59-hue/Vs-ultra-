package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.ActiveSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActiveSessionDao {
    @Query("SELECT * FROM active_session WHERE id = 1 LIMIT 1")
    fun observeActiveSession(): Flow<ActiveSessionEntity?>

    @Query("SELECT * FROM active_session WHERE id = 1 LIMIT 1")
    suspend fun getActiveSession(): ActiveSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ActiveSessionEntity)

    @Query("DELETE FROM active_session")
    suspend fun clear()
}
