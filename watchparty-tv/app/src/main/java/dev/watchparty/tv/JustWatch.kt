package dev.watchparty.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
 * Where to watch a title, and the link that starts it, via JustWatch — the
 * same unofficial GraphQL API justwatch.com uses (and Yamtrack's
 * feat/justwatch-links branch). Titles come from Yamtrack with their TMDB
 * id, so the match is exact; only the title and ids are sent.
 *
 * The query shapes are the ones run by tools/justwatch/jw.py on GitHub
 * Actions. Per-episode links (2026-11-15, US): Disney+ /play/<episode id>,
 * HBO Max play.hbomax.com/video/watch/<episode id>, Apple TV
 * /us/episode/<name>/umc.cmc.<id>?showId=…, Amazon watch?asin=<episode>
 * — different for every episode. Netflix's ANDROID_TV link is the *show's*
 * /watch/<id> for every episode, but its WEB link is the episode's own
 * /watch/<id>, so Netflix uses the WEB link.
 */
object JustWatch {

    private const val API = "https://apis.justwatch.com/graphql"
    private val STREAMING = setOf("FLATRATE", "FREE", "ADS", "FAST")

    data class Title(val id: String, val objectType: String, val title: String, val year: Int, val tmdbId: String?,
                     val poster: String? = null)

    /** A title page from JustWatch, for titles found without Yamtrack. [score]
     *  is TMDB's ([scoreSource] "tmdb"), else IMDb's; [runtime] in minutes. */
    data class Page(
        val title: String,
        val poster: String?,
        val synopsis: String?,
        val genres: List<String>,
        val score: Double?,
        val scoreSource: String,
        val scoreCount: Int?,
        val date: String?,
        val runtime: Int?,
        val certification: String?,
        val cast: List<Credit>,
        val similar: List<Title>,
    )

    /** An actor (JustWatch has no photos; [personId] is JustWatch's own). */
    data class Credit(val personId: String, val name: String, val character: String?)
    data class Season(val id: String, val number: Int, val title: String)
    data class Episode(val id: String, val number: Int, val title: String, val offers: List<Offer>)

    /** A service that streams the title, with the link that starts it and its logo (a URL). */
    data class Offer(val technicalName: String, val clearName: String, val link: String, val icon: String? = null)

    /** JustWatch's icons are paths on this host (as in Yamtrack's providers/justwatch.py). */
    private const val IMAGES = "https://images.justwatch.com"

    /** Links two episodes share: a service that gives every episode the same
     *  link (Pluto TV's show page, for some shows) has no episode link. */
    fun sharedLinks(episode: List<Offer>, otherEpisode: List<Offer>): Set<String> =
        episode.map { it.link }.toSet() intersect otherEpisode.map { it.link }.toSet()

    private val HBO_VIDEO = Regex("""(?:hbo)?max\.com/video/watch/([0-9a-f-]{36})""")
    private val DISCOVERY_MOVIE = Regex("""discoveryplus\.com/movie/[0-9a-f-]{36}""")

    /**
     * Discovery+ movie links are its movie page (discoveryplus.com/movie/<id>),
     * which doesn't play. HBO Max and Discovery+ share one catalog — the same
     * ids (Discovery+'s pages say "featureId" = "hbomaxId"), and HBO Max's
     * video id plays in Discovery+ too (play.discoveryplus.com/video/watch/<HBO
     * Max's id> played Ghosts of Flight 401 on a phone). So when the title is
     * on both, Discovery+ gets that play link.
     */
    fun discoveryPlayLinks(offers: List<Offer>): List<Offer> {
        val video = offers.firstNotNullOfOrNull { HBO_VIDEO.find(it.link)?.groupValues?.get(1) } ?: return offers
        return offers.map {
            if (DISCOVERY_MOVIE.containsMatchIn(it.link)) it.copy(link = "https://play.discoveryplus.com/video/watch/$video") else it
        }
    }

    /** The only service, when there's exactly one (the same link twice counts once). */
    fun soleOffer(offers: List<Offer>): Offer? = offers.distinctBy { it.link }.singleOrNull()

    // ── queries (the shapes run by tools/justwatch/jw.py) ─────────────────

    private val SEARCH = """query S(${'$'}f: TitleFilter!, ${'$'}c: Country!, ${'$'}l: Language!, ${'$'}first: Int!) {
      popularTitles(country: ${'$'}c, filter: ${'$'}f, first: ${'$'}first, sortBy: POPULAR, sortRandomSeed: 0) {
        edges { node { id objectType
          content(country: ${'$'}c, language: ${'$'}l) { title originalReleaseYear externalIds { tmdbId } } } }
      }
    }"""

    private val OFFER_FIELDS = """
        web: offers(country: ${'$'}c, platform: WEB) {
          monetizationType standardWebURL deeplinkURL(platform: WEB) package { technicalName clearName icon(profile: S100, format: PNG) }
        }
        atv: offers(country: ${'$'}c, platform: ANDROID_TV) {
          monetizationType deeplinkURL(platform: ANDROID_TV) package { technicalName clearName }
        }"""

    private val TITLE_OFFERS = """query O(${'$'}id: ID!, ${'$'}c: Country!) {
      node(id: ${'$'}id) { ... on MovieOrShowOrSeasonOrEpisode { $OFFER_FIELDS } }
    }"""

    private val SEASONS = """query S(${'$'}id: ID!, ${'$'}c: Country!, ${'$'}l: Language!) {
      node(id: ${'$'}id) { ... on Show {
        seasons(sortDirection: ASC) { id content(country: ${'$'}c, language: ${'$'}l) { seasonNumber title } }
      } }
    }"""

    private val SEASON_EPISODES = """query E(${'$'}id: ID!, ${'$'}c: Country!, ${'$'}l: Language!) {
      node(id: ${'$'}id) { ... on Season {
        episodes(sortDirection: ASC) {
          id content(country: ${'$'}c, language: ${'$'}l) { episodeNumber title }
          $OFFER_FIELDS
        }
      } }
    }"""

    /** Search and title page fields, as tools/justwatch/jw.py --detail printed
     *  them from the live API (2026-11-15). Posters: images.justwatch.com +
     *  "/poster/<id>/{profile}/<slug>.{format}" with s166…s718, jpg or webp. */
    private val TITLE_FIELDS = """id objectType
          content(country: ${'$'}c, language: ${'$'}l) { title originalReleaseYear posterUrl externalIds { tmdbId } }"""

    private val SEARCH_TITLES = """query S(${'$'}f: TitleFilter!, ${'$'}c: Country!, ${'$'}l: Language!, ${'$'}first: Int!) {
      popularTitles(country: ${'$'}c, filter: ${'$'}f, first: ${'$'}first, sortBy: POPULAR, sortRandomSeed: 0) {
        edges { node { $TITLE_FIELDS } }
      }
    }"""

    private val PAGE = """query T(${'$'}id: ID!, ${'$'}c: Country!, ${'$'}l: Language!) {
      node(id: ${'$'}id) { ... on MovieOrShow {
        content(country: ${'$'}c, language: ${'$'}l) {
          title originalReleaseDate runtime shortDescription posterUrl ageCertification
          genres { translation(language: ${'$'}l) }
          scoring { imdbScore imdbVotes tmdbScore }
          credits { role name characterName personId }
        }
        similarTitlesV2(country: ${'$'}c, first: 20) { edges { node { $TITLE_FIELDS } } }
      } }
    }"""

    // ── network (call off the main thread) ──────────────────────────────

    /** Titles matching [query], most popular first. [objectType] is "MOVIE" or "SHOW". */
    fun search(query: String, objectType: String, country: String, first: Int = 30): List<Title> =
        parseTitles(post(SEARCH_TITLES, JSONObject()
            .put("f", JSONObject().put("searchQuery", query).put("objectTypes", JSONArray().put(objectType)))
            .put("c", country).put("l", "en").put("first", first)))

    /** A title's page: description, genres, rating, cast and similar titles. */
    fun page(titleId: String, country: String): Page? =
        parsePage(post(PAGE, JSONObject().put("id", titleId).put("c", country).put("l", "en")))

    /** The JustWatch title for a Yamtrack item: by TMDB id when there is one,
     *  else by exact title. [objectType] is "MOVIE" or "SHOW". */
    fun findTitle(title: String, tmdbId: String?, objectType: String, country: String): Title? {
        val response = post(SEARCH, JSONObject()
            .put("f", JSONObject().put("searchQuery", title).put("objectTypes", JSONArray().put(objectType)))
            .put("c", country).put("l", "en").put("first", 10))
        return pickTitle(parseTitles(response), title, tmdbId)
    }

    fun offers(titleId: String, country: String): List<Offer> =
        parseOffers(post(TITLE_OFFERS, JSONObject().put("id", titleId).put("c", country))
            .optJSONObject("data")?.optJSONObject("node"))

    fun seasons(showId: String, country: String): List<Season> =
        parseSeasons(post(SEASONS, JSONObject().put("id", showId).put("c", country).put("l", "en")))

    fun episodes(seasonId: String, country: String): List<Episode> =
        parseEpisodes(post(SEASON_EPISODES, JSONObject().put("id", seasonId).put("c", country).put("l", "en")))

    // ── pure parts (unit-tested) ────────────────────────────────────────

    fun parseTitles(response: JSONObject): List<Title> {
        val edges = data(response).optJSONObject("popularTitles")?.optJSONArray("edges") ?: return emptyList()
        val out = mutableListOf<Title>()
        for (i in 0 until edges.length()) {
            val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
            out += title(node) ?: continue
        }
        return out
    }

    private fun title(node: JSONObject): Title? {
        val content = node.optJSONObject("content") ?: return null
        val tmdb = content.optJSONObject("externalIds")?.let { text(it, "tmdbId") }
        return Title(node.optString("id"), node.optString("objectType"), content.optString("title"),
            content.optInt("originalReleaseYear"), tmdb, posterUrl(text(content, "posterUrl")))
    }

    /** "/poster/329366258/{profile}/thunderbolts.{format}" → its [profile] jpg. */
    fun posterUrl(path: String?, profile: String = "s332"): String? {
        if (path.isNullOrBlank()) return null
        val filled = path.replace("{profile}", profile).replace("{format}", "jpg")
        return if (filled.startsWith("/")) IMAGES + filled else filled
    }

    fun parsePage(response: JSONObject): Page? {
        val node = data(response).optJSONObject("node") ?: return null
        val content = node.optJSONObject("content") ?: return null
        val genres = mutableListOf<String>()
        content.optJSONArray("genres")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { text(it, "translation") }?.let { genres += it }
        }
        val scoring = content.optJSONObject("scoring")
        val tmdb = scoring?.let { score(it, "tmdbScore") }
        val imdb = scoring?.let { score(it, "imdbScore") }
        val cast = LinkedHashMap<String, Credit>()   // an actor can be listed twice (two characters)
        content.optJSONArray("credits")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optString("role") != "ACTOR") continue
                val id = o.optString("personId")
                if (id.isNotEmpty() && !cast.containsKey(id)) cast[id] = Credit(id, o.optString("name"), text(o, "characterName"))
            }
        }
        val similar = mutableListOf<Title>()
        node.optJSONObject("similarTitlesV2")?.optJSONArray("edges")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.optJSONObject("node")?.let { title(it) }?.let { similar += it }
        }
        return Page(
            title = content.optString("title"),
            poster = posterUrl(text(content, "posterUrl"), "s592"),
            synopsis = text(content, "shortDescription"),
            genres = genres,
            score = tmdb ?: imdb,
            scoreSource = if (tmdb != null) "tmdb" else "imdb",
            scoreCount = if (tmdb == null && imdb != null) scoring?.optInt("imdbVotes")?.takeIf { it > 0 } else null,
            date = text(content, "originalReleaseDate"),
            runtime = content.optInt("runtime").takeIf { it > 0 },
            certification = text(content, "ageCertification"),
            cast = cast.values.take(30),
            similar = similar,
        )
    }

    /** 127 → "2h 7m", 120 → "2h", 57 → "57m" (as Yamtrack writes them). */
    fun runtimeText(minutes: Int): String = when {
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }

    private fun score(o: JSONObject, key: String): Double? =
        if (o.isNull(key)) null else o.optDouble(key).takeIf { !it.isNaN() && it > 0 }

    /** Same TMDB id if we have one; otherwise the same title (ignoring case
     *  and punctuation), most popular first. */
    fun pickTitle(titles: List<Title>, title: String, tmdbId: String?): Title? {
        if (!tmdbId.isNullOrBlank()) return titles.firstOrNull { it.tmdbId == tmdbId }
        val wanted = simplify(title)
        return titles.firstOrNull { simplify(it.title) == wanted }
    }

    fun parseSeasons(response: JSONObject): List<Season> {
        val seasons = data(response).optJSONObject("node")?.optJSONArray("seasons") ?: return emptyList()
        val out = mutableListOf<Season>()
        for (i in 0 until seasons.length()) {
            val s = seasons.optJSONObject(i) ?: continue
            val content = s.optJSONObject("content")
            out += Season(s.optString("id"), content?.optInt("seasonNumber") ?: 0,
                content?.let { text(it, "title") } ?: "")
        }
        return out
    }

    fun parseEpisodes(response: JSONObject): List<Episode> {
        val episodes = data(response).optJSONObject("node")?.optJSONArray("episodes") ?: return emptyList()
        val out = mutableListOf<Episode>()
        for (i in 0 until episodes.length()) {
            val e = episodes.optJSONObject(i) ?: continue
            val content = e.optJSONObject("content")
            out += Episode(e.optString("id"), content?.optInt("episodeNumber") ?: 0,
                content?.let { text(it, "title") } ?: "", parseOffers(e))
        }
        return out
    }

    /**
     * One offer per service from a node's `web` and `atv` offer lists
     * (streaming only, no rent/buy): the link that starts it on a TV.
     */
    fun parseOffers(node: JSONObject?): List<Offer> {
        if (node == null) return emptyList()
        val web = streaming(node.optJSONArray("web"))
        val atv = streaming(node.optJSONArray("atv"))
        val out = LinkedHashMap<String, Offer>()
        val names = LinkedHashMap<String, String>()
        for (o in web + atv) {
            val p = o.optJSONObject("package") ?: continue
            val technical = p.optString("technicalName")
            if (!names.containsKey(technical)) names[technical] = p.optString("clearName")   // putIfAbsent is API 24+
        }
        for ((technical, clear) in names) {
            val w = web.firstOrNull { technicalOf(it) == technical }
            val a = atv.firstOrNull { technicalOf(it) == technical }
            val link = linkFor(
                technical,
                webDeeplink = w?.let { text(it, "deeplinkURL") },
                atvDeeplink = a?.let { text(it, "deeplinkURL") },
                standardWeb = w?.let { text(it, "standardWebURL") },
            ) ?: continue
            // "Netflix" and "Netflix Standard with Ads" are the same app and link
            val icon = (w ?: a)?.optJSONObject("package")?.let { text(it, "icon") }?.let { if (it.startsWith("/")) IMAGES + it else it }
            if (out.values.none { it.link == link }) out[technical] = Offer(technical, clear, link, icon)
        }
        return out.values.toList()
    }

    /**
     * The link that starts the title on a TV. Netflix: its WEB deeplink (the
     * episode's own /watch/<id>; the ANDROID_TV one is the show's). Others:
     * the ANDROID_TV deeplink as an https link, else the WEB deeplink, else
     * the web page. Then MediaLink's per-service fixes.
     */
    fun linkFor(technicalName: String, webDeeplink: String?, atvDeeplink: String?, standardWeb: String?): String? {
        val netflixWatch = webDeeplink?.takeIf {
            technicalName.startsWith("netflix") && it.contains("netflix.com/watch/")
        }
        val raw = netflixWatch
            ?: atvDeeplink?.let { webLinkOf(it) }
            ?: webDeeplink?.let { webLinkOf(it) }
            ?: standardWeb?.let { webLinkOf(it) }
            ?: return null
        return MediaLink.normalize(raw)?.link ?: raw
    }

    /** "intent://host/path?q#Intent;…;scheme=https;end" → "https://host/path?q";
     *  plain https/http links pass through; other schemes → null. */
    fun webLinkOf(deeplink: String): String? {
        if (!deeplink.startsWith("intent://")) {
            return deeplink.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }
        val hash = deeplink.indexOf("#Intent;")
        if (hash < 0) return null
        val tail = deeplink.substring(hash)
        val at = tail.indexOf(";scheme=")
        if (at < 0) return null
        val end = tail.indexOf(';', at + 1)
        val scheme = if (end < 0) tail.substring(at + 8) else tail.substring(at + 8, end)
        if (scheme != "https" && scheme != "http") return null
        return scheme + "://" + deeplink.substring("intent://".length, hash)
    }

    /** "Thunderbolts*" ~ "thunderbolts", "Disney+" ~ "Disney Plus". */
    fun simplify(s: String): String =
        s.lowercase().replace("+", "plus").filter { it.isLetterOrDigit() }

    private fun streaming(array: JSONArray?): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        if (array == null) return out
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optString("monetizationType") in STREAMING) out += o
        }
        return out
    }

    private fun technicalOf(o: JSONObject) = o.optJSONObject("package")?.optString("technicalName") ?: ""

    private fun data(response: JSONObject): JSONObject {
        val errors = response.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            throw java.io.IOException("JustWatch: " + (errors.optJSONObject(0)?.optString("message") ?: "error"))
        }
        return response.optJSONObject("data") ?: JSONObject()
    }

    /** A string field, or null for missing / JSON null / empty. */
    private fun text(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }

    private fun post(query: String, variables: JSONObject): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables).toString().toByteArray(Charsets.UTF_8)
        val conn = URL(API).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            // JustWatch rejects default library user agents (as Yamtrack notes)
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/120 Safari/537.36")
            conn.setRequestProperty("Origin", "https://www.justwatch.com")
            conn.setRequestProperty("Referer", "https://www.justwatch.com/")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode == 429) throw java.io.IOException("JustWatch is busy (too many requests) — try again in a minute")
            val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
