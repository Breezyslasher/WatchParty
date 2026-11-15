package dev.watchparty.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fragments of HBO Max's public pages as fetched on 2026-11-15 (page probe workflow), trimmed. */
class HboMaxTest {

    private val asl = "1f339dc7-e71d-4679-b0a6-58a85ab5d6d0"
    private val regular = "93ba22b1-833e-47ba-ae94-8ee7b9eefa9a"

    // www.hbomax.com/accessibility/asl: a feature before the series, so the
    // match must not start in the previous item
    private val aslPage = """{"__typename":"Feature","featureId":"931163ce-bfbc-4bc5-b487-eea2ecf9ccd4","url":null,
        "imageUrlLink":"/video/watch/d0d982d3-590b-4fd3-a8f5-5af7a15e9d78","category":"Feature","genres":["Comedy"],
        "offeringDates":{"startDate":"2026-01-01T00:00:00Z","endDate":"2030-01-01T00:00:00Z"},
        "title":{"full_original":null,"short_original":null,"short":"A Movie (with ASL)","full":"A Movie (with ASL)"}},
        {"__typename":"Series","seriesId":"$asl","hbomaxURL":null,"hbomaxId":"$asl","category":"Series",
        "seriesTitleId":null,"imageUrlLink":"/show/$asl","genres":["Drama","Fantasy & Sci-Fi","Dystopian"],
        "brand":["HBOTV"],"ratingCode":[],"offeringDates":{"startDate":"2025-03-31T07:01:00Z","endDate":"2028-02-28T04:59:00Z"},
        "title":{"full_original":null,"short_original":null,"short":"The Last of Us (with ASL)","full":"The Last of Us (with ASL)"},
        "summary":{"short":"Featuring American Sign Language (ASL) interpretation."}}""".replace("\n        ", "")

    // www.hbomax.com/show/<asl>/s1: its own episodes, then "You May Also Like"
    // (whose header names the regular title before any series item)
    private val aslSeason = """"episodeUrl":"/show/$asl/s1/e1-when-youre-lost-in-the-darkness-with-asl/dae9e532-3714-4f2e-b758-fb9a13def902",
        "idref23":{"collectionId":"10684","firstItem":{"title":{"short":"The Last of Us","full":"The Last of Us"}},
        "title":{"short":"The Last of Us (with ASL) - YMAL"},"items":[{"__typename":"Series","seriesId":"$regular",
        "hbomaxURL":null,"hbomaxId":"$regular","category":"Series","seriesTitleId":null,"imageUrlLink":"/show/$regular",
        "genres":["Drama"],"brand":["HBOTV"],"ratingCode":[],"offeringDates":{"startDate":"2023-04-21T00:00:00Z","endDate":"2040-05-25T03:59:00Z"},
        "title":{"full_original":null,"short_original":null,"short":"The Last of Us","full":"The Last of Us"}}]}""".replace("\n        ", "")

    // www.hbomax.com/show/<regular>/s1 (e1 and e10 both present: e1 must not match e10)
    private val regularSeason = """"episodeUrl":"/show/$regular/s1/e10-other/00000000-0000-0000-0000-000000000010",
        "episodeUrl":"/show/$regular/s1/e1-when-youre-lost-in-the-darkness/c07ae042-082e-4b53-ba86-b6ac5e3004db",
        "episodeUrl":"/show/$regular/s1/e2-infected/50b6f9e5-a151-4b87-b51d-ac71a279912f"""".replace("\n        ", "")

    private val pages = mapOf(
        "https://www.hbomax.com/accessibility/asl" to aslPage,
        "https://www.hbomax.com/show/$asl/s1" to aslSeason,
        "https://www.hbomax.com/show/$regular/s1" to regularSeason,
    )

    @Test fun findsTheAslEditionAndTheRegularSeries() {
        assertEquals(asl, HboMax.aslSeriesId(aslPage, "The Last of Us"))
        assertNull(HboMax.aslSeriesId(aslPage, "The Last"))
        assertNull(HboMax.aslSeriesId(aslPage, "Succession"))
        assertEquals(regular, HboMax.regularSeriesId(aslSeason, "The Last of Us", asl))
        assertEquals("c07ae042-082e-4b53-ba86-b6ac5e3004db", HboMax.episodeId(regularSeason, regular, 1, 1))
        assertEquals("50b6f9e5-a151-4b87-b51d-ac71a279912f", HboMax.episodeId(regularSeason, regular, 1, 2))
        assertNull(HboMax.episodeId(regularSeason, regular, 1, 3))
    }

    @Test fun swapsJustWatchsAslLinkForTheRegularEpisode() {
        val justWatch = "https://play.hbomax.com/video/watch/dae9e532-3714-4f2e-b758-fb9a13def902"
        assertEquals("https://play.hbomax.com/video/watch/c07ae042-082e-4b53-ba86-b6ac5e3004db",
            HboMax.regularEpisode(justWatch, "The Last of Us", 1, 1) { pages.getValue(it) })
    }

    @Test fun keepsTheLinkOtherwise() {
        val link = "https://play.hbomax.com/video/watch/aaaaaaaa-1111-2222-3333-444444444444"
        // no ASL edition
        assertEquals(link, HboMax.regularEpisode(link, "Succession", 1, 1) { pages.getValue(it) })
        // a page fails to load
        assertEquals(link, HboMax.regularEpisode(link, "The Last of Us", 1, 1) { throw java.io.IOException("offline") })
        // not an HBO Max link
        assertEquals("https://www.disneyplus.com/play/x",
            HboMax.regularEpisode("https://www.disneyplus.com/play/x", "The Last of Us", 1, 1) { error("no fetch") })
    }
}
