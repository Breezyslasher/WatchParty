package dev.watchparty.tv

import org.json.JSONArray
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
        // cast photos and recommendations come at w500
        assertEquals("https://image.tmdb.org/t/p/w185/4D0PpNI0kmP58hgrwGC3wCjxhnm.jpg",
            YamtrackClient.posterUrl("https://image.tmdb.org/t/p/w500/4D0PpNI0kmP58hgrwGC3wCjxhnm.jpg"))
    }

    @Test fun hidesStatusFiltersWithNothingInThem() {
        val order = listOf(1, 0, 2, 3, -1)   // Watching, Planning, Paused, Completed, All
        // nothing paused or completed
        assertEquals(listOf(1, 0, -1), YamtrackClient.statusesWithTitles(mapOf(1 to 3, 0 to 5, 2 to 0, 3 to 0, -1 to 8), order, -1))
        // only planning: "All" would show the same titles
        assertEquals(listOf(0), YamtrackClient.statusesWithTitles(mapOf(1 to 0, 0 to 4, 2 to 0, 3 to 0, -1 to 4), order, -1))
        // only planning, but some are dropped (no chip): "All" shows more, so it stays
        assertEquals(listOf(0, -1), YamtrackClient.statusesWithTitles(mapOf(1 to 0, 0 to 4, 2 to 0, 3 to 0, -1 to 6), order, -1))
        // an empty library
        assertEquals(emptyList<Int>(), YamtrackClient.statusesWithTitles(mapOf(1 to 0, 0 to 0, 2 to 0, 3 to 0, -1 to 0), order, -1))
        // counts that failed (-1) or haven't arrived keep their chip
        assertEquals(listOf(1, 0, 2, -1), YamtrackClient.statusesWithTitles(mapOf(1 to -1, 2 to 1, 3 to 0, -1 to 9), order, -1))
    }

    @Test fun parsesTheTitlePage() {
        // MEDIA_DETAILS_EXAMPLE (a show), trimmed
        val show = YamtrackClient.parseDetail(JSONObject("""{
            "id": 3599, "media_id": "12345", "source": "tmdb", "media_type": "tv", "title": "Van der Valk",
            "image": "https://image.tmdb.org/t/p/w500/7cSyT7qrAVHOdiaUqcZAdj4Jny1.jpg",
            "synopsis": "Van der Valk is a British television...", "genres": ["Drama"],
            "score": 6.2, "score_count": 10,
            "details": {"format": "TV", "first_air_date": "1972-09-13", "last_air_date": "1992-02-19",
                        "status": "Ended", "seasons": 5, "episodes": 32, "runtime": "50m", "studios": null},
            "consumptions": [{"consumption_id": 322, "score": 9.0, "status": 0}]
        }"""))
        assertEquals("Drama", show.genres.single())
        assertEquals(6.2, show.score!!, 0.0)          // TMDB's, not the 9.0 in consumptions
        assertEquals(10, show.scoreCount)
        assertEquals("1972-09-13", show.date)
        assertEquals("50m", show.runtime)
        assertEquals("Ended", show.status)
        assertEquals(5, show.seasons)

        // a movie with no votes and no overview (providers/tmdb.py: score 0.0, "No synopsis available.")
        val movie = YamtrackClient.parseDetail(JSONObject("""{
            "title": "X", "image": null, "synopsis": "No synopsis available.", "genres": [],
            "score": 0.0, "score_count": 0,
            "details": {"format": "Movie", "release_date": "2026-01-02", "runtime": "2h 6m", "status": "Released"}
        }"""))
        assertNull(movie.score)
        assertNull(movie.synopsis)
        assertEquals("2026-01-02", movie.date)
        assertEquals("2h 6m", movie.runtime)
        assertNull(movie.seasons)
    }

    @Test fun parsesCastAndRecommendations() {
        // CAST_EXAMPLE
        val cast = YamtrackClient.parseCast(JSONObject("""{
            "pagination": {"total": 1, "limit": 20, "offset": 0, "next": null, "previous": null},
            "results": [{"person_id": 6384, "name": "Keanu Reeves", "character": "Thomas A. Anderson / Neo",
                "image": "https://image.tmdb.org/t/p/w500/4D0PpNI0kmP58hgrwGC3wCjxhnm.jpg", "order": 0, "episode_count": null}]
        }"""), "tmdb")
        assertEquals(YamtrackClient.CastMember("6384", "tmdb", "Keanu Reeves", "Thomas A. Anderson / Neo",
            "https://image.tmdb.org/t/p/w500/4D0PpNI0kmP58hgrwGC3wCjxhnm.jpg"), cast.single())

        // RECOMMENDATIONS_EXAMPLE: the endpoint returns a plain array
        val recs = YamtrackClient.parseItems(JSONArray("""[{"source": "tmdb", "media_type": "tv",
            "image": "https://image.tmdb.org/t/p/w500/pFao5i4giBsGl7QQBXi7oMUPzCn.jpg",
            "media_id": "167", "title": "La Femme Nikita"}]"""))
        assertEquals("La Femme Nikita", recs.single().title)
        assertEquals("tv", recs.single().mediaType)
        assertEquals("167", recs.single().mediaId)
    }

    @Test fun parsesAPersonAndWhatTheyAreKnownFor() {
        // PERSON_EXAMPLE
        val person = YamtrackClient.parsePerson(JSONObject("""{
            "person_id": 6384, "source": "tmdb", "source_url": "https://www.themoviedb.org/person/6384",
            "name": "Keanu Reeves", "image": "https://image.tmdb.org/t/p/w500/4D0PpNI0kmP58hgrwGC3wCjxhnm.jpg",
            "biography": "Keanu Charles Reeves is a Canadian actor.", "birthday": "1964-09-02", "deathday": null,
            "place_of_birth": "Beirut, Lebanon", "known_for_department": "Acting"
        }"""))
        assertEquals("Keanu Reeves", person.name)
        assertEquals("1964-09-02", person.birthday)
        assertNull(person.deathday)
        assertEquals("Beirut, Lebanon", person.placeOfBirth)
        assertEquals("Acting", person.department)

        // KNOWN_FOR_EXAMPLE, plus a duplicate (cast and crew of the same film)
        val known = YamtrackClient.parseKnownFor(JSONObject("""{
            "pagination": {"total": 2, "limit": 20, "offset": 0, "next": null, "previous": null},
            "results": [
              {"source": "tmdb", "media_type": "movie", "media_id": "603", "title": "The Matrix",
               "image": "https://image.tmdb.org/t/p/w500/p96dm7sCMn4VYAStA6siNz30G1r.jpg",
               "character": "Thomas A. Anderson / Neo", "departments": ["Acting"], "jobs": [],
               "release_date": "1999-03-31", "episode_count": null},
              {"source": "tmdb", "media_type": "movie", "media_id": "603", "title": "The Matrix",
               "image": null, "character": null, "departments": ["Production"], "jobs": ["Producer"],
               "release_date": "1999-03-31", "episode_count": null}
            ]
        }"""))
        assertEquals(1, known.size)
        assertEquals("1999-03-31", known[0].date)
        assertEquals("Thomas A. Anderson / Neo", known[0].role)
    }
}
