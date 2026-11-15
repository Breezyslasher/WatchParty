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
