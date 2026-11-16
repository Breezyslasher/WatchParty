package io.github.breezyslasher.watchparty

import java.util.regex.Matcher
import java.util.regex.Pattern

/*
 * Turning a link someone shares into (a) the web link the room stores and
 * (b) what each TV actually launches.
 *
 * The room keeps an ordinary https link: Kodi members skip those safely, and
 * every TV converts it to the form its app accepts at launch time. Only
 * formats confirmed on a real TV are rewritten — tested on a Sony Android
 * TV 9 with `am start -f 0x10008000 -a VIEW -d <link>` (see tools/session-probe's
 * README). Anything else passes through unchanged; if it only opens the
 * title's page, everyone presses Play and sync takes over.
 */
object MediaLink {

    const val DISNEY_PACKAGE = "com.disney.disneyplus"
    const val NETFLIX_TV_PACKAGE = "com.netflix.ninja"
    const val HBO_PACKAGE = "com.wbd.stream"
    const val CRUNCHYROLL_PACKAGE = "com.crunchyroll.crunchyroid"
    const val APPLE_TV_PACKAGE = "com.apple.atve.androidtv.appletv"

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
    // Starz's Android TV links (JustWatch's ANDROID_TV deeplink, Yamtrack's tv_link):
    // starz.com/movies/<id> and starz.com/series/<id>/episodes/<id>. The TV app
    // claims them; the phone app only claims www.starz.com/us/<lang>/... (its
    // intent filters: /us/*/play/*, /us/*/movies*, /us/*/series*, ...).
    private val STARZ_TV = p("^https?://(?:www\\.)?starz\\.com/(movies|series)/(\\d+)(/episodes/(\\d+))?$Q")
    // Netflix: /title/<id> and /watch/<id> share the id.
    private val NETFLIX = p("^https?://(?:www\\.)?netflix\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(?:title|watch)/(\\d+)$Q")
    // HBO Max: the TV app only claims play.hbomax.com / play.max.com, so
    // www.hbomax.com/video/watch/<id> links are moved there. (Show pages use
    // a different id — the watch link has to be shared.)
    private val HBO = p("^https?://(?:www\\.|play\\.)?(?:hbo)?max\\.com/video/watch/($UUID(?:/$UUID)?)$Q")
    // Discovery+ (the same player as HBO Max): its share link is on www., the
    // link that played on a phone on play. — the same move as HBO Max's.
    private val DISCOVERY = p("^https?://(?:www\\.|play\\.)?discoveryplus\\.com/video/watch/($UUID(?:/$UUID)?)$Q")
    // Peacock: the app claims www.peacocktv.com/deeplink and /watch/asset (dumpsys,
    // phone). JustWatch's deeplink asks for the title's page ("action":"PDP") and
    // the web link opens it too; the same deeplink with "action":"PLAY" played
    // The Grinch (adb, Samsung phone). pvid = the id at the end of the web link;
    // type PROGRAMME for movies, EPISODE for episodes (as JustWatch sends them).
    private val PEACOCK_DEEP = p("^https?://(?:www\\.)?peacocktv\\.com/deeplink\\?deeplinkData=([^&#]+).*$")
    private val PEACOCK_ASSET = p("^https?://(?:www\\.)?peacocktv\\.com/watch/asset/(movies|tv)/([^?#]*)/([0-9a-f-]{36})$Q")
    // Crunchyroll: the TV app claims no web links, only crunchyroll://, which
    // opens the app but not the episode. Keep the web link clean.
    private val CRUNCHYROLL = p("^https?://(?:www\\.)?crunchyroll\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?watch/([A-Z0-9]+)(/[^?#]*)?$Q")

    // Apple TV: the app claims all of tv.apple.com. A movie page link opens the
    // page; the same link with ?action=play starts it (after the profile picker).
    private val APPLE_MOVIE = p("^https?://tv\\.apple\\.com/([a-z]{2})/movie/([^/?#]+)/(umc\\.[A-Za-z0-9.]+)$Q")
    // An episode (JustWatch: .../episode/<name>/<umc id>?showId=...&playableId=...)
    // opened its page on a phone; it gets ?action=play too, keeping showId and
    // playableId (they say which show and which playable) and dropping tracking.
    private val APPLE_EPISODE = p("^https?://tv\\.apple\\.com/([a-z]{2})/episode/([^/?#]+)/(umc\\.[A-Za-z0-9.]+)(?:\\?([^#]*))?(?:#.*)?$")

    // Apple TV by id alone (what Kodi's plugin.video.appletv has): tv.apple.com/
    // [<cc>/]movie|episode/<umc id> redirects to the title's full link, with its
    // name and an episode's showId (the nameless probe, 2026-11-16). The app
    // was only tried with full links, so the redirect is followed first.
    private val APPLE_BARE = p("^https?://tv\\.apple\\.com/(?:[a-z]{2}/)?(?:movie|episode)/(umc\\.[A-Za-z0-9.]+)/?$")

    // Affiliate redirects (Impact: *.pxf.io, *.sjv.io) wrap the real link in u=,
    // e.g. JustWatch's Crunchyroll links:
    // crunchyroll.pxf.io/xk92Nv?u=https%3A%2F%2Fwww.crunchyroll.com%2Fwatch%2F<id>&subId3=...
    private val REDIRECT = p("^https?://(?:[a-z0-9-]+\\.)*(?:pxf|sjv)\\.io/[^?#]*\\?([^#]*)(?:#.*)?$")

    /** Peacock's deeplink that starts [pvid] ("action":"PLAY"). */
    fun peacockPlay(pvid: String, type: String): String =
        "https://www.peacocktv.com/deeplink?deeplinkData=" + java.net.URLEncoder.encode(
            "{\"pvid\":\"$pvid\",\"type\":\"$type\",\"action\":\"PLAY\"}", "UTF-8")

    /** An Apple TV link by id alone, to expand with [expand] before launching. */
    fun isBareApple(link: String): Boolean = APPLE_BARE.match(link.trim()) != null

    /** [link]'s full form: where it redirects ([location]: the Location header, or
     *  null), normalized (Apple TV: + ?action=play); [link] itself if that fails. */
    fun expand(link: String, location: (String) -> String?): String {
        val to = try {
            location(link)?.let { java.net.URL(java.net.URL(link), it).toString() }
        } catch (e: Exception) {
            null
        } ?: return link
        if (!to.startsWith("https://tv.apple.com/")) return link
        return normalize(to)?.link ?: link
    }

    /** The link an affiliate redirect wraps, if [link] is one. */
    fun unwrapRedirect(link: String): String? {
        val m = REDIRECT.match(link.trim()) ?: return null
        for (param in m.group(1).split('&')) {
            if (!param.startsWith("u=")) continue
            val inner = try {
                java.net.URLDecoder.decode(param.substring(2), "UTF-8")
            } catch (e: IllegalArgumentException) {
                return null
            }
            return inner.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }
        return null
    }

    private fun p(regex: String): Pattern = Pattern.compile(regex)

    private fun Pattern.match(text: String): Matcher? = matcher(text).takeIf { it.matches() }

    /**
     * Other forms of [link] for an app that doesn't take it as given, best
     * first — each device opens the first one its app claims. Starz's TV
     * links become the web links the phone app takes: the /play/ link of the
     * movie or episode (starts it; ids are the web's, confirmed for movies on
     * a phone), then its page (the page only opens it).
     */
    fun alternatives(link: String): List<String> {
        STARZ_TV.match(link.trim())?.let {
            val id = it.group(2)
            return if (it.group(1) == "movies") listOf(
                "https://www.starz.com/us/en/play/$id",
                "https://www.starz.com/us/en/movies/$id",
            ) else {
                val page = "https://www.starz.com/us/en/series/$id${it.group(3) ?: ""}"
                val episode = it.group(4)
                if (episode != null) listOf("https://www.starz.com/us/en/play/$episode", page) else listOf(page)
            }
        }
        return emptyList()
    }

    /** Normalize a pasted link; null if it isn't a web link. */
    fun normalize(raw: String): Shared? {
        val text = raw.trim()
        if (!text.startsWith("http://") && !text.startsWith("https://")) return null
        unwrapRedirect(text)?.let { return normalize(it) }
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
        APPLE_MOVIE.match(text)?.let {
            return Shared("https://tv.apple.com/${it.group(1)}/movie/${it.group(2)}/${it.group(3)}?action=play",
                APPLE_TV_PACKAGE, "Apple TV movie")
        }
        APPLE_EPISODE.match(text)?.let {
            val keep = (it.group(4) ?: "").split('&').filter { kv ->
                kv.startsWith("showId=") || kv.startsWith("playableId=")
            }
            val query = (keep + "action=play").joinToString("&")
            return Shared("https://tv.apple.com/${it.group(1)}/episode/${it.group(2)}/${it.group(3)}?$query",
                APPLE_TV_PACKAGE, "Apple TV episode")
        }
        PEACOCK_DEEP.match(text)?.let {
            val data = try {
                java.net.URLDecoder.decode(it.group(1), "UTF-8")
            } catch (e: IllegalArgumentException) {
                null
            }
            val pvid = data?.let { d -> Regex("\"pvid\"\\s*:\\s*\"([^\"]+)\"").find(d)?.groupValues?.get(1) }
            val type = data?.let { d -> Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(d)?.groupValues?.get(1) }
            if (pvid != null && type != null) return Shared(peacockPlay(pvid, type), null, "Peacock title")
        }
        PEACOCK_ASSET.match(text)?.let {
            val episode = it.group(1) == "tv" && it.group(2).contains("/episodes/")
            if (it.group(1) == "movies" || episode) {
                return Shared(peacockPlay(it.group(3), if (episode) "EPISODE" else "PROGRAMME"), null, "Peacock title")
            }
        }
        DISCOVERY.match(text)?.let {
            return Shared("https://play.discoveryplus.com/video/watch/${it.group(1)!!.lowercase()}", null, "Discovery+ title")
        }
        CRUNCHYROLL.match(text)?.let {
            return Shared("https://www.crunchyroll.com/watch/${it.group(1)}${it.group(2) ?: ""}", CRUNCHYROLL_PACKAGE,
                "Crunchyroll title")
        }
        return Shared(text, null, hostOf(text))
    }

    /** How to launch [link] in [pkg]; [appTakesLink]: the app claims the link
     *  as is (a phone's Crunchyroll app does; the TV app doesn't). */
    fun launchFor(link: String, pkg: String, appTakesLink: Boolean = false): Launch {
        if (pkg == NETFLIX_TV_PACKAGE) {
            // Confirmed: https://www.netflix.com/watch/<id> with the extra
            // source=30 starts the title (after a few seconds) in the Android
            // TV app; without the flags/extra it only opened the app.
            NETFLIX.match(link)?.let {
                return Launch("https://www.netflix.com/watch/${it.group(1)}", mapOf("source" to "30"))
            }
        }
        if (pkg == CRUNCHYROLL_PACKAGE && !appTakesLink) {
            // The TV app's only link: opens the app (not the episode) so everyone can pick it.
            CRUNCHYROLL.match(link)?.let { return Launch("crunchyroll://watch/${it.group(1)}") }
        }
        return Launch(link)
    }

    private fun hostOf(url: String): String {
        val afterScheme = url.substringAfter("://")
        return afterScheme.substringBefore('/').substringBefore('?').removePrefix("www.")
    }
}
