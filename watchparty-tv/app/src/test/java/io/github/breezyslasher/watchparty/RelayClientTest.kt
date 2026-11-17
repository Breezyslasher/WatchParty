package io.github.breezyslasher.watchparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talks to a real relay. Start one, then run with WATCHPARTY_TEST_RELAY set:
 *
 *   python3 relay_standalone.py --port 8765   (from the Kodi addon)
 *   WATCHPARTY_TEST_RELAY=http://127.0.0.1:8765 ./gradlew test
 *
 * Skipped otherwise. Needs org.json on the test classpath (the Android
 * framework provides it on devices).
 */
class RelayClientTest {

    private val relay: String? = System.getenv("WATCHPARTY_TEST_RELAY")
    private val link = "https://www.disneyplus.com/play/f51dce23-7d8f-490a-9f0c-be1b5432c2a9"

    @Test fun twoMembersShareAnAppItemAndPause() {
        assumeTrue("set WATCHPARTY_TEST_RELAY to run", relay != null)
        val room = "TEST" + System.nanoTime() % 100000
        val host = RelayClient(relay!!, room)
        val guest = RelayClient(relay, room)
        host.join("host TV")
        guest.join("guest TV")

        val opened = host.command("open", 0.0,
            RelayClient.appItem(link, "Thunderbolts*", MediaLink.DISNEY_PACKAGE))
        assertEquals(link, opened.item?.file)
        assertTrue(opened.setByMe)

        val seen = guest.poll(0.0, true, "", false, false, 0)
        assertEquals(MediaLink.DISNEY_PACKAGE, seen.item?.androidPackage)
        assertEquals("Thunderbolts*", seen.item?.label)
        assertFalse(seen.paused)
        assertFalse(seen.setByMe)

        guest.command("pause", 42.0)
        val after = host.poll(42.0, true, MediaLink.DISNEY_PACKAGE, false, true, 0)
        assertTrue(after.paused)
        assertEquals(42.0, after.positionSec, 0.001)
        // the relay's clock is this machine's clock here
        assertEquals(System.currentTimeMillis() / 1000.0, host.serverNowSec(), 1.0)

        guest.leave()
        host.leave()
    }

    @Test fun wrongRoomIsRejected() {
        assumeTrue("set WATCHPARTY_TEST_RELAY to run", relay != null)
        val c = RelayClient(relay!!, "")
        try {
            c.join("x")
            throw AssertionError("expected a rejection")
        } catch (e: RelayClient.RelayException) {
            assertEquals(403, e.status)
        }
    }

    @Test fun baseUrlNormalization() {
        assertEquals("http://192.168.1.5:8765", RelayClient.normalizeBase("192.168.1.5:8765/"))
        assertEquals("https://relay.example.com", RelayClient.normalizeBase(" https://relay.example.com "))
    }

    // ── the bridge: identity in Kodi's own fields ──────────────────────

    @Test fun anAppItemCarriesTheTitlesIdentityInKodisFields() {
        val movie = RelayClient.appItem(link, "Thunderbolts*", MediaLink.DISNEY_PACKAGE, Identity.movie("Thunderbolts*", 2025, "986056"))
        assertEquals("movie", movie.getString("type"))
        assertEquals("Thunderbolts*", movie.getString("title"))
        assertEquals(2025, movie.getInt("year"))
        assertEquals("986056", movie.getJSONObject("ids").getString("tmdb"))
        assertEquals(MediaLink.DISNEY_PACKAGE, movie.getJSONObject("ids").getString("android"))

        val episode = RelayClient.appItem(link, "Andor · S1 · E1 · Kassa", MediaLink.DISNEY_PACKAGE, Identity.episode("Andor", 1, 1))
        assertEquals("episode", episode.getString("type"))
        assertEquals("Andor", episode.getString("show"))
        assertEquals(1, episode.getInt("season"))
        assertEquals(1, episode.getInt("episode"))
        // the label stays the item's title, as before
        assertEquals("Andor · S1 · E1 · Kassa", episode.getString("title"))
        assertFalse(episode.has("year"))
        // the show's id and premiere year, which tell same-named shows apart
        val onePiece = RelayClient.appItem(link, "One Piece", "com.netflix.ninja",
            Identity.episode("One Piece", 1, 1, 2023, mapOf("show_tmdb" to "111110")))
        assertEquals(2023, onePiece.getInt("year"))
        assertEquals("111110", onePiece.getJSONObject("ids").getString("show_tmdb"))

        // without an identity: as before
        val plain = RelayClient.appItem(link, "X", MediaLink.DISNEY_PACKAGE)
        assertFalse(plain.has("type"))
    }

    @Test fun readsAKodiItemsIdentity() {
        // engine.py _identity() for a library movie and episode, as the relay stores them
        val movie = org.json.JSONObject("""{"file":"nfs://nas/films/thunderbolts.mkv","label":"Thunderbolts*",
            "type":"movie","title":"Thunderbolts*","year":2025,"ids":{"tmdb":"986056","imdb":"tt20969586"}}""")
        assertEquals(Identity("movie", title = "Thunderbolts*", year = 2025, ids = mapOf("tmdb" to "986056", "imdb" to "tt20969586")),
            RelayClient.parseIdentity(movie))
        val episode = org.json.JSONObject("""{"file":"smb://nas/tv/andor/s01e01.mkv","label":"Kassa",
            "type":"episode","show":"Andor","season":1,"episode":1,"ids":{"tvdb":"8984950"}}""")
        assertEquals(Identity("episode", show = "Andor", season = 1, episode = 1, ids = mapOf("tvdb" to "8984950")),
            RelayClient.parseIdentity(episode))
        // a song, or no identity: nothing to look up
        assertEquals(null, RelayClient.parseIdentity(org.json.JSONObject("""{"file":"x","type":"song","title":"Karma Police"}""")))
        assertEquals(null, RelayClient.parseIdentity(org.json.JSONObject("""{"file":"x","label":"x"}""")))
    }

    @Test fun theRelayKeepsTheIdentity() {
        assumeTrue("set WATCHPARTY_TEST_RELAY to run", relay != null)
        val room = "BRIDGE" + System.nanoTime() % 100000
        val kodi = RelayClient(relay!!, room)
        val tv = RelayClient(relay, room)
        kodi.join("Kodi")
        tv.join("TV")
        kodi.command("open", 0.0, org.json.JSONObject("""{"file":"smb://nas/tv/andor/s01e01.mkv","label":"Kassa",
            "type":"episode","show":"Andor","season":1,"episode":1}"""))
        val seen = tv.poll(0.0, true, "", false, false, 0)
        assertEquals(Identity.episode("Andor", 1, 1), seen.item?.identity)
        assertEquals(null, seen.item?.androidPackage)
        kodi.leave()
        tv.leave()
    }
}
