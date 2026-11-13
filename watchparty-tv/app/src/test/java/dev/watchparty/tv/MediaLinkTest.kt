package dev.watchparty.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
