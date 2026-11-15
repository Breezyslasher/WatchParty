package dev.watchparty.tv

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Read-only Yamtrack API client for the "Browse Yamtrack" screen: your
 * movies and shows, and search. Endpoints and response shapes are from
 * Yamtrack's API (src/api, feat/add-api; same as the Yamtrack Android app):
 *
 *   GET api/v1/media/{movie|tv}/?status=&limit=&offset=
 *       {pagination: {total, …}, results: [{item: {media_id, source,
 *        media_type, title, image}, status, …}]}
 *   GET api/v1/search/{movie|tv}/?search=
 *       {pagination: {…}, results: [{media_id, source, media_type, title, image}]}
 *
 * Auth: "Authorization: Bearer <token>" (api/authentication.py).
 */
class YamtrackClient(baseUrl: String, private val token: String) {

    private val base = normalizeBase(baseUrl)

    data class Item(
        val mediaId: String,
        val source: String,
        val mediaType: String,
        val title: String,
        val image: String?,
        val status: Int?,
    )

    data class Page(val items: List<Item>, val total: Int)

    class YamtrackException(message: String) : IOException(message)

    /** Your tracked [mediaType] ("movie" or "tv") with [status] (null = all). */
    fun library(mediaType: String, status: Int?, offset: Int, limit: Int = PAGE_SIZE): Page {
        val query = StringBuilder("limit=$limit&offset=$offset")
        if (status != null) query.append("&status=").append(status)
        return parseLibrary(get("api/v1/media/$mediaType/?$query"))
    }

    /** Search [mediaType] ("movie" or "tv") on the server's provider (TMDB). */
    fun search(mediaType: String, text: String): Page =
        parseSearch(get("api/v1/search/$mediaType/?search=" + URLEncoder.encode(text, "UTF-8") + "&limit=$PAGE_SIZE"))

    /**
     * Streaming services for a movie or show, with Yamtrack's JustWatch
     * links (`tv_link`, else `link`) — empty when the server has
     * JUSTWATCH_ENABLED off (links are then null).
     *   GET api/v1/media/{type}/{source}/{id}/providers/?region=
     */
    fun providers(mediaType: String, source: String, mediaId: String, region: String): List<JustWatch.Offer> =
        parseProviders(get("api/v1/media/$mediaType/$source/$mediaId/providers/?region=" +
            URLEncoder.encode(region, "UTF-8")))

    private fun get(path: String): JSONObject {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == 401 || code == 403) throw YamtrackException("Yamtrack didn't accept the API token")
            if (code == 404) throw YamtrackException("No Yamtrack API at that address (needs a server with the API)")
            if (code >= 400) throw YamtrackException("Yamtrack error $code")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val PAGE_SIZE = 40

        /** Yamtrack's status numbers (api/helpers.py MEDIA_STATUS_MAP). */
        const val PLANNING = 0
        const val IN_PROGRESS = 1
        const val PAUSED = 2
        const val COMPLETED = 3
        const val DROPPED = 4

        /** "yamtrack.lan:8000" → "https://yamtrack.lan:8000/" (http kept if given). */
        fun normalizeBase(input: String): String {
            var s = input.trim()
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            while (s.endsWith("/")) s = s.substring(0, s.length - 1)
            return "$s/"
        }

        fun parseLibrary(response: JSONObject): Page {
            val results = response.optJSONArray("results")
            val items = mutableListOf<Item>()
            if (results != null) {
                for (i in 0 until results.length()) {
                    val entry = results.optJSONObject(i) ?: continue
                    val item = entry.optJSONObject("item") ?: continue
                    val status = if (entry.isNull("status") || !entry.has("status")) null else entry.optInt("status")
                    parseItem(item, status)?.let { items += it }
                }
            }
            return Page(items, total(response, items.size))
        }

        fun parseSearch(response: JSONObject): Page {
            val results = response.optJSONArray("results")
            val items = mutableListOf<Item>()
            if (results != null) {
                for (i in 0 until results.length()) {
                    parseItem(results.optJSONObject(i) ?: continue, null)?.let { items += it }
                }
            }
            return Page(items, total(response, items.size))
        }

        /** The no-charge (streaming) providers that have a link: `tv_link`
         *  (starts the title) else `link`, through MediaLink's fixes. */
        fun parseProviders(response: JSONObject): List<JustWatch.Offer> {
            val providers = response.optJSONArray("no_charge") ?: return emptyList()
            val out = mutableListOf<JustWatch.Offer>()
            for (i in 0 until providers.length()) {
                val p = providers.optJSONObject(i) ?: continue
                val raw = text(p, "tv_link") ?: text(p, "link") ?: continue
                val link = MediaLink.normalize(raw)?.link ?: continue
                val name = p.optString("provider_name")
                if (out.none { it.link == link }) out += JustWatch.Offer(JustWatch.simplify(name), name, link)
            }
            return out
        }

        private fun text(o: JSONObject, key: String): String? =
            if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }

        /** TMDB's full-size poster ("/t/p/original/") → a list-sized one. */
        fun posterUrl(image: String?): String? {
            if (image.isNullOrBlank()) return null
            val at = image.indexOf("/t/p/original/")
            if (at < 0) return image
            var rest = image.substring(at + "/t/p/original/".length)
            while (rest.startsWith("/")) rest = rest.substring(1)   // Yamtrack has "original//abc.jpg"
            return image.substring(0, at) + "/t/p/w185/" + rest
        }

        private fun parseItem(o: JSONObject, status: Int?): Item? {
            val id = o.optString("media_id").takeIf { it.isNotEmpty() } ?: return null
            val image = if (o.isNull("image")) null else o.optString("image").takeIf { it.isNotEmpty() }
            return Item(id, o.optString("source"), o.optString("media_type"), o.optString("title"), image, status)
        }

        private fun total(response: JSONObject, fallback: Int): Int =
            response.optJSONObject("pagination")?.optInt("total", fallback) ?: fallback
    }
}
