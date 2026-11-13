package dev.watchparty.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.Locale

/**
 * Keeps this TV in a Watch Party room while the user watches in another app.
 *
 * Runs as a foreground service (Android stops background work otherwise),
 * polls the relay once a second on its own thread, reads the playing app's
 * media session, and lets [SyncCore] decide what to do.
 */
class SyncService : Service() {

    companion object {
        private const val TAG = "WatchPartyTV"
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 1
        private const val POLL_MS = 1_000L

        const val EXTRA_RELAY = "relay"
        const val EXTRA_ROOM = "room"
        const val EXTRA_NAME = "name"
        const val ACTION_STOP = "dev.watchparty.tv.STOP"

        /** One line for the main screen; read on the UI thread. */
        @Volatile var status: String = "Not in a room"
            private set
        @Volatile var running = false
            private set
        @Volatile private var instance: SyncService? = null

        /** Announce a link to the room (from the main screen). */
        fun share(link: String, label: String, pkg: String): Boolean {
            val service = instance ?: return false
            service.worker?.post { service.announce(link, label, pkg) }
            return true
        }

        fun start(context: Context, relay: String, room: String, name: String) {
            val intent = Intent(context, SyncService::class.java)
                .putExtra(EXTRA_RELAY, relay).putExtra(EXTRA_ROOM, room).putExtra(EXTRA_NAME, name)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SyncService::class.java).setAction(ACTION_STOP))
        }
    }

    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var client: RelayClient? = null
    private var name = "Android TV"
    private val core = SyncCore()
    private var lastError: String? = null
    private var note: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        val relay = intent?.getStringExtra(EXTRA_RELAY).orEmpty()
        val room = intent?.getStringExtra(EXTRA_ROOM).orEmpty()
        name = intent?.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Android TV" }
        goForeground("Joining room…")
        if (relay.isBlank() || room.isBlank()) {
            setStatus("Enter a relay address and room code")
            stopSelf()
            return START_NOT_STICKY
        }
        // restart cleanly if already in a room
        worker?.let { w -> w.removeCallbacksAndMessages(null); w.post { client?.leave() } }
        thread?.quitSafely()
        val t = HandlerThread("watchparty-sync").also { it.start() }
        thread = t
        worker = Handler(t.looper)
        client = RelayClient(relay, room)
        instance = this
        running = true
        worker?.post { joinThenLoop() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        val c = client
        val t = thread
        worker?.removeCallbacksAndMessages(null)
        worker?.post { c?.leave(); t?.quitSafely() } ?: t?.quitSafely()
        worker = null
        thread = null
        client = null
        instance = null
        running = false
        setStatus("Not in a room")
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(1 /* STOP_FOREGROUND_REMOVE, API 24 */)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    // ── relay loop (worker thread) ──────────────────────────────────────

    private fun joinThenLoop() {
        val c = client ?: return
        try {
            c.join(name)
            lastError = null
            Log.i(TAG, "joined as ${c.memberId}")
        } catch (e: IOException) {
            fail("Can't join: ${e.message}")
            worker?.postDelayed({ joinThenLoop() }, 5_000)
            return
        }
        loop()
    }

    private fun loop() {
        val c = client ?: return
        try {
            // Report the session of the party's app if it's running, else
            // whatever is playing; the room's answer then drives us.
            val previous = lastRoom
            var controller = sessionFor(previous)
            var local = localState(controller)
            val room = c.poll(
                positionSec = if (local.positionMs >= 0) local.positionMs / 1000.0 else 0.0,
                paused = local.play != LocalPlay.PLAYING,
                file = local.pkg ?: "",
                caching = local.play == LocalPlay.BUFFERING,
                onItem = previous != null && core.onItem(previous, local),
                corrections = core.corrections,
            )
            lastRoom = room
            lastError = null
            if (room.item?.androidPackage != previous?.item?.androidPackage) {
                controller = sessionFor(room)
                local = localState(controller)
            }
            val actions = core.tick(room, local, System.currentTimeMillis(), c.serverNowSec())
            for (a in actions) perform(a, controller, c)
            showRoom(room, local)
        } catch (e: RelayClient.RelayException) {
            if (e.status == 410) {
                // the relay forgot us (restart, or we were silent too long)
                Log.i(TAG, "rejoining after 410")
                worker?.post { joinThenLoop() }
                return
            }
            fail(e.message ?: "relay error")
        } catch (e: IOException) {
            fail("Relay unreachable: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "sync step failed", e)
            fail("Error: $e")
        }
        worker?.postDelayed({ loop() }, POLL_MS)
    }

    @Volatile private var lastRoom: RoomState? = null

    private fun perform(a: Action, controller: MediaController?, c: RelayClient) {
        Log.i(TAG, "action: $a")
        when (a) {
            is Action.Open -> openLink(a.link, a.pkg)
            is Action.Play -> controller?.transportControls?.play()
            is Action.Pause -> controller?.transportControls?.pause()
            is Action.SeekTo -> controller?.transportControls?.seekTo(maxOf(0L, a.ms))
            is Action.Send -> try {
                c.command(a.cmd, a.positionSec)
            } catch (e: RelayClient.RelayException) {
                note = if (e.status == 403) "Controls are locked by the host" else e.message
            }
            is Action.Note -> note = a.text
        }
    }

    private fun announce(link: String, label: String, pkg: String) {
        val c = client ?: return
        try {
            c.command("open", 0.0, RelayClient.appItem(link, label, pkg))
            note = "Started for everyone: $label"
        } catch (e: IOException) {
            note = "Couldn't share: ${e.message}"
        }
    }

    // ── launching the app ───────────────────────────────────────────────

    /** Same as the adb test: VIEW the link in that app, in a fresh task,
     *  so an app that's already open doesn't swallow it. */
    private fun openLink(link: String, pkg: String) {
        val launch = MediaLink.launchFor(link, pkg)
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(launch.uri))
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        for ((key, value) in launch.extras) intent.putExtra(key, value)
        try {
            startActivity(intent)
            note = "Opening in ${appName(pkg)}…"
        } catch (e: Exception) {
            note = "${appName(pkg)} isn't installed or can't open this link"
            Log.w(TAG, "open failed for $pkg", e)
        }
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    // ── media sessions ──────────────────────────────────────────────────

    private fun sessionFor(room: RoomState?): MediaController? =
        room?.item?.androidPackage?.let { activeController(it) } ?: activeController(null)

    private fun activeController(pkg: String?): MediaController? {
        val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
        val sessions = try {
            manager.getActiveSessions(ComponentName(this, SessionAccessService::class.java))
        } catch (e: SecurityException) {
            note = "No notification access — see the setup command on the main screen"
            return null
        }
        val mine = sessions.filter { it.packageName != packageName && (pkg == null || it.packageName == pkg) }
        return mine.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: mine.firstOrNull()
    }

    private fun localState(c: MediaController?): LocalState {
        if (c == null) return LocalState(null, -1, LocalPlay.OTHER, null)
        val s = c.playbackState
        val play = when (s?.state) {
            PlaybackState.STATE_PLAYING -> LocalPlay.PLAYING
            PlaybackState.STATE_PAUSED -> LocalPlay.PAUSED
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> LocalPlay.BUFFERING
            else -> LocalPlay.OTHER
        }
        return LocalState(
            pkg = c.packageName,
            positionMs = if (s != null) positionOf(s) else -1,
            play = play,
            title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
        )
    }

    /** Last reported position, advanced by elapsed time while playing. */
    private fun positionOf(s: PlaybackState): Long {
        if (s.position < 0) return -1
        if (s.state != PlaybackState.STATE_PLAYING || s.lastPositionUpdateTime <= 0) return s.position
        val elapsed = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
        return s.position + (elapsed * s.playbackSpeed).toLong()
    }

    // ── status ──────────────────────────────────────────────────────────

    private fun showRoom(room: RoomState, local: LocalState) {
        val c = client ?: return
        val lines = StringBuilder()
        val item = room.item
        if (item == null) {
            lines.append("In the room — nothing playing yet")
        } else {
            val expected = room.expectedSec(c.serverNowSec())
            lines.append("Room: ").append(item.label.ifEmpty { item.file })
                .append(if (room.paused) " · paused at " else " · playing at ").append(fmt(expected))
            if (core.onItem(room, local) && local.positionMs >= 0) {
                val drift = local.positionMs / 1000.0 - expected
                lines.append("\nHere: ").append(local.title ?: local.pkg)
                    .append(" at ").append(fmt(local.positionMs / 1000.0))
                    .append(String.format(Locale.US, " (%+.1fs)", drift))
            } else if (item.androidPackage != null) {
                lines.append("\nWaiting for ").append(appName(item.androidPackage)).append(" to start…")
            }
        }
        note?.let { lines.append("\n").append(it) }
        setStatus(lines.toString())
        updateNotification(item?.label?.ifEmpty { null } ?: "In a Watch Party room")
    }

    private fun fail(message: String) {
        lastError = message
        setStatus(message)
    }

    private fun setStatus(text: String) {
        status = text
    }

    private fun fmt(sec: Double): String {
        val total = maxOf(0L, sec.toLong())
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    // ── foreground notification ─────────────────────────────────────────

    private fun goForeground(text: String) {
        shownNotificationText = text
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, 2 /* FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, API 29 */)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private var shownNotificationText: String? = null

    /** Only re-post when the text changes — posting every second made the
     *  TV log ~900 notification updates a day for an unchanged line. */
    private fun updateNotification(text: String) {
        if (text == shownNotificationText) return
        shownNotificationText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Watch Party", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Watch Party")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
