package dev.watchparty.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixtures are JustWatch's real answers from tools/justwatch/jw.py on
 *  GitHub Actions (US, 2026-11-13), trimmed. */
class JustWatchTest {

    private fun search(vararg titles: Triple<String, Int, Int>): JSONObject {
        val edges = titles.mapIndexed { i, (title, year, runtime) ->
            """{"node":{"id":"tm$i","content":{"title":"$title","originalReleaseYear":$year,"runtime":$runtime}}}"""
        }
        return JSONObject("""{"data":{"popularTitles":{"edges":[${edges.joinToString(",")}]}}}""")
    }

    private fun offers(vararg o: Array<String?>): JSONObject {
        val items = o.joinToString(",") { (tech, clear, deeplink, web) ->
            """{"monetizationType":"FLATRATE","standardWebURL":${web?.let { "\"$it\"" } ?: "null"},""" +
                """"deeplinkURL":${deeplink?.let { "\"$it\"" } ?: "null"},""" +
                """"package":{"technicalName":"$tech","clearName":"$clear"}}"""
        }
        return JSONObject("""{"data":{"node":{"offers":[$items]}}}""")
    }

    @Test fun ironManPicksThe2008FilmByRuntime() {
        val c = JustWatch.parseCandidates(search(
            Triple("Iron Man", 2008, 126), Triple("Iron Man 3", 2013, 130)))
        // Disney+ reported 7,656,000 ms (127.6 min) on the TV
        assertEquals(2008, JustWatch.pickTitle(c, "Iron Man", 7_656_000)!!.year)
    }

    @Test fun duneSameTitlePicksByRuntime() {
        val c = JustWatch.parseCandidates(search(Triple("Dune", 2021, 155), Triple("Dune", 1984, 136)))
        assertEquals(1984, JustWatch.pickTitle(c, "Dune", 136 * 60_000L)!!.year)
        assertEquals(2021, JustWatch.pickTitle(c, "Dune", 155 * 60_000L)!!.year)
    }

    @Test fun titleMustMatchIgnoringPunctuation() {
        val c = JustWatch.parseCandidates(search(
            Triple("Thunderbolts*", 2025, 127), Triple("Thunderbolts* | A Special Look", 2025, 4)))
        assertEquals("Thunderbolts*", JustWatch.pickTitle(c, "Thunderbolts*", 7_721_000)!!.title)
        assertNull(JustWatch.pickTitle(c, "Iron Man", -1))
    }

    @Test fun disneyDeeplinkIsTheConfirmedPlayLink() {
        val o = JustWatch.parseOffers(offers(arrayOf("disneyplus", "Disney Plus",
            "intent://www.disneyplus.com/play/f51dce23-7d8f-490a-9f0c-be1b5432c2a9#Intent;launchFlags=0x00800000;scheme=https;end",
            "https://www.disneyplus.com/browse/entity-f51dce23-7d8f-490a-9f0c-be1b5432c2a9")))
        assertEquals("https://www.disneyplus.com/play/f51dce23-7d8f-490a-9f0c-be1b5432c2a9",
            JustWatch.linkFor(o, MediaLink.DISNEY_PACKAGE, "Disney+"))
    }

    @Test fun hboUsesTheWatchDeeplinkNotTheShowPage() {
        val o = JustWatch.parseOffers(offers(
            arrayOf("amazonhbomax", "HBO Max Amazon Channel",
                "intent://watch.amazon.com/watch?asin=B0BZ3DN1TW&time=0&territory=US#Intent;package=com.amazon.amazonvideo.livingroom;scheme=https;end",
                "https://watch.amazon.com/detail?gti=amzn1.dv.gti.a8025548"),
            arrayOf("max", "HBO Max",
                "intent://play.hbomax.com/video/watch/07323549-c382-4363-a3fe-97a365e81331?utm_source=universal_search#Intent;launchFlags=0x00800000;scheme=https;end",
                "https://play.hbomax.com/show/e7dc7b3a-a494-4ef1-8107-f4308aa6bbf7?utm_source=universal_search")))
        assertEquals("https://play.hbomax.com/video/watch/07323549-c382-4363-a3fe-97a365e81331",
            JustWatch.linkFor(o, MediaLink.HBO_PACKAGE, "HBO Max"))
    }

    @Test fun appleTvGetsActionPlay() {
        val o = JustWatch.parseOffers(offers(arrayOf("appletvplus", "Apple TV",
            "intent://tv.apple.com/us/movie/greyhound/umc.cmc.o5z5ztufuu3uv8lx7m0jcega#Intent;launchFlags=0x00800000;scheme=https;end",
            "https://tv.apple.com/us/movie/greyhound/umc.cmc.o5z5ztufuu3uv8lx7m0jcega?at=1000l3V2&ct=app_tvplus")))
        assertEquals("https://tv.apple.com/us/movie/greyhound/umc.cmc.o5z5ztufuu3uv8lx7m0jcega?action=play",
            JustWatch.linkFor(o, MediaLink.APPLE_TV_PACKAGE, "Apple TV"))
    }

    @Test fun unknownAppsMatchByName() {
        val o = JustWatch.parseOffers(offers(arrayOf("tubitv", "Tubi TV", null, "https://tubitv.com/movies/100016602")))
        assertEquals("https://tubitv.com/movies/100016602", JustWatch.linkFor(o, "com.tubitv", "Tubi TV"))
        assertNull(JustWatch.linkFor(o, "com.example.other", "Other"))
    }

    @Test fun noOfferForTheAppMeansNoLink() {
        val o = JustWatch.parseOffers(offers(arrayOf("disneyplus", "Disney Plus", null,
            "https://www.disneyplus.com/browse/entity-x")))
        assertNull(JustWatch.linkFor(o, MediaLink.NETFLIX_TV_PACKAGE, "Netflix"))
    }

    @Test fun intentLinksBecomeWebLinks() {
        assertEquals("https://www.youtube.com/watch?v=daFKP4qASjU",
            JustWatch.webLinkOf("intent://www.youtube.com/watch?v=daFKP4qASjU#Intent;launchFlags=0x00800000;scheme=https;end"))
        // a non-web scheme (Fawesome's) isn't usable as a room link
        assertNull(JustWatch.webLinkOf("intent://play?type=movies#Intent;launchFlags=0x00800000;scheme=Movies by fawesome;end"))
    }

    @Test fun shareForPrefersTheJustWatchLinkOverTitleOnly() {
        val played = NowPlaying.Played(MediaLink.HBO_PACKAGE, "Dune", 0, 0, 155 * 60_000L)
        val link = "https://play.hbomax.com/video/watch/07323549-c382-4363-a3fe-97a365e81331"
        assertEquals(link, NowPlaying.shareFor(played, emptyList(), link).link)
    }
}
