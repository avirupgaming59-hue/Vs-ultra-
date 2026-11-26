package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiChatService
import com.example.data.local.AppDatabase
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ServerProfile
import com.example.data.repository.ServerProfileRepository
import com.example.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("VS Shell", appName)
    }

    @Test
    fun `firebase reports missing default app configuration`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(false, GeminiChatService.isFirebaseConfigured(context))
    }

    @Test
    fun `buildValidAlternatingHistory strips trailing user turn and duplicates`() {
        val raw = listOf(
            ChatMessageEntity(role = "model", text = "orphan model", modelName = com.example.ai.GeminiChatService.TARGET_MODEL_ID, rolePresetName = "General"),
            ChatMessageEntity(role = "user", text = "first user", modelName = com.example.ai.GeminiChatService.TARGET_MODEL_ID, rolePresetName = "General"),
            ChatMessageEntity(role = "user", text = "duplicate user", modelName = com.example.ai.GeminiChatService.TARGET_MODEL_ID, rolePresetName = "General"),
            ChatMessageEntity(role = "model", text = "first model", modelName = com.example.ai.GeminiChatService.TARGET_MODEL_ID, rolePresetName = "General"),
            ChatMessageEntity(role = "user", text = "pending failed user", modelName = com.example.ai.GeminiChatService.TARGET_MODEL_ID, rolePresetName = "General"),
        )
        val result = ChatViewModel.buildValidAlternatingHistory(raw)
        assertEquals(2, result.size)
        assertEquals("user", result[0].role)
        assertEquals("model", result[1].role)
    }

    @Test
    fun `server profile repository migrates legacy stackblitz url to project idx`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = ServerProfileRepository(db.serverProfileDao(), db.activeSessionDao())
            db.serverProfileDao().insert(
                ServerProfile(
                    name = "StackBlitz (Instant Terminal)",
                    url = "https://stackblitz.com",
                    useDesktopUserAgent = true,
                )
            )
            repo.migrateLegacyProfilesIfNeeded()
            val all = db.serverProfileDao().getAllOnce()
            assertEquals(1, all.size)
            assertEquals("https://idx.google.com", all.first().url)
            assertEquals(false, all.first().useDesktopUserAgent)
            assertTrue(all.first().name.contains("IDX"))
        } finally {
            db.close()
        }
    }
}
