package dev.watchparty.tv

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
 *
 * Not converted (no web link in the path): Paramount+ (its links need the
 * title's name), HBO Max and Discovery+ (edit ids), Apple TV (its links need
 * a name too). Those still play through the title's identity (Bridge).
 */
object AddonLinks {

    fun webLink(plugin: String?): String? {
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
            "plugin.video.tubitv" -> {
                val parts = args["url"]?.takeIf { args["mode"] == "GV" }?.split('|') ?: return null
                when {
                    parts[0] == "trailer" || parts[0].isEmpty() || !parts[0].all(Char::isDigit) -> null
                    parts.size >= 3 -> "https://tubitv.com/tv-shows/${parts[0]}"
                    else -> "https://tubitv.com/movies/${parts[0]}"
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
