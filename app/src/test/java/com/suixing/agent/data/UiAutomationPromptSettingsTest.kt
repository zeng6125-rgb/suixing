package com.suixing.agent.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UiAutomationPromptSettingsTest {
    @get:Rule val folder = TemporaryFolder()
    private val owners = mutableListOf<CoroutineScope>()
    private val key = stringPreferencesKey("settings_json")

    private fun owner() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { owners += it }

    private fun preferences(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            storage = OkioStorage(
                fileSystem = WindowsSafeFileSystem,
                serializer = PreferencesSerializer,
                producePath = { File(folder.root, "settings.preferences_pb").absolutePath.toPath() },
            ),
            scope = scope,
        )

    private fun assertExistingChoices(settings: AppSettings) {
        assertFalse(settings.edgeLight)
        assertFalse(settings.edgeLightInForeground)
        assertTrue(settings.floatingConsole)
        assertFalse(settings.consoleWhileRunning)
        assertEquals(380, settings.consoleWidthDp)
        assertEquals(260, settings.consoleHeightDp)
    }

    @After fun closeScopes() = runBlocking {
        owners.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun `disabled automation prompts survive other settings writes and a store restart`() = runBlocking<Unit> {
        val firstOwner = owner()
        val firstData = preferences(firstOwner)
        firstData.edit { it[key] = """
            {
              "uiAutomationPrompt": false,
              "uiAutomationPromptInForeground": false,
              "edgeLight": false,
              "edgeLightInForeground": false,
              "floatingConsole": true,
              "consoleWhileRunning": false,
              "consoleWidthDp": 380,
              "consoleHeightDp": 260
            }
        """.trimIndent() }
        val first = SettingsRepository(firstData)
        val loaded = first.current()
        assertExistingChoices(loaded)
        assertFalse(loaded.uiAutomationPrompt)
        assertFalse(loaded.uiAutomationPromptInForeground)
        assertTrue(first.save(loaded.copy(themeMode = 2)))
        assertTrue(first.update { it.copy(fontScale = 3) })
        firstOwner.coroutineContext[Job]!!.cancelAndJoin()

        val reopenedData = preferences(owner())
        val reopened = SettingsRepository(reopenedData).current()
        assertExistingChoices(reopened)
        assertFalse(reopened.uiAutomationPrompt)
        assertFalse(reopened.uiAutomationPromptInForeground)
        assertEquals(2, reopened.themeMode)
        assertEquals(3, reopened.fontScale)
        val persisted = Json.parseToJsonElement(reopenedData.data.first()[key]!!).jsonObject
        assertEquals(JsonPrimitive(false), persisted["uiAutomationPrompt"])
        assertEquals(JsonPrimitive(false), persisted["uiAutomationPromptInForeground"])
    }

    @Test fun `older settings keep automation prompts visible in foreground and background`() = runBlocking<Unit> {
        val firstOwner = owner()
        val firstData = preferences(firstOwner)
        firstData.edit { it[key] = """
            {
              "edgeLight": false,
              "edgeLightInForeground": false,
              "floatingConsole": true,
              "consoleWhileRunning": false,
              "consoleWidthDp": 380,
              "consoleHeightDp": 260
            }
        """.trimIndent() }
        val first = SettingsRepository(firstData)
        val loaded = first.current()
        assertExistingChoices(loaded)
        assertTrue(loaded.uiAutomationPrompt)
        assertTrue(loaded.uiAutomationPromptInForeground)
        assertTrue(first.update { it.copy(showTimestamps = false) })
        firstOwner.coroutineContext[Job]!!.cancelAndJoin()

        val reopenedData = preferences(owner())
        val reopened = SettingsRepository(reopenedData).current()
        assertExistingChoices(reopened)
        assertTrue(reopened.uiAutomationPrompt)
        assertTrue(reopened.uiAutomationPromptInForeground)
        assertFalse(reopened.showTimestamps)
        val persisted = Json.parseToJsonElement(reopenedData.data.first()[key]!!).jsonObject
        assertEquals(JsonPrimitive(true), persisted["uiAutomationPrompt"])
        assertEquals(JsonPrimitive(true), persisted["uiAutomationPromptInForeground"])
    }

    @Test fun `each prompt switch saves independently across store restarts`() = runBlocking<Unit> {
        var storeOwner = owner()
        var repository = SettingsRepository(preferences(storeOwner))
        assertTrue(repository.save(AppSettings(
            edgeLight = false,
            edgeLightInForeground = false,
            floatingConsole = true,
            consoleWhileRunning = false,
            consoleWidthDp = 380,
            consoleHeightDp = 260,
        )))
        for ((masterOn, showInForeground) in listOf(false to true, true to false)) {
            assertTrue(repository.update { it.copy(uiAutomationPrompt = masterOn) })
            assertTrue(repository.update { it.copy(uiAutomationPromptInForeground = showInForeground) })
            storeOwner.coroutineContext[Job]!!.cancelAndJoin()

            storeOwner = owner()
            val reopenedData = preferences(storeOwner)
            repository = SettingsRepository(reopenedData)
            val reopened = repository.current()
            assertExistingChoices(reopened)
            assertEquals(masterOn, reopened.uiAutomationPrompt)
            assertEquals(showInForeground, reopened.uiAutomationPromptInForeground)
            val persisted = Json.parseToJsonElement(reopenedData.data.first()[key]!!).jsonObject
            assertEquals(JsonPrimitive(masterOn), persisted["uiAutomationPrompt"])
            assertEquals(JsonPrimitive(showInForeground), persisted["uiAutomationPromptInForeground"])
        }
    }
}
