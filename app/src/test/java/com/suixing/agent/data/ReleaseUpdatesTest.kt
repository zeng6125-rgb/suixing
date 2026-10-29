package com.suixing.agent.data

import org.junit.Assert.*
import org.junit.Test

class ReleaseUpdatesTest {
    @Test fun `numeric version comparison does not confuse patch digits`() {
        assertEquals(ReleaseRelation.UPDATE_AVAILABLE, releaseRelation("0.8.99", "v0.8.545"))
        assertEquals(ReleaseRelation.LOCAL_AHEAD, releaseRelation("0.8.545", "v0.8.532"))
        assertEquals(ReleaseRelation.CURRENT, releaseRelation("0.8.545", "v0.8.545.0"))
        assertEquals(ReleaseRelation.UPDATE_AVAILABLE, releaseRelation("0.8.545", "1.0.0"))
    }

    @Test fun `missing or malformed release versions do not become an update`() {
        for (bad in listOf("", "vnext", "0..1", "0.8.-1", "999999999999.0.0")) {
            assertTrue(bad, runCatching { releaseRelation("0.8.545", bad) }.isFailure)
        }
    }

    private fun release(url: String = "${AppLinks.RELEASES}/tag/v0.8.546", extra: String = "") =
        """{"tag_name":"v0.8.546","html_url":"$url","body":"changes"$extra}"""

    @Test fun `official release parses while foreign download links and prereleases are rejected`() {
        assertEquals("v0.8.546", parseReleaseInfo(release()).version)
        assertTrue(runCatching { parseReleaseInfo(release("https://example.com/download")) }.isFailure)
        assertTrue(runCatching { parseReleaseInfo(release("https://github.com/other/repo/releases/tag/x")) }.isFailure)
        assertTrue(runCatching { parseReleaseInfo(release(extra = ",\"prerelease\":true")) }.isFailure)
    }
}
