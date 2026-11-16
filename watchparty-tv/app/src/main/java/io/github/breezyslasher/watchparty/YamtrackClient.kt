package io.github.breezyslasher.watchparty

import org.json.JSONArray
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
 * The title page (detail, cast, recommendations, people) uses the same
 * endpoints as the Yamtrack Android app's details and person screens; the
 * shapes are Yamtrack's API examples (api/examples.py):
 *
 *   GET api/v1/media/{type}/{source}/{id}/          synopsis, genres, score
 *       (the source's rating, e.g. TMDB's, not yours), details{release_date |
 *       first_air_date, runtime, status, seasons, episodes}
 *   GET api/v1/media/{type}/{source}/{id}/cast/     results[{person_id, name, character, image}]
 *   GET api/v1/media/{type}/{source}/{id}/recommendations/   [{media_id, source, media_type, title, image}]
 *   GET api/v1/people/{source}/{id}/                name, image, biography, birthday, …
 *   GET api/v1/people/{source}/{id}/known_for/      results[{…, character, release_date}], most votes first
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
        /** "1999-03-31" where the list has it (known-for). */
        val date: String? = null,
        /** The character, for a person's known-for titles. */
        val role: String? = null,
        /** JustWatch's id, for a title found on JustWatch (search without Yamtrack). */
        val jwId: String? = null,
    ) {
        companion object {
            /** A JustWatch title as an item: by its TMDB id when it has one, so a
             *  Yamtrack server (if set) can fill its page; else by JustWatch's id. */
            fun fromJustWatch(t: JustWatch.Title): Item = Item(
                mediaId = t.tmdbId ?: t.id,
                source = if (t.tmdbId != null) "tmdb" else "justwatch",
                mediaType = if (t.objectType == "SHOW") "tv" else "movie",
                title = t.title,
                image = t.poster,
                status = null,
                date = t.year.takeIf { it > 0 }?.toString(),
                jwId = t.id,
            )
        }
    }

    /** The title page. [score] is the source's rating (TMDB's), not the user's. */
    data class Detail(
        val title: String,
        val image: String?,
        val synopsis: String?,
        val genres: List<String>,
        val score: Double?,
        val scoreCount: Int?,
        /** release_date (movies) or first_air_date (shows), "yyyy-MM-dd". */
        val date: String?,
        /** "2h 6m", "50m" (Yamtrack formats it). */
        val runtime: String?,
        val status: String?,
        val seasons: Int?,
        val episodes: Int?,
    )

    data class CastMember(val personId: String, val source: String, val name: String, val character: String?, val image: String?)

    data class Person(
        val name: String,
        val image: String?,
        val biography: String?,
        val birthday: String?,
        val deathday: String?,
        val placeOfBirth: String?,
        val department: String?,
    )

    data class Page(val items: List<Item>, val total: Int)

    /** A season or an episode of a show (Yamtrack's TMDB data). */
    data class Numbered(val number: Int, val title: String)

    class YamtrackException(message: String, val code: Int = 0) : IOException(message)

    /**
     * Your tracked [mediaType] ("movie" or "tv") with [status] (null = all),
     * in [sort] order: `<field>[_asc|_desc]` (api/helpers.py parse_sort_filter,
     * Breezyslasher/Yamtrack feat/justwatch-links), e.g. "updated_desc" —
     * last progress first, never-updated titles last. Without a sort the
     * server lists titles in the order they were first added. A server that
     * rejects the sort (400, an older API) is asked again without it.
     */
    fun library(mediaType: String, status: Int?, offset: Int, limit: Int = PAGE_SIZE, sort: String? = null): Page {
        val query = StringBuilder("limit=$limit&offset=$offset")
        if (status != null) query.append("&status=").append(status)
        if (!sort.isNullOrBlank()) {
            try {
                return parseLibrary(get("api/v1/media/$mediaType/?$query&sort=" + URLEncoder.encode(sort, "UTF-8")))
            } catch (e: YamtrackException) {
                if (e.code != 400) throw e
            }
        }
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

    /** A TMDB show's seasons: GET api/v1/media/tv/tmdb/{id}/seasons/ */
    fun seasons(tmdbId: String): List<Numbered> =
        parseNumbered(get("api/v1/media/tv/tmdb/$tmdbId/seasons/?limit=$MAX_LIMIT"), "season_number")

    /** A season's episodes: GET api/v1/media/tv/tmdb/{id}/{season}/episodes/ */
    fun episodes(tmdbId: String, season: Int): List<Numbered> =
        parseNumbered(get("api/v1/media/tv/tmdb/$tmdbId/$season/episodes/?limit=$MAX_LIMIT"), "episode_number")

    /**
     * One episode's services with Yamtrack's per-episode JustWatch links
     * (feat/justwatch-links 4bc82c5) — empty when JustWatch is off or
     * doesn't have the episode (the season's TMDB providers have no links).
     *   GET api/v1/media/tv/tmdb/{id}/{season}/{episode}/providers/?region=
     */
    fun episodeProviders(tmdbId: String, season: Int, episode: Int, region: String): List<JustWatch.Offer> =
        parseProviders(get("api/v1/media/tv/tmdb/$tmdbId/$season/$episode/providers/?region=" +
            URLEncoder.encode(region, "UTF-8")))

    /** The title page: GET api/v1/media/{type}/{source}/{id}/ */
    fun detail(mediaType: String, source: String, mediaId: String): Detail =
        parseDetail(get("api/v1/media/$mediaType/$source/$mediaId/"))

    /** Top-billed cast: GET api/v1/media/{type}/{source}/{id}/cast/ */
    fun cast(mediaType: String, source: String, mediaId: String, limit: Int = 20): List<CastMember> =
        parseCast(get("api/v1/media/$mediaType/$source/$mediaId/cast/?limit=$limit"), source)

    /** "More like this": GET api/v1/media/{type}/{source}/{id}/recommendations/ (a JSON array) */
    fun recommendations(mediaType: String, source: String, mediaId: String): List<Item> =
        parseItems(JSONArray(getText("api/v1/media/$mediaType/$source/$mediaId/recommendations/")))

    /** GET api/v1/people/{source}/{id}/ */
    fun person(source: String, personId: String): Person = parsePerson(get("api/v1/people/$source/$personId/"))

    /** Movies and shows the person is known for: GET api/v1/people/{source}/{id}/known_for/ */
    fun knownFor(source: String, personId: String, limit: Int = PAGE_SIZE): List<Item> =
        parseKnownFor(get("api/v1/people/$source/$personId/known_for/?limit=$limit"))

    private fun get(path: String): JSONObject = JSONObject(getText(path))

    private fun getText(path: String): String {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == 401 || code == 403) throw YamtrackException("Yamtrack didn't accept the API token")
            if (code == 404) throw YamtrackException("No Yamtrack API at that address (needs a server with the API)")
            if (code >= 400) throw YamtrackException("Yamtrack error $code", code)
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val PAGE_SIZE = 40

        /** Library orders (the API's sort values), the first the default. */
        val SORTS = listOf(
            "updated_desc" to "Recently updated",
            "added_desc" to "Recently added",
            "title" to "Title A–Z",
            "score_desc" to "Your rating",
        )
        /** The API's largest page (api/helpers.py MAX_RESULT_LIMIT). */
        const val MAX_LIMIT = 200

        /** Yamtrack's status numbers (api/helpers.py MEDIA_STATUS_MAP). */
        const val PLANNING = 0
        const val IN_PROGRESS = 1
        const val PAUSED = 2
        const val COMPLETED = 3
        const val DROPPED = 4

        /**
         * Which status filters to show, in [order], given how many titles each
         * has ([all] is the code for "no filter"; a count < 0 means unknown, so
         * it stays). Empty statuses go; "All" goes too when it would show the
         * same titles as the only status left; nothing at all → empty list.
         */
        fun statusesWithTitles(counts: Map<Int, Int>, order: List<Int>, all: Int): List<Int> {
            if (counts[all] == 0) return emptyList()
            val shown = order.filter { (counts[it] ?: -1) != 0 }
            val statuses = shown.filter { it != all }
            if (statuses.size == 1 && all in shown && counts[all] == counts[statuses[0]]) return statuses
            return shown
        }

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

        /** Seasons or episodes from a paginated list of media
         *  (`results[].item.season_number` / `episode_number`, `title`). */
        fun parseNumbered(response: JSONObject, numberKey: String): List<Numbered> {
            val results = response.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<Numbered>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i)?.optJSONObject("item") ?: continue
                if (item.isNull(numberKey) || !item.has(numberKey)) continue
                out += Numbered(item.optInt(numberKey), text(item, "title") ?: "")
            }
            return out.sortedBy { it.number }
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
                // the logo: TMDB's (sized down) or JustWatch's icon
                val icon = posterUrl(text(p, "image"), "w92")
                if (out.none { it.link == link }) out += JustWatch.Offer(JustWatch.simplify(name), name, link, icon)
            }
            return out
        }

        fun parseDetail(o: JSONObject): Detail {
            val d = o.optJSONObject("details") ?: JSONObject()
            val genres = mutableListOf<String>()
            o.optJSONArray("genres")?.let { for (i in 0 until it.length()) it.optString(i).takeIf { g -> g.isNotBlank() }?.let { g -> genres += g } }
            val count = int(o, "score_count")
            // TMDB gives 0.0 for "no votes"
            val score = if (o.isNull("score") || !o.has("score")) null
            else o.optDouble("score").takeIf { !it.isNaN() && it > 0.0 && count != 0 }
            // Yamtrack's text for an empty TMDB overview (providers/tmdb.py get_synopsis)
            val synopsis = text(o, "synopsis")?.takeIf { it != "No synopsis available." }
            return Detail(
                title = o.optString("title"),
                image = text(o, "image"),
                synopsis = synopsis,
                genres = genres,
                score = score,
                scoreCount = if (score == null) null else count,
                date = text(d, "release_date") ?: text(d, "first_air_date"),
                runtime = text(d, "runtime"),
                status = text(d, "status"),
                seasons = int(d, "seasons"),
                episodes = int(d, "episodes"),
            )
        }

        fun parseCast(response: JSONObject, source: String): List<CastMember> {
            val results = response.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<CastMember>()
            for (i in 0 until results.length()) {
                val p = results.optJSONObject(i) ?: continue
                val id = text(p, "person_id") ?: continue
                out += CastMember(id, source, p.optString("name"), text(p, "character"), text(p, "image"))
            }
            return out
        }

        /** A plain array of media (recommendations). */
        fun parseItems(array: JSONArray): List<Item> {
            val out = mutableListOf<Item>()
            for (i in 0 until array.length()) parseItem(array.optJSONObject(i) ?: continue, null)?.let { out += it }
            return out
        }

        fun parsePerson(o: JSONObject) = Person(
            name = o.optString("name"),
            image = text(o, "image"),
            biography = text(o, "biography"),
            birthday = text(o, "birthday"),
            deathday = text(o, "deathday"),
            placeOfBirth = text(o, "place_of_birth"),
            department = text(o, "known_for_department"),
        )

        /** Known-for titles (movies and shows only, each once). */
        fun parseKnownFor(response: JSONObject): List<Item> {
            val results = response.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                val item = parseItem(o, null) ?: continue
                if (item.mediaType != "movie" && item.mediaType != "tv") continue
                if (out.any { it.mediaId == item.mediaId && it.mediaType == item.mediaType }) continue
                out += item.copy(date = text(o, "release_date"), role = text(o, "character"))
            }
            return out
        }

        private fun int(o: JSONObject, key: String): Int? = if (o.isNull(key) || !o.has(key)) null else o.optInt(key)

        private fun text(o: JSONObject, key: String): String? =
            if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }

        /** A TMDB image at any size ("/t/p/original/", "/t/p/w500/") → a list-sized one. */
        fun posterUrl(image: String?, size: String = "w185"): String? {
            if (image.isNullOrBlank()) return null
            val at = image.indexOf("/t/p/")
            if (at < 0) return image
            val sizeEnd = image.indexOf('/', at + "/t/p/".length)
            if (sizeEnd < 0) return image
            var rest = image.substring(sizeEnd + 1)
            while (rest.startsWith("/")) rest = rest.substring(1)   // Yamtrack has "original//abc.jpg"
            return image.substring(0, at) + "/t/p/$size/" + rest
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
