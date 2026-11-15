package dev.watchparty.sessionprobe

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

/**
 * Session Probe — answers one question per app: could a background sync app
 * drive this app's playback well enough for a watch party?
 *
 * It lists every active media session on the TV. "Test" runs what a sync app
 * would need, while the target app is in the foreground (which is how a real
 * watch party app would run): pause, seek +30 s, seek back, play, then checks
 * that the reported position keeps pace with real time. Results are shown on
 * screen and written to logcat under the tag "SessionProbe".
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var listenerComponent: ComponentName
    private lateinit var sessionManager: MediaSessionManager
    private lateinit var sessionsBox: LinearLayout
    private lateinit var logView: TextView
    private val statusViews = mutableListOf<Pair<MediaController, TextView>>()
    private var listening = false
    private var testRunning = false   // one test at a time; two would interleave commands

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            showSessions(controllers ?: emptyList())
        }

    private val ticker = object : Runnable {
        override fun run() {
            for ((controller, view) in statusViews) view.text = describe(controller)
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listenerComponent = ComponentName(this, ProbeListenerService::class.java)
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: error("This device has no media session service")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 40)
        }
        root.addView(label("Session Probe", 26f, bold = true))
        root.addView(label(
            "Start playback in the app you want to check, then press Test. " +
                "\"Test (opens app)\" brings that app to the front and runs the " +
                "checks while it plays — the way a real watch party app would " +
                "work. Expect it to pause and jump around for ~20 seconds.", 16f))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("Refresh") { refresh() })
        // For apps that stop playback when they leave the screen, so they
        // never show up in the list: press, switch to the app, press play.
        controls.addView(button("Test what's playing in 20 s") {
            if (hasAccess()) TestRun(null, openApp = false).start() else showAccessHelp()
        })
        controls.addView(button("Clear log") {
            logLines.setLength(0)
            logView.text = ""
        })
        root.addView(controls)
        sessionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sessionsBox)
        root.addView(label("Log", 20f, bold = true))
        logView = label(logLines.toString(), 14f).apply { typeface = Typeface.MONOSPACE }
        root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        logView.text = logLines.toString()
        refresh()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        if (listening) {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChanged)
            listening = false
        }
    }

    // ── access ──────────────────────────────────────────────────────────

    private fun hasAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners") ?: return false
        return TextUtils.split(enabled, ":").any {
            ComponentName.unflattenFromString(it) == listenerComponent
        }
    }

    private fun showAccessHelp() {
        sessionsBox.removeAllViews()
        statusViews.clear()
        sessionsBox.addView(label("Notification access needed", 19f, bold = true))
        sessionsBox.addView(label(
            "Android only shows an app other apps' media sessions if it has " +
                "notification access. TVs usually hide that setting, so grant " +
                "it from a computer with adb:", 16f))
        sessionsBox.addView(label(
            grantCommand(listenerComponent.flattenToString()), 15f)
            .apply { typeface = Typeface.MONOSPACE })
        sessionsBox.addView(button("Try the settings screen instead") {
            try {
                // Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS (a constant
                // only from API 22; the screen exists on some API 21 builds)
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } catch (e: ActivityNotFoundException) {
                log("This TV has no notification-access screen — use the adb command.")
            }
        })
        sessionsBox.addView(label("Then come back here and press Refresh.", 16f))
    }

    // ── sessions ────────────────────────────────────────────────────────

    private fun activeSessions(): List<MediaController> = try {
        sessionManager.getActiveSessions(listenerComponent)
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun refresh() {
        if (!hasAccess()) {
            showAccessHelp()
            return
        }
        if (!listening) {
            try {
                sessionManager.addOnActiveSessionsChangedListener(
                    sessionsChanged, listenerComponent, handler)
                listening = true
            } catch (e: SecurityException) {
                log("Could not watch sessions: $e")
            }
        }
        showSessions(activeSessions())
    }

    private fun showSessions(all: List<MediaController>) {
        sessionsBox.removeAllViews()
        statusViews.clear()
        val sessions = all.filter { it.packageName != packageName }
        if (sessions.isEmpty()) {
            sessionsBox.addView(label(
                "No active media sessions. Start playback in another app, " +
                    "then come back and press Refresh.", 16f))
            return
        }
        for (c in sessions) {
            val pkg = c.packageName
            sessionsBox.addView(label(pkg, 19f, bold = true))
            val status = label(describe(c), 15f)
            statusViews += c to status
            sessionsBox.addView(status)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Test (opens app)") { TestRun(pkg, openApp = true).start() })
            row.addView(button("Test in 20 s") { TestRun(pkg, openApp = false).start() })
            row.addView(button("Pause") { c.transportControls.pause() })
            row.addView(button("Play") { c.transportControls.play() })
            row.addView(button("−30 s") { seekBy(c, -30_000) })
            row.addView(button("+30 s") { seekBy(c, 30_000) })
            sessionsBox.addView(row)
        }
    }

    private fun seekBy(c: MediaController, deltaMs: Long) {
        val s = c.playbackState ?: return
        c.transportControls.seekTo((positionOf(s) + deltaMs).coerceAtLeast(0))
    }

    private fun describe(c: MediaController): String {
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: "(no title)"
        val s = c.playbackState ?: return "$title · no playback state"
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val total = if (duration > 0) " / ${fmt(duration)}" else ""
        return "${stateName(s.state)} · ${fmt(positionOf(s))}$total · $title\n" +
            "advertises: ${actionsOf(s)}"
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            log("  no launcher entry for $pkg — switch to it yourself now")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun returnToProbe() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) {
            log("  (switch back to Session Probe to read the results)")
        }
    }

    // ── the test ────────────────────────────────────────────────────────

    /**
     * One run against one app. Every step looks the session up again by
     * package, because apps often replace their session when they come to
     * the foreground.
     */
    private inner class TestRun(private var pkg: String?, private val openApp: Boolean) {
        private var aborted = false
        private var mark = 0L        // paused position the seeks are relative to
        private var trackPos = 0L    // position when play resumed
        private var trackWall = 0L   // clock when play resumed
        private val verdicts = mutableListOf<String>()

        private fun controller() =
            pkg?.let { p -> activeSessions().firstOrNull { it.packageName == p } }

        /** For "test what's playing": the other app's session, playing ones first. */
        private fun pickPlaying(): MediaController? {
            val others = activeSessions().filter { it.packageName != packageName }
            return others.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: others.firstOrNull()
        }
        private fun state() = controller()?.playbackState

        fun start() {
            if (testRunning) {
                log("  a test is already running — wait for its RESULT line")
                return
            }
            testRunning = true
            val target = pkg
            log("── ${target ?: "whatever is playing"} ──")
            when {
                target == null -> log("  switch to the app and start playback — " +
                    "testing whatever is playing in 20 s")
                openApp -> launchApp(target)
                else -> log("  switch to $target and make sure it's playing — starting in 20 s")
            }
            val steps = listOf<Pair<Long, () -> Unit>>(
                (if (openApp) 5_000L else 20_000L) to { inspect() },
                2_500L to { send("pause") { it.pause() } },
                2_000L to { checkPaused() },
                0L to { send("seek +30 s") { it.seekTo(mark + 30_000) } },
                2_500L to { checkSeek() },
                0L to { send("seek back") { it.seekTo(mark) } },
                2_500L to { send("play") { it.play() } },
                2_000L to { checkPlaying() },
                3_000L to { checkTracking() },
                0L to { finish() },
            )
            step(steps, 0)
        }

        private fun step(steps: List<Pair<Long, () -> Unit>>, i: Int) {
            if (aborted || i >= steps.size) return
            handler.postDelayed({
                try {
                    steps[i].second()
                } catch (e: Exception) {
                    log("  step failed: $e")
                }
                step(steps, i + 1)
            }, steps[i].first)
        }

        private fun inspect() {
            if (pkg == null) {
                val found = pickPlaying()
                    ?: return abort("nothing is playing — start playback within the 20 s")
                pkg = found.packageName
                log("  found: $pkg")
            }
            val c = controller()
                ?: return abort("no media session from $pkg — it may drop its " +
                    "session when it isn't in the foreground")
            val s = c.playbackState ?: return abort("session has no playback state")
            val md = c.metadata
            log("  title: ${md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "(none)"}")
            log("  state ${stateName(s.state)} at ${fmt(positionOf(s))}, duration " +
                fmt(md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L))
            log("  advertises: ${actionsOf(s)}")
            val opens = OPEN_ACTIONS.filter { (s.actions and it.first) != 0L }
                .map { it.second }
            verdicts += "can open by: " + opens.ifEmpty { listOf("nothing") }.joinToString("/")
            if (s.state != PlaybackState.STATE_PLAYING) {
                log("  not playing — sending play first")
                c.transportControls.play()
            }
        }

        private fun send(what: String, action: (MediaController.TransportControls) -> Unit) {
            val c = controller() ?: return abort("session disappeared before '$what'")
            log("  → $what")
            action(c.transportControls)
        }

        private fun checkPaused() {
            val s = state() ?: return abort("session disappeared")
            verdict("pause", s.state == PlaybackState.STATE_PAUSED,
                "state ${stateName(s.state)}")
            mark = positionOf(s).coerceAtLeast(0)
        }

        private fun checkSeek() {
            val s = state() ?: return abort("session disappeared")
            val pos = positionOf(s)
            val err = pos - (mark + 30_000)
            verdict("seek", pos >= 0 && abs(err) <= 2_500,
                "landed at ${fmt(pos)}, off by ${secs(err)}")
        }

        private fun checkPlaying() {
            val s = state() ?: return abort("session disappeared")
            verdict("play", s.state == PlaybackState.STATE_PLAYING,
                "state ${stateName(s.state)}")
            trackPos = positionOf(s)
            trackWall = SystemClock.elapsedRealtime()
        }

        private fun checkTracking() {
            val s = state() ?: return abort("session disappeared")
            val now = SystemClock.elapsedRealtime()
            val expected = trackPos + ((now - trackWall) * 1.0).toLong()
            val err = positionOf(s) - expected
            val age = if (s.lastPositionUpdateTime > 0)
                String.format(Locale.US, "%.1fs", (now - s.lastPositionUpdateTime) / 1000.0)
            else "never"
            verdict("position tracking", trackPos >= 0 && abs(err) <= 1_000,
                "drift ${secs(err)} over 3 s, speed ${s.playbackSpeed}, " +
                    "last position update $age ago")
        }

        private fun finish() {
            testRunning = false
            log("  RESULT $pkg: ${verdicts.joinToString(" · ")}")
            returnToProbe()
        }

        private fun abort(why: String) {
            aborted = true
            testRunning = false
            log("  ✗ stopped: $why")
            if (verdicts.isNotEmpty()) {
                log("  RESULT $pkg: ${verdicts.joinToString(" · ")} · INCOMPLETE")
            }
            returnToProbe()
        }

        private fun verdict(name: String, ok: Boolean, detail: String) {
            log("  ${if (ok) "✓" else "✗"} $name — $detail")
            verdicts += "$name ${if (ok) "✓" else "✗"}"
        }
    }

    // ── ui helpers ──────────────────────────────────────────────────────

    private fun log(line: String) {
        Log.i(TAG, line)
        logLines.append(line).append('\n')
        logView.text = logLines.toString()
    }

    private fun label(s: String, size: Float, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        }

    private fun button(caption: String, onClick: () -> Unit) =
        Button(this).apply {
            text = caption
            setAllCaps(false)
            setOnClickListener { onClick() }
        }

    companion object {
        private const val TAG = "SessionProbe"

        /** Survives the activity being recreated while another app is in front. */
        private val logLines = StringBuilder()
    }
}

// ── playback-state helpers ──────────────────────────────────────────────

/**
 * Current position: the last reported one, advanced by elapsed time when
 * playing — the same extrapolation a sync app's drift correction relies on.
 */
internal fun positionOf(s: PlaybackState): Long {
    if (s.position < 0) return -1
    if (s.state != PlaybackState.STATE_PLAYING || s.lastPositionUpdateTime <= 0) {
        return s.position
    }
    val elapsed = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
    return s.position + (elapsed * s.playbackSpeed).toLong()
}

/**
 * The adb command that grants notification access. `cmd notification` only
 * exists on newer Android; older versions need the setting written directly
 * (which replaces any other listeners — fine on a TV, where there rarely are).
 */
private fun grantCommand(component: String) =
    if (android.os.Build.VERSION.SDK_INT >= 28)
        "adb shell cmd notification allow_listener $component"
    else
        "adb shell settings put secure enabled_notification_listeners $component"

internal fun fmt(ms: Long): String {
    if (ms < 0) return "?"
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val sec = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

private fun secs(ms: Long) = String.format(Locale.US, "%+.1fs", ms / 1000.0)

internal fun stateName(state: Int) = when (state) {
    PlaybackState.STATE_PLAYING -> "PLAYING"
    PlaybackState.STATE_PAUSED -> "PAUSED"
    PlaybackState.STATE_BUFFERING -> "BUFFERING"
    PlaybackState.STATE_STOPPED -> "STOPPED"
    PlaybackState.STATE_NONE -> "NONE"
    PlaybackState.STATE_ERROR -> "ERROR"
    PlaybackState.STATE_CONNECTING -> "CONNECTING"
    PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
    PlaybackState.STATE_REWINDING -> "REWINDING"
    else -> "STATE_$state"
}

private val ACTIONS = listOf(
    PlaybackState.ACTION_PLAY to "play",
    PlaybackState.ACTION_PAUSE to "pause",
    PlaybackState.ACTION_PLAY_PAUSE to "play/pause",
    PlaybackState.ACTION_SEEK_TO to "seek",
    PlaybackState.ACTION_FAST_FORWARD to "ffwd",
    PlaybackState.ACTION_REWIND to "rewind",
    PlaybackState.ACTION_SKIP_TO_NEXT to "next",
    PlaybackState.ACTION_SKIP_TO_PREVIOUS to "previous",
    PlaybackState.ACTION_STOP to "stop",
)

/** Ways an app lets another app start specific content without deep links. */
private val OPEN_ACTIONS = listOf(
    PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "media id",
    8192L to "uri",   // PlaybackState.ACTION_PLAY_FROM_URI (API 23)
    PlaybackState.ACTION_PLAY_FROM_SEARCH to "search",
)

private fun actionsOf(s: PlaybackState): String =
    ACTIONS.filter { (s.actions and it.first) != 0L }
        .joinToString(", ") { it.second }
        .ifEmpty { "nothing" }
