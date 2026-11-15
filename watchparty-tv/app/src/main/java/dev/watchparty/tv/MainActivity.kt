package dev.watchparty.tv

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Watch Party's only activity, in the "Cinema" design: the one-time setup
 * screen until notification access is granted, then a rail (Room, Browse,
 * Paste a link, the room form or members) beside the content
 * screens (Screens.kt). On phones the rail becomes a top section. The
 * syncing itself happens in [SyncService], which keeps running while you
 * watch in the other app.
 */
class MainActivity : Activity(), Host {

    companion object {
        const val MAX_PAGES = 12
        const val SWITCH_TIP =
            "Turn on Watch Party in the settings screen (it's at the top), then come back. If the remote " +
                "can't reach it, click its switch once with a mouse. On a phone, first: Settings → Apps → " +
                "Watch Party → ⋮ → Allow restricted settings."
        const val ACCESS_DONE = "Watch Party can now keep everyone in step"
    }

    override val activity: Activity get() = this
    override val prefs: SharedPreferences by lazy { getSharedPreferences("watchparty", Context.MODE_PRIVATE) }
    override val country: String by lazy { Locale.getDefault().country.uppercase(Locale.US).ifEmpty { "US" } }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private val phone by lazy { Ui.isPhone(this) }

    // setup
    private var setupView: View? = null
    private lateinit var setupButton: TextTile
    private lateinit var devButton: TextTile
    private lateinit var setupBox: LinearLayout
    private lateinit var setupMessage: TextView
    @Volatile private var setupRunning = false

    // shell
    private var shell: View? = null
    private lateinit var content: FrameLayout
    private lateinit var rail: Rail
    private var screen = Screen.ROOM
    private lateinit var roomScreen: RoomScreen
    private var browseScreen: BrowseScreen? = null
    private var linkScreen: LinkScreen? = null
    /** Title and person pages, newest last: Back goes to the one before, then to Browse. */
    private val pages = ArrayList<ContentScreen>()
    private val playing = HashMap<String, Playing>()
    private var wasRunning = SyncService.running

    private lateinit var toastView: TextView
    private val hideToast = Runnable { toastView.animate().alpha(0f).setDuration(200).start() }

    private val ticker = object : Runnable {
        override fun run() {
            if (shell != null) {
                rail.refresh()
                current()?.tick()
            }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        window.decorView.setBackgroundColor(Ui.BG)
        root = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            // Android 15 draws apps targeting SDK 35 edge to edge, under the status
            // and navigation bars: keep everything inside them (and above the
            // keyboard, which these insets include). TVs report none.
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        toastView = Ui.text(this, "", 28, 600, Ui.BG).apply {
            background = Ui.rounded(this@MainActivity, Ui.TEXT, 16)
            setPadding(Ui.px(context, 36), Ui.px(context, 24), Ui.px(context, 36), Ui.px(context, 24))
            elevation = Ui.pxf(context, 30)
            alpha = 0f
        }
        setContentView(root)
        if (SelfSetup.hasAccess(this)) showShell() else showSetup()
        root.addView(toastView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = Ui.px(this@MainActivity, if (phone) 32 else 72)
            bottomMargin = Ui.px(this@MainActivity, 64)
            leftMargin = Ui.px(this@MainActivity, 32)
        })
    }

    override fun onResume() {
        super.onResume()
        if (setupView != null && SelfSetup.hasAccess(this)) {
            showShell()
            toast(ACCESS_DONE)
        } else if (setupView != null) {
            refreshSetup()
        }
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (shell != null) {
            when (screen) {
                Screen.PICKER -> {
                    pages.removeAt(pages.size - 1)
                    go(if (pages.isEmpty()) Screen.BROWSE else Screen.PICKER)
                    return
                }
                Screen.BROWSE, Screen.LINK -> { go(Screen.ROOM); return }
                Screen.ROOM -> {}
            }
        }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ── Host ────────────────────────────────────────────────────────────

    override fun go(screen: Screen) {
        val target = when (screen) {
            Screen.ROOM -> roomScreen
            Screen.BROWSE -> browseScreen ?: BrowseScreen(this).also { browseScreen = it }
            Screen.LINK -> linkScreen ?: LinkScreen(this).also { linkScreen = it }
            Screen.PICKER -> pages.lastOrNull() ?: return
        }
        if (screen != Screen.PICKER) pages.clear()
        this.screen = screen
        content.removeAllViews()
        (target.view.parent as? FrameLayout)?.removeView(target.view)
        content.addView(target.view, FrameLayout.LayoutParams(MATCH, MATCH))
        rail.select(screen)
        target.tick()
        val back = target.lastFocus
        target.lastFocus = null
        content.post { (back ?: target.defaultFocus())?.requestFocus() }
    }

    override fun openPicker(item: YamtrackClient.Item) = open(PickerScreen(this, item))

    override fun openPerson(member: YamtrackClient.CastMember) = open(PersonScreen(this, member))

    private fun open(page: ContentScreen) {
        if (screen == Screen.PICKER) {
            pages.lastOrNull()?.lastFocus = content.findFocus()
            // a long walk through cast and recommendations keeps only the latest pages
            if (pages.size >= MAX_PAGES) pages.removeAt(0)
        } else {
            pages.clear()
        }
        pages.add(page)
        go(Screen.PICKER)
    }

    override fun sent(playing: Playing) {
        this.playing[playing.file] = playing
        pages.clear()
        go(Screen.ROOM)
        toast("Sent to the room: ${playing.title}")
    }

    override fun toast(text: String) {
        toastView.text = text
        toastView.animate().cancel()
        toastView.alpha = 1f
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, 3_000)
    }

    private fun current(): ContentScreen? = when (screen) {
        Screen.ROOM -> roomScreen
        Screen.BROWSE -> browseScreen
        Screen.LINK -> linkScreen
        Screen.PICKER -> pages.lastOrNull()
    }

    // ── shell ───────────────────────────────────────────────────────────

    private fun showShell() {
        setupView?.let { root.removeView(it) }
        setupView = null
        roomScreen = RoomScreen(this) { file -> playing[file] }
        rail = Rail()
        content = FrameLayout(this)
        val s = if (phone) {
            Ui.column(this).apply {
                addView(rail.view, lp(MATCH))
                addView(content, lp(MATCH, 0, 1f))
            }
        } else {
            Ui.row(this).apply {
                addView(rail.view, lp(Ui.px(this@MainActivity, 600), MATCH))
                addView(View(this@MainActivity).apply { setBackgroundColor(Ui.LINE_SOFT) },
                    lp(maxOf(1, Ui.px(this@MainActivity, 2) / 2), MATCH))
                addView(content, lp(0, MATCH, 1f))
            }
        }
        shell = s
        root.addView(s, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        go(Screen.ROOM)
        rail.refresh()
    }

    /** The left rail (TV) or the top section (phone). */
    private inner class Rail {
        val view: View
        private val c: Context = this@MainActivity
        private val navs = LinkedHashMap<Screen, TextTile>()
        private val form = Ui.column(c)
        private val info = Ui.column(c)
        private val roomCode = Ui.text(c, "", 56, 800, spacingEm = 0.02f)
        private val members = Ui.column(c)
        private val status = Ui.text(c, SyncService.status, 22, 400, Ui.FAINT).apply { maxLines = 2 }
        private val join = Ui.primary(c, "Join room", 80, 0, 30, 14, Ui.RAIL, 5, 1.03f) { toggleJoin() }
        private val relay = railField("Relay", "relay.example.com", prefs.getString("relay", "") ?: "")
        private val room = railField("Room code", "", prefs.getString("room", "") ?: "", caps = true)
        private val name = railField("Your name", "Living room TV", prefs.getString("name", "") ?: "")
        // phones: the room part folds away behind a header
        private val body = Ui.column(c)
        private val fold = Ui.listButton(c, "Room", null) { toggleFold() }
        private var shownMembers: List<Member>? = null

        init {
            form.addView(Ui.sectionLabel(c, "Join a room"))
            for (f in listOf(relay, room, name)) form.addView(f, lp(MATCH).apply { topMargin = Ui.px(c, 16) })
            info.addView(Ui.sectionLabel(c, "In room"))
            info.addView(roomCode, lp().apply { topMargin = Ui.px(c, 14) })
            info.addView(members, lp(MATCH).apply { topMargin = Ui.px(c, 14) })

            val navGroup = if (phone) Ui.row(c) else Ui.column(c)
            for ((s, label) in listOf(Screen.ROOM to "Room", Screen.BROWSE to "Browse", Screen.LINK to "Paste a link")) {
                val b = Ui.nav(c, if (phone && s == Screen.BROWSE) "Browse" else label) { go(s) }
                navs[s] = b
                navGroup.addView(b, if (phone) lp(0, Ui.tap(c, 72), 1f).apply { if (navGroup.childCount > 0) leftMargin = Ui.px(c, 10) }
                else lp(MATCH, Ui.px(c, 72)).apply { if (navGroup.childCount > 0) topMargin = Ui.px(c, 10) })
            }

            body.addView(form, lp(MATCH))
            body.addView(info, lp(MATCH))
            body.addView(join, lp(MATCH, Ui.tap(c, 80)).apply { topMargin = Ui.px(c, 28) })

            view = if (phone) {
                Ui.column(c).apply {
                    setBackgroundColor(Ui.RAIL)
                    setPadding(Ui.px(c, 40), Ui.px(c, 40), Ui.px(c, 40), Ui.px(c, 28))
                    addView(Ui.brand(c, 18, 30, 14))
                    addView(navGroup, lp(MATCH).apply { topMargin = Ui.px(c, 28) })
                    addView(fold, lp(MATCH, Ui.tap(c, 68)).apply { topMargin = Ui.px(c, 20) })
                    addView(body, lp(MATCH).apply { topMargin = Ui.px(c, 20) })
                    body.addView(status, lp(MATCH).apply { topMargin = Ui.px(c, 20) })
                    body.visibility = if (SyncService.running) View.GONE else View.VISIBLE
                }
            } else {
                Ui.column(c).apply {
                    setBackgroundColor(Ui.RAIL)
                    setPadding(Ui.px(c, 56), Ui.px(c, 56), Ui.px(c, 56), Ui.px(c, 48))
                    addView(Ui.brand(c, 18, 30, 14))
                    addView(navGroup, lp(MATCH).apply { topMargin = Ui.px(c, 28) })
                    addView(Ui.divider(c), lp(MATCH, maxOf(1, Ui.px(c, 2) / 2)).apply { topMargin = Ui.px(c, 28) })
                    addView(body, lp(MATCH).apply { topMargin = Ui.px(c, 28) })
                    addView(View(c), lp(MATCH, 0, 1f))      // pins the status line to the bottom
                    addView(status, lp(MATCH).apply { topMargin = Ui.px(c, 28) })
                }
            }
        }

        private fun railField(label: String, hint: String, value: String, caps: Boolean = false): LinearLayout =
            Ui.column(c).apply {
                addView(Ui.text(c, label, 22, 400, Ui.MUTED))
                addView(Ui.field(c, hint, value, 68, 26, Ui.BG, 12, 20,
                    spacingEm = if (caps) 0.08f else 0f, caps = caps), lp(MATCH, Ui.tap(c, 68)).apply { topMargin = Ui.px(c, 8) })
            }

        private fun value(f: LinearLayout) = (f.getChildAt(1) as EditText).text.toString().trim()

        fun select(s: Screen) {
            for ((key, b) in navs) b.setChosen(key == s || (key == Screen.BROWSE && s == Screen.PICKER))
        }

        private fun toggleFold() {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        private fun toggleJoin() {
            if (SyncService.running) {
                SyncService.stop(c)
            } else {
                val r = value(relay)
                val code = value(room).uppercase(Locale.US)
                val n = value(name)
                prefs.edit().putString("relay", r).putString("room", code).putString("name", n).apply()
                SyncService.start(c, r, code, n)
            }
            handler.postDelayed({ refresh() }, 300)
        }

        /** Once a second: room form or members, the button, the status line, toasts. */
        fun refresh() {
            val running = SyncService.running
            val code = prefs.getString("room", "") ?: ""
            if (running != wasRunning) {
                wasRunning = running
                toast(if (running) "Joined $code" else "Left $code")
                if (phone) body.visibility = if (running) View.GONE else View.VISIBLE
            }
            form.visibility = if (running) View.GONE else View.VISIBLE
            info.visibility = if (running) View.VISIBLE else View.GONE
            join.label.text = if (running) "Leave room" else "Join room"
            roomCode.text = code
            status.text = SyncService.status
            fold.label.text = if (running) "In room $code · ${SyncService.room?.members?.size ?: 0} here" else "Join a room"
            val list = if (running) SyncService.room?.members ?: emptyList() else emptyList()
            if (list != shownMembers) {
                shownMembers = list
                members.removeAllViews()
                for (m in list) members.addView(memberRow(m), lp(MATCH))
            }
        }

        private fun memberRow(m: Member): View = Ui.column(c).apply {
            val row = Ui.row(c).apply { gravity = Gravity.CENTER_VERTICAL }
            val texts = Ui.column(c)
            texts.addView(Ui.oneLine(Ui.text(c, m.name.ifEmpty { "Someone" }, 26, 600)))
            val detail = when {
                m.caching -> "Buffering"
                !m.onItem -> "Not on the title yet"
                m.paused -> "Paused"
                else -> "Watching"
            }
            texts.addView(Ui.oneLine(Ui.text(c, detail, 20, 400, Ui.MUTED)), lp().apply { topMargin = Ui.px(c, 2) })
            row.addView(texts, lp(0, WRAP, 1f))
            val drift = m.driftSec?.let { String.format(Locale.US, "%+.1fs", it) } ?: "—"
            row.addView(Ui.text(c, drift, 22, 400, Ui.MUTED, mono = true))
            setPadding(0, Ui.px(c, 12), 0, 0)
            addView(row, lp(MATCH))
            addView(Ui.divider(c, Ui.LINE_SOFT), lp(MATCH, maxOf(1, Ui.px(c, 2) / 2)).apply { topMargin = Ui.px(c, 12) })
        }
    }

    // ── setup (until notification access is granted) ────────────────────

    private fun showSetup() {
        val c: Context = this
        val component = ComponentName(this, SessionAccessService::class.java)
        val adb = if (Build.VERSION.SDK_INT >= 28) "adb shell cmd notification allow_listener ${component.flattenToString()}"
        else "adb shell settings put secure enabled_notification_listeners ${component.flattenToString()}"

        val left = Ui.column(c).apply { gravity = Gravity.CENTER_VERTICAL }
        left.addView(Ui.brand(c, 20, 28, 16))
        left.addView(Ui.text(c, "One-time setup", if (phone) 72 else 96, 800, spacingEm = -0.035f),
            lp().apply { topMargin = Ui.px(c, 40) })
        left.addView(Ui.text(c, "Watch Party needs access to what's playing on this TV, so it can keep everyone's " +
            "pause, play and seek in step.", 34, 400, Ui.TEXT_2, lineHeight = 1.4f).apply { maxWidth = Ui.px(c, 900) },
            lp().apply { topMargin = Ui.px(c, 40) })
        val buttons = Ui.row(c)
        setupButton = Ui.primary(c, "Set up access", 96, 56, 32, 16, Ui.BG, 6, 1.05f) { startSetup() }
        buttons.addView(setupButton, lp(WRAP, Ui.tap(c, 96)))
        // TVs without a settings switch (e.g. the Sony) need debugging turned on by hand
        devButton = Ui.nav(c, "Open developer settings") { openDeveloperSettings() }.apply {
            label.layoutParams = lp()
            gravity = Gravity.CENTER
        }
        buttons.addView(devButton, lp(WRAP, Ui.tap(c, 96)).apply { leftMargin = Ui.px(c, 24) })
        left.addView(buttons, lp().apply { topMargin = Ui.px(c, 40) })

        setupMessage = Ui.text(c, "", 30, 400, Ui.TEXT, lineHeight = 1.3f)
        setupBox = Ui.row(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(c, Ui.SURFACE, 16)
            setPadding(Ui.px(c, 32), Ui.px(c, 28), Ui.px(c, 32), Ui.px(c, 28))
            addView(View(c).apply { background = Ui.oval(Ui.ACCENT) }, lp(Ui.px(c, 14), Ui.px(c, 14)))
            addView(setupMessage, lp(0, WRAP, 1f).apply { leftMargin = Ui.px(c, 20) })
            visibility = View.GONE
        }
        left.addView(setupBox, lp(if (phone) MATCH else Ui.px(c, 1000)).apply { topMargin = Ui.px(c, 40) })
        left.addView(Ui.text(c, "From a computer instead:", 22, 400, Ui.FAINT, lineHeight = 1.5f),
            lp().apply { topMargin = Ui.px(c, 40) })
        left.addView(Ui.text(c, adb, 20, 400, Ui.MUTED, mono = true), lp().apply { topMargin = Ui.px(c, 6) })

        val right = Ui.column(c).apply { gravity = Gravity.CENTER_VERTICAL }
        right.addView(Ui.text(c, "What happens", 24, 600, Ui.MUTED, spacingEm = 0.12f, caps = true))
        val steps = Ui.column(c)
        steps.addView(Ui.divider(c), lp(MATCH, maxOf(1, Ui.px(c, 2) / 2)))
        for ((i, s) in listOf(
            "If debugging is on, Watch Party grants itself access. No computer needed.",
            "Otherwise it opens the settings switch. Turn Watch Party on.",
            "Updates keep the access. Only uninstalling clears it.",
        ).withIndex()) {
            val row = Ui.row(c).apply { setPadding(0, Ui.px(c, 32), 0, Ui.px(c, 32)) }
            row.addView(Ui.text(c, "${i + 1}", 30, 700, Ui.ACCENT), lp(Ui.px(c, 40)))
            row.addView(Ui.text(c, s, 30, 400, lineHeight = 1.35f), lp(0, WRAP, 1f).apply { leftMargin = Ui.px(c, 28) })
            steps.addView(row, lp(MATCH))
            steps.addView(Ui.divider(c), lp(MATCH, maxOf(1, Ui.px(c, 2) / 2)))
        }
        right.addView(steps, lp(MATCH).apply { topMargin = Ui.px(c, 24) })

        val page = if (phone) {
            Ui.column(c).apply {
                setPadding(Ui.px(c, 48), Ui.px(c, 64), Ui.px(c, 48), Ui.px(c, 64))
                addView(left, lp(MATCH))
                addView(right, lp(MATCH).apply { topMargin = Ui.px(c, 64) })
            }
        } else {
            Ui.row(c).apply {
                setPadding(Ui.px(c, 140), Ui.px(c, 120), Ui.px(c, 140), Ui.px(c, 120))
                addView(left, lp(0, MATCH, 1f))
                addView(right, lp(Ui.px(c, 640), MATCH).apply { leftMargin = Ui.px(c, 120) })
            }
        }
        val v = ScrollView(c).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            Ui.noClip(this)
            addView(page, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        setupView = v
        root.addView(v, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        refreshSetup()
        v.post { setupButton.requestFocus() }
    }

    private fun refreshSetup() {
        if (setupView == null) return
        setupButton.isEnabled = !setupRunning
        setupButton.alpha = if (setupRunning) 0.6f else 1f
        val screenIntent = accessScreen(ComponentName(this, SessionAccessService::class.java))
        devButton.visibility = if (screenIntent == null && !SelfSetup.debuggingOn(this)) View.VISIBLE else View.GONE
    }

    private fun setupSays(message: String) {
        setupMessage.text = message
        setupBox.visibility = View.VISIBLE
    }

    /**
     * One button for both ways in: automatic setup through the TV's own adb
     * first; if that can't run (debugging off, or Wireless debugging only) and
     * the TV has a settings switch for notification access, open that.
     */
    private fun startSetup() {
        if (setupRunning) return
        setupRunning = true
        refreshSetup()
        setupSays("Starting setup…")
        val app = applicationContext
        val screenIntent = accessScreen(ComponentName(this, SessionAccessService::class.java))
        Thread {
            var granted = false
            try {
                granted = SelfSetup.run(app, quietWhenDebuggingOff = screenIntent != null) { message ->
                    handler.post { if (setupView != null) setupSays(message) }
                }
            } finally {
                handler.post {
                    setupRunning = false
                    if (setupView == null) return@post
                    if (granted) {
                        showShell()
                        toast(ACCESS_DONE)
                        return@post
                    }
                    if (screenIntent != null) {
                        setupSays(SWITCH_TIP)
                        openAccessScreen(screenIntent)
                    }
                    refreshSetup()
                }
            }
        }.start()
    }

    /** Developer options if they're on, else the About screen (press Build 7 times). */
    private fun openDeveloperSettings() {
        val candidates = ArrayList<Intent>()
        if (SelfSetup.developerOptionsOn(this)) candidates.add(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        candidates.add(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        candidates.add(Intent(Settings.ACTION_SETTINGS))
        for (intent in candidates) {
            if (intent.resolveActivity(packageManager) == null) continue
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // try the next screen
            }
        }
    }

    /**
     * The system screen where notification access is switched on, if this TV
     * has one (many TVs hide it): Android 11+'s page for just this app, else
     * the list of all apps.
     */
    private fun accessScreen(component: ComponentName): Intent? {
        val candidates = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= 30) {
            candidates += Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        }
        candidates += Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        return candidates.firstOrNull { it.resolveActivity(packageManager) != null }
    }

    private fun openAccessScreen(screen: Intent) {
        try {
            startActivity(screen)
        } catch (e: Exception) {
            setupSays("Couldn't open the settings screen (${e.javaClass.simpleName}). From a computer: " +
                "adb shell cmd notification allow_listener " +
                ComponentName(this, SessionAccessService::class.java).flattenToString())
        }
    }
}
