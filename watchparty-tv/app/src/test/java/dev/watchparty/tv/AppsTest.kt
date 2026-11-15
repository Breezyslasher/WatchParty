package dev.watchparty.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsTest {

    @Test fun matchesAnAppToItsService() {
        assertTrue(Apps.sameService("STARZ", "Starz"))
        assertTrue(Apps.sameService("Paramount+", "Paramount Plus"))
        assertTrue(Apps.sameService("Peacock TV", "peacock"))      // the link's site name
        assertTrue(Apps.sameService("Disney+", "disneyplus"))
        assertTrue(Apps.sameService("Starz", "starz"))
    }

    @Test fun doesNotMatchOtherApps() {
        assertFalse(Apps.sameService("Max", "Maxdome"))              // short names match exactly
        assertFalse(Apps.sameService("Starz", "Netflix"))
        assertFalse(Apps.sameService("Prime Video", "Primer"))
        assertFalse(Apps.sameService("", "Starz"))
    }
}
