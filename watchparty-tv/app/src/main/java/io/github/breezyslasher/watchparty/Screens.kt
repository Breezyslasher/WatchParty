package io.github.breezyslasher.watchparty

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
 * The content screens of the "Cinema" design: Room (home), Browse,
 * (search, or a pasted link) and the season / episode / service picker. Values are the
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

enum class Screen { ROOM, BROWSE, PICKER }

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
    private val browseTile = Ui.homeTile(c, "Browse", "Search any title, or paste a link") { host.go(Screen.BROWSE) }
    private var shownFile: String? = "-"

    // the whole room's controls (everyone follows), shown while something is playing
    private val controls = Ui.column(c)
    private val clock = Ui.text(c, "0:00", if (phone) 88 else 104, 700, spacingEm = -0.02f)
        .apply { fontFeatureSettings = "tnum" }
    private val stateDot = Glyph(Glyph.Kind.DOT, Ui.ACCENT)
    private val clockState = Ui.text(c, "", 24, 600, Ui.TEXT_2).apply {
        val d = Ui.px(c, 22)
        stateDot.setBounds(0, 0, d, d)
        setCompoundDrawables(stateDot, null, null, null)
        compoundDrawablePadding = Ui.px(c, 10)
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(Ui.CHIP); cornerRadius = Ui.pxf(c, 30) }
        setPadding(Ui.px(c, 22), Ui.px(c, 12), Ui.px(c, 24), Ui.px(c, 12))
    }
    private val playGlyph = Glyph(Glyph.Kind.PAUSE, Ui.ON_ACCENT)
    private var shownPaused: Boolean? = null
    private val playPause = Ui.roundButton(c, if (phone) 150 else 156, if (phone) 64 else 68, playGlyph, Ui.ACCENT) {
        SyncService.control(if (SyncService.room?.paused == true) "play" else "pause")
    }
    private var stopArmedUntil = 0L
    private val stop = Ui.chip(c, "Stop for everyone") { stopPressed() }.apply {
        val g = Glyph(Glyph.Kind.STOP, Ui.WARN)
        val d = Ui.px(c, 26)
        g.setBounds(0, 0, d, d)
        label.setCompoundDrawables(g, null, null, null)
        label.compoundDrawablePadding = Ui.px(c, 12)
        label.setTextColor(Ui.WARN)
        fill = 0
        border = 0x66F3C47A
        borderFocus = Ui.WARN
        ring = Ui.TEXT
        ringWidth = Ui.pxf(c, 4)
        gravity = Gravity.CENTER
    }
    private val jumpField = Ui.field(c, "Go to 1:02:03", "", 68, 26, Ui.BG, 12, 22)
    private val controlsNote = Ui.text(c, "", 24, 400, Ui.WARN)
    // this device: watch here, or only run the room (a phone as the TVs' remote)
    private val watchHere = Ui.chip(c, "Watch here") { setMode(false) }
    private val remoteOnly = Ui.chip(c, "Remote only") { setMode(true) }
    private val modeNote = Ui.text(c, "", 24, 400, Ui.MUTED, lineHeight = 1.3f)

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

        // a card: the room's clock and state, the transport, then go-to-time and stop
        controls.background = GradientDrawable().apply { setColor(Ui.SURFACE); cornerRadius = Ui.pxf(c, 28) }
        val pad = Ui.px(c, if (phone) 36 else 48)
        controls.setPadding(pad, Ui.px(c, if (phone) 32 else 40), pad, Ui.px(c, if (phone) 32 else 40))
        val head = Ui.row(c).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.sectionLabel(c, "Everyone"), lp(0, WRAP, 1f))
        head.addView(clockState)
        controls.addView(head, lp(MATCH))
        controls.addView(clock, lp().apply { topMargin = Ui.px(c, 8) })

        val small = if (phone) 104 else 112
        val icon = if (phone) 64 else 68
        val bold = Ui.font(c, 700)
        val seek = { kind: Glyph.Kind, n: Int -> Ui.roundButton(c, small, icon, Glyph(kind, Ui.TEXT, n.toString(), bold), Ui.CHIP) {
            val by = if (kind == Glyph.Kind.BACK) -n.toDouble() else n.toDouble()
            SyncService.roomNowSec()?.let { now -> SyncService.control("seek", maxOf(0.0, now + by)) }
        } }
        val transport = Ui.row(c).apply { gravity = Gravity.CENTER }
        for (v in listOf(seek(Glyph.Kind.BACK, 30), seek(Glyph.Kind.BACK, 10), playPause,
            seek(Glyph.Kind.FORWARD, 10), seek(Glyph.Kind.FORWARD, 30))) {
            val size = if (v === playPause) (if (phone) 150 else 156) else small
            transport.addView(v, lp(Ui.px(c, size), Ui.px(c, size))
                .apply { if (transport.childCount > 0) leftMargin = Ui.px(c, if (phone) 18 else 32) })
        }
        controls.addView(transport, if (phone) lp(MATCH).apply { topMargin = Ui.px(c, 28) }
            else lp().apply { topMargin = Ui.px(c, 28) })

        controls.addView(Ui.divider(c), lp(MATCH, maxOf(1, Ui.px(c, 2) / 2)).apply { topMargin = Ui.px(c, 36) })
        val bottom = Ui.row(c).apply { gravity = Gravity.CENTER_VERTICAL }
        jumpField.imeOptions = EditorInfo.IME_ACTION_GO
        jumpField.inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_TIME
        jumpField.setOnEditorActionListener { _, _, _ -> jumpTo(); true }
        bottom.addView(jumpField, if (phone) lp(0, Ui.tap(c, 68), 1f) else lp(Ui.px(c, 300), Ui.tap(c, 68)))
        bottom.addView(Ui.primary(c, "Go", 68, 36, 26, 12, Ui.SURFACE, 5, 1.05f) { jumpTo() },
            lp(WRAP, Ui.tap(c, 68)).apply { leftMargin = Ui.px(c, 14) })
        if (phone) {
            controls.addView(bottom, lp(MATCH).apply { topMargin = Ui.px(c, 28) })
            controls.addView(stop, lp(MATCH, Ui.tap(c, 68)).apply { topMargin = Ui.px(c, 18) })
        } else {
            bottom.addView(View(c), lp(0, 1, 1f))
            bottom.addView(stop, lp(WRAP, Ui.tap(c, 68)))
            controls.addView(bottom, lp(MATCH).apply { topMargin = Ui.px(c, 28) })
        }
        controls.addView(controlsNote, lp(MATCH).apply { topMargin = Ui.px(c, 12) })
        controls.visibility = View.GONE
        col.addView(controls, lp(MATCH).apply { topMargin = Ui.px(c, 32) })

        idle.setPadding(0, Ui.px(c, 40), 0, 0)
        idle.addView(Ui.text(c, "What are we watching?", if (phone) 60 else 80, 800, spacingEm = -0.03f, lineHeight = 1.02f))
        idle.addView(Ui.text(c, "Pick a title and every TV in the room opens it in the same app.", 32, 400, Ui.TEXT_2)
            .apply { maxWidth = Ui.px(c, 1000) }, lp().apply { topMargin = Ui.px(c, 18) })
        col.addView(idle, lp(MATCH))

        col.addView(browseTile, (if (phone) lp(MATCH, Ui.px(c, 220)) else lp(Ui.px(c, 760), Ui.px(c, 260)))
            .apply { topMargin = Ui.px(c, 48) })

        val mode = Ui.row(c).apply { gravity = Gravity.CENTER_VERTICAL }
        mode.addView(Ui.sectionLabel(c, if (phone) "This phone" else "This TV"))
        mode.addView(watchHere, lp(WRAP, Ui.tap(c, 52)).apply { leftMargin = Ui.px(c, 24) })
        mode.addView(remoteOnly, lp(WRAP, Ui.tap(c, 52)).apply { leftMargin = Ui.px(c, 12) })
        col.addView(mode, lp(MATCH).apply { topMargin = Ui.px(c, 48) })
        col.addView(modeNote, lp(MATCH).apply { topMargin = Ui.px(c, 12) })
        showMode()
        addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun setMode(remote: Boolean) {
        SyncService.setRemoteOnly(c, remote)
        showMode()
    }

    private fun showMode() {
        val remote = SyncService.remoteOnly
        watchHere.setChosen(!remote)
        remoteOnly.setChosen(remote)
        modeNote.text = if (remote) "Runs the room without playing here: start titles and control everyone from this " +
            (if (phone) "phone." else "TV.")
        else "Opens the room's titles in their apps here and keeps them in sync."
    }

    /** Stop asks twice (it ends the title for everyone): the first press arms it for 4 s. */
    private fun stopPressed() {
        val now = System.currentTimeMillis()
        if (now < stopArmedUntil) {
            stopArmedUntil = 0
            stop.label.text = "Stop for everyone"
            SyncService.control("stop", 0.0)
            shownPaused = null
        } else {
            stopArmedUntil = now + 4_000
            stop.label.text = "Press again to stop"
        }
    }

    private fun jumpTo() {
        val sec = parseTime(jumpField.text.toString())
        if (sec == null) {
            controlsNote.text = "Type a time like 1:02:03 or 45:10"
            return
        }
        controlsNote.text = ""
        jumpField.setText("")
        jumpField.clearFocus()
        SyncService.control("seek", sec)
    }

    companion object {
        /** "1:02:03", "62:03" or "90" (seconds) → seconds, or null. */
        fun parseTime(text: String): Double? {
            val parts = text.trim().split(':')
            if (parts.isEmpty() || parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
            return parts.fold(0.0) { acc, p -> acc * 60 + p.toDouble() }
        }

        fun clockText(sec: Double): String {
            val t = maxOf(0, sec.toLong())
            val h = t / 3600
            val m = t / 60 % 60
            val s = t % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }

    override fun defaultFocus(): View = browseTile

    override fun tick() {
        val room = SyncService.room
        val item = room?.item
        val file = item?.file
        if (stopArmedUntil in 1 until System.currentTimeMillis()) {
            stopArmedUntil = 0
            stop.label.text = "Stop for everyone"
        }
        if (item == null || !SyncService.running) {
            controls.visibility = View.GONE
            if (shownFile != null) {
                shownFile = null
                backdrop.visibility = View.GONE
                idle.visibility = View.VISIBLE
            }
            return
        }
        controls.visibility = View.VISIBLE
        clock.text = clockText(SyncService.roomNowSec() ?: room.positionSec)
        if (shownPaused != room.paused) {
            shownPaused = room.paused
            clockState.text = if (room.paused) "Paused" else "Playing"
            stateDot.color = if (room.paused) Ui.WARN else Ui.ACCENT
            // the button shows what it does: play when paused, pause when playing
            playPause.removeAllViews()
            playPause.addView(ImageView(c).apply {
                setImageDrawable(Glyph(if (room.paused) Glyph.Kind.PLAY else Glyph.Kind.PAUSE, Ui.ON_ACCENT))
            }, LinearLayout.LayoutParams(Ui.px(c, if (phone) 64 else 68), Ui.px(c, if (phone) 64 else 68)))
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

// ── Browse: Yamtrack's library, or search (Yamtrack, else JustWatch) ────

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
    /** Titles per status, for "movie" and "tv": statuses with none aren't offered. */
    private val counts = HashMap<String, Map<Int, Int>>()
    private var countGeneration = 0

    private val tabs = LinkedHashMap<String, TextTile>()
    // the library's status and order: dropdowns (a second row of chips for the
    // order crowded a phone's screen; a dropdown opens a list a remote moves through too)
    private var sort = host.prefs.getString(KEY_SORT, null)?.takeIf { s -> YamtrackClient.SORTS.any { it.first == s } }
        ?: YamtrackClient.SORTS.first().first
    private val statusButton = Ui.dropdown(c, "Watching") { pickStatus() }
    private val sortButton = Ui.dropdown(c, "Recently updated") { pickSort() }
    private val chipRow = Ui.row(c)
    private val typeChips = LinkedHashMap<String, TextTile>()
    private val typeRow = Ui.row(c)
    private val searchField = Ui.field(c, "Search movies", "", 76, 30, Ui.SURFACE, 14, 28)
    private val serverBox = Ui.column(c)
    private val serverField = Ui.field(c, "yamtrack.example.com or 192.168.1.5:8000", "", 76, 28, Ui.SURFACE, 14, 28)
    private val tokenField = Ui.field(c, "API token", "", 76, 28, Ui.SURFACE, 14, 28)
    private val empty = Ui.text(c, "", 30, 400, Ui.MUTED)
    /** A link typed into Search: one result, to start it for everyone. */
    private val linkResult = Ui.column(c)
    private val grid = Ui.column(c)
    private val scroll = ScrollView(c)
    private var rowView: LinearLayout? = null

    override val view: View = Ui.column(c).apply {
        if (phone) setPadding(Ui.px(c, 40), Ui.px(c, 40), Ui.px(c, 40), 0)
        else setPadding(Ui.px(c, 96), Ui.px(c, 64), Ui.px(c, 96), 0)

        val tabRow = Ui.row(c)
        // without Yamtrack: Search (JustWatch) and Connect Yamtrack
        for ((key, name) in listOf("movie" to "Movies", "tv" to "TV shows", "search" to "Search", "yamtrack" to "Connect Yamtrack")) {
            val t = Ui.tab(c, name) { selectTab(key) }
            tabs[key] = t
            tabRow.addView(t, lp(WRAP, Ui.tap(c, 68)).apply { if (tabRow.childCount > 0) leftMargin = Ui.px(c, 12) })
        }
        addView(tabRow)

        chipRow.addView(statusButton, lp(WRAP, Ui.tap(c, 56)))
        chipRow.addView(sortButton, lp(WRAP, Ui.tap(c, 56)).apply { leftMargin = Ui.px(c, 12) })
        val chipScroll = android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            Ui.noClip(this)
            addView(chipRow)
        }
        addView(chipScroll, lp(MATCH).apply { topMargin = Ui.px(c, 32) })

        // Search: movies or TV shows, in the same place as the status and order
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

        // Yamtrack server + token, until set (optional: Search works without it)
        serverBox.addView(Ui.text(c, "Connect to Yamtrack", 44, 700))
        serverBox.addView(Ui.text(c, "Optional: your library (Watching, Planning, …) and people's pages. Enter your " +
            "server and API token (Yamtrack's settings show the token). Typing is " +
            "easiest from the Google TV app on your phone.", 28, 400, Ui.TEXT_2, lineHeight = 1.3f).apply {
            maxWidth = Ui.px(c, 1000)
        }, lp().apply { topMargin = Ui.px(c, 14) })
        serverBox.addView(serverField, lp(if (phone) MATCH else Ui.px(c, 900), Ui.tap(c, 76)).apply { topMargin = Ui.px(c, 28) })
        tokenField.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        serverBox.addView(tokenField, lp(if (phone) MATCH else Ui.px(c, 900), Ui.tap(c, 76)).apply { topMargin = Ui.px(c, 16) })
        serverBox.addView(Ui.primary(c, "Save", 80, 52, 30, 16, Ui.BG, 5, 1.05f) { saveServer() },
            lp(WRAP, Ui.tap(c, 80)).apply { topMargin = Ui.px(c, 28) })
        addView(serverBox, lp(MATCH).apply { topMargin = Ui.px(c, 32) })

        linkResult.visibility = View.GONE
        addView(linkResult, lp(MATCH).apply { topMargin = Ui.px(c, 40) })
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
        if (!configured()) tab = "search"
        updateHeader()
        if (configured()) {
            reload()
            countStatuses(tab)
        } else {
            empty.text = "Type a title to search, or paste a link."
            empty.visibility = View.VISIBLE
        }
    }

    override fun defaultFocus(): View? = tabs[tab]

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
        if (tab == "yamtrack") tab = "movie"
        updateHeader()
        reload()
        if (tab != "search") countStatuses(tab)
        tabs[tab]?.requestFocus()
    }

    private fun selectTab(key: String) {
        tab = key
        updateHeader()
        if (key == "yamtrack") {
            clearGrid()
            empty.visibility = View.GONE
            serverField.requestFocus()
        } else if (key == "search") {
            clearGrid()
            empty.text = if (query.isEmpty()) "Type a title to search, or paste a link." else ""
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
        val hadFocus = statusButton.isFocused || sortButton.isFocused
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
        if (hadFocus) (statusButton.takeIf { (chipRow.parent as View).visibility == View.VISIBLE } ?: tabs[tab])?.requestFocus()
    }

    private fun emptyText(): String = when {
        tab == "search" -> "No matches."
        counts[tab]?.get(ALL) == 0 -> "No ${if (tab == "tv") "TV shows" else "movies"} in your Yamtrack yet. Search finds any title."
        else -> "Nothing here."
    }

    private fun runSearch() {
        val text = searchField.text.toString().trim()
        if (isLink(text)) {
            showLink(text)
            return
        }
        query = text
        if (query.isEmpty()) {
            clearGrid()
            empty.text = "Type a title to search, or paste a link."
            empty.visibility = View.VISIBLE
            return
        }
        reload()
    }

    private fun isLink(text: String) = text.startsWith("https://") || text.startsWith("http://")

    /** A pasted link: its service and app here, and Start for everyone. */
    internal fun showLink(text: String) {
        clearGrid()
        linkResult.removeAllViews()
        val shared = MediaLink.normalize(text)
        val pkg = shared?.let { Apps.packageFor(c, it.link) }
        if (shared == null || pkg == null) {
            empty.text = if (shared == null) "That isn't a web link." else "No app on this device opens that link."
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        val note = Ui.text(c, "", 26, 400, Ui.WARN)
        linkResult.addView(Ui.sectionLabel(c, "Link"))
        linkResult.addView(Ui.oneLine(Ui.text(c, shared.label, 44, 700)), lp(MATCH).apply { topMargin = Ui.px(c, 10) })
        linkResult.addView(Ui.oneLine(Ui.text(c, "Opens in ${Apps.name(c, pkg)} · ${shared.link}", 26, 400, Ui.TEXT_2)),
            lp(MATCH).apply { topMargin = Ui.px(c, 8) })
        val row = if (phone) Ui.column(c) else Ui.row(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val start = Ui.primary(c, "Start for everyone", 80, 52, 30, 16, Ui.BG, 5, 1.05f) {
            if (!SyncService.running) {
                note.text = "Join a room first."
                return@primary
            }
            SyncService.share(shared.link, shared.label, pkg)
            host.sent(Playing(shared.link, shared.label, "Shared link", Apps.name(c, pkg), null))
        }
        row.addView(start, lp(WRAP, Ui.tap(c, 80)))
        row.addView(note, if (phone) lp(MATCH).apply { topMargin = Ui.px(c, 16) }
        else lp(0, WRAP, 1f).apply { leftMargin = Ui.px(c, 28) })
        linkResult.addView(row, lp(MATCH).apply { topMargin = Ui.px(c, 24) })
        linkResult.addView(Ui.text(c, "Links copied while the title plays work best. Without a title's details, " +
            "Kodi members can't play their own copy.", 24, 400, Ui.MUTED, lineHeight = 1.3f),
            lp(MATCH).apply { topMargin = Ui.px(c, 20) })
        linkResult.visibility = View.VISIBLE
        start.requestFocus()
    }

    /** The statuses with titles in them (all of them until the counts arrive), with how many. */
    private fun pickStatus() {
        val known = counts[tab]
        val shown = if (known == null) STATUSES.map { it.first }
        else YamtrackClient.statusesWithTitles(known, STATUSES.map { it.first }, ALL)
        ChoiceDialog(c, STATUSES.filter { it.first in shown }.map { (code, name) ->
            ChoiceDialog.Option(code.toString(), name, known?.get(code)?.takeIf { it >= 0 }?.toString(), code == status)
        }) { key ->
            status = key.toInt()
            reload()
            statusButton.requestFocus()
        }.show(statusButton)
    }

    private fun pickSort() {
        ChoiceDialog(c, YamtrackClient.SORTS.map { (key, name) -> ChoiceDialog.Option(key, name, chosen = key == sort) }) { key ->
            sort = key
            host.prefs.edit().putString(KEY_SORT, key).apply()
            reload()
            sortButton.requestFocus()
        }.show(sortButton)
    }

    private fun updateHeader() {
        val ok = configured()
        serverBox.visibility = if (tab == "yamtrack") View.VISIBLE else View.GONE
        for ((key, t) in tabs) {
            t.setChosen(key == tab)
            // the library needs Yamtrack; Search doesn't
            t.visibility = when (key) {
                "search" -> View.VISIBLE
                "yamtrack" -> if (ok) View.GONE else View.VISIBLE
                else -> if (ok) View.VISIBLE else View.GONE
            }
        }
        (chipRow.parent as View).visibility =
            if (ok && tab != "search" && counts[tab]?.get(ALL) != 0) View.VISIBLE else View.GONE
        statusButton.label.text = statusName(status) ?: "All"
        sortButton.label.text = YamtrackClient.SORTS.firstOrNull { it.first == sort }?.second ?: "Recently updated"
        for ((key, chip) in typeChips) chip.setChosen(key == searchType)
        typeRow.visibility = if (tab == "search") View.VISIBLE else View.GONE
        searchField.hint = if (searchType == "tv") "Search TV shows, or paste a link" else "Search movies, or paste a link"
        searchField.visibility = if (tab == "search") View.VISIBLE else View.GONE
        scroll.visibility = if (tab == "yamtrack") View.GONE else View.VISIBLE
        if (tab == "yamtrack") empty.visibility = View.GONE
    }

    private fun clearGrid() {
        generation++
        linkResult.visibility = View.GONE
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
        val searching = tab == "search"
        if (loading || tab == "yamtrack" || (!searching && !configured())) return
        if (searching && query.isEmpty()) return
        loading = true
        val gen = generation
        val type = if (tab == "tv") "tv" else "movie"
        val code = status
        val order = sort
        val from = offset
        val text = query
        empty.text = if (searching) "Searching…" else "Loading…"
        empty.visibility = View.VISIBLE
        Thread {
            var page: YamtrackClient.Page? = null
            var error: Exception? = null
            try {
                page = if (searching) {
                    search(searchType, text)
                } else {
                    client().library(type, if (code == ALL) null else code, from, sort = order)
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
                    if (!searching && (failed is YamtrackClient.YamtrackException || failed is java.io.IOException)) {
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

    /**
     * Yamtrack's search when it's set up; JustWatch's when it isn't, fails or
     * finds nothing. JustWatch titles carry their TMDB id when JustWatch has
     * it, so with Yamtrack their page still comes from Yamtrack.
     */
    private fun search(type: String, text: String): YamtrackClient.Page {
        if (configured()) {
            try {
                val p = client().search(type, text)
                if (p.items.isNotEmpty()) return p
            } catch (e: Exception) {
                // try JustWatch
            }
        }
        val found = JustWatch.search(text, if (type == "tv") "SHOW" else "MOVIE", host.country)
            .map { YamtrackClient.Item.fromJustWatch(it) }
        return YamtrackClient.Page(found, found.size)
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
        const val KEY_SORT = "yamtrack_sort"

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

        /** "TV · Watching" (Yamtrack's lists have no year); "Movie · 2025" for search results with one. */
        fun meta(item: YamtrackClient.Item) =
            listOfNotNull(kind(item.mediaType), statusName(item.status) ?: Ui.year(item.date)).joinToString(" · ")
    }
}

// ── Picker: season → episode → service ──────────────────────────────────

class PickerScreen(host: Host, private val item: YamtrackClient.Item) : ContentScreen(host) {
    private val phone = Ui.isPhone(c)
    private val show = item.mediaType == "tv"
    private val tmdb = if (item.source == "tmdb") item.mediaId else null
    private val yamtrack = BrowseScreen.configured(host.prefs)
    /** The title on JustWatch: the search result's id, else looked up once. */
    @Volatile private var jwTitle: String? = item.jwId

    /** A season or episode from Yamtrack (TMDB numbers) or JustWatch (with its id / offers). */
    internal data class Pick(val number: Int, val title: String, val jwId: String? = null, val offers: List<JustWatch.Offer>? = null)

    private val seasonList = Ui.column(c)
    private val episodeList = Ui.column(c)
    private val episodeCol = Ui.column(c)
    /** Loading and errors for seasons and episodes (services have theirs in the dialog). */
    private val hint = Ui.text(c, "", 28, 400, Ui.FAINT, lineHeight = 1.4f).apply { visibility = View.GONE }
    /** Movies: opens the services dialog. */
    private val watchButton: TextTile? = if (show) null else Ui.primary(c, "Watch", 84, 60, 30, 16, Ui.BG, 5, 1.05f) { openMovieServices() }
    private val seasonButtons = ArrayList<TextTile>()
    private val episodeButtons = ArrayList<TextTile>()
    private var season: Pick? = null
    /** The season and episode whose services are shown. */
    private var picked: Pair<Int, Int>? = null
    /** The chosen season's episodes (a neighbour's links show which services have no episode link). */
    private var seasonEpisodes: List<Pick> = emptyList()
    private var starting = false
    private var firstSeason: View? = null
    private var gen = 0
    /** The services dialog that's open (or last opened). */
    private var dialog: ServiceDialog? = null
    /** A movie's services, loaded with the page so Watch opens them at once; null until loaded. */
    private var movieOffers: List<JustWatch.Offer>? = null
    private var movieError: String? = null

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
        watchButton?.let { under.addView(it, lp(WRAP, Ui.tap(c, 84)).apply { topMargin = Ui.px(c, 28) }) }
        root.addView(hero, lp(MATCH))
        root.addView(hint, lp(MATCH).apply { topMargin = Ui.px(c, 32) })

        if (show) {
            // season and episode; picking an episode opens the services dialog
            val cols = if (phone) Ui.column(c) else Ui.row(c)
            val seasonCol = Ui.column(c)
            seasonCol.addView(Ui.sectionLabel(c, "Season"), lp().apply { bottomMargin = Ui.px(c, 16) })
            seasonCol.addView(scrolling(seasonList), if (phone) lp(MATCH) else lp(MATCH, 0, 1f))
            cols.addView(seasonCol, if (phone) lp(MATCH) else lp(Ui.px(c, 300), MATCH))

            episodeCol.addView(Ui.sectionLabel(c, "Episode"), lp().apply { bottomMargin = Ui.px(c, 16) })
            episodeCol.addView(scrolling(episodeList), if (phone) lp(MATCH) else lp(MATCH, 0, 1f))
            cols.addView(episodeCol, if (phone) lp(MATCH).apply { topMargin = Ui.px(c, 40) }
            else lp(0, MATCH, 1f).apply { leftMargin = Ui.px(c, 40) })
            // a TV shows a season's episodes in a fixed-height box that scrolls on its own
            root.addView(cols, (if (phone) lp(MATCH) else lp(MATCH, Ui.px(c, 540))).apply { topMargin = Ui.px(c, 40) })
        }
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

    override fun defaultFocus(): View? = watchButton ?: firstSeason

    private fun setPoster(image: String?) {
        if (image.isNullOrBlank() || posterSet) return
        posterSet = true
        Posters.load(YamtrackClient.posterUrl(image, "w342"), poster.image, Ui.px(c, 220))
    }

    /** The detail, cast and recommendations; each is optional (an older server, a title without them). */
    private fun loadPage() {
        if (!yamtrack || item.source.isEmpty() || item.source == "justwatch") {
            // without Yamtrack: JustWatch's page (description, genres, rating, similar — no cast row)
            quietly {
                val id = jwTitleId() ?: return@quietly
                JustWatch.page(id, host.country)?.let { p -> ui { showJustWatchPage(p) } }
            }
            return
        }
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

    /** JustWatch's id for this title (searched by TMDB id or title the first time). */
    private fun jwTitleId(): String? {
        jwTitle?.let { return it }
        val t = JustWatch.findTitle(item.title, tmdb, if (show) "SHOW" else "MOVIE", host.country) ?: return null
        jwTitle = t.id
        return t.id
    }

    internal fun showJustWatchPage(p: JustWatch.Page) {
        showDetail(YamtrackClient.Detail(p.title, p.poster, p.synopsis, p.genres, p.score, p.scoreCount, p.date,
            p.runtime?.let { JustWatch.runtimeText(it) }, null, null, null), p.scoreSource, p.certification)
        showRecommendations(p.similar.map { YamtrackClient.Item.fromJustWatch(it) })
    }

    /** The title's identity for the room, so Kodi members play their library copy (Bridge). */
    private fun identity(): Identity? {
        if (show) {
            val (season, episode) = picked ?: return null
            val ids = listOfNotNull(tmdb?.let { "show_tmdb" to it }, wbd?.let { "wbd" to it }).toMap()
            return Identity.episode(item.title, season, episode, Ui.year(detailDate ?: item.date)?.toIntOrNull(), ids)
        }
        return Identity.movie(item.title, Ui.year(detailDate ?: item.date)?.toIntOrNull(), tmdb)
    }

    /** The release date from the title's page (Yamtrack's or JustWatch's), for the identity's year. */
    private var detailDate: String? = null

    internal fun showDetail(d: YamtrackClient.Detail, scoreSource: String = item.source, certification: String? = null) {
        if (d.date != null) detailDate = d.date
        setPoster(d.image)
        facts.removeAllViews()
        if (d.score != null) facts.addView(Ui.rating(c, d.score, scoreSource, d.scoreCount), lp().apply { rightMargin = Ui.px(c, 20) })
        val parts = if (show) listOfNotNull(
            Ui.date(d.date)?.let { "First aired $it" },
            d.seasons?.let { if (it == 1) "1 season" else "$it seasons" },
            d.runtime,
            d.status,
            certification,
        ) else listOfNotNull(Ui.date(d.date), d.runtime, certification)
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

    // movies: Yamtrack's links, else JustWatch; shown when Watch opens the dialog
    private fun loadMovie() {
        val offers = try {
            val fromYamtrack = if (yamtrack && tmdb != null) try {
                client().providers("movie", "tmdb", tmdb, host.country)
            } catch (e: Exception) {
                emptyList()
            } else emptyList()
            fromYamtrack.ifEmpty {
                val id = jwTitleId()
                if (id == null) emptyList() else JustWatch.offers(id, host.country)
            }
        } catch (e: Exception) {
            ui { movieError = "Couldn't look it up: ${e.message}"; movieOffers = emptyList(); refreshMovieDialog() }
            return
        }
        ui { movieOffers = offers; refreshMovieDialog() }
    }

    private fun openMovieServices() {
        dialog?.dismiss()
        dialog = ServiceDialog(c, item.title, "Movie").also { it.show() }
        refreshMovieDialog()
    }

    private fun refreshMovieDialog() {
        val d = dialog ?: return
        if (show || !d.isShowing) return
        val offers = movieOffers
        when {
            offers == null -> d.hint("Finding where to watch…")
            movieError != null -> d.hint(movieError!!, warn = true)
            else -> fill(d, item.title, "Movie", offers)
        }
    }

    // shows: seasons and episodes from Yamtrack (TMDB), else JustWatch
    private fun loadSeasons() {
        val fromYamtrack = if (yamtrack && tmdb != null) try {
            client().seasons(tmdb).map { Pick(it.number, it.title) }
        } catch (e: Exception) {
            emptyList()
        } else emptyList()
        val seasons = fromYamtrack.ifEmpty {
            val id = jwTitleId()
            if (id == null) emptyList() else JustWatch.seasons(id, host.country).map { Pick(it.number, it.title, jwId = it.id) }
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
        seasonEpisodes = episodes
        for (b in seasonButtons) b.setChosen(b.tag == s.number)
        episodeList.removeAllViews()
        episodeButtons.clear()
        if (episodes.isEmpty()) {
            showHint("No episodes for ${seasonName(s.number).lowercase(Locale.US)}.")
            return
        }
        hint.visibility = View.GONE
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
    }

    private fun episodeSub(s: Pick, e: Pick) = "S${s.number} · E${e.number}" + if (e.title.isNotBlank()) " · ${e.title}" else ""

    private fun chooseEpisode(s: Pick, e: Pick) {
        val myGen = ++gen
        chooseEpisodeTitle(s, e)
        val sub = episodeSub(s, e)
        dialog?.dismiss()
        val d = ServiceDialog(c, item.title, sub).also { it.show() }
        dialog = d
        d.hint("Finding where to watch…")
        Thread {
            try {
                val offers = e.offers ?: episodeOffers(s.number, e.number)
                val showLevel = showLevelLinks(s, e, offers)
                ui { if (myGen == gen && d.isShowing) fill(d, item.title, sub, offers, showLevel = showLevel) }
            } catch (ex: Exception) {
                ui { d.hint("Couldn't look it up: ${ex.message}", warn = true) }
            }
        }.start()
    }

    /**
     * The links [offers] share with the next (else previous) episode: those
     * services only have the show's page, not this episode (Pluto TV for some
     * shows). The season's links are cached (Yamtrack) or already here
     * (JustWatch), so this is cheap; any failure marks nothing.
     */
    private fun showLevelLinks(s: Pick, e: Pick, offers: List<JustWatch.Offer>): Set<String> {
        val other = seasonEpisodes.firstOrNull { it.number == e.number + 1 }
            ?: seasonEpisodes.firstOrNull { it.number == e.number - 1 }
            ?: return emptySet()
        return try {
            JustWatch.sharedLinks(offers, other.offers ?: episodeOffers(s.number, other.number))
        } catch (ex: Exception) {
            emptySet()
        }
    }

    /** Yamtrack's per-episode links; JustWatch when it has none. */
    private fun episodeOffers(season: Int, episode: Int): List<JustWatch.Offer> {
        val fromYamtrack = if (yamtrack && tmdb != null) try {
            client().episodeProviders(tmdb, season, episode, host.country)
        } catch (e: Exception) {
            emptyList()
        } else emptyList()
        if (fromYamtrack.isNotEmpty()) return fromYamtrack
        val id = jwTitleId() ?: return emptyList()
        val jwSeason = JustWatch.seasons(id, host.country).firstOrNull { it.number == season } ?: return emptyList()
        return JustWatch.episodes(jwSeason.id, host.country).firstOrNull { it.number == episode }?.offers ?: emptyList()
    }

    /** For screenshots and tests: open the dialog for [title] / [sub] with [offers]. */
    internal fun showServices(title: String, sub: String, offers: List<JustWatch.Offer>) {
        val d = dialog?.takeIf { it.isShowing } ?: ServiceDialog(c, title, sub).also { it.show(); dialog = it }
        fill(d, title, sub, offers)
    }

    private fun fill(d: ServiceDialog, title: String, sub: String, offers: List<JustWatch.Offer>, autoStart: Boolean = true,
                     showLevel: Set<String> = emptySet()) {
        // a Discovery+ movie page becomes its play link when HBO Max has the title
        @Suppress("NAME_SHADOWING") val offers = JustWatch.discoveryPlayLinks(offers)
        wbd = offers.firstNotNullOfOrNull { it.wbd }
        if (offers.isEmpty()) {
            d.setServices(emptyList())
            d.hint("No streaming service has this in ${host.country}.")
            return
        }
        if (SyncService.running) d.hideHint()
        else d.hint("Join a room first (in the rail), then pick a service here.", warn = true)
        // services with their app on this TV first
        val sorted = offers.sortedBy { if (installedPackage(it) != null) 0 else 1 }
        d.setServices(sorted.map { o ->
            val pkg = installedPackage(o)
            val note = when {
                pkg == null -> "Not on this ${if (phone) "phone" else "TV"}. Others in the room may have it."
                // the service has no link for this episode, only the show's page
                o.link in showLevel -> "Opens the show's page: pick the episode."
                o.link.contains("discoveryplus.com/movie/") -> "Opens the movie's page: press Play."
                // the app doesn't take its website's links: it opens at its home screen
                Apps.linkFor(c, o.link, pkg) == null -> "Opens the app: find it, then press Play."
                autoPlays(o.link) -> "Starts playing"
                else -> "Opens the app. Everyone presses Play."
            }
            Ui.serviceCard(c, serviceName(o), note, o.icon, dimmed = pkg == null) { start(d, title, sub, o) }
        })
        // only one of them has its app here: nothing to choose, start it
        val sole = JustWatch.onlyInstalled(offers) { installedPackage(it) != null }
        if (autoStart && sole != null && sole.link !in showLevel && SyncService.running) {
            d.hint(if (JustWatch.soleOffer(offers) != null) "Starting on ${serviceName(sole)}…"
                   else "Starting on ${serviceName(sole)}, the one on this ${if (phone) "phone" else "TV"}…")
            start(d, title, sub, sole)
        }
    }

    /** The episode's show and numbers on HBO Max / Discovery+ (JustWatch.wbdEpisode), for Kodi. */
    private var wbd: String? = null

    private fun installedPackage(o: JustWatch.Offer): String? {
        val pkg = Apps.packageFor(c, o.link, o.technicalName, o.clearName) ?: return null
        return if (Apps.isInstalled(c, pkg)) pkg else null
    }

    private fun start(d: ServiceDialog, title: String, sub: String, o: JustWatch.Offer) {
        if (!SyncService.running) {
            d.hint("Join a room first (in the rail), then pick a service here.", warn = true)
            return
        }
        val pkg = Apps.packageFor(c, o.link, o.technicalName, o.clearName)
        if (pkg == null) {
            d.hint("Don't know which app opens ${serviceName(o)} links.", warn = true)
            return
        }
        val label = if (show) "$title · $sub" else title
        val episode = picked
        if (show && episode != null && HboMax.isHboMax(o.link)) {
            // JustWatch's HBO Max link can be the "(with ASL)" edition: use the regular one
            if (starting) return
            starting = true
            d.hint("Checking HBO Max…")
            Thread {
                val link = HboMax.regularEpisode(o.link, item.title, episode.first, episode.second)
                ui {
                    starting = false
                    share(d, link, label, pkg, title, sub, o)
                }
            }.start()
            return
        }
        share(d, o.link, label, pkg, title, sub, o)
    }

    private fun share(d: ServiceDialog, link: String, label: String, pkg: String, title: String, sub: String, o: JustWatch.Offer) {
        d.dismiss()
        SyncService.share(link, label, pkg, identity = identity())
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
                (link.contains("tv.apple.com/") && (link.contains("/movie/") || link.contains("/episode/"))) ||
                link.contains("tubitv.com/movies/") || link.contains("tubitv.com/tv-shows/") ||
                link.contains("crunchyroll.com/watch/") ||
                link.contains("discoveryplus.com/video/watch/") ||
                (link.contains("pluto.tv/") && (link.contains("/on-demand/movies/") || link.contains("/episode/"))) ||
                link.contains("watch.amazon.com/watch") ||
                link.contains("youtube.com/watch") ||
                (link.contains("peacocktv.com/deeplink") && link.contains("PLAY")) ||
                (link.contains("watch.plex.tv/") && (link.contains("/movie/") || link.contains("/episode/"))) ||
                link.contains("paramountplus.com/movies/") ||
                (link.contains("paramountplus.com/shows/") && link.contains("/video/"))
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
