package dev.watchparty.tv

import java.util.regex.Matcher
import java.util.regex.Pattern

/*
 * Turning a link someone shares into (a) the web link the room stores and
 * (b) what each TV actually launches.
 *
 * The room keeps an ordinary https link: Kodi members skip those safely, and
 * every TV converts it to the form its app accepts at launch time. Only
 * formats confirmed on a real TV are rewritten — tested on a Sony Android
 * TV 9 with `am start -f 0x10008000 -a VIEW -d <link>` (see session-probe's
 * README). Anything else passes through unchanged; if it only opens the
 * title's page, everyone presses Play and sync takes over.
 */
object MediaLink {

    const val DISNEY_PACKAGE = "com.disney.disneyplus"
    const val NETFLIX_TV_PACKAGE = "com.netflix.ninja"
    const val HBO_PACKAGE = "com.wbd.stream"
    const val CRUNCHYROLL_PACKAGE = "com.crunchyroll.crunchyroid"

    /** What to share with the room. [knownPackage] is null when the app
     *  should be found by asking Android who opens [link]. */
    data class Shared(val link: String, val knownPackage: String?, val label: String)

    /** What to hand Android on this TV. */
    data class Launch(val uri: String, val extras: Map<String, String> = emptyMap())

    private const val Q = "(?:[/?#].*)?$"   // rest of the URL: ignored (tracking etc.)
    private const val UUID = "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"

    // Disney+: /play/<id> starts playback; /browse/entity-<id> (same id) only opens the page.
    private val DISNEY = p("^https?://(?:www\\.)?disneyplus\\.com/(?:[a-z]{2}-[a-z]{2}/)?(?:play/|browse/entity-)$UUID$Q")
    // Starz: /play/<id> starts playback; the /movies/<id> page uses the same id.
    private val STARZ = p("^https?://(?:www\\.)?starz\\.com/([a-z]{2})/([a-z]{2})/(?:play|movies)/(\\d+)$Q")
    // Netflix: /title/<id> and /watch/<id> share the id.
    private val NETFLIX = p("^https?://(?:www\\.)?netflix\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(?:title|watch)/(\\d+)$Q")
    // HBO Max: the TV app only claims play.hbomax.com / play.max.com, so
    // www.hbomax.com/video/watch/<id> links are moved there. (Show pages use
    // a different id — the watch link has to be shared.)
    private val HBO = p("^https?://(?:www\\.|play\\.)?(?:hbo)?max\\.com/video/watch/($UUID(?:/$UUID)?)$Q")
    // Crunchyroll: the TV app claims no web links, only crunchyroll://, which
    // opens the app but not the episode. Keep the web link clean.
    private val CRUNCHYROLL = p("^https?://(?:www\\.)?crunchyroll\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?watch/([A-Z0-9]+)(/[^?#]*)?$Q")

    private fun p(regex: String): Pattern = Pattern.compile(regex)

    private fun Pattern.match(text: String): Matcher? = matcher(text).takeIf { it.matches() }

    /** Normalize a pasted link; null if it isn't a web link. */
    fun normalize(raw: String): Shared? {
        val text = raw.trim()
        if (!text.startsWith("http://") && !text.startsWith("https://")) return null
        DISNEY.match(text)?.let {
            return Shared("https://www.disneyplus.com/play/${it.group(1)!!.lowercase()}", DISNEY_PACKAGE, "Disney+ title")
        }
        STARZ.match(text)?.let {
            return Shared("https://www.starz.com/${it.group(1)}/${it.group(2)}/play/${it.group(3)}", null, "Starz title")
        }
        NETFLIX.match(text)?.let {
            return Shared("https://www.netflix.com/watch/${it.group(1)}", null, "Netflix title")
        }
        HBO.match(text)?.let {
            return Shared("https://play.hbomax.com/video/watch/${it.group(1)!!.lowercase()}", HBO_PACKAGE, "HBO Max title")
        }
        CRUNCHYROLL.match(text)?.let {
            return Shared("https://www.crunchyroll.com/watch/${it.group(1)}${it.group(2) ?: ""}", CRUNCHYROLL_PACKAGE,
                "Crunchyroll title")
        }
        return Shared(text, null, hostOf(text))
    }

    /** How this TV should open a room link in [pkg]. */
    fun launchFor(link: String, pkg: String): Launch {
        if (pkg == NETFLIX_TV_PACKAGE) {
            // Confirmed: https://www.netflix.com/watch/<id> with the extra
            // source=30 starts the title (after a few seconds) in the Android
            // TV app; without the flags/extra it only opened the app.
            NETFLIX.match(link)?.let {
                return Launch("https://www.netflix.com/watch/${it.group(1)}", mapOf("source" to "30"))
            }
        }
        if (pkg == CRUNCHYROLL_PACKAGE) {
            // Its only link: opens the app (not the episode) so everyone can pick it.
            CRUNCHYROLL.match(link)?.let { return Launch("crunchyroll://watch/${it.group(1)}") }
        }
        return Launch(link)
    }

    private fun hostOf(url: String): String {
        val afterScheme = url.substringAfter("://")
        return afterScheme.substringBefore('/').substringBefore('?').removePrefix("www.")
    }
}
