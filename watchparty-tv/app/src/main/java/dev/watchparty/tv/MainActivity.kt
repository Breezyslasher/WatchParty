package dev.watchparty.tv

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
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

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var relayField: EditText
    private lateinit var roomField: EditText
    private lateinit var nameField: EditText
    private lateinit var linkField: EditText
    private lateinit var labelField: EditText
    private lateinit var joinButton: Button
    private lateinit var statusView: TextView
    private lateinit var accessView: TextView
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

        root.addView(label("Start something for everyone", 20f, bold = true))
        root.addView(label(
            "Paste a link to a movie or episode — e.g. from the app's website. Every TV in " +
                "the room opens it. Disney+ links start playing straight away; for other apps, " +
                "if the link only opens the title's page, everyone presses Play and sync takes over.",
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
        val pkg = shared.knownPackage ?: appFor(shared.link)
        if (pkg == null) {
            shareNote.text = "No app on this TV opens that link."
            return
        }
        val label = labelField.text.toString().trim().ifEmpty { shared.label }
        SyncService.share(shared.link, label, pkg)
        shareNote.text = "Sent: $label"
    }

    /** The app (not a browser) that opens [link] on this TV, if any. */
    private fun appFor(link: String): String? {
        val pm = packageManager
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
        val handlers = pm.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.distinct()
        // anything that also opens an arbitrary website is a browser
        val browsers = pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.MATCH_DEFAULT_ONLY,
        ).map { it.activityInfo.packageName }.toSet()
        return handlers.firstOrNull { it !in browsers && it != packageName }
    }

    // ── notification access ─────────────────────────────────────────────

    private fun showAccess() {
        val component = ComponentName(this, SessionAccessService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val granted = TextUtils.split(enabled, ":").any { ComponentName.unflattenFromString(it) == component }
        if (granted) {
            accessView.visibility = View.GONE
        } else {
            accessView.visibility = View.VISIBLE
            val c = component.flattenToString()
            accessView.text = "One-time setup — Watch Party needs access to what's playing. From a computer:\n" +
                if (Build.VERSION.SDK_INT >= 28) "adb shell cmd notification allow_listener $c"
                else "adb shell settings put secure enabled_notification_listeners $c"
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
