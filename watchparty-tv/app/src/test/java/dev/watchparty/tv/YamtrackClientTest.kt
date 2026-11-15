package dev.watchparty.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Responses are Yamtrack's own API examples (src/api/examples.py, feat/justwatch-links). */
class YamtrackClientTest {

    @Test fun parsesTheMediaList() {
        val page = YamtrackClient.parseLibrary(JSONObject("""{
            "pagination": {"total": 57, "limit": 20, "offset": 0, "next": null, "previous": null},
            "results": [{
                "id": 1, "consumption_id": 1,
                "item": {"media_id": "100088", "source": "tmdb", "media_type": "tv", "title": "The Last of Us",
                         "image": "https://image.tmdb.org/t/p/original//dmo6TYuuJgaYinXBPjrgG9mB5od.jpg",
                         "season_number": null, "episode_number": null},
                "item_id": "tv/tmdb/100088", "tracked": true, "status": 0, "progress": 0
            }]
        }"""))
        assertEquals(57, page.total)
        assertEquals(
            listOf(YamtrackClient.Item("100088", "tmdb", "tv", "The Last of Us",
                "https://image.tmdb.org/t/p/original//dmo6TYuuJgaYinXBPjrgG9mB5od.jpg", YamtrackClient.PLANNING)),
            page.items,
        )
    }

    @Test fun parsesSearchResults() {
        val page = YamtrackClient.parseSearch(JSONObject("""{
            "pagination": {"total": 1, "limit": 20, "offset": 0, "next": null, "previous": null},
            "results": [{"media_id": "603", "source": "tmdb", "media_type": "movie", "title": "The Matrix",
                         "image": "https://image.tmdb.org/t/p/w500/x.jpg"}]
        }"""))
        assertEquals(listOf(YamtrackClient.Item("603", "tmdb", "movie", "The Matrix",
            "https://image.tmdb.org/t/p/w500/x.jpg", null)), page.items)
    }

    @Test fun skipsEntriesWithoutAnId() {
        val page = YamtrackClient.parseSearch(JSONObject("""{"results": [{"title": "broken"}]}"""))
        assertEquals(0, page.items.size)
        assertEquals(0, page.total)
    }

    @Test fun providersPreferTheTvLinkAndSkipProvidersWithoutLinks() {
        // WATCH_PROVIDERS_EXAMPLE with tv_link (feat/justwatch-links e94646c)
        val offers = YamtrackClient.parseProviders(JSONObject("""{
            "region": "US",
            "link": "https://www.themoviedb.org/movie/603-the-matrix/watch?locale=US",
            "no_charge": [
                {"provider_id": 8, "provider_name": "Netflix", "display_priority": 0,
                 "link": "https://www.netflix.com/title/20557937",
                 "tv_link": "https://www.netflix.com/watch/20557937"},
                {"provider_id": 337, "provider_name": "Disney Plus", "display_priority": 1,
                 "link": "https://www.disneyplus.com/browse/entity-f51dce23-7d8f-490a-9f0c-be1b5432c2a9",
                 "tv_link": null},
                {"provider_id": 15, "provider_name": "Hulu", "display_priority": 2,
                 "link": null, "tv_link": null}
            ],
            "paid": [
                {"provider_id": 2, "provider_name": "Apple TV", "display_priority": 4,
                 "link": "https://tv.apple.com/us/movie/the-matrix/umc.cmc.1fcm4ui9ibn5pbk1jcuqnszmx",
                 "tv_link": null}
            ]
        }"""))
        assertEquals(
            listOf(
                JustWatch.Offer("netflix", "Netflix", "https://www.netflix.com/watch/20557937"),
                // no tv_link: the web link, through MediaLink (Disney+ page → /play/)
                JustWatch.Offer("disneyplus", "Disney Plus",
                    "https://www.disneyplus.com/play/f51dce23-7d8f-490a-9f0c-be1b5432c2a9"),
            ),
            offers,
        )
    }

    @Test fun providersWithJustWatchOffAreEmpty() {
        // JUSTWATCH_ENABLED off: TMDB providers, every link null
        assertEquals(emptyList<JustWatch.Offer>(), YamtrackClient.parseProviders(JSONObject("""{
            "region": "US", "link": null,
            "no_charge": [{"provider_id": 8, "provider_name": "Netflix", "link": null, "tv_link": null}],
            "paid": []
        }""")))
    }

    @Test fun parsesSeasonsAndEpisodes() {
        // SEASONS_EXAMPLE / SEASON_EPISODES_EXAMPLE (the season's title is the show's)
        val seasons = YamtrackClient.parseNumbered(JSONObject("""{"pagination": {"total": 2}, "results": [
            {"id": null, "item": {"media_id": "32868", "source": "tmdb", "media_type": "season",
             "title": "Nikita", "season_number": 2, "episode_number": null}},
            {"id": 2892, "item": {"media_id": "32868", "source": "tmdb", "media_type": "season",
             "title": "Nikita", "season_number": 1, "episode_number": null}}
        ]}"""), "season_number")
        assertEquals(listOf(1, 2), seasons.map { it.number })   // sorted

        val episodes = YamtrackClient.parseNumbered(JSONObject("""{"pagination": {"total": 1}, "results": [
            {"id": null, "item": {"media_id": "32868", "source": "tmdb", "media_type": "episode",
             "title": "Pilot", "season_number": 1, "episode_number": 1}}
        ]}"""), "episode_number")
        assertEquals(listOf(YamtrackClient.Numbered(1, "Pilot")), episodes)
    }

    @Test fun episodeProvidersUseTheEpisodesTvLink() {
        // shape of feat/justwatch-links 4bc82c5's episode providers: link is the
        // episode's web deep link, Netflix's tv_link the episode's /watch/<id>
        val offers = YamtrackClient.parseProviders(JSONObject("""{"region": "US", "link": null,
            "no_charge": [
              {"provider_id": 8, "provider_name": "Netflix",
               "link": "https://www.netflix.com/watch/82946792", "tv_link": "https://www.netflix.com/watch/82946792"},
              {"provider_id": 1899, "provider_name": "Max",
               "link": "https://play.hbomax.com/video/watch/dae9e532-3714-4f2e-b758-fb9a13def902?utm_source=universal_search",
               "tv_link": "https://play.hbomax.com/video/watch/dae9e532-3714-4f2e-b758-fb9a13def902"}
            ], "paid": []}"""))
        assertEquals(listOf("https://www.netflix.com/watch/82946792",
            "https://play.hbomax.com/video/watch/dae9e532-3714-4f2e-b758-fb9a13def902"), offers.map { it.link })
        assertEquals(listOf("netflix", "max"), offers.map { it.technicalName })
    }

    @Test fun normalizesTheServerAddress() {
        assertEquals("https://yamtrack.lan:8000/", YamtrackClient.normalizeBase(" yamtrack.lan:8000/ "))
        assertEquals("http://192.168.1.5:8000/", YamtrackClient.normalizeBase("http://192.168.1.5:8000"))
        assertEquals("https://yt.example.com/sub/", YamtrackClient.normalizeBase("https://yt.example.com/sub//"))
    }

    @Test fun postersUseASmallTmdbSize() {
        assertEquals("https://image.tmdb.org/t/p/w185/dmo6TYuuJgaYinXBPjrgG9mB5od.jpg",
            YamtrackClient.posterUrl("https://image.tmdb.org/t/p/original//dmo6TYuuJgaYinXBPjrgG9mB5od.jpg"))
        assertEquals("https://example.com/cover.png", YamtrackClient.posterUrl("https://example.com/cover.png"))
        assertNull(YamtrackClient.posterUrl(""))
        assertNull(YamtrackClient.posterUrl(null))
    }
}
