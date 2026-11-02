package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationTargetTest {
    @Test fun `a queued jump follows its message after earlier rows are inserted`() {
        assertEquals(3, navigationTargetIndex("chosen", mapOf("chosen" to 2), 1, null, 5))
        assertEquals(11, navigationTargetIndex("chosen", mapOf("chosen" to 10), 1, null, 14))
    }

    @Test fun `last page removing its header shifts the same message once`() {
        assertEquals(2, navigationTargetIndex("chosen", mapOf("chosen" to 2), 0, null, 6))
    }

    @Test fun `removed targets never fall through to a different message or newest turn`() {
        assertNull(navigationTargetIndex("removed", mapOf("replacement" to 2), 1, "live", 6))
        assertNull(navigationTargetIndex("live", emptyMap(), 0, null, -1))
    }

    @Test fun `live to settled handover retains the same target key`() {
        assertEquals(6, navigationTargetIndex("run", emptyMap(), 1, "run", 6))
        assertEquals(5, navigationTargetIndex("run", mapOf("run" to 4), 1, null, 6))
    }

    @Test fun `navigation frames respect motion duration scale and cap long frame gaps`() {
        assertEquals(8_333_333L, navigationScaledFrameDelta(16_666_667L, 2f))
        assertEquals(32_000_000L, navigationScaledFrameDelta(100_000_000L, 1f))
        assertEquals(32_000_000L, navigationScaledFrameDelta(100_000_000L, 0.5f))
        assertEquals(0L, navigationScaledFrameDelta(0L, 1f))
    }
}
