package io.github.breezyslasher.watchparty

import java.net.URI
import java.net.URLDecoder

/**
 * Kodi streaming addons' plugin:// paths → the web link of the same title,
 * which an app here opens. A Kodi member playing an addon title shares its
 * path (the item's "plugin"); the Kodi addon does the other way
 * (service.watchparty addon_links.py). Each format was read from the addon's
 * own source (2026-11-15):
 *
 *  - plugin.video.netflix (CastagnaIT): play[_strm]/movie/<id>/ and
 *    play[_strm]/show/<s>/season/<x>/episode/<id>/ → netflix.com/watch/<id>
 *  - plugin.video.youtube: play/?video_id=<id> → youtube.com/watch?v=<id>
 *  - slyguy.disney.plus: ?_=play&deeplink_id=<id> → disneyplus.com/play/<id>
 *  - slyguy.hulu: ?_=play&id=EAB::<entity id>::… (or the id) → hulu.com/watch/<entity id>
 *  - plugin.video.amazon-test: ?mode=PlayVideo&asin=<id> → watch.amazon.com/watch?asin=<id>
 *    (gti= for a Prime Video "amzn1.dv.gti…" id, as the addon builds it)
 *  - plugin.video.tubitv: ?mode=GV&url=<id> → tubitv.com/movies/<id>;
 *    url=<episode>|<series>|<season> → tubitv.com/tv-shows/<episode>
 *  - plugin.video.crunchyroll: /video/[<series>/]<episode>/<stream> → crunchyroll.com/watch/<episode>
 *  - plugin.video.starz (Breezyslasher's, Kodi-Addons branch starz): ?action=play&content_id=<id> →
 *    starz.com/us/en/play/<id>, which starts playing in the app and on the website
 *    (tools/session-probe). Its content ids are Starz's own, as in its links.
 *  - plugin.video.skyott (Paco8; Peacock): ?action=play&slug=<slug> →
 *    peacocktv.com/watch/asset<slug>. The slug is the web link's path: the
 *    peacock probe (tools/probe/peacock_slug.py, 2026-11-16) looked up
 *    /movies/the-grinch/153a1aee-… and /tv/the-office/…/episodes/…/58fabbf4-…
 *    with the addon's own client. The addon also plays SkyShowtime and NOW,
 *    whose paths look the same; Peacock is assumed (the US service).
 *  - slyguy.paramount.plus: ?_=play&video_id=<id> → paramountplus.com/movies/video/<id>/
 *    (a movie) or /shows/video/<id>/ (anything else). Paramount+'s own links
 *    have no name: JustWatch's /movies/<name>/<id> and /shows/<show>/video/<id>/<name>
 *    redirect there, and /shows/video/<a movie's id>/ redirects to the movie
 *    (the nameless probe, tools/probe/nameless_links.py, 2026-11-16).
 *  - plugin.video.appletv: ?action=play&item_id=<umc id>&item_type=Movie|Episode
 *    → tv.apple.com/movie/<id> or /episode/<id>, which redirect to the title's
 *    full link (with its name, and an episode's showId; same probe). The kind
 *    has to be right (the other one is a 404). SyncService follows that
 *    redirect before launching (MediaLink.isBareApple).
 *
 * Not converted: HBO Max and Discovery+ (edit ids, which no public page
 * gives: HBO Max's public /video/watch/ pages are an error page, the HBO Max
 * probe, 2026-11-16). Those still play through the title's identity (Bridge).
 */
object AddonLinks {

    /**
     * The website each Kodi streaming addon plays, for a Kodi title whose
     * path can't be turned into a link: of the title's JustWatch offers, the
     * one on this site comes first (not JustWatch's first, e.g. Prime Video
     * for a Starz title), and an add-on channel (on Amazon or Apple TV) isn't it.
     */
    private val ADDON_HOSTS = mapOf(
        "plugin.video.netflix" to listOf("netflix.com"),
        "plugin.video.youtube" to listOf("youtube.com", "youtu.be"),
        "slyguy.disney.plus" to listOf("disneyplus.com"),
        "slyguy.hulu" to listOf("hulu.com"),
        "slyguy.paramount.plus" to listOf("paramountplus.com"),
        "slyguy.max" to listOf("hbomax.com", "max.com"),
        "slyguy.discovery.plus" to listOf("discoveryplus.com"),
        "plugin.video.amazon-test" to listOf("amazon.com", "primevideo.com"),
        "plugin.video.appletv" to listOf("tv.apple.com"),
        "plugin.video.skyott" to listOf("peacocktv.com"),
        "plugin.video.tubitv" to listOf("tubitv.com"),
        "plugin.video.crunchyroll" to listOf("crunchyroll.com"),
        "plugin.video.starz" to listOf("starz.com"),
        "plugin.video.plutotv" to listOf("pluto.tv"),
        "plugin.video.plexondemand" to listOf("watch.plex.tv"),
    )

    /** The addon in a plugin:// path ("plugin://slyguy.hulu/?…" → "slyguy.hulu"). */
    fun addonOf(plugin: String?): String? =
        plugin?.let { Regex("^plugin://([^/?#]+)").find(it.trim())?.groupValues?.get(1) }

    /** Whether [link] is on the site Kodi's [addon] plays. */
    fun sameService(link: String, addon: String?): Boolean {
        val hosts = ADDON_HOSTS[addon] ?: return false
        val uri = try { java.net.URI(link.trim()) } catch (e: Exception) { return false }
        val host = uri.host?.lowercase() ?: return false
        // a live channel on the same site (Plex, Pluto) isn't the title
        if (uri.path?.contains("/live-tv/") == true) return false
        return hosts.any { host == it || host.endsWith(".$it") }
    }


    /** [type]: the Kodi item's media type ("movie", "episode"), if it gave one. */
    fun webLink(plugin: String?, type: String? = null): String? {
        if (plugin.isNullOrBlank() || !plugin.startsWith("plugin://")) return null
        val uri = try {
            URI(plugin.replace(" ", "%20").replace("|", "%7C"))
        } catch (e: Exception) {
            return null
        }
        val addon = uri.host ?: return null
        val path = uri.rawPath.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        val args = query(uri.rawQuery)
        return when (addon) {
            "plugin.video.netflix" -> {
                if (path.firstOrNull() !in setOf("play", "play_strm")) return null
                val id = when (path.getOrNull(1)) {
                    "movie" -> path.getOrNull(2)
                    "show" -> path.getOrNull(6)?.takeIf { path.getOrNull(5) == "episode" }
                    else -> null
                }
                id?.takeIf { it.all(Char::isDigit) }?.let { "https://www.netflix.com/watch/$it" }
            }
            "plugin.video.youtube" ->
                args["video_id"]?.takeIf { path.firstOrNull() == "play" && it.matches(Regex("[A-Za-z0-9_-]{6,}")) }
                    ?.let { "https://www.youtube.com/watch?v=$it" }
            "slyguy.disney.plus" ->
                args["deeplink_id"]?.takeIf { args["_"] == "play" }?.removePrefix("entity-")
                    ?.let { "https://www.disneyplus.com/play/$it" }
            "slyguy.hulu" -> {
                val id = args["id"]?.takeIf { args["_"] == "play" } ?: return null
                Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").find(id)?.value
                    ?.let { "https://www.hulu.com/watch/${it.lowercase()}" }
            }
            "plugin.video.amazon-test" -> {
                val asin = args["asin"]?.takeIf { args["mode"] == "PlayVideo" } ?: return null
                val key = if (asin.startsWith("amzn1.dv.gti")) "gti" else "asin"
                "https://watch.amazon.com/watch?$key=$asin"
            }
            "plugin.video.starz" -> args["content_id"]?.takeIf { args["action"] == "play" && it.isNotEmpty() && it.all(Char::isDigit) }
                ?.let { "https://www.starz.com/us/en/play/$it" }
            "plugin.video.tubitv" -> {
                val parts = args["url"]?.takeIf { args["mode"] == "GV" }?.split('|') ?: return null
                when {
                    parts[0] == "trailer" || parts[0].isEmpty() || !parts[0].all(Char::isDigit) -> null
                    parts.size >= 3 -> "https://tubitv.com/tv-shows/${parts[0]}"
                    else -> "https://tubitv.com/movies/${parts[0]}"
                }
            }
            "plugin.video.skyott" -> {
                val slug = args["slug"]?.takeIf { args["action"] == "play" } ?: return null
                if (!slug.matches(Regex("/(movies|tv)/[^?#\\s]+"))) return null
                "https://www.peacocktv.com/watch/asset$slug"
            }
            "slyguy.paramount.plus" -> {
                val id = args["video_id"]?.takeIf { args["_"] == "play" && it.matches(Regex("[A-Za-z0-9_-]+")) } ?: return null
                if (type == "movie") "https://www.paramountplus.com/movies/video/$id/"
                else "https://www.paramountplus.com/shows/video/$id/"
            }
            "plugin.video.appletv" -> {
                val id = args["item_id"]?.takeIf { args["action"] == "play" && it.matches(Regex("umc\\.[A-Za-z0-9.]+")) }
                    ?: return null
                when (args["item_type"]?.lowercase()) {
                    "movie" -> "https://tv.apple.com/movie/$id"
                    "episode" -> "https://tv.apple.com/episode/$id"
                    else -> null
                }
            }
            "plugin.video.crunchyroll" -> {
                if (path.firstOrNull() != "video") return null
                val episode = when (path.size) {
                    4 -> path[2]
                    3 -> path[1]
                    else -> null
                }
                episode?.takeIf { it.matches(Regex("[A-Z0-9]+")) }?.let { "https://www.crunchyroll.com/watch/$it" }
            }
            else -> null
        }
    }

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in raw.split('&')) {
            val at = pair.indexOf('=')
            val k = if (at < 0) pair else pair.substring(0, at)
            val v = if (at < 0) "" else pair.substring(at + 1)
            try {
                out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            } catch (e: IllegalArgumentException) {
                // a malformed escape: skip that argument
            }
        }
        return out
    }
}
