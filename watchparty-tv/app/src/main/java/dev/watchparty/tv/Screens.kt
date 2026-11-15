package dev.watchparty.tv

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/*
 * The content screens of the "Cinema" design: Room (home), Browse Yamtrack,
 * the season / episode / service picker, and Paste a link. Values are the
 * design file's px (Ui.px halves them to dp). Behaviour is the same as
 * before the redesign: Yamtrack for titles, seasons, episodes and links,
 * JustWatch when Yamtrack has none, SyncService to start it for the room.
 */

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun lp(w: Int = WRAP, h: Int = WRAP, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

/** What the room screen shows as "Now playing" for a link we started. */
data class Playing(val file: String, val title: String, val sub: String, val service: String, val image: String?)

/** What the screens need from the activity. */
interface Host {
    val activity: Activity
    val prefs: SharedPreferences
    val country: String
    fun go(screen: Screen)
    fun openPicker(item: YamtrackClient.Item)
    fun toast(text: String)
    /** A title was sent to the room: remember it for Now playing, go to Room. */
    fun sent(playing: Playing)
}

enum class Screen { ROOM, BROWSE, PICKER, LINK }

abstract class ContentScreen(protected val host: Host) {
    protected val c get() = host.activity
    abstract val view: View
    /** The view that gets focus when the screen opens. */
    abstract fun defaultFocus(): View?
    open fun tick() {}
}

// ── Room (home) ─────────────────────────────────────────────────────────

class RoomScreen(host: Host, private val playingFor: (String) -> Playing?) : ContentScreen(host) {
    private val phone = Ui.isPhone(c)
    private val backdrop = FrameLayout(c)
    private val backdropImage = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.35f }
    private val nowLabel = Ui.text(c, "", 24, 600, Ui.ACCENT, spacingEm = 0.12f, caps = true)
    private val nowTitle = Ui.text(c, "", 84, 800, spacingEm = -0.03f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val nowSub = Ui.oneLine(Ui.text(c, "", 30, 400, Ui.TEXT_2))
    private val idle = Ui.column(c)
    private val browseTile = Ui.homeTile(c, "Browse Yamtrack", "Your movies and shows, or search") { host.go(Screen.BROWSE) }
    private val linkTile = Ui.homeTile(c, "Paste a link", "From Disney+, Netflix, HBO Max and more") { host.go(Screen.LINK) }
    private var shownFile: String? = "-"

    override val view: View = ScrollView(c).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        Ui.noClip(this)
        val col = Ui.column(c).apply {
            if (phone) setPadding(Ui.px(c, 40), Ui.px(c, 40), Ui.px(c, 40), Ui.px(c, 40))
            else setPadding(Ui.px(c, 96), Ui.px(c, 72), Ui.px(c, 96), Ui.px(c, 72))
        }
        // now playing: a backdrop card fading to the background
        backdrop.background = StripesDrawable(0xFF17191D.toInt(), 0xFF131518.toInt(), Ui.pxf(c, 14))
        Ui.clipRounded(backdrop, Ui.pxf(c, 24))
        backdrop.addView(backdropImage, FrameLayout.LayoutParams(MATCH, MATCH))
        val overlay = Ui.column(c).apply {
            setPadding(Ui.px(c, 56), Ui.px(c, 120), Ui.px(c, 56), Ui.px(c, 56))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x000B0C0E, 0xD90B0C0E.toInt(), Ui.BG))
            addView(nowLabel)
            addView(nowTitle, lp(MATCH).apply { topMargin = Ui.px(c, 14) })
            addView(nowSub, lp(MATCH).apply { topMargin = Ui.px(c, 14) })
        }
        backdrop.addView(overlay, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        col.addView(backdrop, lp(MATCH, Ui.px(c, if (phone) 420 else 560)))

        idle.setPadding(0, Ui.px(c, 40), 0, 0)
        idle.addView(Ui.text(c, "What are we watching?", if (phone) 60 else 80, 800, spacingEm = -0.03f, lineHeight = 1.02f))
        idle.addView(Ui.text(c, "Pick a title and every TV in the room opens it in the same app.", 32, 400, Ui.TEXT_2)
            .apply { maxWidth = Ui.px(c, 1000) }, lp().apply { topMargin = Ui.px(c, 18) })
        col.addView(idle, lp(MATCH))

        val tiles = if (phone) Ui.column(c) else Ui.row(c)
        if (phone) {
            tiles.addView(browseTile, lp(MATCH, Ui.px(c, 220)))
            tiles.addView(linkTile, lp(MATCH, Ui.px(c, 220)).apply { topMargin = Ui.px(c, 32) })
        } else {
            tiles.addView(browseTile, lp(0, Ui.px(c, 260), 1f))
            tiles.addView(linkTile, lp(0, Ui.px(c, 260), 1f).apply { leftMargin = Ui.px(c, 32) })
        }
        col.addView(tiles, lp(MATCH).apply { topMargin = Ui.px(c, 48) })
        addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    override fun defaultFocus(): View = browseTile

    override fun tick() {
        val room = SyncService.room
        val item = room?.item
        val file = item?.file
        if (item == null || !SyncService.running) {
            if (shownFile != null) {
                shownFile = null
                backdrop.visibility = View.GONE
                idle.visibility = View.VISIBLE
            }
            return
        }
        val mine = playingFor(item.file)
        val pkg = item.androidPackage
        val service = mine?.service ?: pkg?.let { Apps.name(c, it) } ?: "Kodi"
        nowLabel.text = "Now playing · $service"
        nowTitle.text = mine?.title ?: item.label.ifEmpty { item.file }
        val sub = mine?.sub ?: "Started in the room"
        nowSub.text = "$sub · " + if (room.paused) "Paused" else "Playing"
        if (shownFile != file) {
            shownFile = file
            backdrop.visibility = View.VISIBLE
            idle.visibility = View.GONE
            val image = mine?.image
            if (image != null) Posters.load(image, backdropImage, Ui.px(c, 1200)) else backdropImage.setImageDrawable(null)
        }
    }
}

// ── Browse Yamtrack ─────────────────────────────────────────────────────

class BrowseScreen(host: Host) : ContentScreen(host) {
    private val phone = Ui.isPhone(c)
    private val columns = if (!phone) 5 else if (c.resources.configuration.screenWidthDp >= 600) 3 else 2

    private var tab = "movie"           // movie | tv | search
    private var status: Int = YamtrackClient.IN_PROGRESS
    private var query = ""
    private var offset = 0
    private var total = 0
    private var loading = false
    private var generation = 0
    private val items = ArrayList<YamtrackClient.Item>()

    private val tabs = LinkedHashMap<String, TextTile>()
    private val chips = LinkedHashMap<Int, TextTile>()
    private val chipRow = Ui.row(c)
    private val searchField = Ui.field(c, "Search movies and shows", "", 76, 30, Ui.SURFACE, 14, 28)
    private val serverBox = Ui.column(c)
    private val serverField = Ui.field(c, "yamtrack.example.com or 192.168.1.5:8000", "", 76, 28, Ui.SURFACE, 14, 28)
    private val tokenField = Ui.field(c, "API token", "", 76, 28, Ui.SURFACE, 14, 28)
    private val empty = Ui.text(c, "", 30, 400, Ui.MUTED)
    private val grid = Ui.column(c)
    private val scroll = ScrollView(c)
    private var rowView: LinearLayout? = null

    override val view: View = Ui.column(c).apply {
        if (phone) setPadding(Ui.px(c, 40), Ui.px(c, 40), Ui.px(c, 40), 0)
        else setPadding(Ui.px(c, 96), Ui.px(c, 64), Ui.px(c, 96), 0)

        val tabRow = Ui.row(c)
        for ((key, name) in listOf("movie" to "Movies", "tv" to "TV shows", "search" to "Search")) {
            val t = Ui.tab(c, name) { selectTab(key) }
            tabs[key] = t
            tabRow.addView(t, lp(WRAP, Ui.tap(c, 68)).apply { if (tabRow.childCount > 0) leftMargin = Ui.px(c, 12) })
        }
        addView(tabRow)

        for ((code, name) in STATUSES) {
            val chip = Ui.chip(c, name) { status = code; reload() }
            chips[code] = chip
            chipRow.addView(chip, lp(WRAP, Ui.tap(c, 52)).apply { if (chipRow.childCount > 0) leftMargin = Ui.px(c, 10) })
        }
        val chipScroll = android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            Ui.noClip(this)
            addView(chipRow)
        }
        addView(chipScroll, lp(MATCH).apply { topMargin = Ui.px(c, 32) })

        searchField.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchField.setOnEditorActionListener { _, _, _ -> runSearch(); true }
        searchField.setOnKeyListener { _, code, e ->
            if (e.action == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER)) {
                runSearch(); true
            } else false
        }
        addView(searchField, lp(if (phone) MATCH else Ui.px(c, 900), Ui.tap(c, 76)).apply { topMargin = Ui.px(c, 32) })

        // Yamtrack server + token, until set
        serverBox.addView(Ui.text(c, "Connect to Yamtrack", 44, 700))
        serverBox.addView(Ui.text(c, "Your server and API token (Yamtrack's settings show the token). Typing is " +
            "easiest from the Google TV app on your phone.", 28, 400, Ui.TEXT_2, lineHeight = 1.3f).apply {
            maxWidth = Ui.px(c, 1000)
        }, lp().apply { topMargin = Ui.px(c, 14) })
        serverBox.addView(serverField, lp(if (phone) MATCH else Ui.px(c, 900), Ui.tap(c, 76)).apply { topMargin = Ui.px(c, 28) })
        tokenField.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        serverBox.addView(tokenField, lp(if (phone) MATCH else Ui.px(c, 900), Ui.tap(c, 76)).apply { topMargin = Ui.px(c, 16) })
        serverBox.addView(Ui.primary(c, "Save", 80, 52, 30, 16, Ui.BG, 5, 1.05f) { saveServer() },
            lp(WRAP, Ui.tap(c, 80)).apply { topMargin = Ui.px(c, 28) })
        addView(serverBox, lp(MATCH).apply { topMargin = Ui.px(c, 32) })

        addView(empty, lp().apply { topMargin = Ui.px(c, 32) })
        empty.setPadding(0, Ui.px(c, 40), 0, 0)

        // the poster grid scrolls; ScrollView keeps the focused card in view
        Ui.noClip(scroll)
        scroll.isVerticalScrollBarEnabled = false
        scroll.isFocusable = false
        grid.setPadding(Ui.px(c, 12), Ui.px(c, 12), Ui.px(c, 12), Ui.px(c, 64))
        scroll.addView(grid, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, lp(MATCH, 0, 1f).apply {
            topMargin = Ui.px(c, 20)
            leftMargin = -Ui.px(c, 12)
            rightMargin = -Ui.px(c, 12)
        })

        serverField.setText(host.prefs.getString(KEY_URL, "") ?: "")
        tokenField.setText(host.prefs.getString(KEY_TOKEN, "") ?: "")
    }

    init {
        updateHeader()
        if (configured()) reload()
    }

    override fun defaultFocus(): View? = if (!configured()) serverField else tabs[tab]

    private fun configured() =
        !host.prefs.getString(KEY_URL, "").isNullOrBlank() && !host.prefs.getString(KEY_TOKEN, "").isNullOrBlank()

    private fun client() = YamtrackClient(host.prefs.getString(KEY_URL, "") ?: "", host.prefs.getString(KEY_TOKEN, "") ?: "")

    private fun saveServer() {
        val url = serverField.text.toString().trim()
        val token = tokenField.text.toString().trim()
        if (url.isEmpty() || token.isEmpty()) {
            empty.text = "Enter both the server and the token."
            empty.visibility = View.VISIBLE
            return
        }
        host.prefs.edit().putString(KEY_URL, url).putString(KEY_TOKEN, token).apply()
        updateHeader()
        reload()
        tabs[tab]?.requestFocus()
    }

    private fun selectTab(key: String) {
        tab = key
        updateHeader()
        if (key == "search") {
            clearGrid()
            empty.text = if (query.isEmpty()) "Type a title to search Yamtrack." else ""
            empty.visibility = View.VISIBLE
            searchField.requestFocus()
            if (query.isNotEmpty()) reload()
        } else {
            reload()
        }
    }

    private fun runSearch() {
        query = searchField.text.toString().trim()
        if (query.isEmpty()) {
            clearGrid()
            empty.text = "Type a title to search Yamtrack."
            empty.visibility = View.VISIBLE
            return
        }
        reload()
    }

    private fun updateHeader() {
        val ok = configured()
        serverBox.visibility = if (ok) View.GONE else View.VISIBLE
        for ((key, t) in tabs) {
            t.setChosen(key == tab)
            t.visibility = if (ok) View.VISIBLE else View.GONE
        }
        for ((code, chip) in chips) chip.setChosen(code == status)
        (chipRow.parent as View).visibility = if (ok && tab != "search") View.VISIBLE else View.GONE
        searchField.visibility = if (ok && tab == "search") View.VISIBLE else View.GONE
        scroll.visibility = if (ok) View.VISIBLE else View.GONE
        if (!ok) empty.visibility = View.GONE
    }

    private fun clearGrid() {
        generation++
        items.clear()
        grid.removeAllViews()
        rowView = null
        offset = 0
        total = 0
        loading = false
    }

    private fun reload() {
        updateHeader()
        clearGrid()
        load()
    }

    private fun load() {
        if (loading || !configured()) return
        val searching = tab == "search"
        if (searching && query.isEmpty()) return
        loading = true
        val gen = generation
        val type = if (tab == "tv") "tv" else "movie"
        val code = status
        val from = offset
        val text = query
        empty.text = if (searching) "Searching…" else "Loading…"
        empty.visibility = View.VISIBLE
        Thread {
            var page: YamtrackClient.Page? = null
            var error: Exception? = null
            try {
                page = if (searching) {
                    // search covers movies and shows: two requests, merged
                    val movies = client().search("movie", text)
                    val shows = client().search("tv", text)
                    YamtrackClient.Page(movies.items + shows.items, movies.total + shows.total)
                } else {
                    client().library(type, if (code == ALL) null else code, from)
                }
            } catch (e: Exception) {
                error = e
            }
            val loaded = page
            val failed = error
            c.runOnUiThread {
                if (gen != generation) return@runOnUiThread
                loading = false
                if (failed != null) {
                    empty.text = "Couldn't load: ${failed.message}"
                    empty.visibility = View.VISIBLE
                    // wrong server or token: bring the form back to fix it
                    if (failed is YamtrackClient.YamtrackException || failed is java.io.IOException) {
                        serverBox.visibility = View.VISIBLE
                    }
                    return@runOnUiThread
                }
                val p = loaded ?: return@runOnUiThread
                serverBox.visibility = View.GONE
                for (item in p.items) addCard(item)
                offset += p.items.size
                total = if (searching) items.size else p.total
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                empty.text = if (items.isEmpty()) (if (searching) "No matches." else "Nothing here.") else ""
            }
        }.start()
    }

    /** Shows [list] as the grid (what a loaded page does). */
    internal fun show(list: List<YamtrackClient.Item>) {
        clearGrid()
        serverBox.visibility = View.GONE
        for (item in list) addCard(item)
        total = list.size
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addCard(item: YamtrackClient.Item) {
        items.add(item)
        // each row starts as [columns] empty slots, so a short last row keeps
        // the same card width; cards replace the slots in order
        val slot = (items.size - 1) % columns
        var row = rowView
        if (row == null || slot == 0) {
            row = Ui.row(c)
            for (i in 0 until columns) {
                row.addView(View(c), lp(0, 1, 1f).apply { if (i > 0) leftMargin = Ui.px(c, 28) })
            }
            grid.addView(row, lp(MATCH).apply { if (grid.childCount > 0) topMargin = Ui.px(c, 36) })
            rowView = row
        }
        val card = Ui.posterCard(c, item.title, meta(item), item.image) { host.openPicker(item) }
        card.setOnFocusChangeListener { _, has ->
            // focus keeps the card in view (ScrollView); near the end, load more
            if (has && tab != "search" && !loading && offset < total && items.indexOf(item) >= items.size - columns * 2) load()
        }
        row.removeViewAt(slot)
        row.addView(card, slot, lp(0, WRAP, 1f).apply { if (slot > 0) leftMargin = Ui.px(c, 28) })
    }

    companion object {
        const val KEY_URL = "yamtrack_url"
        const val KEY_TOKEN = "yamtrack_token"
        const val ALL = -1
        val STATUSES = listOf(
            YamtrackClient.IN_PROGRESS to "Watching",
            YamtrackClient.PLANNING to "Planning",
            YamtrackClient.PAUSED to "Paused",
            YamtrackClient.COMPLETED to "Completed",
            ALL to "All",
        )

        fun kind(type: String) = if (type == "tv") "TV" else "Movie"

        fun statusName(status: Int?) = STATUSES.firstOrNull { it.first == status }?.second

        /** "TV · Watching" — Yamtrack's lists have no year. */
        fun meta(item: YamtrackClient.Item) = listOfNotNull(kind(item.mediaType), statusName(item.status)).joinToString(" · ")
    }
}

// ── Picker: season → episode → service ──────────────────────────────────

class PickerScreen(host: Host, private val item: YamtrackClient.Item) : ContentScreen(host) {
    private val phone = Ui.isPhone(c)
    private val show = item.mediaType == "tv"
    private val tmdb = if (item.source == "tmdb") item.mediaId else null

    /** A season or episode from Yamtrack (TMDB numbers) or JustWatch (with its id / offers). */
    internal data class Pick(val number: Int, val title: String, val jwId: String? = null, val offers: List<JustWatch.Offer>? = null)

    private val seasonList = Ui.column(c)
    private val episodeList = Ui.column(c)
    private val serviceList = Ui.column(c)
    private val episodeCol = Ui.column(c)
    private val servicesTitle = Ui.sectionLabel(c, if (show) "Pick an episode" else "Watch on")
    private val hint = Ui.text(c, if (show) "Choose a season and episode, then a service." else "Finding where to watch…",
        28, 400, Ui.FAINT, lineHeight = 1.4f)
    private val seasonButtons = ArrayList<TextTile>()
    private val episodeButtons = ArrayList<TextTile>()
    private var season: Pick? = null
    private var firstSeason: View? = null
    private var firstService: View? = null
    private var gen = 0

    override val view: View = build()

    private fun build(): View {
        val root = Ui.column(c).apply {
            if (phone) setPadding(Ui.px(c, 40), Ui.px(c, 40), Ui.px(c, 40), Ui.px(c, 40))
            else setPadding(Ui.px(c, 96), Ui.px(c, 64), Ui.px(c, 96), Ui.px(c, 64))
        }
        // header: poster, meta, title
        val header = Ui.row(c).apply { gravity = Gravity.BOTTOM }
        val poster = PosterView(c, Ui.pxf(c, 14))
        header.addView(poster, lp(Ui.px(c, if (phone) 140 else 180), WRAP))
        Posters.load(YamtrackClient.posterUrl(item.image), poster.image, Ui.px(c, 180))
        val texts = Ui.column(c)
        texts.addView(Ui.oneLine(Ui.text(c, listOfNotNull(BrowseScreen.kind(item.mediaType),
            BrowseScreen.statusName(item.status)).joinToString(" · "), 24, 400, Ui.MUTED)))
        texts.addView(Ui.text(c, item.title, if (phone) 56 else 76, 800, spacingEm = -0.03f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp(MATCH).apply { topMargin = Ui.px(c, 10) })
        header.addView(texts, lp(0, WRAP, 1f).apply { leftMargin = Ui.px(c, 40) })
        root.addView(header, lp(MATCH))

        val cols = if (phone) Ui.column(c) else Ui.row(c)
        if (show) {
            val seasonCol = Ui.column(c)
            seasonCol.addView(Ui.sectionLabel(c, "Season"), lp().apply { bottomMargin = Ui.px(c, 16) })
            seasonCol.addView(scrolling(seasonList), if (phone) lp(MATCH) else lp(MATCH, 0, 1f))
            cols.addView(seasonCol, if (phone) lp(MATCH) else lp(Ui.px(c, 280), MATCH))

            episodeCol.addView(Ui.sectionLabel(c, "Episode"), lp().apply { bottomMargin = Ui.px(c, 16) })
            episodeCol.addView(scrolling(episodeList), if (phone) lp(MATCH) else lp(MATCH, 0, 1f))
            cols.addView(episodeCol, if (phone) lp(MATCH).apply { topMargin = Ui.px(c, 40) }
            else lp(Ui.px(c, 440), MATCH).apply { leftMargin = Ui.px(c, 40) })
        }
        val serviceCol = Ui.column(c)
        serviceCol.addView(servicesTitle, lp().apply { bottomMargin = Ui.px(c, 6) })
        serviceCol.addView(hint, lp().apply { topMargin = Ui.px(c, 12) })
        serviceCol.addView(scrolling(serviceList), if (phone) lp(MATCH) else lp(MATCH, 0, 1f).apply { topMargin = Ui.px(c, 6) })
        cols.addView(serviceCol, if (phone) lp(MATCH).apply { topMargin = Ui.px(c, 40) }
        else lp(0, MATCH, 1f).apply { if (show) leftMargin = Ui.px(c, 40) })
        root.addView(cols, (if (phone) lp(MATCH) else lp(MATCH, 0, 1f)).apply { topMargin = Ui.px(c, 40) })
        if (show) load { loadSeasons() } else load { loadMovie() }
        return if (phone) ScrollView(c).apply { Ui.noClip(this); isVerticalScrollBarEnabled = false; addView(root) } else root
    }

    /** On TV each column scrolls on its own; on phones the whole screen scrolls. */
    private fun scrolling(list: LinearLayout): View {
        list.setPadding(Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6))
        if (phone) return list
        return ScrollView(c).apply {
            Ui.noClip(this)
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    override fun defaultFocus(): View? = firstService ?: firstSeason

    private fun client() = YamtrackClient(host.prefs.getString(BrowseScreen.KEY_URL, "") ?: "",
        host.prefs.getString(BrowseScreen.KEY_TOKEN, "") ?: "")

    private fun load(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (e: Exception) {
                ui { showHint("Couldn't look it up: ${e.message}", warn = true) }
            }
        }.start()
    }

    private fun ui(block: () -> Unit) = c.runOnUiThread(block)

    private fun showHint(text: String, warn: Boolean = false) {
        hint.text = text
        hint.setTextColor(if (warn) Ui.WARN else Ui.FAINT)
        hint.visibility = View.VISIBLE
    }

    // movies: Yamtrack's links, else JustWatch
    private fun loadMovie() {
        val fromYamtrack = if (tmdb != null) try {
            client().providers("movie", "tmdb", tmdb, host.country)
        } catch (e: Exception) {
            emptyList()
        } else emptyList()
        val offers = fromYamtrack.ifEmpty {
            val t = JustWatch.findTitle(item.title, tmdb, "MOVIE", host.country)
            if (t == null) emptyList() else JustWatch.offers(t.id, host.country)
        }
        ui { showServices(item.title, "Movie", offers, focus = true) }
    }

    // shows: seasons and episodes from Yamtrack (TMDB), else JustWatch
    private fun loadSeasons() {
        val fromYamtrack = if (tmdb != null) try {
            client().seasons(tmdb).map { Pick(it.number, it.title) }
        } catch (e: Exception) {
            emptyList()
        } else emptyList()
        val seasons = fromYamtrack.ifEmpty {
            val t = JustWatch.findTitle(item.title, tmdb, "SHOW", host.country)
            if (t == null) emptyList() else JustWatch.seasons(t.id, host.country).map { Pick(it.number, it.title, jwId = it.id) }
        }
        ui { showSeasons(seasons) }
    }

    internal fun showSeasons(seasons: List<Pick>) {
            seasonList.removeAllViews()
            seasonButtons.clear()
            if (seasons.isEmpty()) {
                showHint("No seasons found for \"${item.title}\".")
                return
            }
            for (s in seasons) {
                val b = Ui.listButton(c, seasonName(s.number), null) { chooseSeason(s) }
                b.tag = s.number
                seasonButtons.add(b)
                seasonList.addView(b, lp(MATCH, Ui.tap(c, 68)).apply { if (seasonList.childCount > 0) topMargin = Ui.px(c, 10) })
            }
            firstSeason = seasonButtons.first()
            if (c.currentFocus == null || c.currentFocus?.isAttachedToWindow != true) firstSeason?.requestFocus()
    }

    private fun chooseSeason(s: Pick) {
        season = s
        val myGen = ++gen
        for (b in seasonButtons) b.setChosen(b.tag == s.number)
        episodeList.removeAllViews()
        episodeButtons.clear()
        clearServices()
        servicesTitle.text = "Pick an episode"
        showHint("Loading ${seasonName(s.number).lowercase(Locale.US)}…")
        load {
            val episodes = if (s.jwId != null) {
                JustWatch.episodes(s.jwId, host.country).map { Pick(it.number, it.title, offers = it.offers) }
            } else {
                client().episodes(tmdb!!, s.number).map { Pick(it.number, it.title) }
            }
            ui { if (myGen == gen) showEpisodes(s, episodes) }
        }
    }

    internal fun showEpisodes(s: Pick, episodes: List<Pick>) {
        season = s
        for (b in seasonButtons) b.setChosen(b.tag == s.number)
        episodeList.removeAllViews()
        episodeButtons.clear()
        if (episodes.isEmpty()) {
            showHint("No episodes for ${seasonName(s.number).lowercase(Locale.US)}.")
            return
        }
        showHint("Choose an episode, then a service.")
        for (e in episodes) {
            val b = Ui.listButton(c, "Episode ${e.number}", e.title) { chooseEpisode(s, e) }
            b.tag = e.number
            episodeButtons.add(b)
            episodeList.addView(b, lp(MATCH, Ui.tap(c, 68)).apply { if (episodeList.childCount > 0) topMargin = Ui.px(c, 10) })
        }
        episodeButtons.first().requestFocus()
    }

    internal fun chooseEpisodeTitle(s: Pick, e: Pick) {
        for (b in episodeButtons) b.setChosen(b.tag == e.number)
        servicesTitle.text = "S${s.number} · E${e.number} on"
    }

    private fun chooseEpisode(s: Pick, e: Pick) {
        val myGen = ++gen
        clearServices()
        chooseEpisodeTitle(s, e)
        showHint("Finding where to watch…")
        val sub = "S${s.number} · E${e.number}" + if (e.title.isNotBlank()) " · ${e.title}" else ""
        load {
            val offers = e.offers ?: episodeOffers(s.number, e.number)
            ui { if (myGen == gen) showServices(item.title, sub, offers, focus = true) }
        }
    }

    /** Yamtrack's per-episode links; JustWatch when it has none. */
    private fun episodeOffers(season: Int, episode: Int): List<JustWatch.Offer> {
        val fromYamtrack = try {
            client().episodeProviders(tmdb!!, season, episode, host.country)
        } catch (e: Exception) {
            emptyList()
        }
        if (fromYamtrack.isNotEmpty()) return fromYamtrack
        val t = JustWatch.findTitle(item.title, tmdb, "SHOW", host.country) ?: return emptyList()
        val jwSeason = JustWatch.seasons(t.id, host.country).firstOrNull { it.number == season } ?: return emptyList()
        return JustWatch.episodes(jwSeason.id, host.country).firstOrNull { it.number == episode }?.offers ?: emptyList()
    }

    private fun clearServices() {
        serviceList.removeAllViews()
        firstService = null
    }

    internal fun showServices(title: String, sub: String, offers: List<JustWatch.Offer>, focus: Boolean) {
        clearServices()
        if (offers.isEmpty()) {
            showHint("No streaming service has this in ${host.country}.")
            return
        }
        hint.visibility = View.GONE
        // services with their app on this TV first
        val sorted = offers.sortedBy { if (installedPackage(it) != null) 0 else 1 }
        for (o in sorted) {
            val pkg = installedPackage(o)
            val note = when {
                pkg == null -> "Not on this TV. Others in the room may have it."
                autoPlays(o.link) -> "Starts playing"
                else -> "Opens the app. Everyone presses Play."
            }
            val card = Ui.serviceCard(c, serviceName(o), note, dimmed = pkg == null) { start(title, sub, o) }
            serviceList.addView(card, lp(MATCH, WRAP).apply { if (serviceList.childCount > 0) topMargin = Ui.px(c, 12) })
            if (firstService == null) firstService = card
        }
        if (focus) firstService?.requestFocus()
    }

    private fun installedPackage(o: JustWatch.Offer): String? {
        val pkg = Apps.packageFor(c, o.link, o.technicalName) ?: return null
        return if (Apps.isInstalled(c, pkg)) pkg else null
    }

    private fun start(title: String, sub: String, o: JustWatch.Offer) {
        if (!SyncService.running) {
            showHint("Join a room first.", warn = true)
            return
        }
        val pkg = Apps.packageFor(c, o.link, o.technicalName)
        if (pkg == null) {
            showHint("Don't know which app opens ${serviceName(o)} links.", warn = true)
            return
        }
        val label = if (show) "$title · $sub" else title
        SyncService.share(o.link, label, pkg)
        host.sent(Playing(o.link, title, sub, serviceName(o), YamtrackClient.posterUrl(item.image)))
    }

    companion object {
        fun seasonName(n: Int) = if (n == 0) "Specials" else "Season $n"

        /** TMDB / JustWatch names → the names people know. */
        fun serviceName(o: JustWatch.Offer) = when (o.clearName) {
            "Disney Plus" -> "Disney+"
            "Max" -> "HBO Max"
            "Apple TV Plus" -> "Apple TV+"
            "Paramount Plus" -> "Paramount+"
            "Amazon Prime Video" -> "Prime Video"
            "Amazon Prime Video with Ads" -> "Prime Video"
            else -> o.clearName
        }

        /** Links confirmed to start playback on a TV (README: Which link to share). */
        fun autoPlays(link: String): Boolean =
            link.contains("disneyplus.com/play/") || link.contains("netflix.com/watch/") ||
                link.contains("hbomax.com/video/watch/") || link.contains("max.com/video/watch/") ||
                (link.contains("starz.com/") && link.contains("/play/")) ||
                (link.contains("tv.apple.com/") && link.contains("/movie/"))
    }
}

// ── Paste a link ────────────────────────────────────────────────────────

class LinkScreen(host: Host) : ContentScreen(host) {
    private val phone = Ui.isPhone(c)
    private val linkField = Ui.field(c, "https://www.disneyplus.com/play/…", "", 84, 30, Ui.SURFACE, 14, 28, mono = true)
    private val titleField = Ui.field(c, "Title (optional)", "", 84, 30, Ui.SURFACE, 14, 28)
    private val note = Ui.text(c, "", 26, 400, Ui.WARN)

    override val view: View = ScrollView(c).apply {
        Ui.noClip(this)
        isVerticalScrollBarEnabled = false
        val col = Ui.column(c).apply {
            val p = Ui.px(c, if (phone) 40 else 96)
            setPadding(p, p, p, p)
        }
        col.addView(Ui.text(c, "Start for everyone", if (phone) 60 else 80, 800, spacingEm = -0.03f))
        col.addView(Ui.text(c, "Paste a movie or episode link from the app's website, ideally copied while it plays. " +
            "Typing is easiest from the Google TV app on your phone.", 30, 400, Ui.TEXT_2, lineHeight = 1.4f),
            lp(MATCH).apply { topMargin = Ui.px(c, 32) })
        col.addView(linkField, lp(MATCH, Ui.tap(c, 84)).apply { topMargin = Ui.px(c, 32) })
        col.addView(titleField, lp(MATCH, Ui.tap(c, 84)).apply { topMargin = Ui.px(c, 32) })
        val actions = if (phone) Ui.column(c) else Ui.row(c).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(Ui.primary(c, "Start for everyone", 88, 52, 30, 16, Ui.BG, 6, 1.05f) { share() },
            lp(WRAP, Ui.tap(c, 88)))
        actions.addView(note, if (phone) lp(MATCH).apply { topMargin = Ui.px(c, 20) }
        else lp(0, WRAP, 1f).apply { leftMargin = Ui.px(c, 28) })
        col.addView(actions, lp(MATCH).apply { topMargin = Ui.px(c, 32) })

        col.addView(Ui.divider(c), lp(MATCH, maxOf(1, Ui.px(c, 2) / 2)).apply { topMargin = Ui.px(c, 56) })
        val footer = if (phone) Ui.column(c) else Ui.row(c)
        footer.addView(footnote("Starts playing", "Disney+, Netflix, HBO Max, Starz, Apple TV"),
            if (phone) lp(MATCH) else lp(0, WRAP, 1f))
        footer.addView(footnote("Opens the app", "Crunchyroll and others. Everyone presses Play, then sync takes over."),
            if (phone) lp(MATCH).apply { topMargin = Ui.px(c, 24) } else lp(0, WRAP, 1f).apply { leftMargin = Ui.px(c, 32) })
        col.addView(footer, lp(MATCH).apply { topMargin = Ui.px(c, 32) })
        addView(col, FrameLayout.LayoutParams(if (phone) MATCH else Ui.px(c, 1240), WRAP))
    }

    private fun footnote(title: String, body: String) = Ui.column(c).apply {
        addView(Ui.sectionLabel(c, title))
        addView(Ui.text(c, body, 26, 400, lineHeight = 1.4f), lp(MATCH).apply { topMargin = Ui.px(c, 8) })
    }

    override fun defaultFocus(): View = linkField

    private fun share() {
        if (!SyncService.running) {
            note.text = "Join a room first."
            return
        }
        val shared = MediaLink.normalize(linkField.text.toString())
        if (shared == null) {
            note.text = "That isn't a web link. It should start with https://"
            return
        }
        val pkg = Apps.packageFor(c, shared.link)
        if (pkg == null) {
            note.text = "No app on this TV opens that link."
            return
        }
        note.text = ""
        val label = titleField.text.toString().trim().ifEmpty { shared.label }
        SyncService.share(shared.link, label, pkg)
        host.sent(Playing(shared.link, label, "Shared link", Apps.name(c, pkg), null))
    }

    fun clear() {
        linkField.setText("")
        titleField.setText("")
        note.text = ""
    }
}
