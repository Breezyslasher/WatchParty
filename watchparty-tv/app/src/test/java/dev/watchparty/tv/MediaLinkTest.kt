package dev.watchparty.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaLinkTest {

    private val id = "f51dce23-7d8f-490a-9f0c-be1b5432c2a9"
    private val play = "https://www.disneyplus.com/play/$id"

    @Test fun disneyPageLinkBecomesPlayLink() {
        val s = MediaLink.normalize("https://www.disneyplus.com/browse/entity-$id")!!
        assertEquals(play, s.link)
        assertEquals(MediaLink.DISNEY_PACKAGE, s.knownPackage)
    }

    @Test fun disneyPlayLinkDropsTrackingParameters() {
        assertEquals(play, MediaLink.normalize("$play?contextId=EiR1cm46ZXZlbnQ6aW5mb2Jsb2Nr%3D")!!.link)
    }

    @Test fun disneyRegionalAndBareDomain() {
        assertEquals(play, MediaLink.normalize("https://disneyplus.com/en-gb/browse/entity-$id")!!.link)
        assertEquals(play, MediaLink.normalize("  https://www.disneyplus.com/play/${id.uppercase()}  ")!!.link)
    }

    @Test fun disneyLaunchesTheRoomLinkAsIs() {
        assertEquals(MediaLink.Launch(play), MediaLink.launchFor(play, MediaLink.DISNEY_PACKAGE))
    }

    // the links below are the ones tested on a Sony Android TV 9

    @Test fun starzPageAndPlayLinksBecomeThePlayLink() {
        val playLink = "https://www.starz.com/us/en/play/72168"
        assertEquals(playLink, MediaLink.normalize("https://www.starz.com/us/en/movies/72168")!!.link)
        assertEquals(playLink, MediaLink.normalize(playLink)!!.link)
        assertNull(MediaLink.normalize(playLink)!!.knownPackage)   // Android finds the app
    }

    @Test fun netflixTitleAndWatchLinksBecomeTheWatchLink() {
        val watch = "https://www.netflix.com/watch/82700085"
        assertEquals(watch, MediaLink.normalize("https://www.netflix.com/title/82700085")!!.link)
        assertEquals(watch, MediaLink.normalize(
            "https://www.netflix.com/watch/82700085?trackId=14277281&tctx=-97%2C-97%2C%2C%2C%2C%2C%2C%2C%2CVideo%3A82700085%2CdetailsPagePlayButton%2C"
        )!!.link)
    }

    @Test fun netflixTvAppLaunchesTheWatchLinkWithSource30() {
        val launch = MediaLink.launchFor("https://www.netflix.com/watch/82700085", MediaLink.NETFLIX_TV_PACKAGE)
        assertEquals("https://www.netflix.com/watch/82700085", launch.uri)
        assertEquals(mapOf("source" to "30"), launch.extras)
        // another Netflix app (e.g. on a phone) gets the plain link
        assertEquals(MediaLink.Launch("https://www.netflix.com/watch/82700085"),
            MediaLink.launchFor("https://www.netflix.com/watch/82700085", "com.netflix.mediaclient"))
    }

    @Test fun hboWatchLinksMoveToTheDomainTheAppClaims() {
        val s = MediaLink.normalize("https://www.hbomax.com/video/watch/e95435b2-2c89-4788-b711-b7f346f03da9")!!
        assertEquals("https://play.hbomax.com/video/watch/e95435b2-2c89-4788-b711-b7f346f03da9", s.link)
        assertEquals(MediaLink.HBO_PACKAGE, s.knownPackage)
        val two = "e95435b2-2c89-4788-b711-b7f346f03da9/6ce5965d-cdb2-4f9c-b22b-ae7a091d95a8"
        assertEquals("https://play.hbomax.com/video/watch/$two",
            MediaLink.normalize("https://play.max.com/video/watch/$two?utm_source=x")!!.link)
    }

    @Test fun hboShowPagesAreNotRewritten() {
        val show = "https://www.hbomax.com/show/6ce5965d-cdb2-4f9c-b22b-ae7a091d95a8?utm_source=universal_search"
        assertEquals(show, MediaLink.normalize(show)!!.link)
        assertNull(MediaLink.normalize(show)!!.knownPackage)
    }

    @Test fun crunchyrollKeepsACleanWebLinkAndOpensTheApp() {
        val s = MediaLink.normalize(
            "https://www.crunchyroll.com/watch/G9DU92MQK/one-piece-film-strong-world?irclickid=2kORXdV&utm_source=impact"
        )!!
        assertEquals("https://www.crunchyroll.com/watch/G9DU92MQK/one-piece-film-strong-world", s.link)
        assertEquals(MediaLink.CRUNCHYROLL_PACKAGE, s.knownPackage)
        assertEquals(MediaLink.Launch("crunchyroll://watch/G9DU92MQK"),
            MediaLink.launchFor(s.link, MediaLink.CRUNCHYROLL_PACKAGE))
    }

    @Test fun appleTvMovieLinksGetActionPlayAndLoseTracking() {
        val s = MediaLink.normalize(
            "https://tv.apple.com/us/movie/greyhound/umc.cmc.o5z5ztufuu3uv8lx7m0jcega?at=1000l3V2&ct=app_tvplus&itscg=30200&itsct=justwatch_tv"
        )!!
        assertEquals("https://tv.apple.com/us/movie/greyhound/umc.cmc.o5z5ztufuu3uv8lx7m0jcega?action=play", s.link)
        assertEquals(MediaLink.APPLE_TV_PACKAGE, s.knownPackage)
        // already has action=play: unchanged
        assertEquals(s.link, MediaLink.normalize(s.link)!!.link)
        assertEquals(MediaLink.Launch(s.link), MediaLink.launchFor(s.link, MediaLink.APPLE_TV_PACKAGE))
    }

    @Test fun appleTvShowLinksAreNotRewritten() {
        val show = "https://tv.apple.com/us/show/severance/umc.cmc.1srk2goyh2q2zdxcx605w8vtx"
        assertEquals(show, MediaLink.normalize(show)!!.link)
    }

    @Test fun otherLinksPassThroughUnchanged() {
        val raw = "https://www.example-stream.com/watch/abc123?t=10"
        val s = MediaLink.normalize(raw)!!
        assertEquals(raw, s.link)
        assertNull(s.knownPackage)
        assertEquals("example-stream.com", s.label)
    }

    @Test fun nonLinksAreRejected() {
        assertNull(MediaLink.normalize("Thunderbolts"))
        assertNull(MediaLink.normalize("smb://nas/movie.mkv"))
    }
}
