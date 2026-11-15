package dev.watchparty.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fixtures are trimmed from real JustWatch responses printed by
 * tools/justwatch/jw.py on GitHub Actions (US, 2026-11-15).
 */
class JustWatchTest {

    private fun offer(type: String, technical: String, clear: String, deeplink: String?, web: String? = null) =
        JSONObject().put("monetizationType", type)
            .put("deeplinkURL", deeplink ?: JSONObject.NULL)
            .put("standardWebURL", web ?: JSONObject.NULL)
            .put("package", JSONObject().put("technicalName", technical).put("clearName", clear))

    private fun node(web: List<JSONObject>, atv: List<JSONObject>) =
        JSONObject().put("web", JSONArray(web)).put("atv", JSONArray(atv))

    // ── finding the title ───────────────────────────────────────────────

    private val search = JSONObject("""{"data":{"popularTitles":{"edges":[
        {"node":{"id":"ts38796","objectType":"SHOW","content":{"title":"Stranger Things","originalReleaseYear":2016,"externalIds":{"tmdbId":"66732"}}}},
        {"node":{"id":"ts390315","objectType":"SHOW","content":{"title":"Stranger Things: Tales from '85","originalReleaseYear":2026,"externalIds":{"tmdbId":"224263"}}}},
        {"node":{"id":"ts156200","objectType":"SHOW","content":{"title":"Severance","originalReleaseYear":2014,"externalIds":{"tmdbId":null}}}}
    ]}}}""")

    @Test fun picksTheTitleByTmdbId() {
        val titles = JustWatch.parseTitles(search)
        assertEquals(3, titles.size)
        assertNull(titles[2].tmdbId)
        assertEquals("ts390315", JustWatch.pickTitle(titles, "Stranger Things", "224263")?.id)
        assertEquals("ts38796", JustWatch.pickTitle(titles, "Stranger Things", "66732")?.id)
    }

    @Test fun aWrongTmdbIdMatchesNothingEvenIfTheTitleMatches() {
        assertNull(JustWatch.pickTitle(JustWatch.parseTitles(search), "Stranger Things", "1"))
    }

    @Test fun withoutATmdbIdTheExactTitleIsUsed() {
        val titles = JustWatch.parseTitles(search)
        assertEquals("ts38796", JustWatch.pickTitle(titles, "stranger things", null)?.id)
        assertNull(JustWatch.pickTitle(titles, "Stranger", null))
    }

    @Test fun graphqlErrorsAreReported() {
        try {
            JustWatch.parseTitles(JSONObject("""{"errors":[{"message":"rate limited"}]}"""))
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!, e.message!!.contains("rate limited"))
        }
    }

    // ── seasons and episodes ────────────────────────────────────────────

    @Test fun parsesSeasons() {
        val seasons = JustWatch.parseSeasons(JSONObject("""{"data":{"node":{"seasons":[
            {"id":"tss62569","content":{"seasonNumber":1,"title":"Season 1"}},
            {"id":"tss70830","content":{"seasonNumber":2,"title":null}}]}}}"""))
        assertEquals(listOf(JustWatch.Season("tss62569", 1, "Season 1"), JustWatch.Season("tss70830", 2, "")), seasons)
    }

    @Test fun netflixEpisodesUseTheEpisodesOwnWebLinkNotTheShowLink() {
        // Stranger Things S1E1: two Netflix listings plus "with Ads"; the
        // ANDROID_TV link is the show (82939566) for every episode
        val episode = JSONObject()
            .put("id", "tse1126259")
            .put("content", JSONObject().put("episodeNumber", 1).put("title", "Chapter One: The Vanishing of Will Byers"))
            .put("web", JSONArray(listOf(
                offer("FLATRATE", "netflix", "Netflix", "https://www.netflix.com/watch/82946792", "https://www.netflix.com/title/82939566"),
                offer("FLATRATE", "netflix", "Netflix", "https://www.netflix.com/watch/80077368", "https://www.netflix.com/title/80057281"),
                offer("FLATRATE", "netflixbasicwithads", "Netflix Standard with Ads", "https://www.netflix.com/watch/82946792"),
            )))
            .put("atv", JSONArray(listOf(
                offer("FLATRATE", "netflix", "Netflix",
                    "intent://www.netflix.com/watch/82939566#Intent;launchFlags=0x00800000;scheme=https;package=com.netflix.ninja;S.source=30;end"),
            )))
        val episodes = JustWatch.parseEpisodes(
            JSONObject().put("data", JSONObject().put("node", JSONObject().put("episodes", JSONArray().put(episode)))))
        assertEquals(1, episodes.size)
        assertEquals(1, episodes[0].number)
        // one Netflix entry ("with Ads" has the same link), the episode's /watch/ id
        assertEquals(listOf(JustWatch.Offer("netflix", "Netflix", "https://www.netflix.com/watch/82946792")), episodes[0].offers)
    }

    @Test fun androidTvLinksAreTurnedIntoPlayableHttpsLinks() {
        val offers = JustWatch.parseOffers(node(
            web = listOf(
                offer("FLATRATE", "disneyplus", "Disney Plus", "https://www.disneyplus.com/play/91310366-d44b-44a4-bdc4-71f999472826",
                    "https://www.disneyplus.com/browse/entity-faba988a-a9f5-45f2-a074-0775a7d6f67a"),
            ),
            atv = listOf(
                // Andor S1E1
                offer("FLATRATE", "disneyplus", "Disney Plus",
                    "intent://www.disneyplus.com/play/91310366-d44b-44a4-bdc4-71f999472826#Intent;launchFlags=0x00800000;scheme=https;end"),
                // The Last of Us S1E1
                offer("FLATRATE", "max", "HBO Max",
                    "intent://play.hbomax.com/video/watch/dae9e532-3714-4f2e-b758-fb9a13def902?utm_source=universal_search#Intent;launchFlags=0x00800000;scheme=https;end"),
                // Severance S1E1
                offer("FLATRATE", "appletvplus", "Apple TV",
                    "intent://tv.apple.com/us/episode/good-news-about-hell/umc.cmc.s80mx1ic96pu6ewupz8pfasf?showId=umc.cmc.1srk2goyh2q2zdxcx605w8vtx&playableId=tvs.sbd.4000%3AVSEVR0560101#Intent;launchFlags=0x00800000;scheme=https;end"),
                // Amazon
                offer("FLATRATE", "amazonprime", "Amazon Prime Video",
                    "intent://watch.amazon.com/watch?asin=B0CH88VRKB&time=0&territory=US#Intent;package=com.amazon.amazonvideo.livingroom;scheme=https;end"),
            ),
        ))
        assertEquals(listOf("disneyplus", "max", "appletvplus", "amazonprime"), offers.map { it.technicalName })
        assertEquals("https://www.disneyplus.com/play/91310366-d44b-44a4-bdc4-71f999472826", offers[0].link)
        assertEquals("https://play.hbomax.com/video/watch/dae9e532-3714-4f2e-b758-fb9a13def902", offers[1].link)
        assertEquals("https://tv.apple.com/us/episode/good-news-about-hell/umc.cmc.s80mx1ic96pu6ewupz8pfasf" +
            "?showId=umc.cmc.1srk2goyh2q2zdxcx605w8vtx&playableId=tvs.sbd.4000%3AVSEVR0560101&action=play", offers[2].link)
        assertEquals("https://watch.amazon.com/watch?asin=B0CH88VRKB&time=0&territory=US", offers[3].link)
    }

    @Test fun rentAndBuyAreLeftOut() {
        val offers = JustWatch.parseOffers(node(
            web = listOf(offer("RENT", "itunes", "Apple TV Store", null, "https://tv.apple.com/us/movie/x/umc.cmc.1")),
            atv = listOf(offer("BUY", "amazon", "Amazon Video", "intent://watch.amazon.com/watch?asin=1#Intent;scheme=https;end")),
        ))
        assertEquals(emptyList<JustWatch.Offer>(), offers)
    }

    @Test fun fallsBackToTheWebPageWhenThereIsNoDeeplink() {
        val offers = JustWatch.parseOffers(node(
            web = listOf(offer("FLATRATE", "tnt", "TNT", null, "https://www.tntdrama.com/movies/dune-theatrical")),
            atv = listOf(offer("FLATRATE", "tnt", "TNT", null)),
        ))
        assertEquals(listOf(JustWatch.Offer("tnt", "TNT", "https://www.tntdrama.com/movies/dune-theatrical")), offers)
    }

    @Test fun customSchemesAreNotPlayableLinks() {
        // Fawesome's ANDROID_TV deeplink
        assertNull(JustWatch.webLinkOf(
            "intent://play?type=movies/action=play/searchKeys=10510714#Intent;launchFlags=0x00800000;scheme=Movies by fawesome;end"))
        assertNull(JustWatch.webLinkOf("watchtnt://play?title=2430987"))
        assertEquals("https://www.youtube.com/watch?v=daFKP4qASjU",
            JustWatch.webLinkOf("intent://www.youtube.com/watch?v=daFKP4qASjU#Intent;launchFlags=0x00800000;scheme=https;end"))
    }

    @Test fun offersCarryTheServicesIcon() {
        val node = JSONObject().put("web", org.json.JSONArray().put(JSONObject()
            .put("monetizationType", "FLATRATE")
            .put("deeplinkURL", "https://www.disneyplus.com/play/91310366")
            .put("package", JSONObject().put("technicalName", "disneyplus").put("clearName", "Disney Plus")
                .put("icon", "/icon/147638351/s100/disneyplus.png"))))
        assertEquals("https://images.justwatch.com/icon/147638351/s100/disneyplus.png", JustWatch.parseOffers(node).single().icon)
    }

    @Test fun aSoleServiceIsOnlyOneLink() {
        val disney = JustWatch.Offer("disneyplus", "Disney Plus", "https://www.disneyplus.com/play/1")
        assertEquals(disney, JustWatch.soleOffer(listOf(disney)))
        // the same link under two plans is still one service
        assertEquals(disney, JustWatch.soleOffer(listOf(disney, disney.copy(technicalName = "disneyplusads"))))
        assertEquals(null, JustWatch.soleOffer(listOf(disney, JustWatch.Offer("netflix", "Netflix", "https://www.netflix.com/watch/2"))))
        assertEquals(null, JustWatch.soleOffer(emptyList()))
    }

    @Test fun aLinkEveryEpisodeSharesIsTheShowsPage() {
        // Pluto TV, The Twilight Zone: the show's page for every episode (2026-11-15)
        val pluto = JustWatch.Offer("plutotv", "Pluto TV", "https://pluto.tv/us/shows/the-twilight-zone/")
        val e1 = listOf(pluto, JustWatch.Offer("hulu", "Hulu", "https://www.hulu.com/watch/a332f2b9"))
        val e2 = listOf(pluto, JustWatch.Offer("hulu", "Hulu", "https://www.hulu.com/watch/b2047a23"))
        assertEquals(setOf(pluto.link), JustWatch.sharedLinks(e1, e2))
        assertEquals(emptySet<String>(), JustWatch.sharedLinks(e1, emptyList()))
    }

    @Test fun discoveryPlusMoviesGetHboMaxsVideoId() {
        // Ghosts of Flight 401 (JustWatch, 2026-11-15); the result played on a phone
        val hbo = JustWatch.Offer("max", "HBO Max", "https://play.hbomax.com/video/watch/2d776436-900b-451d-b78d-2a4767729dd7")
        val dplus = JustWatch.Offer("discoveryplusus", "Discovery +", "https://www.discoveryplus.com/movie/fa6e0381-45a4-4a92-8c29-7560b2933d9e")
        val tubi = JustWatch.Offer("tubitv", "Tubi TV", "https://tubitv.com/movies/100047201")
        assertEquals(listOf(hbo, dplus.copy(link = "https://play.discoveryplus.com/video/watch/2d776436-900b-451d-b78d-2a4767729dd7"), tubi),
            JustWatch.discoveryPlayLinks(listOf(hbo, dplus, tubi)))
        // not on HBO Max: unchanged (the page)
        assertEquals(listOf(dplus, tubi), JustWatch.discoveryPlayLinks(listOf(dplus, tubi)))
    }

    // ── search and title pages without Yamtrack (live API output, tools/justwatch/jw.py --detail, 2026-11-15)

    @Test fun searchResultsCarryTheirPosterAndTmdbId() {
        val response = JSONObject("""{"data":{"popularTitles":{"edges":[
            {"node":{"id":"tm1238268","objectType":"MOVIE","content":{"title":"Thunderbolts*","originalReleaseYear":2025,
             "posterUrl":"/poster/329366258/{profile}/thunderbolts.{format}","externalIds":{"tmdbId":"986056"}}}},
            {"node":{"id":"tm3022632","objectType":"MOVIE","content":{"title":"Mallory Reimagined","originalReleaseYear":2025,
             "posterUrl":null,"externalIds":{"tmdbId":null}}}}]}}}""")
        val titles = JustWatch.parseTitles(response)
        assertEquals(JustWatch.Title("tm1238268", "MOVIE", "Thunderbolts*", 2025, "986056",
            "https://images.justwatch.com/poster/329366258/s332/thunderbolts.jpg"), titles[0])
        assertEquals(null, titles[1].poster)

        // as Browse items: by TMDB id when there is one (Yamtrack can fill the page), else JustWatch's
        assertEquals(YamtrackClient.Item("986056", "tmdb", "movie", "Thunderbolts*",
            "https://images.justwatch.com/poster/329366258/s332/thunderbolts.jpg", null, "2025", jwId = "tm1238268"),
            YamtrackClient.Item.fromJustWatch(titles[0]))
        val noTmdb = YamtrackClient.Item.fromJustWatch(titles[1])
        assertEquals("tm3022632", noTmdb.mediaId)
        assertEquals("justwatch", noTmdb.source)
        assertEquals("tv", YamtrackClient.Item.fromJustWatch(titles[0].copy(objectType = "SHOW")).mediaType)
    }

    @Test fun parsesATitlePage() {
        val response = JSONObject("""{"data":{"node":{
            "content":{"title":"The Last of Us","originalReleaseDate":"2023-01-15","runtime":57,
              "shortDescription":"Twenty years after modern civilization has been destroyed, Joel ...",
              "posterUrl":"/poster/301981224/{profile}/the-last-of-us.{format}","ageCertification":"TV-MA",
              "genres":[{"translation":"Action & Adventure"},{"translation":"Drama"}],
              "scoring":{"imdbScore":8.4,"imdbVotes":756329,"tmdbScore":8.394}},
            "similarTitlesV2":{"edges":[{"node":{"id":"ts88632","objectType":"SHOW","content":{"title":"Chernobyl",
              "originalReleaseYear":2019,"posterUrl":"/poster/130849429/{profile}/chernobyl.{format}","externalIds":{"tmdbId":"87108"}}}}]}
        }}}""")
        val p = JustWatch.parsePage(response)!!
        assertEquals("The Last of Us", p.title)
        assertEquals("https://images.justwatch.com/poster/301981224/s592/the-last-of-us.jpg", p.poster)
        assertEquals(listOf("Action & Adventure", "Drama"), p.genres)
        assertEquals(8.394, p.score!!, 0.0001)
        assertEquals("tmdb", p.scoreSource)
        assertEquals(null, p.scoreCount)
        assertEquals("2023-01-15", p.date)
        assertEquals(57, p.runtime)
        assertEquals("TV-MA", p.certification)
        assertEquals(listOf("Chernobyl"), p.similar.map { it.title })
        assertEquals("87108", p.similar[0].tmdbId)
    }

    @Test fun imdbRatingWhenTmdbHasNone() {
        val response = JSONObject("""{"data":{"node":{"content":{"title":"X","runtime":0,"ageCertification":"",
            "scoring":{"imdbScore":7.1,"imdbVotes":313489,"tmdbScore":null},"genres":[]}}}}""")
        val p = JustWatch.parsePage(response)!!
        assertEquals(7.1, p.score!!, 0.0001)
        assertEquals("imdb", p.scoreSource)
        assertEquals(313489, p.scoreCount)
        assertEquals(null, p.runtime)
        assertEquals(null, p.certification)
        assertEquals(emptyList<JustWatch.Title>(), p.similar)
    }

    @Test fun runtimeText() {
        assertEquals("2h 7m", JustWatch.runtimeText(127))
        assertEquals("2h", JustWatch.runtimeText(120))
        assertEquals("57m", JustWatch.runtimeText(57))
    }
}
