package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "active_session")
data class ActiveSessionEntity(
    @PrimaryKey val id: Int = 1,
    val activeProfileId: Long,
    val lastUrl: String,
    val isDesktopMode: Boolean = true,
    val isBackgroundKeepAlive: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)
