package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.ActiveSessionEntity
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ConnectionMode
import com.example.data.model.LocalhostRuntimeKind
import com.example.data.model.ServerProfile
import com.example.data.model.WorkspaceProvisioningState

class ProfileTypeConverters {
    @TypeConverter
    fun fromConnectionMode(mode: ConnectionMode): String = mode.name

    @TypeConverter
    fun toConnectionMode(value: String): ConnectionMode =
        runCatching { ConnectionMode.valueOf(value) }.getOrDefault(ConnectionMode.TAILSCALE_CUSTOM)

    @TypeConverter
    fun fromProvisioningState(state: WorkspaceProvisioningState): String = state.name

    @TypeConverter
    fun toProvisioningState(value: String): WorkspaceProvisioningState =
        runCatching { WorkspaceProvisioningState.valueOf(value) }
            .getOrDefault(WorkspaceProvisioningState.NOT_STARTED)

    @TypeConverter
    fun fromRuntimeKind(kind: LocalhostRuntimeKind): String = kind.name

    @TypeConverter
    fun toRuntimeKind(value: String): LocalhostRuntimeKind =
        runCatching { LocalhostRuntimeKind.valueOf(value) }
            .getOrDefault(LocalhostRuntimeKind.EMBEDDED_EDITOR)
}

@Database(
    entities = [
        ServerProfile::class,
        ActiveSessionEntity::class,
        ChatMessageEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(ProfileTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverProfileDao(): ServerProfileDao
    abstract fun activeSessionDao(): ActiveSessionDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private fun readColumnNames(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val cols = linkedSetOf<String>()
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                if (nameIdx >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(nameIdx)?.let { cols.add(it) }
                    }
                }
            }
            return cols
        }

        private fun addMissingColumns(
            db: SupportSQLiteDatabase,
            tableName: String,
            columns: List<Pair<String, String>>,
        ) {
            val existing = readColumnNames(db, tableName)
            for ((columnName, definition) in columns) {
                if (!existing.contains(columnName)) {
                    db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $definition")
                }
            }
        }

        internal fun upgradeToV4(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `server_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `connectionMode` TEXT NOT NULL, `passwordHint` TEXT NOT NULL, `useDesktopUserAgent` INTEGER NOT NULL, `autoConnectOnLaunch` INTEGER NOT NULL, `useMasterAutoLogin` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `lastConnectedAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `provisioningState` TEXT NOT NULL, `runtimeKind` TEXT NOT NULL, `lastError` TEXT NOT NULL, `workspaceSlug` TEXT NOT NULL)",
            )
            addMissingColumns(
                db = db,
                tableName = "server_profiles",
                columns = listOf(
                    "connectionMode" to "TEXT NOT NULL DEFAULT 'TAILSCALE_CUSTOM'",
                    "passwordHint" to "TEXT NOT NULL DEFAULT ''",
                    "useDesktopUserAgent" to "INTEGER NOT NULL DEFAULT 1",
                    "autoConnectOnLaunch" to "INTEGER NOT NULL DEFAULT 1",
                    "useMasterAutoLogin" to "INTEGER NOT NULL DEFAULT 0",
                    "isPinned" to "INTEGER NOT NULL DEFAULT 0",
                    "isActive" to "INTEGER NOT NULL DEFAULT 0",
                    "lastConnectedAt" to "INTEGER NOT NULL DEFAULT 0",
                    "createdAt" to "INTEGER NOT NULL DEFAULT 0",
                    "provisioningState" to "TEXT NOT NULL DEFAULT 'NOT_STARTED'",
                    "runtimeKind" to "TEXT NOT NULL DEFAULT 'EMBEDDED_EDITOR'",
                    "lastError" to "TEXT NOT NULL DEFAULT ''",
                    "workspaceSlug" to "TEXT NOT NULL DEFAULT ''",
                ),
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `active_session` (`id` INTEGER NOT NULL, `activeProfileId` INTEGER NOT NULL, `lastUrl` TEXT NOT NULL, `isDesktopMode` INTEGER NOT NULL, `isBackgroundKeepAlive` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            addMissingColumns(
                db = db,
                tableName = "active_session",
                columns = listOf(
                    "activeProfileId" to "INTEGER NOT NULL DEFAULT 0",
                    "lastUrl" to "TEXT NOT NULL DEFAULT ''",
                    "isDesktopMode" to "INTEGER NOT NULL DEFAULT 1",
                    "isBackgroundKeepAlive" to "INTEGER NOT NULL DEFAULT 1",
                    "updatedAt" to "INTEGER NOT NULL DEFAULT 0",
                ),
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chat_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, `modelName` TEXT NOT NULL, `rolePresetName` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)",
            )
            addMissingColumns(
                db = db,
                tableName = "chat_messages",
                columns = listOf(
                    "role" to "TEXT NOT NULL DEFAULT 'user'",
                    "text" to "TEXT NOT NULL DEFAULT ''",
                    "modelName" to "TEXT NOT NULL DEFAULT 'gemini-4-hydrogen'",
                    "rolePresetName" to "TEXT NOT NULL DEFAULT 'General'",
                    "timestamp" to "INTEGER NOT NULL DEFAULT 0",
                ),
            )

            // Preserve existing active or non-localhost cloud profiles accurately without losing rows
            db.execSQL(
                "UPDATE server_profiles SET runtimeKind = 'REMOTE_CLOUD_IDE' WHERE url LIKE 'https://%' OR connectionMode = 'CLOUD_IDE_WEB'",
            )
            db.execSQL(
                "UPDATE server_profiles SET provisioningState = 'READY' WHERE isActive = 1 OR lastConnectedAt > 0",
            )
        }

        val MIGRATION_1_4 = object : Migration(1, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                upgradeToV4(db)
            }
        }

        val MIGRATION_2_4 = object : Migration(2, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                upgradeToV4(db)
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                upgradeToV4(db)
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vsshell_database",
                )
                    .addMigrations(MIGRATION_1_4, MIGRATION_2_4, MIGRATION_3_4)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }

        internal fun resetInstanceForRecovery() {
            synchronized(this) {
                runCatching { instance?.close() }
                instance = null
            }
        }
    }
}
