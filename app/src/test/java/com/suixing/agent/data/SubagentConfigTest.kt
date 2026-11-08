package com.suixing.agent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubagentConfigTest {
    private val catalogue = setOf("read_file", "write_file", "run_shell", "bash", "ui_virtual_screen")
    private val parentProvider = ProviderConfig(id = "parent", baseUrl = "https://parent.example/v1",
        apiKey = "parent-secret", enabled = listOf("parent-model", "assistant-model"))
    private val childProvider = ProviderConfig(id = "child", baseUrl = "https://child.example/v1",
        apiKey = "child-secret", enabled = listOf("child-model"), protocol = PROTOCOL_ANTHROPIC)
    private val childAssistant = Assistant(id = "child-assistant", name = "Reviewer",
        systemPrompt = "Review the supplied context.", providerId = parentProvider.id, model = "assistant-model",
        disabledTools = listOf("write_file"), createdAt = 1L)

    private fun parentSettings() = AppSettings(baseUrl = parentProvider.baseUrl, apiKey = parentProvider.apiKey,
        model = "parent-model", systemPrompt = "Parent instructions.", approvalMode = ApprovalMode.ASK,
        maxToolRounds = 12, providers = listOf(parentProvider, childProvider),
        assistants = listOf(Assistant(id = "parent-assistant", name = "Parent", createdAt = 1L), childAssistant),
        activeAssistantId = "parent-assistant", requestHeaders = mapOf("X-Parent" to "parent-header-secret"))

    private fun args(raw: String) = Json.parseToJsonElement(raw) as JsonObject

    @Test fun `native bash is subject to the effective child tool whitelist`() {
        val parent = parentSettings()
        val allowed = parent.resolveSubagentConfig(SubagentLaunchOptions(allowedTools = listOf("bash")), catalogue).config
        assertEquals(listOf("bash"), allowed.allowedTools)
        assertNull(allowed.denialReason("bash", args("""{"command":"printf native"}""")))
        assertNotNull(allowed.denialReason("run_shell", JsonObject(emptyMap())))
        val denied = parent.resolveSubagentConfig(SubagentLaunchOptions(allowedTools = listOf("read_file")), catalogue).config
        assertNotNull(denied.denialReason("bash", args("""{"command":"printf native"}""")))
    }

    @Test
    fun `parent catalogue template launch and assistant restrictions all constrain execution`() {
        val profile = SubagentProfile(id = "restricted", assistantId = childAssistant.id,
            approvalMode = ApprovalMode.DENY,
            allowedTools = listOf("read_file", "write_file", "ui_virtual_screen", "outside_parent"),
            allowedActions = mapOf("ui_virtual_screen" to listOf("status", "screenshot")))
        val parent = parentSettings().copy(subagentProfiles = listOf(profile))
        val child = parent.resolveSubagentConfig(SubagentLaunchOptions(profileId = profile.id,
            approvalMode = ApprovalMode.AUTO,
            allowedTools = listOf("write_file", "run_shell", "ui_virtual_screen", "outside_parent"),
            allowedActions = mapOf("ui_virtual_screen" to listOf("screenshot", "close"))), catalogue)

        assertEquals(listOf("ui_virtual_screen"), child.config.allowedTools)
        assertEquals(mapOf("ui_virtual_screen" to listOf("screenshot")), child.config.allowedActions)
        assertEquals(ApprovalMode.DENY, child.settings.approvalMode)
        assertNull(child.config.denialReason("ui_virtual_screen", args("""{"action":"screenshot"}""")))
        assertNotNull(child.config.denialReason("ui_virtual_screen", args("""{"action":"close"}""")))
        assertNotNull(child.config.denialReason("ui_virtual_screen", JsonObject(emptyMap())))
        for (tool in listOf("read_file", "write_file", "run_shell", "outside_parent")) {
            assertNotNull(child.config.denialReason(tool, JsonObject(emptyMap())))
        }
    }

    @Test
    fun `clearing typed draft choices restores inheritance rather than saved or legacy child choices`() {
        val selection = SubagentModelSelection(childProvider.id, "child-model")
        val saved = SubagentProfile(id = "saved", assistantId = childAssistant.id, model = selection,
            approvalMode = ApprovalMode.DENY, allowedTools = emptyList(), maxToolRounds = 3,
            allowedActions = mapOf("ui_virtual_screen" to emptyList()), extraInstructions = "Saved instruction.")
        val parent = parentSettings().copy(subagentProfiles = listOf(saved),
            defaultSubagentProfileId = saved.id, subagentModel = selection)
        val cleared = SubagentProfile(id = saved.id, name = "This launch")
        val child = parent.resolveSubagentConfig(SubagentLaunchOptions(profileId = saved.id,
            draftProfile = cleared), catalogue)

        assertEquals(catalogue.sorted(), child.config.allowedTools)
        assertEquals(emptyMap<String, List<String>>(), child.config.allowedActions)
        assertEquals(parent.model, child.settings.model)
        assertEquals(parent.baseUrl, child.settings.baseUrl)
        assertEquals(parent.activeAssistantId, child.config.assistantId)
        assertEquals(parent.approvalMode, child.config.approvalMode)
        assertEquals(parent.maxToolRounds, child.config.maxToolRounds)
        assertEquals(parent.systemPrompt, child.settings.systemPrompt)
        assertEquals(parent.requestHeaders, child.settings.requestHeaders)
        assertEquals(saved, parent.subagentProfiles.single())
    }

    @Test
    fun `a fresh draft binds assistant and model without mutating the parent or retaining its credentials`() {
        val parent = parentSettings()
        val before = parent.copy()
        val draft = SubagentProfile(id = "new-after-parent-started", name = "Review task",
            assistantId = childAssistant.id, model = SubagentModelSelection(childProvider.id, "child-model"),
            approvalMode = ApprovalMode.AUTO, extraInstructions = "Use concise findings.")
        val child = parent.resolveSubagentConfig(SubagentLaunchOptions(profileId = draft.id,
            draftProfile = draft), catalogue)

        assertEquals(childAssistant.id, child.settings.activeAssistantId)
        assertEquals(childAssistant.name, child.config.assistantName)
        assertEquals(childProvider.id, child.config.providerId)
        assertEquals("child-model", child.config.model)
        assertEquals(childProvider.baseUrl, child.settings.baseUrl)
        assertEquals(childProvider.apiKey, child.settings.apiKey)
        assertEquals(PROTOCOL_ANTHROPIC, child.settings.protocol)
        assertEquals(ApprovalMode.ASK, child.config.approvalMode)
        assertEquals(emptyMap<String, String>(), child.settings.requestHeaders)
        assertEquals("Review the supplied context.\n\nUse concise findings.", child.settings.systemPrompt)
        assertFalse("write_file" in child.config.allowedTools)
        assertEquals(before, parent)
        val retained = Json.encodeToString(ChildRunConfig.serializer(), child.config)
        for (secret in listOf("parent-secret", "child-secret", "parent-header-secret")) {
            assertFalse(retained.contains(secret))
        }
    }

    @Test
    fun `round budgets honor the template but launch overrides never enlarge the parent ceiling`() {
        val profile = SubagentProfile(id = "limited", maxToolRounds = 4)
        val parent = parentSettings().copy(maxToolRounds = 7, subagentProfiles = listOf(profile))
        assertEquals(4, parent.resolveSubagentConfig(SubagentLaunchOptions(profileId = profile.id), catalogue).config.maxToolRounds)
        assertEquals(7, parent.resolveSubagentConfig(SubagentLaunchOptions(profileId = "", maxToolRounds = 30), catalogue).config.maxToolRounds)
        assertEquals(7, parent.resolveSubagentConfig(SubagentLaunchOptions(profileId = "", maxToolRounds = 0), catalogue).config.maxToolRounds)
        assertEquals(2, parent.resolveSubagentConfig(SubagentLaunchOptions(profileId = "", maxToolRounds = 2), catalogue).config.maxToolRounds)
        val unlimited = parent.copy(maxToolRounds = 0)
        assertEquals(4, unlimited.resolveSubagentConfig(SubagentLaunchOptions(profileId = profile.id), catalogue).config.maxToolRounds)
        assertEquals(0, unlimited.resolveSubagentConfig(SubagentLaunchOptions(profileId = "", maxToolRounds = 0), catalogue).config.maxToolRounds)
    }

    @Test
    fun `empty tools are text only and disjoint action restrictions permit no action`() {
        val profile = SubagentProfile(id = "actions", allowedTools = listOf("read_file", "ui_virtual_screen"),
            allowedActions = mapOf("ui_virtual_screen" to listOf("status")))
        val parent = parentSettings().copy(subagentProfiles = listOf(profile))
        val textOnly = parent.resolveSubagentConfig(SubagentLaunchOptions(profileId = profile.id,
            allowedTools = emptyList()), catalogue).config
        assertEquals(emptyList<String>(), textOnly.allowedTools)
        assertNotNull(textOnly.denialReason("read_file", JsonObject(emptyMap())))
        val actions = parent.resolveSubagentConfig(SubagentLaunchOptions(profileId = profile.id,
            allowedActions = mapOf("ui_virtual_screen" to listOf("close"))), catalogue).config
        assertEquals(emptyList<String>(), actions.allowedActions.getValue("ui_virtual_screen"))
        assertNull(actions.denialReason("read_file", JsonObject(emptyMap())))
        for (action in listOf("status", "close")) {
            assertNotNull(actions.denialReason("ui_virtual_screen", args("""{"action":"$action"}""")))
        }
    }
}
