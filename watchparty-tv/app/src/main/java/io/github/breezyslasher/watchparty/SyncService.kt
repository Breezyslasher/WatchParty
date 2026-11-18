package io.github.breezyslasher.watchparty

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
import android.view.KeyEvent
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
        private const val STALL_MS = 6_000L
        /** androidx MediaMetadataCompat.METADATA_KEY_ADVERTISEMENT (1 = an ad). */
        private const val METADATA_KEY_ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT"
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 1
        private const val POLL_MS = 1_000L
        /** Warner Bros. Discovery's apps (same player, play.<app>.com/video/watch/<id>
         *  links): on a phone a plain launch, and the link sent twice when the
         *  player is already open (see openLink, resendIfNotPlaying). HBO Max
         *  confirmed; Discovery+ showed the same failure. */
        private val RESEND_IF_OPEN = setOf(MediaLink.HBO_PACKAGE, "com.discovery.discoveryplus.mobile")
        private const val RESEND_AFTER_MS = 8_000L
        private const val KEY_FALLBACK_MS = 1_500L
        private const val KEY_STEP_MS = 900L
        private const val KEY_GAP_MS = 150L   // between keys sent together
        private const val KEY_SEEK_MAX_MOVES = 40
        /** Apps that ignore other apps' play / pause / seek but obey media keys:
         *  Apple TV's phone app (logs and adb, 2026-11-18). */
        private val KEYS_FIRST = setOf(MediaLink.APPLE_TV_PACKAGE)
        /** How long after a seek in a paused room to pause again (see Action.SeekTo). */
        private const val PAUSE_AFTER_SEEK_MS = 1_000L

        const val EXTRA_RELAY = "relay"
        const val EXTRA_ROOM = "room"
        const val EXTRA_NAME = "name"
        const val ACTION_STOP = "io.github.breezyslasher.watchparty.STOP"

        /** One line for the main screen; read on the UI thread. */
        @Volatile var status: String = "Not in a room"
            private set
        @Volatile var running = false
            private set
        /** The room as of the last poll (for the room screen), null when not in one. */
        @Volatile var room: RoomState? = null
            private set
        @Volatile private var instance: SyncService? = null

        /** Remote only: this device stays in the room and runs it (starts titles,
         *  plays, pauses, seeks, stops for everyone) but opens and follows
         *  nothing itself — a phone controlling the TVs. Kept in the prefs. */
        @Volatile var remoteOnly = false
            private set
        const val PREF_REMOTE_ONLY = "remote_only"

        fun setRemoteOnly(context: Context, on: Boolean) {
            remoteOnly = on
            context.getSharedPreferences("watchparty", Context.MODE_PRIVATE).edit().putBoolean(PREF_REMOTE_ONLY, on).apply()
            // back to watching: open what the room is playing now
            instance?.let { s -> s.worker?.post { s.core.reset() } }
        }

        /** The saved Watch here / Remote only (the screen opening reads it,
         *  and the service when it joins). Not into a running service from
         *  the screen: the service's flag is already the one in use. */
        fun loadRemoteOnly(context: Context) {
            if (instance != null && context !is SyncService) return
            remoteOnly = context.getSharedPreferences("watchparty", Context.MODE_PRIVATE).getBoolean(PREF_REMOTE_ONLY, false)
        }

        /** Where the room is now, in seconds (null when not in one). */
        fun roomNowSec(): Double? {
            val s = instance ?: return null
            val c = s.client ?: return null
            return room?.expectedSec(c.serverNowSec())
        }

        /** Play, pause, seek or stop the whole room ([positionSec]: where; now if null). */
        fun control(cmd: String, positionSec: Double? = null): Boolean {
            val s = instance ?: return false
            s.worker?.post { s.sendControl(cmd, positionSec) }
            return true
        }

        /** Announce a link to the room (from the main screen). */
        fun share(link: String, label: String, pkg: String, positionSec: Double = 0.0, identity: Identity? = null): Boolean {
            val service = instance ?: return false
            service.worker?.post { service.announce(link, label, pkg, positionSec, identity) }
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
        loadRemoteOnly(this)
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
        room = null
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
            val remote = remoteOnly
            // Remote only: report nothing playing here (never on the item, so the
            // room neither waits for us nor counts our drift), and follow nothing
            var controller = if (remote) null else sessionFor(previous)
            var local = localState(controller)
            val room = localize(c.poll(
                positionSec = if (local.positionMs >= 0) local.positionMs / 1000.0 else 0.0,
                paused = local.play != LocalPlay.PLAYING,
                file = local.pkg ?: "",
                // loading a just-opened title isn't buffering the party should wait for
                caching = local.play == LocalPlay.BUFFERING && !core.isStarting(System.currentTimeMillis()),
                onItem = previous != null && core.onItem(previous, local),
                corrections = core.corrections,
            ))
            lastRoom = room
            Companion.room = room
            lastError = null
            if (!remote && room.item?.androidPackage != previous?.item?.androidPackage) {
                controller = sessionFor(room)
                local = localState(controller)
            }
            val actions = if (remote) emptyList() else core.tick(room, local, System.currentTimeMillis(), c.serverNowSec())
            if (actions.isNotEmpty()) {
                // what this device was doing, to read the actions by
                Log.i(TAG, "local ${local.play} at ${if (local.positionMs >= 0) "%.1f".format(local.positionMs / 1000.0) else "?"}s" +
                    " in ${local.pkg}; room ${if (room.paused) "paused" else "playing"} at %.1fs".format(room.expectedSec(c.serverNowSec())))
            }
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

    private var localizedKey: String? = null
    private var localizedPkg: String? = null

    /**
     * The room's item names the app of whoever started it, but TVs and phones
     * can use different apps for the same service (Netflix: com.netflix.ninja
     * on Android TV, com.netflix.mediaclient on phones). If that app isn't
     * installed here, use the app that opens the link on this device, so
     * opening the link and following its media session both work.
     */
    private fun localize(room: RoomState): RoomState {
        val item = room.item ?: return room
        if (item.androidPackage == null) {
            // a Kodi item this device found on one of its apps (Bridge)
            resolved[item.file]?.let { return room.copy(item = item.copy(androidPackage = it)) }
            // a Kodi addon's title: the app here that opens its web link (AddonLinks)
            val link = item.link ?: return room
            val key = "\n" + link
            if (localizedKey != key) {
                localizedKey = key
                localizedPkg = Apps.appFor(this, link) ?: Apps.serviceOfLink(link)?.let { Apps.appByName(this, it) }
            }
            val found = localizedPkg ?: return room
            return room.copy(item = item.copy(androidPackage = found))
        }
        val pkg = item.androidPackage
        val link = item.link ?: return room
        val key = pkg + "\n" + link
        if (localizedKey != key) {
            localizedKey = key
            localizedPkg = if (Apps.isInstalled(this, pkg)) pkg
            else Apps.appFor(this, link) ?: Apps.serviceOfLink(link)?.let { Apps.appByName(this, it) } ?: pkg
        }
        val local = localizedPkg ?: pkg
        return if (local == pkg) room else room.copy(item = item.copy(androidPackage = local))
    }

    /** Where to look titles up (as the app's Browse does). */
    private val country: String by lazy { java.util.Locale.getDefault().country.uppercase(java.util.Locale.US).ifEmpty { "US" } }

    /** Kodi items found on an app here: their file → that app's package. */
    private val resolved = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * A Kodi member's title: look it up on JustWatch (off the sync thread, a
     * few seconds) and open it on the first of its services installed here.
     * Until then this device isn't on the item; Kodi's file and the stream
     * may differ by a few seconds (logos, recaps), which sync then holds.
     */
    private fun resolve(a: Action.Resolve) {
        val name = Bridge.name(a.identity, a.label)
        note = "Finding \"$name\" on an app here…"
        Thread {
            val result = try {
                val offers = Bridge.offers(a.identity,
                    find = { title, tmdb, type, imdb, year -> JustWatch.findTitle(title, tmdb, type, country, imdb, year) },
                    titleOffers = { JustWatch.offers(it, country) },
                    seasons = { JustWatch.seasons(it, country) },
                    episodes = { JustWatch.episodes(it, country) })
                if (offers.isEmpty()) "No streaming service has \"$name\" in $country"
                else Bridge.choose(offers) { o ->
                    Apps.packageFor(this, o.link, o.technicalName, o.clearName)?.takeIf { Apps.isInstalled(this, it) }
                } ?: "\"$name\" isn't on an app here (${offers.joinToString { it.clearName }})"
            } catch (e: Exception) {
                "Couldn't look up \"$name\": ${e.message}"
            }
            worker?.post {
                if (result is Pair<*, *>) {
                    val offer = result.first as JustWatch.Offer
                    val pkg = result.second as String
                    var link = offer.link
                    if (a.identity.type == "episode" && HboMax.isHboMax(link)) {
                        link = HboMax.regularEpisode(link, a.identity.show ?: "", a.identity.season ?: 0, a.identity.episode ?: 0)
                    }
                    resolved[a.file] = pkg
                    Log.i(TAG, "Kodi item ${a.file} → $link ($pkg)")
                    openLink(link, pkg)
                    note = "Playing \"$name\" on ${offer.clearName}"
                } else {
                    note = result as String
                }
            }
        }.start()
    }

    private fun perform(a: Action, controller: MediaController?, c: RelayClient) {
        Log.i(TAG, "action: $a")
        when (a) {
            is Action.Open -> if (MediaLink.isBareApple(a.link)) expandThenOpen(a.link, a.pkg) else openLink(a.link, a.pkg)
            is Action.Resolve -> resolve(a)
            is Action.Play -> if (keysFirst(controller)) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) else {
                controller?.transportControls?.play()
                keyIfIgnored(controller, play = true)
            }
            is Action.Pause -> if (keysFirst(controller)) mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE) else {
                controller?.transportControls?.pause()
                keyIfIgnored(controller, play = false)
            }
            is Action.SeekTo -> if (controller != null) {
                val target = maxOf(0L, a.ms)
                if (keysFirst(controller)) {
                    seekByKeys(controller, target, roomPlaying = !a.pauseAfter)
                } else {
                    controller.transportControls.seekTo(target)
                    if (a.pauseAfter) {
                        worker?.postDelayed({
                            controller.transportControls.pause()
                            keyIfIgnored(controller, play = false)
                        }, PAUSE_AFTER_SEEK_MS)
                    }
                }
            }
            is Action.Send -> try {
                c.command(a.cmd, a.positionSec)
            } catch (e: RelayClient.RelayException) {
                note = if (e.status == 403) "Controls are locked by the host" else e.message
            }
            is Action.Note -> note = a.text
        }
    }

    /**
     * Apple TV's phone app takes no play / pause / seek from another app's
     * MediaController, though its session lists them (logs, 2026-11-18), but
     * obeys media keys (adb: input keyevent KEYCODE_MEDIA_PLAY / PAUSE, cmd
     * media_session dispatch): those reach it from the system, like a
     * headset's button. So when an app hasn't followed a play or pause after
     * [KEY_FALLBACK_MS], the same goes as a media key through the system
     * (AudioManager.dispatchMediaKeyEvent, to the app now playing media).
     */
    private fun keyIfIgnored(c: MediaController?, play: Boolean) {
        c ?: return
        worker?.postDelayed({
            val state = c.playbackState?.state
            val done = if (play) state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                       else state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_STOPPED
            if (done) return@postDelayed
            Log.i(TAG, "${c.packageName} ignored ${if (play) "play" else "pause"} (state $state): sending the media key")
            mediaKey(if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE)
        }, KEY_FALLBACK_MS)
    }

    /** Apps that take media keys and no MediaController commands: keys straight away. */
    private fun keysFirst(c: MediaController?): Boolean = c?.packageName in KEYS_FIRST

    /** Until when (elapsedRealtime) a media-key seek is moving the app (LocalState.stepping). */
    @Volatile private var steppingUntil = 0L

    /**
     * Seek [c] (an app in KEYS_FIRST) to [targetMs] with media keys, one
     * KeySeek move at a time. [roomPlaying]: the target moves on meanwhile.
     */
    private fun seekByKeys(c: MediaController, targetMs: Long, roomPlaying: Boolean) {
        if (SystemClock.elapsedRealtime() < steppingUntil) return   // one already under way
        val issued = SystemClock.elapsedRealtime()
        val target = { targetMs + if (roomPlaying) SystemClock.elapsedRealtime() - issued else 0L }
        val s = c.playbackState ?: return
        val gap = target() - positionOf(s)
        if (Math.abs(gap) <= KeySeek.CLOSE_MS) return
        if (Math.abs(gap) > KeySeek.MAX_GAP_MS) {
            note = "${appName(c.packageName)} can't be seeked from here: skip to the room's spot by hand"
            return
        }
        Log.i(TAG, "${c.packageName}: seeking ${"%.1f".format(gap / 1000.0)}s with media keys")
        step(c, target, roomPlaying, KEY_SEEK_MAX_MOVES, null)
    }

    private fun step(c: MediaController, target: () -> Long, roomPlaying: Boolean, left: Int, gapBeforeKey: Long?) {
        steppingUntil = SystemClock.elapsedRealtime() + KEY_STEP_MS + 3_000L
        val s = c.playbackState
        if (s == null || left <= 0) return finishStepping(c, roomPlaying)
        val gap = target() - positionOf(s)
        // skips that didn't move it: the app doesn't skip on this key either
        if (gapBeforeKey != null && Math.abs(gap - gapBeforeKey) < 3_000L) {
            Log.i(TAG, "${c.packageName}: a skip key didn't move it (gap ${gap / 1000}s)")
            return finishStepping(c, roomPlaying)
        }
        val playing = s.state == PlaybackState.STATE_PLAYING
        when (val move = KeySeek.next(gap, roomPlaying)) {
            KeySeek.Move.Done -> finishStepping(c, roomPlaying)
            is KeySeek.Move.Forward, is KeySeek.Move.Back -> {
                val forward = move is KeySeek.Move.Forward
                val keys = if (move is KeySeek.Move.Forward) move.keys else (move as KeySeek.Move.Back).keys
                val code = if (forward) KeyEvent.KEYCODE_MEDIA_FAST_FORWARD else KeyEvent.KEYCODE_MEDIA_REWIND
                for (k in 0 until keys) worker?.postDelayed({ mediaKey(code) }, k * KEY_GAP_MS)
                steppingUntil = SystemClock.elapsedRealtime() + keys * KEY_GAP_MS + KEY_STEP_MS + 3_000L
                worker?.postDelayed({ step(c, target, roomPlaying, left - 1, gap) }, (keys - 1) * KEY_GAP_MS + KEY_STEP_MS)
            }
            is KeySeek.Move.Hold -> {
                steppingUntil = SystemClock.elapsedRealtime() + move.ms + 3_000L
                if (playing) mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                worker?.postDelayed({ finishStepping(c, roomPlaying) }, move.ms)
            }
            is KeySeek.Move.Run -> {
                steppingUntil = SystemClock.elapsedRealtime() + move.ms + 3_000L
                if (!playing) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                worker?.postDelayed({ finishStepping(c, roomPlaying) }, move.ms)
            }
        }
    }

    /** Leave the app playing or paused as the room is, and let sync judge it again shortly. */
    private fun finishStepping(c: MediaController, roomPlaying: Boolean) {
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        if (roomPlaying && !playing) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        if (!roomPlaying && playing) mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        steppingUntil = SystemClock.elapsedRealtime() + 1_500L
        Log.i(TAG, "${c.packageName}: media-key seek done")
    }

    private fun mediaKey(code: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        val now = SystemClock.uptimeMillis()
        try {
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        } catch (e: Exception) {
            Log.w(TAG, "media key $code failed", e)
        }
    }

    private fun sendControl(cmd: String, positionSec: Double?) {
        val c = client ?: return
        val r = lastRoom ?: return
        val at = maxOf(0.0, positionSec ?: r.expectedSec(c.serverNowSec()))
        try {
            val state = localize(c.command(cmd, at))
            lastRoom = state
            room = state
        } catch (e: RelayClient.RelayException) {
            note = if (e.status == 403) "Controls are locked by the host" else e.message
        } catch (e: IOException) {
            note = "Couldn't send it: ${e.message}"
        }
    }

    private fun announce(link: String, label: String, pkg: String, positionSec: Double, identity: Identity?) {
        val c = client ?: return
        try {
            c.command("open", positionSec, RelayClient.appItem(link, label, pkg, identity))
            note = "Started for everyone: $label"
        } catch (e: IOException) {
            note = "Couldn't share: ${e.message}"
        }
    }

    // ── launching the app ───────────────────────────────────────────────

    /** A Kodi Apple TV title (its id alone): follow tv.apple.com's redirect to
     *  the full link, off the sync thread, then open that. */
    private fun expandThenOpen(link: String, pkg: String) {
        note = "Opening in ${appName(pkg)}…"
        Thread {
            val full = MediaLink.expand(link) { url ->
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                try {
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) WatchParty")
                    conn.responseCode
                    conn.getHeaderField("Location")
                } finally {
                    conn.disconnect()
                }
            }
            Log.i(TAG, "Apple TV $link → $full")
            worker?.post { openLink(full, pkg) }
        }.start()
    }

    /** Same as the adb test: VIEW the link in that app, in a fresh task,
     *  so an app that's already open doesn't swallow it. */
    private fun openLink(link: String, pkg: String) {
        // the form this app opens (a phone's Starz app doesn't take the TV link)
        val target = Apps.linkFor(this, link, pkg)
        val launch = MediaLink.launchFor(target ?: link, pkg, appTakesLink = target != null)
        // what the app is playing now (e.g. the movie before), to tell it apart from the new title.
        // HBO Max only has a media session while its player is open (dumpsys on a
        // phone): a session now means it's open, the case where it ignores the link
        val openBefore = if (pkg in RESEND_IF_OPEN) activeController(pkg) else null
        val before = titleOf(openBefore)
        // Normally a fresh task, so an app that's already open doesn't swallow the
        // link. HBO Max on a phone mishandles that (its player opened, then closed;
        // or its home screen): there a plain launch works unless its player is
        // open, when the fresh task goes first and the plain resend follows (adb).
        val plain = pkg in RESEND_IF_OPEN && openBefore == null && !isTv()
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(launch.uri))
            .setPackage(pkg)
            .addFlags(if (plain) Intent.FLAG_ACTIVITY_NEW_TASK else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        for ((key, value) in launch.extras) intent.putExtra(key, value)
        Log.i(TAG, "launch $pkg ${if (plain) "plain" else "fresh task"}, player open before: ${openBefore != null}")
        try {
            startActivity(intent)
            note = "Opening in ${appName(pkg)}…"
            // not open: it starts the link itself (still loading at 8 s, with no session
            // yet — a resend then interrupted it), so only resend when it was open
            if (openBefore != null) resendIfNotPlaying(launch, pkg, before)
        } catch (e: Exception) {
            // the app takes no form of this link (e.g. The Roku Channel): open it
            // at its home screen; once someone plays, sync takes over
            val home = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
            if (home != null) try {
                startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                note = "Opened ${appName(pkg)}: find the title, then press Play"
                return
            } catch (e2: Exception) {
                Log.w(TAG, "home screen failed for $pkg", e2)
            }
            note = "${appName(pkg)} isn't installed or can't open this link"
            Log.w(TAG, "open failed for $pkg", e)
        }
    }

    /**
     * HBO Max on a phone ignores a link while it's already open (after a
     * movie, it shows its home screen, even in a fresh task and with playback
     * stopped); the same link sent again, without clearing the task, then
     * opens (adb on a Samsung phone, 2026-11-15). So if it isn't playing a few
     * seconds (8 s: 4 s was sometimes too soon) after the first link, send it once more.
     */
    private fun resendIfNotPlaying(launch: MediaLink.Launch, pkg: String, before: String?) {
        worker?.postDelayed({
            val c = activeController(pkg)
            val state = c?.playbackState?.state
            val busy = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
            // the session can still be the title from before (the app on its home
            // screen): only a different title counts as the new one starting
            val title = titleOf(c)
            if (busy && (before == null || title != before)) {
                Log.i(TAG, "$pkg started ($title), no resend")
                return@postDelayed
            }
            val again = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(launch.uri))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            for ((key, value) in launch.extras) again.putExtra(key, value)
            try {
                startActivity(again)
                note = "Sent the link to ${appName(pkg)} again"
                Log.i(TAG, "sent the link again to $pkg (state $state, title $title, before $before)")
            } catch (e: Exception) {
                Log.w(TAG, "resend failed for $pkg", e)
            }
        }, RESEND_AFTER_MS)
    }

    private fun isTv(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

    private fun titleOf(c: MediaController?): String? =
        c?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)

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
        trace(c, s)
        val play = when (s?.state) {
            PlaybackState.STATE_PLAYING -> LocalPlay.PLAYING
            PlaybackState.STATE_PAUSED -> LocalPlay.PAUSED
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> LocalPlay.BUFFERING
            else -> LocalPlay.OTHER
        }
        val stalled = stalled(c.packageName, s)
        return LocalState(
            pkg = c.packageName,
            // stalled: the app's own position, not advanced by the time since
            // it reported it (that made a sawtooth: 0 → 3 s, back to 0 at its next report)
            positionMs = if (s == null) -1 else if (stalled) s.position else positionOf(s),
            play = play,
            stalled = stalled,
            stepping = SystemClock.elapsedRealtime() < steppingUntil,
            title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            durationMs = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: -1,
        )
    }

    // What the playing app reports, for finding out how apps show ads: a
    // line whenever its state, length, title, metadata keys, the standard
    // ad flag (androidx's METADATA_KEY_ADVERTISEMENT, set by apps on
    // ExoPlayer's MediaSessionConnector), custom actions ("Skip ad"?) or
    // playback extras change, and whenever its position jumps. Read with
    // adb logcat -s WatchPartyTV.
    private var traceKey: String? = null
    private var tracePosMs = -1L
    private var traceAtMs = 0L
    private var tracePlaying = false

    /** The transport controls a session says it takes (PlaybackState.actions), for the log. */
    private fun actionNames(actions: Long): String = listOf(
        PlaybackState.ACTION_PLAY to "play", PlaybackState.ACTION_PAUSE to "pause",
        PlaybackState.ACTION_PLAY_PAUSE to "play_pause", PlaybackState.ACTION_SEEK_TO to "seek",
        PlaybackState.ACTION_FAST_FORWARD to "fast_forward", PlaybackState.ACTION_REWIND to "rewind",
        PlaybackState.ACTION_SKIP_TO_NEXT to "next", PlaybackState.ACTION_SKIP_TO_PREVIOUS to "previous",
        PlaybackState.ACTION_STOP to "stop",
    ).filter { actions and it.first != 0L }.joinToString(",") { it.second } + " (0x${java.lang.Long.toHexString(actions)})"

    private fun trace(c: MediaController, s: PlaybackState?) {
        val md = c.metadata
        val keys = md?.keySet()?.sorted()?.joinToString(",") ?: "-"
        val ad = md?.takeIf { it.containsKey(METADATA_KEY_ADVERTISEMENT) }?.getLong(METADATA_KEY_ADVERTISEMENT)
        val key = listOf(
            c.packageName, s?.state, md?.getLong(MediaMetadata.METADATA_KEY_DURATION),
            md?.getString(MediaMetadata.METADATA_KEY_TITLE), md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            keys, ad, s?.customActions?.joinToString(",") { it.action + "/" + it.name },
            s?.extras?.keySet()?.sorted()?.joinToString(","), s?.actions,
        ).joinToString(" | ")
        val now = System.currentTimeMillis()
        val pos = if (s != null) positionOf(s) else -1
        val predicted = if (tracePosMs >= 0) tracePosMs + (if (tracePlaying) now - traceAtMs else 0) else -1
        val jumped = pos >= 0 && predicted >= 0 && Math.abs(pos - predicted) > 2_000
        if (key != traceKey || jumped) {
            Log.i(TAG, "session ${c.packageName}: state ${s?.state} (${stateName(s?.state)}) at ${"%.1f".format(pos / 1000.0)}s" +
                (if (s != null) " (reported ${"%.1f".format(s.position / 1000.0)}s, " +
                    "${"%.1f".format((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) / 1000.0)}s ago)" else "") +
                (if (jumped) " (jumped from ${"%.1f".format(predicted / 1000.0)}s)" else "") +
                ", length ${md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.let { "%.1f".format(it / 1000.0) + "s" }}" +
                ", title ${md?.getString(MediaMetadata.METADATA_KEY_TITLE)}" +
                ", subtitle ${md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)}" +
                ", ad flag ${ad ?: "absent"}" +
                ", metadata keys [$keys]" +
                ", custom actions [${s?.customActions?.joinToString(", ") { it.name.toString() } ?: ""}]" +
                ", extras [${s?.extras?.keySet()?.sorted()?.joinToString(",") ?: ""}]" +
                ", controls [${s?.let { actionNames(it.actions) } ?: ""}]")
            traceKey = key
        }
        tracePosMs = pos
        traceAtMs = now
        tracePlaying = s?.state == PlaybackState.STATE_PLAYING
    }

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_NONE -> "none"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_FAST_FORWARDING -> "fast-forwarding"
        PlaybackState.STATE_REWINDING -> "rewinding"
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "skipping"
        else -> "?"
    }

    // Playing, but the position doesn't move: Prime Video during an ad
    // (Sony TV, 2026-11-17) reports nothing else — no length, no title, no
    // ad flag — just "playing" with the title's position frozen near 0,
    // sending a fresh report every ~3 s. Stalled = the app keeps reporting
    // (its update time moves) while its position has stayed within 1 s for
    // STALL_MS.
    private var stallPkg: String? = null
    private var stallPositionMs = 0L
    private var stallSinceMs = 0L
    private var stallLastUpdate = 0L
    private var stallReports = 0

    private var stallActive = false

    private fun stalled(pkg: String, s: PlaybackState?): Boolean {
        stallActive = stalledNow(pkg, s)
        return stallActive
    }

    private fun stalledNow(pkg: String, s: PlaybackState?): Boolean {
        val now = SystemClock.elapsedRealtime()
        // paused where it stalled: still stalled (the user pausing an app that
        // reports no position is a pause to send, not the app coming back)
        if (stallActive && s != null && s.state == PlaybackState.STATE_PAUSED && pkg == stallPkg &&
            Math.abs(s.position - stallPositionMs) <= 1_000
        ) return true
        if (s == null || s.state != PlaybackState.STATE_PLAYING || s.position < 0 ||
            pkg != stallPkg || Math.abs(s.position - stallPositionMs) > 1_000
        ) {
            stallPkg = pkg
            stallPositionMs = s?.position ?: 0L
            stallSinceMs = now
            stallLastUpdate = s?.lastPositionUpdateTime ?: 0L
            stallReports = 0
            return false
        }
        if (s.lastPositionUpdateTime != stallLastUpdate) {
            stallLastUpdate = s.lastPositionUpdateTime
            stallReports++
        }
        return stallReports >= 1 && now - stallSinceMs >= STALL_MS
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
            if (remoteOnly) {
                lines.append("\nRemote only: nothing plays here")
            } else if (core.onItem(room, local) && local.positionMs >= 0) {
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
        if (Build.VERSION.SDK_INT >= 34) {
            // the manifest's specialUse type (see AndroidManifest.xml)
            startForeground(NOTIFICATION_ID, n, 1 shl 30 /* FOREGROUND_SERVICE_TYPE_SPECIAL_USE, API 34 */)
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
