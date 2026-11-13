package dev.watchparty.tv

import java.util.regex.Pattern

/*
 * Turning a link someone shares into the link every TV should open.
 *
 * Only formats confirmed on a real TV are rewritten (see session-probe's
 * README for how each was tested). Anything else is passed through exactly
 * as shared: Android hands it to whichever app registered that website, and
 * if that only opens the title's page, everyone presses Play and sync takes
 * over from there.
 */
object MediaLink {

    const val DISNEY_PACKAGE = "com.disney.disneyplus"

    /** Disney+ title ids are UUIDs, in /play/<id> or /browse/entity-<id>.
     *  (java.util.regex rather than kotlin.text.Regex keeps the APK simple.) */
    private val DISNEY_ID: Pattern = Pattern.compile(
        "^https?://(?:www\\.)?disneyplus\\.com/(?:[a-z]{2}-[a-z]{2}/)?" +
            "(?:play/|browse/entity-)([0-9a-fA-F-]{36})(?:[/?#].*)?$"
    )

    data class Shared(val link: String, val knownPackage: String?, val label: String)

    /**
     * Normalize a pasted link. Returns null if it isn't a web link at all.
     * Confirmed on a Sony Android TV 9: disneyplus.com/play/<id> starts
     * playback; /browse/entity-<id> only opens the page — so both become
     * /play/<id>, and tracking parameters (?contextId=…) are dropped.
     */
    fun normalize(raw: String): Shared? {
        val text = raw.trim()
        if (!text.startsWith("http://") && !text.startsWith("https://")) return null
        val disney = DISNEY_ID.matcher(text)
        if (disney.matches()) {
            val id = disney.group(1)!!.lowercase()
            return Shared("https://www.disneyplus.com/play/$id", DISNEY_PACKAGE, "Disney+ title")
        }
        return Shared(text, null, hostOf(text))
    }

    private fun hostOf(url: String): String {
        val afterScheme = url.substringAfter("://")
        return afterScheme.substringBefore('/').substringBefore('?').removePrefix("www.")
    }
}
