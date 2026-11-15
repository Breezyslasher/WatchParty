package dev.watchparty.tv

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Join a room, see what the room is doing, and start a title for everyone.
 * The syncing itself happens in [SyncService], which keeps running while you
 * watch in the other app.
 */
class MainActivity : Activity() {

    companion object {
        const val SWITCH_TIP =
            "In the settings screen, turn on Watch Party (it's at the top of the list), then come back here.\n" +
                "If the remote can't reach the list at all, click Watch Party's switch once with a USB or " +
                "Bluetooth mouse — after that the remote works too.\n" +
                "On a phone, if Android says it's a restricted setting: Settings → Apps → Watch Party → ⋮ → " +
                "Allow restricted settings, then try again."
        const val ACCESS_DONE = "Done — Watch Party can now see and control what's playing."
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var relayField: EditText
    private lateinit var roomField: EditText
    private lateinit var nameField: EditText
    private lateinit var linkField: EditText
    private lateinit var labelField: EditText
    private lateinit var joinButton: Button
    private lateinit var statusView: TextView
    private lateinit var accessView: TextView
    private lateinit var setupButton: Button
    private lateinit var settingsButton: Button
    @Volatile private var setupRunning = false
    /** Latest message from automatic setup; shown instead of the instructions. */
    private var setupStatus: String? = null
    private lateinit var shareNote: TextView

    private val ticker = object : Runnable {
        override fun run() {
            statusView.text = SyncService.status
            joinButton.text = if (SyncService.running) "Leave room" else "Join room"
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("watchparty", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 40)
        }
        root.addView(label("Watch Party", 26f, bold = true))

        accessView = label("", 15f).apply { typeface = Typeface.MONOSPACE }
        root.addView(accessView)
        setupButton = button("Set up access") { startSetup() }
        root.addView(setupButton)
        settingsButton = button("Open developer settings") { openDeveloperSettings() }
        root.addView(settingsButton)

        root.addView(label("Relay", 15f))
        relayField = field("relay.example.com or 192.168.1.5:8765", prefs.getString("relay", "") ?: "")
        root.addView(relayField)
        root.addView(label("Room code", 15f))
        roomField = field("room code", prefs.getString("room", "") ?: "")
        root.addView(roomField)
        root.addView(label("Your name", 15f))
        nameField = field("Living room TV", prefs.getString("name", "") ?: "")
        root.addView(nameField)

        joinButton = button("Join room") {
            if (SyncService.running) {
                SyncService.stop(this)
            } else {
                val relay = relayField.text.toString().trim()
                val room = roomField.text.toString().trim()
                val name = nameField.text.toString().trim()
                prefs.edit().putString("relay", relay).putString("room", room)
                    .putString("name", name).apply()
                SyncService.start(this, relay, room, name)
            }
        }
        root.addView(joinButton)

        statusView = label(SyncService.status, 17f)
        root.addView(statusView)

        root.addView(label("Pick something to watch", 20f, bold = true))
        root.addView(label(
            "Browse your movies and shows on a Yamtrack server (or search), pick an episode and a " +
                "service, and every TV in the room starts it.", 15f))
        root.addView(button("Browse Yamtrack") { startActivity(Intent(this, BrowseActivity::class.java)) })

        root.addView(label("Start something for everyone", 20f, bold = true))
        root.addView(label(
            "Paste a link to a movie or episode from the app's website — ideally copied while it " +
                "plays. Every TV in the room opens it. Disney+, Netflix, HBO Max, Starz and Apple TV movie links " +
                "start playing on their own; for other apps (Crunchyroll opens just the app), " +
                "everyone picks the title and presses Play, and sync takes over.",
            15f))
        linkField = field("https://www.disneyplus.com/play/…", "")
        root.addView(linkField)
        labelField = field("Title (optional)", "")
        root.addView(labelField)
        shareNote = label("", 15f)
        root.addView(button("Start for everyone") { share() })
        root.addView(shareNote)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        showAccess()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ── sharing a link ──────────────────────────────────────────────────

    private fun share() {
        if (!SyncService.running) {
            shareNote.text = "Join a room first."
            return
        }
        val shared = MediaLink.normalize(linkField.text.toString())
        if (shared == null) {
            shareNote.text = "That isn't a web link (it should start with https://)."
            return
        }
        val pkg = Apps.packageFor(this, shared.link)
        if (pkg == null) {
            shareNote.text = "No app on this TV opens that link."
            return
        }
        val label = labelField.text.toString().trim().ifEmpty { shared.label }
        SyncService.share(shared.link, label, pkg)
        shareNote.text = "Sent: $label"
    }

    // ── notification access ─────────────────────────────────────────────

    private fun showAccess() {
        val component = ComponentName(this, SessionAccessService::class.java)
        if (SelfSetup.hasAccess(this)) {
            // say "done" once, right after setup; hide the section after that
            val after = setupStatus
            setupStatus = null
            accessView.visibility = if (after != null) View.VISIBLE else View.GONE
            if (after != null) accessView.text = if (after == SelfSetup.DONE) after else ACCESS_DONE
            setupButton.visibility = View.GONE
            settingsButton.visibility = View.GONE
            return
        }
        val screen = accessScreen(component)
        accessView.visibility = View.VISIBLE
        setupButton.visibility = View.VISIBLE
        setupButton.isEnabled = !setupRunning
        // only TVs without a settings switch need debugging turned on by hand
        settingsButton.visibility =
            if (screen == null && !SelfSetup.debuggingOn(this)) View.VISIBLE else View.GONE

        val status = setupStatus
        if (status != null) {
            accessView.text = status
            return
        }
        val c = component.flattenToString()
        val adb = if (Build.VERSION.SDK_INT >= 28) "adb shell cmd notification allow_listener $c"
        else "adb shell settings put secure enabled_notification_listeners $c"
        accessView.text = "One-time setup — Watch Party needs access to what's playing. Press Set up access.\n" +
            (if (screen != null) "It sets everything up automatically if debugging is on; otherwise it opens " +
                "the settings switch for you to turn on."
            else "It sets everything up automatically and shows you how to turn on debugging for a moment " +
                "(this TV has no settings switch for it).") +
            "\nFrom a computer instead: $adb"
    }

    /**
     * One button for both ways in: automatic setup through the TV's own adb
     * first; if that can't run (debugging off, or Wireless debugging only) and
     * the TV has a settings switch for notification access, open that.
     */
    private fun startSetup() {
        if (setupRunning) return
        setupRunning = true
        setupButton.isEnabled = false
        setupStatus = "Starting setup…"
        showAccess()
        val app = applicationContext
        val screen = accessScreen(ComponentName(this, SessionAccessService::class.java))
        Thread {
            var granted = false
            try {
                granted = SelfSetup.run(app, quietWhenDebuggingOff = screen != null) { message ->
                    handler.post {
                        setupStatus = message
                        showAccess()
                    }
                }
            } finally {
                handler.post {
                    setupRunning = false
                    if (!granted && screen != null) {
                        setupStatus = SWITCH_TIP
                        openAccessScreen(screen)
                    }
                    showAccess()
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
            setupStatus = "Couldn't open the settings screen (${e.javaClass.simpleName}). " +
                "From a computer: adb shell cmd notification allow_listener " +
                ComponentName(this, SessionAccessService::class.java).flattenToString()
        }
    }

    // ── views ───────────────────────────────────────────────────────────

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
}
