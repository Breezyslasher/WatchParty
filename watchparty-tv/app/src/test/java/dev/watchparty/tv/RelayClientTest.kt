package dev.watchparty.tv

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
}
