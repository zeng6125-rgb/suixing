package com.suixing.agent.ui.chat

import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSendPolicyTest {

    private fun user(id: String) = StoredMessage(id = id, role = Roles.USER, text = id)

    @Test
    fun `a second send is refused until the first preparation finishes`() {
        val gate = SendPreparationGate()

        assertTrue(gate.tryBegin("a", busy = false))
        assertFalse("the same chat cannot prepare twice", gate.tryBegin("a", busy = false))
        assertTrue(gate.isPreparing("a"))

        gate.finish("a")
        assertFalse(gate.isPreparing("a"))
        assertTrue("the slot is reusable once released", gate.tryBegin("a", busy = false))
    }

    @Test
    fun `preparing one chat does not block another`() {
        val gate = SendPreparationGate()

        assertTrue(gate.tryBegin("a", busy = false))
        assertTrue(gate.tryBegin("b", busy = false))
        assertTrue(gate.isPreparing("a"))
        assertTrue(gate.isPreparing("b"))

        gate.finish("a")
        assertFalse(gate.isPreparing("a"))
        assertTrue(gate.isPreparing("b"))
    }

    @Test
    fun `a running or still-owned session is refused without taking the slot`() {
        val gate = SendPreparationGate()

        assertFalse(gate.tryBegin("a", busy = true))
        assertFalse(gate.isPreparing("a"))
        assertTrue(gate.tryBegin("a", busy = false))
    }

    @Test
    fun `an empty chat id is never prepared`() {
        val gate = SendPreparationGate()

        assertFalse(gate.tryBegin("", busy = false))
        assertFalse(gate.isPreparing(""))
    }


    @Test
    fun `a failed start restores the state captured before acceptance`() {
        val before = ChatUiState(conversationId = "a", messages = listOf(user("u1")), status = "已就绪")
        val sent = user("u2")
        val accepted = before.acceptSend(sent, "run-2")

        assertEquals(listOf("u1", "u2"), accepted.messages.map { it.id })
        assertEquals("run-2", accepted.activeRuns["u2"])
        assertEquals("正在思考中", accepted.status)

        val rolled = accepted.rollbackSend(before, sent.id, "run-2")
        assertEquals(before, rolled)
    }

    @Test
    fun `a late failure cannot erase a newer run on the same line`() {
        val before = ChatUiState(conversationId = "a")
        val sent = user("u2")
        val accepted = before.acceptSend(sent, "run-2")
        val newer = accepted.copy(activeRuns = accepted.activeRuns + (sent.id to "run-3"))

        assertEquals(newer, newer.rollbackSend(before, sent.id, "run-2"))
    }

    @Test
    fun `a rollback in another conversation leaves the screen alone`() {
        val before = ChatUiState(conversationId = "a", messages = listOf(user("u1")))
        val accepted = before.acceptSend(user("u2"), "run-2")
        val switched = ChatUiState(conversationId = "b", messages = listOf(user("b1")))

        assertEquals(switched, switched.rollbackSend(before, "u2", "run-2"))
    }


    @Test
    fun `only an accepted transcript turn moves the reading position`() {
        assertTrue(shouldJumpToLatest(accepted = true))
        assertFalse(shouldJumpToLatest(accepted = false))
    }

    @Test
    fun `an empty wire falls back to the window that already contains the line`() {
        val sent = user("u2")
        val shown = listOf(user("u1"), sent)

        assertEquals(shown, sendHistory(wire = null, shown = shown, user = sent))
        assertEquals(shown, sendHistory(wire = emptyList(), shown = shown, user = sent))
    }

    @Test
    fun `the line is appended only when the wire predates it`() {
        val older = user("u1")
        val sent = user("u2")

        assertEquals(
            listOf(older, sent),
            sendHistory(wire = listOf(older), shown = listOf(older, sent), user = sent),
        )
    }

    @Test
    fun `a wire that already ends with the line is not duplicated`() {
        val older = user("u1")
        val sent = user("u2")
        val wire = listOf(older, sent)

        assertEquals(wire, sendHistory(wire = wire, shown = listOf(older, sent), user = sent))
    }
}

class ChatSendEndpointTest {

    private fun settings(global: String, lan: String) = AppSettings(
        baseUrl = global,
        apiKey = "global-key",
        model = "global-model",
        providers = listOf(
            ProviderConfig(id = "lan", baseUrl = lan, apiKey = "lan-key"),
            ProviderConfig(id = "web", baseUrl = global, apiKey = "web-key"),
        ),
    )

    @Test
    fun `a conversation pick resolves to its own endpoint`() {
        val state = ChatUiState(providerId = "lan", model = "lan-model")

        assertEquals("http://192.168.1.5:8080/v1", effectiveBaseUrl(settings("https://api.example.com/v1", "http://192.168.1.5:8080/v1"), state))
    }

    @Test
    fun `without a pick the app default is used`() {
        val state = ChatUiState(providerId = null, model = null)

        assertEquals("https://api.example.com/v1", effectiveBaseUrl(settings("https://api.example.com/v1", "http://192.168.1.5:8080/v1"), state))
    }

    @Test
    fun `a legacy persona pin no longer moves the endpoint`() {
        // 助手不再钉模型（用户：模型只靠用户选、按会话隔离）。老数据里可能还留着 providerId，
        // 但它不再决定这一轮发去哪；只有会话自己的选择与全局默认两档。
        val pinned = Assistant(id = "a1", name = "LAN", providerId = "lan", model = "lan-model")
        val withPin = settings("https://api.example.com/v1", "http://192.168.1.5:8080/v1")
            .copy(assistants = listOf(pinned), activeAssistantId = "a1")

        assertEquals(
            "https://api.example.com/v1",
            effectiveBaseUrl(withPin, ChatUiState(assistantId = "a1")),
        )
        // The conversation's own pick still wins over the persona.
        assertEquals(
            "http://192.168.1.5:8080/v1",
            effectiveBaseUrl(withPin, ChatUiState(assistantId = "a1", providerId = "lan", model = "lan-model")),
        )
    }
}
