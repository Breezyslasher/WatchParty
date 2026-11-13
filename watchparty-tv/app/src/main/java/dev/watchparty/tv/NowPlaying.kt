package dev.watchparty.tv

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.regex.Pattern

/*
 * "Share what's playing": turning what the host last watched into something
 * the room can start, without pasting a link.
 *
 * Media sessions give the app and (usually) the title, never an id. Where an
 * app's id can be found elsewhere it becomes a real link; otherwise the room
 * gets a title-only item and every TV opens that app for its viewer to pick
 * the title, then sync takes over.
 *
 * Disney+: its SDK logs the explore request for a title's page, which carries
 * the same id /play/<id> takes — confirmed on a Sony Android TV 9 for Iron Man
 * (entity-0c520152-…) and Thunderbolts* (entity-f51dce23-…). Reading other
 * apps' logs needs android.permission.READ_LOGS, granted once with adb.
 */
object NowPlaying {

    /** What the host last had playing (remembered by SyncService). */
    data class Played(val pkg: String, val title: String, val positionMs: Long, val atMs: Long)

    // The page request Disney+'s SDK logs when a title's page opens.
    private val DISNEY_ENTITY = Pattern.compile(
        "disney\\.api\\.edge\\.bamgrid\\.com.*?/page/entity-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
    )

    /** The most recent Disney+ title id in these log lines, or null. */
    fun latestDisneyEntity(lines: List<String>): String? {
        var found: String? = null
        for (line in lines) {
            val m = DISNEY_ENTITY.matcher(line)
            while (m.find()) found = m.group(1)
        }
        return found
    }

    // A title-only item: an https link on a reserved name (.invalid never
    // resolves), so Kodi members skip it like any web link.
    private const val TITLE_ONLY_HOST = "https://title.invalid/"

    fun titleOnlyLink(pkg: String, title: String): String =
        TITLE_ONLY_HOST + pkg + "?t=" + URLEncoder.encode(title, "UTF-8")

    fun isTitleOnly(link: String) = link.startsWith(TITLE_ONLY_HOST)

    /** The title in a title-only link, or null. */
    fun titleOf(link: String): String? {
        if (!isTitleOnly(link)) return null
        val q = link.indexOf("?t=")
        return if (q < 0) null else URLDecoder.decode(link.substring(q + 3), "UTF-8")
    }

    /**
     * What to share for [played]: a real link where the app's id is known,
     * else a title-only link. [logLines] is recent logcat output (empty if
     * this app can't read logs).
     */
    fun shareFor(played: Played, logLines: List<String>): MediaLink.Shared {
        if (played.pkg == MediaLink.DISNEY_PACKAGE) {
            latestDisneyEntity(logLines)?.let {
                return MediaLink.Shared("https://www.disneyplus.com/play/$it", played.pkg, played.title)
            }
        }
        return MediaLink.Shared(titleOnlyLink(played.pkg, played.title), played.pkg, played.title)
    }
}
