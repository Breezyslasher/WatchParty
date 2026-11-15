package dev.watchparty.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * "Browse Yamtrack": your movies and shows from a Yamtrack server, and
 * search. Pick a title (and for a show, a season and episode), pick a
 * service, and every TV in the room starts it. Links come from JustWatch
 * (see JustWatch.kt), matched by the TMDB id Yamtrack gives each title.
 */
class BrowseActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("watchparty", Context.MODE_PRIVATE) }
    private val country by lazy { Locale.getDefault().country.uppercase(Locale.US).ifEmpty { "US" } }

    private lateinit var setupBox: LinearLayout
    private lateinit var browseBox: LinearLayout
    private lateinit var serverField: EditText
    private lateinit var tokenField: EditText
    private lateinit var searchField: EditText
    private lateinit var statusView: TextView
    private lateinit var list: LinearLayout
    private lateinit var moreButton: Button
    private val typeButtons = LinkedHashMap<String, Button>()
    private val statusButtons = LinkedHashMap<Int, Button>()

    private var mediaType = "movie"
    private var status: Int = STATUS_WATCHING
    private var query: String? = null
    private var offset = 0
    private var loading = false
    /** Bumped on every new list, so late results of an older one are dropped. */
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 40)
        }
        root.addView(label("Browse Yamtrack", 26f, bold = true))

        // ── server + token (until set; "Change server" brings it back) ──
        setupBox = vertical()
        setupBox.addView(label(
            "Your Yamtrack server and API token (Yamtrack's settings show the token). " +
                "Typing is easiest with the Google TV app's keyboard on your phone.", 15f))
        serverField = field("yamtrack.example.com or 192.168.1.5:8000", prefs.getString(KEY_URL, "") ?: "")
        setupBox.addView(serverField)
        tokenField = field("API token", prefs.getString(KEY_TOKEN, "") ?: "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        setupBox.addView(tokenField)
        setupBox.addView(button("Save") { saveServer() })
        root.addView(setupBox)

        // ── type, status, search ──
        browseBox = vertical()
        val types = horizontal()
        for ((type, name) in listOf("movie" to "Movies", "tv" to "TV shows")) {
            types.addView(button(name) { mediaType = type; query = null; reload() }.also { typeButtons[type] = it })
        }
        types.addView(button("Change server") { setupBox.visibility = View.VISIBLE; serverField.requestFocus() })
        browseBox.addView(types)
        val statuses = horizontal()
        for ((code, name) in STATUS_NAMES) {
            statuses.addView(button(name) { status = code; query = null; reload() }.also { statusButtons[code] = it })
        }
        browseBox.addView(statuses)
        val searchRow = horizontal()
        searchField = field("Search all movies / shows", "")
        searchRow.addView(searchField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(button("Search") {
            query = searchField.text.toString().trim().ifEmpty { null }
            reload()
        })
        browseBox.addView(searchRow)
        root.addView(browseBox)

        statusView = label("", 16f)
        root.addView(statusView)
        list = vertical()
        root.addView(list)
        moreButton = button("Load more") { load() }
        root.addView(moreButton)

        setContentView(ScrollView(this).apply { addView(root) })

        if (configured()) {
            setupBox.visibility = View.GONE
            reload()
        } else {
            browseBox.visibility = View.GONE
            moreButton.visibility = View.GONE
            serverField.requestFocus()
        }
    }

    // ── server ──────────────────────────────────────────────────────────

    private fun configured() =
        !prefs.getString(KEY_URL, "").isNullOrBlank() && !prefs.getString(KEY_TOKEN, "").isNullOrBlank()

    private fun client(): YamtrackClient =
        YamtrackClient(prefs.getString(KEY_URL, "") ?: "", prefs.getString(KEY_TOKEN, "") ?: "")

    private fun saveServer() {
        val url = serverField.text.toString().trim()
        val token = tokenField.text.toString().trim()
        if (url.isEmpty() || token.isEmpty()) {
            statusView.text = "Enter both the server and the token."
            return
        }
        prefs.edit().putString(KEY_URL, url).putString(KEY_TOKEN, token).apply()
        setupBox.visibility = View.GONE
        browseBox.visibility = View.VISIBLE
        reload()
    }

    // ── the list ────────────────────────────────────────────────────────

    private fun reload() {
        generation++
        offset = 0
        loading = false
        list.removeAllViews()
        for ((type, b) in typeButtons) b.text = (if (type == mediaType) "• " else "") + typeName(type)
        for ((code, b) in statusButtons) {
            b.text = (if (query == null && code == status) "• " else "") + STATUS_NAMES.first { it.first == code }.second
        }
        load()
    }

    private fun load() {
        if (loading) return
        loading = true
        val gen = generation
        val type = mediaType
        val text = query
        val code = status
        val from = offset
        statusView.text = if (text != null) "Searching \"$text\"…" else "Loading…"
        moreButton.visibility = View.GONE
        Thread {
            var page: YamtrackClient.Page? = null
            var error: Exception? = null
            try {
                val c = client()
                page = if (text != null) c.search(type, text)
                else c.library(type, if (code == STATUS_ALL) null else code, from)
            } catch (e: Exception) {
                error = e
            }
            val loaded = page
            val failed = error
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                loading = false
                if (failed != null) statusView.text = "Couldn't load: ${failed.message}"
                loaded?.let { page ->
                    for (item in page.items) list.addView(row(item))
                    offset += page.items.size
                    val shown = list.childCount
                    statusView.text = when {
                        shown == 0 && text != null -> "Nothing found for \"$text\"."
                        shown == 0 -> "Nothing here."
                        text != null -> "Results for \"$text\" — pick one to watch together."
                        else -> "$shown of ${page.total} — pick one to watch together."
                    }
                    moreButton.visibility =
                        if (text == null && page.items.isNotEmpty() && offset < page.total) View.VISIBLE else View.GONE
                }
            }
        }.start()
    }

    private fun row(item: YamtrackClient.Item): View {
        val row = horizontal().apply {
            setPadding(16, 10, 16, 10)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, has -> v.setBackgroundColor(if (has) FOCUSED else Color.TRANSPARENT) }
            setOnClickListener { pick(item) }
        }
        val poster = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val w = dp(62)
        row.addView(poster, LinearLayout.LayoutParams(w, dp(93)))
        Posters.load(YamtrackClient.posterUrl(item.image), poster, w)
        val text = vertical().apply { setPadding(24, 0, 0, 0); gravity = Gravity.CENTER_VERTICAL }
        text.addView(label(item.title, 18f, bold = true).apply { setPadding(0, 0, 0, 0) })
        val sub = listOfNotNull(
            typeName(item.mediaType),
            item.status?.let { s -> STATUS_NAMES.firstOrNull { it.first == s }?.second },
        ).joinToString(" · ")
        text.addView(label(sub, 14f).apply { setPadding(0, 4, 0, 0) })
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        return row
    }

    // ── picking: title → (season → episode) → service ─────────────────

    private fun pick(item: YamtrackClient.Item) {
        val show = item.mediaType == "tv"
        statusView.text = "Finding where to watch \"${item.title}\"…"
        Thread {
            try {
                val tmdb = if (item.source == "tmdb") item.mediaId else null
                if (!show && tmdb != null) {
                    // Yamtrack's own JustWatch links (cached on the server);
                    // none when the server has JustWatch off → ask JustWatch
                    val fromYamtrack = try {
                        client().providers("movie", "tmdb", tmdb, country)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (fromYamtrack.isNotEmpty()) {
                        ui { chooseOffer(item.title, fromYamtrack) }
                        return@Thread
                    }
                }
                if (show && tmdb != null) {
                    // seasons and episodes from Yamtrack (TMDB); each episode's
                    // links from Yamtrack's episode providers, else JustWatch
                    val seasons = try {
                        client().seasons(tmdb)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (seasons.isNotEmpty()) {
                        ui { chooseYamtrackSeason(item.title, tmdb, seasons) }
                        return@Thread
                    }
                }
                val title = JustWatch.findTitle(item.title, tmdb, if (show) "SHOW" else "MOVIE", country)
                if (title == null) {
                    ui { statusView.text = "JustWatch doesn't list \"${item.title}\" in $country." }
                    return@Thread
                }
                if (show) {
                    val seasons = JustWatch.seasons(title.id, country)
                    ui { chooseSeason(item.title, seasons) }
                } else {
                    val offers = JustWatch.offers(title.id, country)
                    ui { chooseOffer(item.title, offers) }
                }
            } catch (e: Exception) {
                ui { statusView.text = "Couldn't look it up: ${e.message}" }
            }
        }.start()
    }

    // ── shows from Yamtrack (TMDB numbering) ────────────────────────────

    private fun seasonName(number: Int) = if (number == 0) "Specials" else "Season $number"

    private fun chooseYamtrackSeason(show: String, tmdb: String, seasons: List<YamtrackClient.Numbered>) {
        statusView.text = ""
        AlertDialog.Builder(this)
            .setTitle(show)
            .setItems(seasons.map { seasonName(it.number) }.toTypedArray()) { _, which ->
                val season = seasons[which].number
                statusView.text = "Loading ${seasonName(season).lowercase(Locale.US)}…"
                Thread {
                    try {
                        val episodes = client().episodes(tmdb, season)
                        ui { chooseYamtrackEpisode(show, tmdb, season, episodes) }
                    } catch (e: Exception) {
                        ui { statusView.text = "Couldn't load episodes: ${e.message}" }
                    }
                }.start()
            }
            .show()
    }

    private fun chooseYamtrackEpisode(show: String, tmdb: String, season: Int, episodes: List<YamtrackClient.Numbered>) {
        if (episodes.isEmpty()) {
            statusView.text = "Yamtrack has no episodes for ${seasonName(season).lowercase(Locale.US)}."
            return
        }
        statusView.text = ""
        val names = episodes.map { e -> "E${e.number}" + (if (e.title.isNotBlank()) " · ${e.title}" else "") }
        AlertDialog.Builder(this)
            .setTitle("$show · ${seasonName(season)}")
            .setItems(names.toTypedArray()) { _, which ->
                val e = episodes[which]
                val label = "$show · S${season}E${e.number}" + (if (e.title.isNotBlank()) " ${e.title}" else "")
                statusView.text = "Finding where to watch $label…"
                Thread {
                    val offers = try {
                        episodeOffers(show, tmdb, season, e.number)
                    } catch (ex: Exception) {
                        ui { statusView.text = "Couldn't look it up: ${ex.message}" }
                        return@Thread
                    }
                    ui { chooseOffer(label, offers) }
                }.start()
            }
            .show()
    }

    /** Yamtrack's per-episode links (cached on the server); if it has none
     *  (JustWatch off there, older server), JustWatch directly. */
    private fun episodeOffers(show: String, tmdb: String, season: Int, episode: Int): List<JustWatch.Offer> {
        val fromYamtrack = try {
            client().episodeProviders(tmdb, season, episode, country)
        } catch (e: Exception) {
            emptyList()
        }
        if (fromYamtrack.isNotEmpty()) return fromYamtrack
        val title = JustWatch.findTitle(show, tmdb, "SHOW", country) ?: return emptyList()
        val jwSeason = JustWatch.seasons(title.id, country).firstOrNull { it.number == season } ?: return emptyList()
        return JustWatch.episodes(jwSeason.id, country).firstOrNull { it.number == episode }?.offers ?: emptyList()
    }

    // ── shows from JustWatch (non-TMDB items, or Yamtrack without seasons) ──

    private fun chooseSeason(show: String, seasons: List<JustWatch.Season>) {
        if (seasons.isEmpty()) {
            statusView.text = "JustWatch has no seasons for \"$show\"."
            return
        }
        statusView.text = ""
        val names = seasons.map { s ->
            val plain = "Season ${s.number}"
            if (s.title.isBlank() || s.title == plain) plain else "$plain · ${s.title}"
        }
        AlertDialog.Builder(this)
            .setTitle(show)
            .setItems(names.toTypedArray()) { _, which -> loadEpisodes(show, seasons[which]) }
            .show()
    }

    private fun loadEpisodes(show: String, season: JustWatch.Season) {
        statusView.text = "Loading season ${season.number}…"
        Thread {
            try {
                val episodes = JustWatch.episodes(season.id, country)
                ui { chooseEpisode(show, season, episodes) }
            } catch (e: Exception) {
                ui { statusView.text = "Couldn't load episodes: ${e.message}" }
            }
        }.start()
    }

    private fun chooseEpisode(show: String, season: JustWatch.Season, episodes: List<JustWatch.Episode>) {
        if (episodes.isEmpty()) {
            statusView.text = "JustWatch has no episodes for season ${season.number}."
            return
        }
        statusView.text = ""
        val names = episodes.map { e -> "E${e.number}" + (if (e.title.isNotBlank()) " · ${e.title}" else "") }
        AlertDialog.Builder(this)
            .setTitle("$show · Season ${season.number}")
            .setItems(names.toTypedArray()) { _, which ->
                val e = episodes[which]
                val label = "$show · S${season.number}E${e.number}" + (if (e.title.isNotBlank()) " ${e.title}" else "")
                chooseOffer(label, e.offers)
            }
            .show()
    }

    private fun chooseOffer(label: String, offers: List<JustWatch.Offer>) {
        if (offers.isEmpty()) {
            statusView.text = "No streaming service has \"$label\" in $country, says JustWatch."
            return
        }
        statusView.text = ""
        val names = offers.map { o ->
            val pkg = Apps.packageFor(this, o.link, o.technicalName)
            o.clearName + (if (pkg == null || !Apps.isInstalled(this, pkg)) "  (not on this TV)" else "")
        }
        AlertDialog.Builder(this)
            .setTitle("Watch \"$label\" on…")
            .setItems(names.toTypedArray()) { _, which -> start(label, offers[which]) }
            .show()
    }

    private fun start(label: String, offer: JustWatch.Offer) {
        if (!SyncService.running) {
            AlertDialog.Builder(this)
                .setTitle("Join a room first")
                .setMessage("Go back, join a room, then pick \"$label\" again — every TV in the room starts it.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val pkg = Apps.packageFor(this, offer.link, offer.technicalName)
        if (pkg == null) {
            statusView.text = "Don't know which app opens ${offer.clearName} links."
            return
        }
        SyncService.share(offer.link, label, pkg)
        statusView.text = "Started for everyone: $label (${offer.clearName})"
    }

    // ── views ───────────────────────────────────────────────────────────

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun typeName(type: String) = if (type == "tv") "TV shows" else if (type == "movie") "Movies" else type

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun label(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 12, 0, 4)
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun button(caption: String, onClick: () -> Unit) = Button(this).apply {
        text = caption
        setAllCaps(false)
        setOnClickListener { onClick() }
    }

    companion object {
        const val KEY_URL = "yamtrack_url"
        const val KEY_TOKEN = "yamtrack_token"
        private const val FOCUSED = 0x33FFFFFF
        private const val STATUS_WATCHING = YamtrackClient.IN_PROGRESS
        private const val STATUS_ALL = -1
        private val STATUS_NAMES = listOf(
            YamtrackClient.IN_PROGRESS to "Watching",
            YamtrackClient.PLANNING to "Planning",
            YamtrackClient.PAUSED to "Paused",
            YamtrackClient.COMPLETED to "Completed",
            STATUS_ALL to "All",
        )
    }
}
