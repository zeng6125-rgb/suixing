package com.suixing.agent.ui.settings

import com.suixing.agent.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingSettingsWritesTest {
    @Test fun `typing coalesces each field and preserves settings changed elsewhere`() {
        val pending = PendingSettingsWrites()
        pending.put("endpoint") { it.copy(searchEndpoint = "h") }
        pending.put("key") { it.copy(searchApiKey = "key") }
        pending.put("endpoint") { it.copy(searchEndpoint = "https://host/search") }
        val result = pending.take()!!(AppSettings(themeMode = 2))
        assertEquals("https://host/search", result.searchEndpoint)
        assertEquals("key", result.searchApiKey)
        assertEquals(2, result.themeMode)
        assertNull(pending.take())
    }

    @Test fun `leaving after dispatch does not resend a batch and later edits remain independent`() {
        val pending = PendingSettingsWrites()
        pending.put("key") { it.copy(searchApiKey = "first") }
        val dispatched = pending.take()!!
        assertNull(pending.take())
        pending.put("key") { it.copy(searchApiKey = "second") }
        val later = pending.take()!!
        assertEquals("first", dispatched(AppSettings()).searchApiKey)
        assertEquals("second", later(AppSettings()).searchApiKey)
    }
}
