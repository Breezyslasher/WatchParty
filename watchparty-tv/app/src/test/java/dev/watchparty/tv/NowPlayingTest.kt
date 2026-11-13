package dev.watchparty.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTest {

    // shortened from the Sony Android TV 9's logcat while Disney+ played Iron Man
    private fun bam(path: String) =
        "11-13 16:23:39.779  7634  7755 I [BAM-SDK]r8-map-id-686072a5: urn:bamtech:dust:bamsdk:api:explore:query " +
            "(EdgeEvent(startTime=2026-11-13T21:23:39.343Z, services=[ServiceInteraction(request=ServiceRequest(" +
            "host=disney.api.edge.bamgrid.com, path=$path, method=GET), response=ServiceResponse(statusCode=200)))"

    private val ironMan = "0c520152-b81f-4c20-9310-003debd1947e"
    private val thunderbolts = "f51dce23-7d8f-490a-9f0c-be1b5432c2a9"

    private val homeThenIronMan = listOf(
        bam("/explore/v1.21/page/page-1253c06b-6608-439c-bb79-268ed85b5457"),
        bam("/explore/v1.21/deeplink"),
        bam("/explore/v1.21/partner/continueWatching"),
        bam("/explore/v1.21/page/entity-$ironMan"),
        bam("/explore/v1.21/playerExperience/d36085bf-6375-479c-9a42-05f8e89d9625"),  // not a title id (Error 41)
        bam("/explore/v1.21/upNext"),
    )

    @Test fun findsTheTitlePageIdNotHomePagesOrPlayerIds() {
        assertEquals(ironMan, NowPlaying.latestDisneyEntity(homeThenIronMan))
    }

    @Test fun theMostRecentTitleWins() {
        assertEquals(thunderbolts, NowPlaying.latestDisneyEntity(homeThenIronMan + bam("/explore/v1.21/page/entity-$thunderbolts")))
    }

    @Test fun otherAppsLinesAreIgnored() {
        val other = "11-13 16:23:40.000  1234  1234 I SomeApp: opened https://example.com/page/entity-$thunderbolts"
        assertNull(NowPlaying.latestDisneyEntity(listOf(other)))
    }

    @Test fun disneyWithItsIdInTheLogSharesAPlayLink() {
        val played = NowPlaying.Played(MediaLink.DISNEY_PACKAGE, "Iron Man", 1_320_000, 0)
        val s = NowPlaying.shareFor(played, homeThenIronMan)
        assertEquals("https://www.disneyplus.com/play/$ironMan", s.link)
        assertEquals(MediaLink.DISNEY_PACKAGE, s.knownPackage)
        assertEquals("Iron Man", s.label)
    }

    @Test fun withoutAnIdItSharesTheTitleOnly() {
        val played = NowPlaying.Played("com.netflix.ninja", "Greyhound & Friends?", 0, 0)
        val s = NowPlaying.shareFor(played, emptyList())
        assertTrue(NowPlaying.isTitleOnly(s.link))
        assertTrue(s.link.startsWith("https://"))       // so Kodi members skip it like any web link
        assertEquals("Greyhound & Friends?", NowPlaying.titleOf(s.link))
        assertEquals("com.netflix.ninja", s.knownPackage)
        // Disney+ without readable logs falls back the same way
        assertTrue(NowPlaying.isTitleOnly(NowPlaying.shareFor(
            NowPlaying.Played(MediaLink.DISNEY_PACKAGE, "Iron Man", 0, 0), emptyList()).link))
    }

    @Test fun realLinksAreNotTitleOnly() {
        assertFalse(NowPlaying.isTitleOnly("https://www.disneyplus.com/play/$ironMan"))
        assertNull(NowPlaying.titleOf("https://www.disneyplus.com/play/$ironMan"))
    }
}
