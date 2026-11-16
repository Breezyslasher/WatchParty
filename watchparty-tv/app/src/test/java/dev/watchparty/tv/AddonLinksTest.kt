package dev.watchparty.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kodi addons' plugin paths (as each addon builds them, from its source) → web links. */
class AddonLinksTest {

    @Test fun convertsTheAddonsPaths() {
        val cases = listOf(
            "plugin://plugin.video.netflix/play/movie/80057281/" to "https://www.netflix.com/watch/80057281",
            "plugin://plugin.video.netflix/play_strm/movie/80057281/" to "https://www.netflix.com/watch/80057281",
            "plugin://plugin.video.netflix/play/show/80057281/season/80077209/episode/80077368/" to "https://www.netflix.com/watch/80077368",
            "plugin://plugin.video.youtube/play/?video_id=dQw4w9WgXcQ" to "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            // SlyGuy's router: route in "_", arguments sorted, the profile appended by the addon
            "plugin://slyguy.disney.plus/?_=play&deeplink_id=entity-f51dce23-7d8f-490a-9f0c-be1b5432c2a9&profile_id=abc" to
                "https://www.disneyplus.com/play/f51dce23-7d8f-490a-9f0c-be1b5432c2a9",
            "plugin://slyguy.hulu/?_=play&id=EAB%3A%3A3e8b8c5f-1a2b-4c3d-9e8f-0a1b2c3d4e5f%3A%3A60585172%3A%3A123" to
                "https://www.hulu.com/watch/3e8b8c5f-1a2b-4c3d-9e8f-0a1b2c3d4e5f",
            "plugin://plugin.video.amazon-test/?asin=B0CH88VRKB&mode=PlayVideo&name=Iron+Man&adult=0" to
                "https://watch.amazon.com/watch?asin=B0CH88VRKB",
            "plugin://plugin.video.amazon-test/?asin=amzn1.dv.gti.1234&mode=PlayVideo&name=&adult=0" to
                "https://watch.amazon.com/watch?gti=amzn1.dv.gti.1234",
            "plugin://plugin.video.tubitv/?mode=GV&url=100012345" to "https://tubitv.com/movies/100012345",
            "plugin://plugin.video.tubitv/?mode=GV&url=200012345%7C300012345%7C1" to "https://tubitv.com/tv-shows/200012345",
            "plugin://plugin.video.crunchyroll/video/GY5P48XEY/GRDQPM1ZY/abc123" to "https://www.crunchyroll.com/watch/GRDQPM1ZY",
            "plugin://plugin.video.crunchyroll/video/G4PH0WXVJ/abc123" to "https://www.crunchyroll.com/watch/G4PH0WXVJ",
            // SkyOTT (Peacock): the slug is the web link's path (the peacock probe)
            "plugin://plugin.video.skyott/?action=play&slug=%2Fmovies%2Fthe-grinch%2F153a1aee-b720-356e-bac2-6a9b85fb81b3" to
                "https://www.peacocktv.com/watch/asset/movies/the-grinch/153a1aee-b720-356e-bac2-6a9b85fb81b3",
            "plugin://plugin.video.skyott/?action=play&slug=%2Ftv%2Fthe-office%2F4902514835143843112%2Fseasons%2F1%2Fepisodes%2Fpilot-episode-1%2F58fabbf4-9291-3575-bcdc-bd25eee192c8" to
                "https://www.peacocktv.com/watch/asset/tv/the-office/4902514835143843112/seasons/1/episodes/pilot-episode-1/58fabbf4-9291-3575-bcdc-bd25eee192c8",
        )
        for ((plugin, link) in cases) assertEquals(plugin, link, AddonLinks.webLink(plugin))
    }

    /** Paramount+ and Apple TV by id alone (the nameless probe, 2026-11-16). */
    @Test fun paramountAndAppleTvNeedNoName() {
        val p = "plugin://slyguy.paramount.plus/?_=play&video_id=Alcn0hcGx0HosdhcawKteH8DXh3RiOF7"
        assertEquals("https://www.paramountplus.com/movies/video/Alcn0hcGx0HosdhcawKteH8DXh3RiOF7/", AddonLinks.webLink(p, "movie"))
        // an episode, or a Kodi item without a type (the site sends a movie's id on to the movie)
        assertEquals("https://www.paramountplus.com/shows/video/Alcn0hcGx0HosdhcawKteH8DXh3RiOF7/", AddonLinks.webLink(p, "episode"))
        assertEquals("https://www.paramountplus.com/shows/video/Alcn0hcGx0HosdhcawKteH8DXh3RiOF7/", AddonLinks.webLink(p))
        assertEquals("https://tv.apple.com/movie/umc.cmc.c3xhu25rw4jxxxzq4oio6snu",
            AddonLinks.webLink("plugin://plugin.video.appletv/?action=play&item_id=umc.cmc.c3xhu25rw4jxxxzq4oio6snu&item_type=Movie"))
        assertEquals("https://tv.apple.com/episode/umc.cmc.s80mx1ic96pu6ewupz8pfasf",
            AddonLinks.webLink("plugin://plugin.video.appletv/?action=play&item_id=umc.cmc.s80mx1ic96pu6ewupz8pfasf&item_type=Episode"))
    }

    @Test fun leavesOtherPathsAlone() {
        for (plugin in listOf(
            "plugin://slyguy.max/?_=play&edit_id=0fc1d4b1-0f6a-4b5b-9c39-6a6e2ee44b3f",
            "plugin://plugin.video.appletv/?action=play&item_id=umc.cmc.1&item_type=Show",
            "plugin://slyguy.paramount.plus/?_=play_trailer&video_id=abc",
            "plugin://plugin.video.tubitv/?mode=GV&url=trailer%7C100012345",
            "plugin://plugin.video.netflix/directory/home/",
            "plugin://plugin.video.skyott/?action=play&service_key=NBCU",   // a live channel
            "plugin://slyguy.disney.plus/?_=play_trailer&deeplink_id=x",
            "smb://nas/movie.mkv", "", null,
        )) assertNull(plugin, AddonLinks.webLink(plugin))
    }

    @Test fun aKodiAddonItemIsOpenedByItsWebLink() {
        // Kodi shares the addon's path in "plugin"; its "file" is Kodi's own stream
        val json = org.json.JSONObject("""{"seq":1,"position":0,"paused":false,"speed":1,"set_at":0,
            "item":{"file":"http://127.0.0.1:52103/manifest.mpd","label":"Thunderbolts*",
            "plugin":"plugin://slyguy.disney.plus/?_=play&deeplink_id=f51dce23-7d8f-490a-9f0c-be1b5432c2a9"}}""")
        val m = RelayClient::class.java.getDeclaredMethod("parseState", org.json.JSONObject::class.java).apply { isAccessible = true }
        val room = m.invoke(RelayClient("relay.example.com", "X"), json) as RoomState
        assertEquals("https://www.disneyplus.com/play/f51dce23-7d8f-490a-9f0c-be1b5432c2a9", room.item?.link)
        // an addon this app can't convert: no link, and Kodi's stream is never opened
        val other = org.json.JSONObject("""{"seq":1,"position":0,"paused":false,"speed":1,"set_at":0,
            "item":{"file":"http://127.0.0.1:52103/manifest.mpd","label":"Thunderbolts*",
            "plugin":"plugin://slyguy.max/?_=play&edit_id=1"}}""")
        assertNull((m.invoke(RelayClient("relay.example.com", "X"), other) as RoomState).item?.link)
        // a SkyOTT (Peacock) title opens by Peacock's PLAY deeplink, the form that starts playback
        val peacock = org.json.JSONObject("""{"seq":1,"item":{"file":"http://127.0.0.1:57012/manifest.mpd","label":"The Grinch",
            "plugin":"plugin://plugin.video.skyott/?action=play&slug=%2Fmovies%2Fthe-grinch%2F153a1aee-b720-356e-bac2-6a9b85fb81b3"}}""")
        assertEquals(MediaLink.peacockPlay("153a1aee-b720-356e-bac2-6a9b85fb81b3", "PROGRAMME"),
            (m.invoke(RelayClient("relay.example.com", "X"), peacock) as RoomState).item?.link)

        // the app that opens the link is named on the item (SyncService.localize): it opens like an app link
        val core = SyncCore()
        val item = room.item!!.copy(androidPackage = MediaLink.DISNEY_PACKAGE)
        assertEquals(listOf<Action>(Action.Open("https://www.disneyplus.com/play/f51dce23-7d8f-490a-9f0c-be1b5432c2a9", MediaLink.DISNEY_PACKAGE)),
            core.tick(room.copy(item = item), LocalState(null, -1, LocalPlay.OTHER, null), 0L, 1000.0))
    }
}
