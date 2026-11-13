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
    private lateinit var grantButton: Button
    private lateinit var shareNote: TextView
    private lateinit var nowPlayingView: TextView

    private val ticker = object : Runnable {
        override fun run() {
            statusView.text = SyncService.status
            joinButton.text = if (SyncService.running) "Leave room" else "Join room"
            nowPlayingView.text = describeLastPlayed()
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
        grantButton = button("Grant access") { openAccessScreen() }
        root.addView(grantButton)

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

        root.addView(label("Share what's playing", 20f, bold = true))
        nowPlayingView = label("", 15f)
        root.addView(nowPlayingView)
        root.addView(button("Share what I was watching") { shareWhatsPlaying() })

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
        val pkg = shared.knownPackage ?: appFor(shared.link)
        if (pkg == null) {
            shareNote.text = "No app on this TV opens that link."
            return
        }
        val label = labelField.text.toString().trim().ifEmpty { shared.label }
        SyncService.share(shared.link, label, pkg)
        shareNote.text = "Sent: $label"
    }

    // ── sharing what was playing ────────────────────────────────────────

    private fun describeLastPlayed(): String {
        if (!SyncService.running) return "Join a room, then play something in another app and come back here."
        val p = SyncService.lastPlayed
            ?: return "Play something in another app (Disney+, Netflix, …), then come back here."
        val ago = (System.currentTimeMillis() - p.atMs) / 60_000
        return "Last played: \"${p.title}\" in ${appName(p.pkg)} at ${clock(p.positionMs)}" +
            (if (ago >= 1) " · $ago min ago" else "")
    }

    private fun shareWhatsPlaying() {
        if (!SyncService.running) {
            shareNote.text = "Join a room first."
            return
        }
        val played = SyncService.lastPlayed
        if (played == null) {
            shareNote.text = "Nothing played yet. Play something in another app (Apple TV doesn't say " +
                "what's playing — paste its link below instead)."
            return
        }
        shareNote.text = "Looking up \"${played.title}\"…"
        Thread {
            val logs = if (played.pkg == MediaLink.DISNEY_PACKAGE) recentLogLines() else emptyList()
            val shared = NowPlaying.shareFor(played, logs)
            runOnUiThread { confirmShare(played, shared) }
        }.start()
    }

    private fun confirmShare(played: NowPlaying.Played, shared: MediaLink.Shared) {
        val app = appName(played.pkg)
        val titleOnly = NowPlaying.isTitleOnly(shared.link)
        val how = if (titleOnly) {
            "Every TV will open $app; each person picks \"${played.title}\" and presses Play, then sync takes over." +
                (if (played.pkg == MediaLink.DISNEY_PACKAGE && !canReadLogs())
                    "\n\nTo start Disney+ titles automatically, grant log access once:\n" +
                        "adb shell pm grant $packageName android.permission.READ_LOGS"
                else if (played.pkg == MediaLink.DISNEY_PACKAGE)
                    "\n\n(Disney+ only reveals a title's id when its page is opened — open the title's page, " +
                        "then play it, to start it automatically.)"
                else "")
        } else {
            "Every TV will start it at ${clock(played.positionMs)}."
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Share \"${played.title}\" ($app)?")
            .setMessage(how)
            .setPositiveButton("Share") { _, _ ->
                SyncService.share(shared.link, played.title, played.pkg, played.positionMs / 1000.0)
                shareNote.text = "Sent: ${played.title}"
            }
            .setNegativeButton("Cancel") { _, _ -> shareNote.text = "" }
            .show()
    }

    private fun canReadLogs() =
        checkCallingOrSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED

    /** Recent Disney+ SDK log lines — only readable once READ_LOGS is granted
     *  (Android 13+ also asks the viewer to allow it). */
    private fun recentLogLines(): List<String> {
        if (!canReadLogs()) return emptyList()
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
            val lines = process.inputStream.bufferedReader().useLines { all ->
                all.filter { it.contains("bamgrid") }.toList()
            }
            process.destroy()
            lines
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun clock(ms: Long): String {
        val t = maxOf(0L, ms / 1000)
        return if (t >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", t / 3600, t % 3600 / 60, t % 60)
        else String.format(java.util.Locale.US, "%d:%02d", t / 60, t % 60)
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
            grantButton.visibility = View.GONE
            return
        }
        accessView.visibility = View.VISIBLE
        val c = component.flattenToString()
        val adb = if (Build.VERSION.SDK_INT >= 28) "adb shell cmd notification allow_listener $c"
        else "adb shell settings put secure enabled_notification_listeners $c"
        val screen = accessScreen(component)
        if (screen != null) {
            grantButton.visibility = View.VISIBLE
            accessView.text = "One-time setup — Watch Party needs access to what's playing. " +
                "Press Grant access and turn on Watch Party. If the switch is greyed out " +
                "or missing, run this from a computer instead:\n$adb"
        } else {
            grantButton.visibility = View.GONE
            accessView.text = "One-time setup — Watch Party needs access to what's playing. " +
                "This TV has no settings screen for it, so run this from a computer:\n$adb"
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

    private fun openAccessScreen() {
        val screen = accessScreen(ComponentName(this, SessionAccessService::class.java)) ?: return
        try {
            startActivity(screen)
        } catch (e: Exception) {
            accessView.text = "Couldn't open the settings screen (${e.javaClass.simpleName}). " +
                "Use the adb command instead:\nadb shell cmd notification allow_listener " +
                ComponentName(this, SessionAccessService::class.java).flattenToString()
            grantButton.visibility = View.GONE
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
