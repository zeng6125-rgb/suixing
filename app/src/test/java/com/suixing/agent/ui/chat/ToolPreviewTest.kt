package com.suixing.agent.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ToolPreviewTest {
    @Test fun masksNestedCredentialsAndBearerHeaderWithoutChangingArguments() {
        val raw = """{"headers":{"Authorization":"Bearer abc123"},"api_key":"secret-key","path":"/workspace/project"}"""
        val preview = toolArgumentPreview("run_shell", raw)!!
        assertFalse(preview.contains("abc123"))
        assertFalse(preview.contains("secret-key"))
        assertTrue(preview.contains("/workspace/project"))
        assertTrue(raw.contains("secret-key"))
    }
    @Test fun shellAndUrlCredentialsAreMaskedAsPreviewText() {
        val preview = toolArgumentPreview("run_shell",
            """curl -H 'Authorization: Bearer abc123' 'https://alice:user-password@example.test/?token=token-value&x=1' API_KEY=secret-value""")!!
        for (secret in listOf("abc123", "token-value", "secret-value", "alice", "user-password")) assertFalse(preview.contains(secret))
        assertTrue(preview.contains("example.test"))
    }
    @Test fun ordinaryCallsAndNonSecretTokenLimitsRemainReadable() {
        assertEquals("""{"command":"ls -la","max_tokens":100}""",
            toolArgumentPreview("run_shell", """{"command":"ls -la","max_tokens":100}"""))
        assertNull(toolArgumentPreview("run_shell", "{}"))
    }
    @Test fun respectsExistingSensitiveToolRedaction() {
        assertFalse(toolArgumentPreview("ui_type", """{"text":"private-value"}""")!!.contains("private-value"))
    }
}
