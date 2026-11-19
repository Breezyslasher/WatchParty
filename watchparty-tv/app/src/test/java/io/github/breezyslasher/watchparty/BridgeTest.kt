package io.github.breezyslasher.watchparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kodi → Android: a Kodi title found on JustWatch, opened on an app installed here. */
class BridgeTest {

    private val disney = JustWatch.Offer("disneyplus", "Disney Plus", "https://www.disneyplus.com/play/f51dce23-7d8f-490a-9f0c-be1b5432c2a9")
    private val netflix = JustWatch.Offer("netflix", "Netflix", "https://www.netflix.com/watch/1")
    private val searched = ArrayList<String>()

    private fun find(title: String, tmdb: String?, type: String, imdb: String?, year: Int?): JustWatch.Title? {
        searched += "$type $title tmdb=$tmdb imdb=$imdb year=$year"
        return when (type) {
            "MOVIE" -> JustWatch.Title("tm1238268", "MOVIE", "Thunderbolts*", 2025, "986056")
            else -> JustWatch.Title("ts281017", "SHOW", "Andor", 2022, "83867")
        }
    }

    @Test fun aKodiMovieIsFoundByItsIds() {
        val offers = Bridge.offers(Identity("movie", title = "Thunderbolts*", year = 2025, ids = mapOf("tmdb" to "986056", "imdb" to "tt20969586")),
            ::find, titleOffers = { assertEquals("tm1238268", it); listOf(disney, netflix) },
            seasons = { error("not a show") }, episodes = { error("not a show") })
        assertEquals(listOf(disney, netflix), offers)
        assertEquals(listOf("MOVIE Thunderbolts* tmdb=986056 imdb=tt20969586 year=2025"), searched)
    }

    @Test fun aKodiEpisodeIsFoundByItsShowSeasonAndEpisode() {
        val pluto = JustWatch.Offer("plutotv", "Pluto TV", "https://pluto.tv/us/shows/andor")
        val episodes = listOf(
            JustWatch.Episode("tse1", 1, "Kassa", listOf(disney, pluto)),
            JustWatch.Episode("tse2", 2, "That Would Be Me", listOf(disney.copy(link = "https://www.disneyplus.com/play/2"), pluto)),
        )
        val offers = Bridge.offers(Identity("episode", show = "Andor", season = 1, episode = 2, ids = mapOf("tvdb" to "1")), ::find,
            titleOffers = { error("not a movie") },
            seasons = { listOf(JustWatch.Season("tss0", 0, ""), JustWatch.Season("tss1", 1, "")) },
            episodes = { assertEquals("tss1", it); episodes })
        // episode 2's own Disney+ link; Pluto's show page (the same for every episode) is left out
        assertEquals(listOf("https://www.disneyplus.com/play/2"), offers.map { it.link })
        // the show by its title: an episode's ids aren't the show's
        assertEquals(listOf("SHOW Andor tmdb=null imdb=null year=null"), searched)
    }

    @Test fun aKodiEpisodesShowIsFoundByTheShowsIdsAndYear() {
        // shows share names (One Piece, 1999 and 2023): Kodi sends its show's ids and premiere year
        Bridge.offers(Identity.episode("One Piece", 1, 1, 2023, mapOf("tmdb" to "2229460", "show_tmdb" to "111110", "show_imdb" to "tt11737520")),
            ::find, titleOffers = { emptyList() }, seasons = { emptyList() }, episodes = { emptyList() })
        assertEquals(listOf("SHOW One Piece tmdb=111110 imdb=tt11737520 year=2023"), searched)
    }

    @Test fun nothingWhenTheSeasonOrEpisodeIsMissing() {
        assertEquals(emptyList<JustWatch.Offer>(), Bridge.offers(Identity.episode("Andor", 3, 1), ::find,
            titleOffers = { emptyList() }, seasons = { listOf(JustWatch.Season("tss1", 1, "")) }, episodes = { emptyList() }))
    }

    @Test fun aKodiAddonsOwnServiceComesFirst() {
        // Hot Fuzz from Kodi's Starz addon: JustWatch lists Starz's Amazon channel and Prime first
        val prime = JustWatch.Offer("amazonprime", "Amazon Prime Video", "https://watch.amazon.com/watch?asin=B00D5UNIXI")
        val starzChannel = JustWatch.Offer("amazonstarz", "Starz Amazon Channel", "https://watch.amazon.com/watch?asin=B00D5UNIXI")
        val starz = JustWatch.Offer("starz", "Starz", "https://starz.com/movies/45001")
        val installed = { o: JustWatch.Offer -> if (o == starz) "com.bydeluxe.d3.android.program.starz" else "com.amazon.amazonvideo.livingroom" }
        assertEquals(starz, Bridge.choose(listOf(prime, starzChannel, starz), "plugin.video.starz", installed)?.first)
        // without the addon (a library file): JustWatch's order, as before
        assertEquals(prime, Bridge.choose(listOf(prime, starzChannel, starz), null, installed)?.first)
        // the addon's service not installed here: the next one
        assertEquals(prime, Bridge.choose(listOf(prime, starz), "plugin.video.starz") { o -> if (o == prime) "x" else null }?.first)
        assertEquals("plugin.video.starz", AddonLinks.addonOf("plugin://plugin.video.starz/?action=play&content_id=45001"))
        assertNull(AddonLinks.addonOf("smb://nas/film.mkv"))
    }

    @Test fun choosesTheFirstServiceInstalledHere() {
        assertEquals(netflix to "com.netflix.ninja",
            Bridge.choose(listOf(disney, netflix)) { if (it == netflix) "com.netflix.ninja" else null })
        assertNull(Bridge.choose(listOf(disney, netflix)) { null })
    }
}
