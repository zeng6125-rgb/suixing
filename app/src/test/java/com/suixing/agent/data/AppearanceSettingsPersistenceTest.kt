package com.suixing.agent.data

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppearanceSettingsPersistenceTest {
    @get:Rule val folder = TemporaryFolder()
    private val owners = mutableListOf<CoroutineScope>()

    private fun owner() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { owners += it }

    private fun preferences(scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(
            fileSystem = WindowsSafeFileSystem,
            serializer = PreferencesSerializer,
            producePath = { File(folder.root, "settings.preferences_pb").absolutePath.toPath() },
        ),
        scope = scope,
    )

    @After fun closeScopes() = runBlocking {
        owners.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun `new install keeps flat standard replies after a settings write and restart`() = runBlocking<Unit> {
        val firstOwner = owner()
        val repository = SettingsRepository(preferences(firstOwner))
        val initial = repository.current()
        assertFalse(initial.assistantCard)
        assertEquals(1, initial.bubbleWidth)

        assertTrue(repository.update { it.copy(themeMode = 2) })
        firstOwner.coroutineContext[Job]!!.cancelAndJoin()
        val reopened = SettingsRepository(preferences(owner())).current()
        assertFalse(reopened.assistantCard)
        assertEquals(1, reopened.bubbleWidth)
        assertEquals(2, reopened.themeMode)
    }

    @Test fun `stored card and width choices survive the new defaults`() = runBlocking<Unit> {
        val firstOwner = owner()
        val data = preferences(firstOwner)
        data.edit {
            it[stringPreferencesKey("settings_json")] = """{"assistantCard":true,"bubbleWidth":2}"""
        }
        val repository = SettingsRepository(data)
        assertTrue(repository.update { it.copy(fontScale = 3) })
        firstOwner.coroutineContext[Job]!!.cancelAndJoin()

        val reopened = SettingsRepository(preferences(owner())).current()
        assertTrue(reopened.assistantCard)
        assertEquals(2, reopened.bubbleWidth)
        assertEquals(3, reopened.fontScale)
    }

    @Test fun `miuix ui theme and font preset persist independently of paletteMode`() = runBlocking<Unit> {
        val firstOwner = owner()
        val repository = SettingsRepository(preferences(firstOwner))
        assertEquals(FONT_PRESET_JAGENT, repository.current().fontPreset)
        assertTrue(repository.update { it.copy(uiTheme = 1, paletteMode = 4, themeMode = 2, fontPreset = FONT_PRESET_SYSTEM) })
        firstOwner.coroutineContext[Job]!!.cancelAndJoin()

        val reopened = SettingsRepository(preferences(owner())).current()
        assertEquals(1, reopened.uiTheme)
        assertEquals(4, reopened.paletteMode)
        assertEquals(2, reopened.themeMode)
        assertEquals(FONT_PRESET_SYSTEM, reopened.fontPreset)
    }
}
