package dev.watchparty.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    @Test fun starzTvLinksHaveTheWebFormsThePhoneAppTakes() {
        // JustWatch ANDROID_TV / Yamtrack tv_link forms (2026-11-15)
        assertEquals(listOf("https://www.starz.com/us/en/play/72168", "https://www.starz.com/us/en/movies/72168"),
            MediaLink.alternatives("https://starz.com/movies/72168"))
        // an episode: its /play/ link (the page only opened it on a phone), then the page
        assertEquals(listOf("https://www.starz.com/us/en/play/73283", "https://www.starz.com/us/en/series/69342/episodes/73283"),
            MediaLink.alternatives("https://starz.com/series/69342/episodes/73283"))
        // web links and other services have none
        assertEquals(emptyList<String>(), MediaLink.alternatives("https://www.starz.com/us/en/play/72168"))
        assertEquals(emptyList<String>(), MediaLink.alternatives("https://www.disneyplus.com/play/x"))
    }

    @Test fun appleTvEpisodeLinksGetActionPlayAndKeepTheirIds() {
        // JustWatch's episode link (The Office S1 E1, from a phone's log 2026-11-15) plus tracking
        val s = MediaLink.normalize("https://tv.apple.com/us/episode/pilot/umc.cmc.5sc6v0g2nj8tk7nvyc7wejees" +
            "?showId=umc.cmc.52w04zy67tiv11p8xvbc57wmc&playableId=tvs.sbd.4000%3AA0070401001&at=1000l3V2&ct=app_tvplus")!!
        assertEquals("https://tv.apple.com/us/episode/pilot/umc.cmc.5sc6v0g2nj8tk7nvyc7wejees" +
            "?showId=umc.cmc.52w04zy67tiv11p8xvbc57wmc&playableId=tvs.sbd.4000%3AA0070401001&action=play", s.link)
        assertEquals(MediaLink.APPLE_TV_PACKAGE, s.knownPackage)
        // without its ids
        assertEquals("https://tv.apple.com/us/episode/pilot/umc.cmc.5sc6v0g2nj8tk7nvyc7wejees?action=play",
            MediaLink.normalize("https://tv.apple.com/us/episode/pilot/umc.cmc.5sc6v0g2nj8tk7nvyc7wejees")!!.link)
    }

    @Test fun affiliateRedirectsAreUnwrapped() {
        // JustWatch's Crunchyroll link for Frieren S1 E1 (2026-11-15)
        val wrapped = "https://crunchyroll.pxf.io/xk92Nv?u=https%3A%2F%2Fwww.crunchyroll.com%2Fwatch%2FGE00324920ARSA&subId3=justappsvod"
        assertEquals("https://www.crunchyroll.com/watch/GE00324920ARSA", MediaLink.unwrapRedirect(wrapped))
        assertEquals("https://www.crunchyroll.com/watch/GE00324920ARSA", MediaLink.normalize(wrapped)!!.link)
        assertEquals(null, MediaLink.unwrapRedirect("https://www.crunchyroll.com/watch/GE00324920ARSA"))
        assertEquals(null, MediaLink.unwrapRedirect("https://crunchyroll.pxf.io/xk92Nv?subId3=x"))
    }

    @Test fun crunchyrollUsesTheWebLinkWhereTheAppTakesIt() {
        val link = "https://www.crunchyroll.com/watch/GE00324920ARSA"
        // the TV app claims no web links: its own scheme (opens the app)
        assertEquals(MediaLink.Launch("crunchyroll://watch/GE00324920ARSA"), MediaLink.launchFor(link, MediaLink.CRUNCHYROLL_PACKAGE))
        // a phone's app claims www.crunchyroll.com/watch/*: the link as is
        assertEquals(MediaLink.Launch(link), MediaLink.launchFor(link, MediaLink.CRUNCHYROLL_PACKAGE, appTakesLink = true))
    }

    @Test fun discoveryPlusShareLinksMoveToPlay() {
        // Discovery+'s share link for Christmas of Yes; play.discoveryplus.com/video/watch/<id> played it (adb)
        assertEquals("https://play.discoveryplus.com/video/watch/ef8dbdd7-9527-47a2-b322-9be4d25bdcbf",
            MediaLink.normalize("https://www.discoveryplus.com/video/watch/ef8dbdd7-9527-47a2-b322-9be4d25bdcbf")!!.link)
        assertEquals("https://play.discoveryplus.com/video/watch/ef8dbdd7-9527-47a2-b322-9be4d25bdcbf",
            MediaLink.normalize("https://play.discoveryplus.com/video/watch/ef8dbdd7-9527-47a2-b322-9be4d25bdcbf?utm_source=x")!!.link)
    }

    @Test fun peacockLinksBecomeThePlayDeeplink() {
        // the deeplink that played The Grinch (adb, 2026-11-15)
        val play = "https://www.peacocktv.com/deeplink?deeplinkData=%7B%22pvid%22%3A%22153a1aee-b720-356e-bac2-6a9b85fb81b3" +
            "%22%2C%22type%22%3A%22PROGRAMME%22%2C%22action%22%3A%22PLAY%22%7D"
        assertEquals(play, MediaLink.peacockPlay("153a1aee-b720-356e-bac2-6a9b85fb81b3", "PROGRAMME"))
        // JustWatch's Android TV deeplink (opens the page): PDP becomes PLAY
        assertEquals(play, MediaLink.normalize("https://www.peacocktv.com/deeplink?deeplinkData=%7B%22pvid%22%3A%22" +
            "153a1aee-b720-356e-bac2-6a9b85fb81b3%22%2C%22type%22%3A%22PROGRAMME%22%2C%22action%22%3A%22PDP%22%7D")!!.link)
        // the web link (opens the page)
        assertEquals(play, MediaLink.normalize(
            "https://www.peacocktv.com/watch/asset/movies/the-grinch/153a1aee-b720-356e-bac2-6a9b85fb81b3")!!.link)
        // an episode's web link: type EPISODE (JustWatch, The King of Queens S1 E1)
        assertEquals(MediaLink.peacockPlay("ae752e96-f1de-34ac-9d65-36c6c057bf51", "EPISODE"), MediaLink.normalize(
            "https://www.peacocktv.com/watch/asset/tv/the-king-of-queens/8979760515297072112/seasons/1/episodes/" +
                "pilot-episode-1/ae752e96-f1de-34ac-9d65-36c6c057bf51")!!.link)
    }

    /** Kodi's Apple TV titles (id alone) follow tv.apple.com's redirect to the full link. */
    @Test fun bareAppleTvLinksExpandToTheFullLink() {
        val movie = "https://tv.apple.com/movie/umc.cmc.c3xhu25rw4jxxxzq4oio6snu"
        assertTrue(MediaLink.isBareApple(movie))
        assertFalse(MediaLink.isBareApple("https://tv.apple.com/us/movie/wolfs/umc.cmc.c3xhu25rw4jxxxzq4oio6snu"))
        assertEquals("https://tv.apple.com/us/movie/wolfs/umc.cmc.c3xhu25rw4jxxxzq4oio6snu?action=play",
            MediaLink.expand(movie) { "https://tv.apple.com/us/movie/wolfs/umc.cmc.c3xhu25rw4jxxxzq4oio6snu" })
        // an episode, its Location relative (the probe saw absolute ones): showId kept
        assertEquals("https://tv.apple.com/us/episode/good-news-about-hell/umc.cmc.s80mx1ic96pu6ewupz8pfasf?showId=umc.cmc.1srk2goyh2q2zdxcx605w8vtx&action=play",
            MediaLink.expand("https://tv.apple.com/episode/umc.cmc.s80mx1ic96pu6ewupz8pfasf") {
                "/us/episode/good-news-about-hell/umc.cmc.s80mx1ic96pu6ewupz8pfasf?showId=umc.cmc.1srk2goyh2q2zdxcx605w8vtx"
            })
        // no redirect, an error, or somewhere else: the link as it was
        assertEquals(movie, MediaLink.expand(movie) { null })
        assertEquals(movie, MediaLink.expand(movie) { throw java.io.IOException("offline") })
        assertEquals(movie, MediaLink.expand(movie) { "https://example.com/x" })
    }
}
