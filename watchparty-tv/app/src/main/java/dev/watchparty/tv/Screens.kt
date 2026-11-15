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
    /** A title's page; from another title or person page, Back returns there. */
    fun openPicker(item: YamtrackClient.Item)
    fun openPerson(member: YamtrackClient.CastMember)
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
    /** Where focus was when another page opened on top of this one (Back returns to it). */
    var lastFocus: View? = null
}

/** Grid rows of [columns] cards; a short last row keeps the card width. */
fun posterGrid(c: Activity, cards: List<View>, columns: Int): LinearLayout {
    val grid = Ui.column(c)
    var row: LinearLayout? = null
    for ((i, card) in cards.withIndex()) {
        val slot = i % columns
        if (slot == 0) {
            row = Ui.row(c)
            grid.addView(row, lp(MATCH).apply { if (grid.childCount > 0) topMargin = Ui.px(c, 36) })
        }
        row!!.addView(card, lp(0, WRAP, 1f).apply { if (slot > 0) leftMargin = Ui.px(c, 28) })
    }
    val rest = cards.size % columns
    if (row != null && rest != 0) for (i in rest until columns) row.addView(View(c), lp(0, 1, 1f).apply { leftMargin = Ui.px(c, 28) })
    return grid
}

/** A horizontal rail of cards under a section label (cast, More like this). */
fun rail(c: Activity, label: String, cards: List<View>, cardWidthPx: Int): LinearLayout = Ui.column(c).apply {
    addView(Ui.sectionLabel(c, label), lp().apply { bottomMargin = Ui.px(c, 20) })
    val row = Ui.row(c).apply { setPadding(Ui.px(c, 8), Ui.px(c, 8), Ui.px(c, 8), Ui.px(c, 8)) }
    for (card in cards) row.addView(card, lp(Ui.px(c, cardWidthPx), WRAP).apply { if (row.childCount > 0) leftMargin = Ui.px(c, 24) })
    addView(android.widget.HorizontalScrollView(c).apply {
        Ui.noClip(this)
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        addView(row)
    }, lp(MATCH))
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
    /** What Search looks for: "movie" or "tv" (one Yamtrack search each, not mixed). */
    private var searchType = "movie"
    private var offset = 0
    private var total = 0
    private var loading = false
    private var generation = 0
    private val items = ArrayList<YamtrackClient.Item>()
    /** Titles per status, for "movie" and "tv": chips with none are hidden. */
    private val counts = HashMap<String, Map<Int, Int>>()
    private var countGeneration = 0

    private val tabs = LinkedHashMap<String, TextTile>()
    private val chips = LinkedHashMap<Int, TextTile>()
    private val chipRow = Ui.row(c)
    private val typeChips = LinkedHashMap<String, TextTile>()
    private val typeRow = Ui.row(c)
    private val searchField = Ui.field(c, "Search movies", "", 76, 30, Ui.SURFACE, 14, 28)
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

        // Search: movies or TV shows, in the same place as the status chips
        for ((key, name) in listOf("movie" to "Movies", "tv" to "TV shows")) {
            val chip = Ui.chip(c, name) {
                searchType = key
                updateHeader()
                if (query.isNotEmpty()) reload()
            }
            typeChips[key] = chip
            typeRow.addView(chip, lp(WRAP, Ui.tap(c, 52)).apply { if (typeRow.childCount > 0) leftMargin = Ui.px(c, 10) })
        }
        addView(typeRow, lp(MATCH).apply { topMargin = Ui.px(c, 32) })

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
        // a view is clipped by its parent: this frame keeps posters scrolled up
        // out of the grid from drawing over the tabs and chips
        val gridBox = FrameLayout(c).apply {
            clipChildren = true
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        addView(gridBox, lp(MATCH, 0, 1f).apply {
            topMargin = Ui.px(c, 20)
            leftMargin = -Ui.px(c, 12)
            rightMargin = -Ui.px(c, 12)
        })

        serverField.setText(host.prefs.getString(KEY_URL, "") ?: "")
        tokenField.setText(host.prefs.getString(KEY_TOKEN, "") ?: "")
    }

    init {
        updateHeader()
        if (configured()) {
            reload()
            countStatuses(tab)
        }
    }

    override fun defaultFocus(): View? = if (!configured()) serverField else tabs[tab]

    private fun configured() = configured(host.prefs)

    private fun client() = client(host.prefs)

    private fun saveServer() {
        val url = serverField.text.toString().trim()
        val token = tokenField.text.toString().trim()
        if (url.isEmpty() || token.isEmpty()) {
            empty.text = "Enter both the server and the token."
            empty.visibility = View.VISIBLE
            return
        }
        host.prefs.edit().putString(KEY_URL, url).putString(KEY_TOKEN, token).apply()
        counts.clear()
        updateHeader()
        reload()
        if (tab != "search") countStatuses(tab)
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
            countStatuses(key)
        }
    }

    /** One small request per status (limit=1, read the total), all at once. */
    private fun countStatuses(type: String) {
        if (!configured()) return
        val my = ++countGeneration
        val client = client()
        Thread {
            val result = HashMap<Int, Int>()
            STATUSES.map { (code, _) ->
                Thread {
                    val n = try {
                        client.library(type, if (code == ALL) null else code, 0, 1).total
                    } catch (e: Exception) {
                        -1
                    }
                    synchronized(result) { result[code] = n }
                }.apply { start() }
            }.forEach { it.join() }
            c.runOnUiThread { if (my == countGeneration) applyCounts(type, result) }
        }.start()
    }

    internal fun applyCounts(type: String, result: Map<Int, Int>) {
        counts[type] = result
        if (tab != type) return
        val shown = YamtrackClient.statusesWithTitles(result, STATUSES.map { it.first }, ALL)
        val hadFocus = chips.values.any { it.isFocused }
        updateHeader()
        if (shown.isNotEmpty() && status !in shown) {
            // e.g. nothing being watched: open the first list that has something
            status = shown.first()
            reload()
        } else if (shown.isEmpty()) {
            clearGrid()
            empty.text = emptyText()
            empty.visibility = View.VISIBLE
        }
        if (hadFocus) (chips[status]?.takeIf { it.visibility == View.VISIBLE } ?: tabs[tab])?.requestFocus()
    }

    private fun emptyText(): String = when {
        tab == "search" -> "No matches."
        counts[tab]?.get(ALL) == 0 -> "No ${if (tab == "tv") "TV shows" else "movies"} in your Yamtrack yet. Search finds any title."
        else -> "Nothing here."
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
        (chipRow.parent as View).visibility =
            if (ok && tab != "search" && counts[tab]?.get(ALL) != 0) View.VISIBLE else View.GONE
        // chips hidden for an empty status on this tab (counts arrive a moment later)
        val known = counts[tab]
        val shown = if (known == null) null else YamtrackClient.statusesWithTitles(known, STATUSES.map { it.first }, ALL)
        for ((code, chip) in chips) chip.visibility = if (shown == null || code in shown) View.VISIBLE else View.GONE
        for ((key, chip) in typeChips) chip.setChosen(key == searchType)
        typeRow.visibility = if (ok && tab == "search") View.VISIBLE else View.GONE
        searchField.hint = if (searchType == "tv") "Search TV shows" else "Search movies"
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
                    client().search(searchType, text)
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
                empty.text = if (items.isEmpty()) emptyText() else ""
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

        fun configured(prefs: SharedPreferences) =
            !prefs.getString(KEY_URL, "").isNullOrBlank() && !prefs.getString(KEY_TOKEN, "").isNullOrBlank()

        fun client(prefs: SharedPreferences) = YamtrackClient(prefs.getString(KEY_URL, "") ?: "", prefs.getString(KEY_TOKEN, "") ?: "")
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
    /** The season and episode whose services are shown. */
    private var picked: Pair<Int, Int>? = null
    private var starting = false
    private var firstSeason: View? = null
    private var firstService: View? = null
    private var gen = 0

    // the title page around the picker: facts, genres, description, cast, more like this
    private val poster = PosterView(c, Ui.pxf(c, 14))
    private val facts = Ui.row(c).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
    private val genres = Ui.row(c).apply { visibility = View.GONE }
    private val about = Ui.column(c)
    private val castSection = Ui.column(c).apply { visibility = View.GONE }
    private val moreSection = Ui.column(c).apply { visibility = View.GONE }
    private var posterSet = false

    override val view: View = build()

    private fun build(): View {
        val root = Ui.column(c).apply {
            if (phone) setPadding(Ui.px(c, 40), Ui.px(c, 40), Ui.px(c, 40), Ui.px(c, 64))
            else setPadding(Ui.px(c, 96), Ui.px(c, 64), Ui.px(c, 96), Ui.px(c, 80))
        }
        // header: poster beside kind · status, title, rating and facts, genres, description
        val hero = Ui.column(c)
        val top = Ui.row(c)
        top.addView(poster, lp(Ui.px(c, if (phone) 180 else 220), WRAP))
        setPoster(item.image)
        val texts = Ui.column(c)
        texts.addView(Ui.oneLine(Ui.text(c, listOfNotNull(BrowseScreen.kind(item.mediaType),
            BrowseScreen.statusName(item.status)).joinToString(" · "), 24, 400, Ui.MUTED)))
        texts.addView(Ui.text(c, item.title, if (phone) 52 else 64, 800, spacingEm = -0.03f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp(MATCH).apply { topMargin = Ui.px(c, 10) })
        texts.addView(facts, lp(MATCH).apply { topMargin = Ui.px(c, 18) })
        top.addView(texts, lp(0, WRAP, 1f).apply { leftMargin = Ui.px(c, if (phone) 32 else 48) })
        hero.addView(top, lp(MATCH))
        // on a TV the genres and description sit beside the poster; on a phone, under it
        val under = if (phone) hero else texts
        under.addView(genres, lp(MATCH).apply { topMargin = Ui.px(c, if (phone) 28 else 18) })
        under.addView(about, lp(MATCH).apply { topMargin = Ui.px(c, 10); leftMargin = -Ui.px(c, 16) })
        root.addView(hero, lp(MATCH))

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
        // a TV shows a season's episodes in a fixed-height box that scrolls on
        // its own, so the season and service columns stay beside them
        val boxed = show && !phone
        serviceCol.addView(if (boxed) scrolling(serviceList) else serviceList.also { it.setPadding(Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6)) },
            if (boxed) lp(MATCH, 0, 1f).apply { topMargin = Ui.px(c, 6) } else lp(MATCH).apply { topMargin = Ui.px(c, 6) })
        cols.addView(serviceCol, when {
            phone -> lp(MATCH).apply { if (show) topMargin = Ui.px(c, 40) }
            show -> lp(0, MATCH, 1f).apply { leftMargin = Ui.px(c, 40) }
            else -> lp(Ui.px(c, 760), WRAP)
        })
        root.addView(cols, (if (boxed) lp(MATCH, Ui.px(c, 540)) else lp(MATCH)).apply { topMargin = Ui.px(c, 48) })
        root.addView(castSection, lp(MATCH).apply { topMargin = Ui.px(c, 56) })
        root.addView(moreSection, lp(MATCH).apply { topMargin = Ui.px(c, 56) })

        if (show) load { loadSeasons() } else load { loadMovie() }
        loadPage()
        return PageScroll(c).apply { addView(root) }
    }

    /** On TV each column scrolls on its own; on phones the whole screen scrolls. */
    private fun scrolling(list: LinearLayout): View {
        list.setPadding(Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6))
        if (phone) return list
        val scroll = ScrollView(c).apply {
            Ui.noClip(this)
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        // a view is clipped by its parent: this frame keeps rows scrolled out of
        // the box from drawing over the page (rings fit in the list's padding)
        return FrameLayout(c).apply {
            clipChildren = true
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    override fun defaultFocus(): View? = firstService ?: firstSeason

    private fun setPoster(image: String?) {
        if (image.isNullOrBlank() || posterSet) return
        posterSet = true
        Posters.load(YamtrackClient.posterUrl(image, "w342"), poster.image, Ui.px(c, 220))
    }

    /** The detail, cast and recommendations; each is optional (an older server, a title without them). */
    private fun loadPage() {
        if (!BrowseScreen.configured(host.prefs) || item.source.isEmpty()) return
        val type = item.mediaType
        quietly { client().detail(type, item.source, item.mediaId).let { d -> ui { showDetail(d) } } }
        quietly { client().cast(type, item.source, item.mediaId).let { list -> ui { showCast(list) } } }
        quietly { client().recommendations(type, item.source, item.mediaId).let { list -> ui { showRecommendations(list) } } }
    }

    private fun quietly(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (e: Exception) {
                // the page works without it
            }
        }.start()
    }

    internal fun showDetail(d: YamtrackClient.Detail) {
        setPoster(d.image)
        facts.removeAllViews()
        if (d.score != null) facts.addView(Ui.rating(c, d.score, item.source, d.scoreCount), lp().apply { rightMargin = Ui.px(c, 20) })
        val parts = if (show) listOfNotNull(
            Ui.date(d.date)?.let { "First aired $it" },
            d.seasons?.let { if (it == 1) "1 season" else "$it seasons" },
            d.runtime,
            d.status,
        ) else listOfNotNull(Ui.date(d.date), d.runtime)
        if (parts.isNotEmpty()) facts.addView(Ui.text(c, parts.joinToString(" · "), 26, 500, Ui.TEXT_2, lineHeight = 1.3f), lp(0, WRAP, 1f))
        facts.visibility = if (facts.childCount > 0) View.VISIBLE else View.GONE

        genres.removeAllViews()
        for (g in d.genres.take(if (phone) 3 else 5)) genres.addView(Ui.tag(c, g), lp().apply { if (genres.childCount > 0) leftMargin = Ui.px(c, 12) })
        genres.visibility = if (genres.childCount > 0) View.VISIBLE else View.GONE

        about.removeAllViews()
        d.synopsis?.let { about.addView(Ui.moreText(c, it, if (phone) 28 else 26, if (phone) 5 else 3), lp(MATCH)) }
    }

    internal fun showCast(cast: List<YamtrackClient.CastMember>) {
        castSection.removeAllViews()
        if (cast.isEmpty()) return
        castSection.addView(rail(c, "Cast", cast.map { m ->
            Ui.castCard(c, m.name, m.character, m.image) { host.openPerson(m) }
        }, 190))
        castSection.visibility = View.VISIBLE
    }

    internal fun showRecommendations(items: List<YamtrackClient.Item>) {
        moreSection.removeAllViews()
        if (items.isEmpty()) return
        moreSection.addView(rail(c, "More like this", items.take(20).map { rec ->
            Ui.posterCard(c, rec.title, BrowseScreen.kind(rec.mediaType), rec.image) { host.openPicker(rec) }
        }, 200))
        moreSection.visibility = View.VISIBLE
    }

    private fun client() = BrowseScreen.client(host.prefs)

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
        picked = s.number to e.number
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
        val episode = picked
        if (show && episode != null && HboMax.isHboMax(o.link)) {
            // JustWatch's HBO Max link can be the "(with ASL)" edition: use the regular one
            if (starting) return
            starting = true
            showHint("Checking HBO Max…")
            Thread {
                val link = HboMax.regularEpisode(o.link, item.title, episode.first, episode.second)
                ui {
                    starting = false
                    hint.visibility = View.GONE
                    share(link, label, pkg, title, sub, o)
                }
            }.start()
            return
        }
        share(o.link, label, pkg, title, sub, o)
    }

    private fun share(link: String, label: String, pkg: String, title: String, sub: String, o: JustWatch.Offer) {
        SyncService.share(link, label, pkg)
        host.sent(Playing(link, title, sub, serviceName(o), YamtrackClient.posterUrl(item.image)))
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

// ── Person: photo, born, biography, known for ───────────────────────────

class PersonScreen(host: Host, private val member: YamtrackClient.CastMember) : ContentScreen(host) {
    private val phone = Ui.isPhone(c)
    private val columns = if (!phone) 5 else if (c.resources.configuration.screenWidthDp >= 600) 3 else 2
    private val photo = PosterView(c, Ui.pxf(c, 14))
    private val department = Ui.oneLine(Ui.text(c, "", 24, 400, Ui.MUTED))
    private val facts = Ui.text(c, "", 26, 500, Ui.TEXT_2, lineHeight = 1.3f).apply { visibility = View.GONE }
    private val about = Ui.column(c)
    private val knownLabel = Ui.sectionLabel(c, "Known for")
    private val hint = Ui.text(c, "Loading…", 28, 400, Ui.FAINT)
    private val known = Ui.column(c)
    private var firstCard: View? = null

    override val view: View = build()

    private fun build(): View {
        val root = Ui.column(c).apply {
            if (phone) setPadding(Ui.px(c, 40), Ui.px(c, 40), Ui.px(c, 40), Ui.px(c, 64))
            else setPadding(Ui.px(c, 96), Ui.px(c, 64), Ui.px(c, 96), Ui.px(c, 80))
        }
        val hero = Ui.column(c)
        val top = Ui.row(c)
        top.addView(photo, lp(Ui.px(c, if (phone) 180 else 220), WRAP))
        Posters.load(YamtrackClient.posterUrl(member.image, "w342"), photo.image, Ui.px(c, 220))
        val texts = Ui.column(c)
        texts.addView(department)
        texts.addView(Ui.text(c, member.name, if (phone) 52 else 64, 800, spacingEm = -0.03f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp(MATCH).apply { topMargin = Ui.px(c, 10) })
        texts.addView(facts, lp(MATCH).apply { topMargin = Ui.px(c, 18) })
        top.addView(texts, lp(0, WRAP, 1f).apply { leftMargin = Ui.px(c, if (phone) 32 else 48) })
        hero.addView(top, lp(MATCH))
        (if (phone) hero else texts).addView(about, lp(MATCH).apply {
            topMargin = Ui.px(c, if (phone) 28 else 14); leftMargin = -Ui.px(c, 16)
        })
        root.addView(hero, lp(MATCH))

        val section = Ui.column(c)
        section.addView(knownLabel, lp().apply { bottomMargin = Ui.px(c, 20) })
        section.addView(hint)
        section.addView(known, lp(MATCH))
        root.addView(section, lp(MATCH).apply { topMargin = Ui.px(c, 56) })

        if (BrowseScreen.configured(host.prefs)) {
            Thread {
                val client = BrowseScreen.client(host.prefs)
                try {
                    val p = client.person(member.source, member.personId)
                    c.runOnUiThread { showPerson(p) }
                } catch (e: Exception) {
                    // the known-for list is the useful part
                }
                try {
                    val list = client.knownFor(member.source, member.personId)
                    c.runOnUiThread { showKnownFor(list) }
                } catch (e: Exception) {
                    c.runOnUiThread { hint.text = "Couldn't load: ${e.message}"; hint.setTextColor(Ui.WARN) }
                }
            }.start()
        }
        return PageScroll(c).apply { addView(root) }
    }

    /** The biography first, so the page opens at the top (name, photo); Down reaches the titles. */
    override fun defaultFocus(): View? = about.getChildAt(0) ?: firstCard

    private fun nothingFocused() = c.currentFocus == null || c.currentFocus?.isAttachedToWindow != true

    internal fun showPerson(p: YamtrackClient.Person) {
        if (member.image.isNullOrBlank()) Posters.load(YamtrackClient.posterUrl(p.image, "w342"), photo.image, Ui.px(c, 220))
        department.text = p.department ?: ""
        val born = listOfNotNull(Ui.date(p.birthday), p.placeOfBirth?.let { "in $it" }).joinToString(" ")
        val parts = listOfNotNull(born.takeIf { it.isNotEmpty() }?.let { "Born $it" }, Ui.date(p.deathday)?.let { "Died $it" })
        facts.text = parts.joinToString(" · ")
        facts.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        about.removeAllViews()
        p.biography?.let { about.addView(Ui.moreText(c, it, if (phone) 28 else 26, if (phone) 6 else 5), lp(MATCH)) }
        if (nothingFocused()) defaultFocus()?.requestFocus()
    }

    internal fun showKnownFor(items: List<YamtrackClient.Item>) {
        known.removeAllViews()
        if (items.isEmpty()) {
            hint.text = "Nothing listed."
            return
        }
        hint.visibility = View.GONE
        val cards = items.map { item ->
            val meta = listOfNotNull(Ui.year(item.date), item.role ?: BrowseScreen.kind(item.mediaType)).joinToString(" · ")
            Ui.posterCard(c, item.title, meta, item.image) { host.openPicker(item) }
        }
        firstCard = cards.first()
        known.addView(posterGrid(c, cards, columns), lp(MATCH))
        if (nothingFocused()) firstCard?.requestFocus()
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
