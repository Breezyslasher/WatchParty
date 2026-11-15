package dev.watchparty.tv

import java.net.HttpURLConnection
import java.net.URL

/**
 * HBO Max lists some shows twice: the show, and an "<Title> (with ASL)"
 * edition with American Sign Language interpretation. That edition is a
 * separate series with its own episode ids, and JustWatch's HBO Max links can
 * be its episodes: for The Last of Us (US, checked 2026-11-15) S1 E1
 * `dae9e532…` is "When You're Lost in the Darkness (with ASL)" and S1 E2
 * `2449a6d6…` is "Infected (with ASL)"; the regular ones are `c07ae042…` and
 * `50b6f9e5…`.
 *
 * [regularEpisode] swaps such a link for the regular edition's episode, from
 * HBO Max's public pages (no account; the page probe workflow found them):
 *
 *   www.hbomax.com/accessibility/asl   every ASL title: a Series item with its
 *                                      seriesId and title "<Title> (with ASL)"
 *   www.hbomax.com/show/<asl id>/s<N>  "You May Also Like" lists the regular
 *                                      series: a Series item titled "<Title>"
 *   www.hbomax.com/show/<id>/s<N>      season N's episodes:
 *                                      "episodeUrl":"/show/<id>/s<N>/e<E>-<slug>/<video id>"
 *
 * Shows without an ASL edition cost one fetch (the ASL list, kept for the
 * session); anything unexpected keeps the original link.
 */
object HboMax {
    private const val SITE = "https://www.hbomax.com"
    private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val WATCH = Regex("""(?:hbo)?max\.com/video/watch/($UUID)""")

    /** The fields of one JSON object, with at most one level of nested objects
     *  (offeringDates, title) — so a match can't run into the next item. */
    private const val FIELDS = """(?:[^{}]|\{[^{}]*\})*?"""

    private val cache = HashMap<String, String>()

    fun isHboMax(link: String) = WATCH.containsMatchIn(link)

    /** The regular edition's link for [season] / [episode] of [title], or [link] unchanged. */
    fun regularEpisode(link: String, title: String, season: Int, episode: Int,
                       fetch: (String) -> String = ::cachedGet): String {
        if (!isHboMax(link) || title.isBlank()) return link
        return try {
            val asl = aslSeriesId(fetch("$SITE/accessibility/asl"), title) ?: return link
            val regular = regularSeriesId(fetch("$SITE/show/$asl/s$season"), title, asl) ?: return link
            val id = episodeId(fetch("$SITE/show/$regular/s$season"), regular, season, episode) ?: return link
            "https://play.hbomax.com/video/watch/$id"
        } catch (e: Exception) {
            link
        }
    }

    /** The series id of "<title> (with ASL)" on the ASL page, if HBO Max has one. */
    fun aslSeriesId(page: String, title: String): String? =
        Regex(""""__typename":"Series","seriesId":"($UUID)"$FIELDS"title":\{[^{}]*"short":"${titlePattern(title)} \(with ASL\)"""",
            RegexOption.IGNORE_CASE).find(page)?.groupValues?.get(1)

    /** The regular series ("<title>", not the ASL one) listed on an ASL series' page. */
    fun regularSeriesId(page: String, title: String, aslId: String): String? =
        Regex(""""__typename":"Series","seriesId":"($UUID)"$FIELDS"title":\{[^{}]*"short":"${titlePattern(title)}"""",
            RegexOption.IGNORE_CASE).findAll(page).map { it.groupValues[1] }.firstOrNull { it != aslId }

    /** An episode's video id on its season page. */
    fun episodeId(page: String, seriesId: String, season: Int, episode: Int): String? =
        Regex(""""episodeUrl":"/show/$seriesId/s$season/e$episode-[^"/]*/($UUID)"""").find(page)?.groupValues?.get(1)

    /** The title as a regex: literal, except either apostrophe matches (HBO Max writes ’). */
    private fun titlePattern(title: String): String =
        title.trim().split('\'', '’').joinToString("['’]") { Regex.escape(it) }

    private fun cachedGet(url: String): String {
        synchronized(cache) { cache[url]?.let { return it } }
        val text = get(url)
        synchronized(cache) {
            if (cache.size > 6) cache.clear()   // pages are ~1 MB: keep a few
            cache[url] = text
        }
        return text
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            if (conn.responseCode != 200) throw java.io.IOException("HBO Max page ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
