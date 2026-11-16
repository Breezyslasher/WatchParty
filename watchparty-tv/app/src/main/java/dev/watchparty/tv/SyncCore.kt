package dev.watchparty.tv

/*
 * The sync decisions, with no Android or network code, so they can be unit
 * tested on a plain JVM. SyncService feeds it the room state from the relay
 * and the local media session's state once a second, and carries out the
 * actions it returns.
 *
 * The rules mirror the Kodi addon's engine (service.watchparty):
 *  - everyone computes the expected position from the room's anchor
 *    (position + elapsed server time × speed while playing);
 *  - drift past DRIFT_THRESHOLD is corrected by seeking, at most once per
 *    CORRECTION_COOLDOWN;
 *  - our own commands are suppressed for SUPPRESS_MS so their echo isn't
 *    mistaken for the user acting;
 *  - a change the user makes locally (pause, play, seek with the remote) is
 *    sent to the room so everyone follows;
 *  - a just-launched app is left alone (no play, pause or seek) until it has
 *    played for STARTED_MS, or OPEN_GRACE_MS passes: seeking HBO Max's player
 *    while it started (5 s in, the room ahead) closed it on a phone. Its
 *    loading isn't reported as buffering either (see isStarting).
 */

/** A party item as the relay stores it. Android items carry their link in
 *  [file] and the app's package in ids["android"]; Kodi items a file and
 *  the title's [identity]. */
data class Item(
    val file: String,
    val label: String,
    val androidPackage: String?,
    val identity: Identity? = null,
    /** The web link to open: [file] for Android and browser items; for a
     *  Kodi addon's title, its path converted (AddonLinks); else null. */
    val link: String? = file.takeIf { it.startsWith("https://") || it.startsWith("http://") },
)

/**
 * What a title is, in the Kodi addon's terms (engine.py's _identity: type,
 * title, year and ids for a movie; show, season and episode for an episode),
 * so each device finds its own copy: Kodi in its library, Android and
 * browsers on JustWatch. [ids] are Kodi's uniqueids: tmdb, imdb, tvdb.
 */
data class Identity(
    val type: String,
    val title: String? = null,
    val year: Int? = null,
    val show: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val ids: Map<String, String> = emptyMap(),
) {
    /** Enough to look the title up. */
    val usable: Boolean get() = when (type) {
        "movie" -> !title.isNullOrBlank()
        "episode" -> !show.isNullOrBlank() && season != null && episode != null
        else -> false
    }

    companion object {
        fun movie(title: String, year: Int?, tmdbId: String?) =
            Identity("movie", title = title, year = year?.takeIf { it > 0 },
                ids = if (tmdbId.isNullOrBlank()) emptyMap() else mapOf("tmdb" to tmdbId))

        fun episode(show: String, season: Int, episode: Int) =
            Identity("episode", show = show, season = season, episode = episode)
    }
}

/** A room member as the relay reports it (for showing the room; sync
 *  doesn't use it). [driftSec] is null when they're not on the item. */
data class Member(
    val name: String,
    val paused: Boolean,
    val caching: Boolean,
    val onItem: Boolean,
    val driftSec: Double?,
)

data class RoomState(
    val seq: Long,
    val item: Item?,
    val positionSec: Double,
    val paused: Boolean,
    val speed: Double,
    val setAtSec: Double,
    val setByMe: Boolean,
    val members: List<Member> = emptyList(),
) {
    /** Where playback should be at [serverNowSec]. */
    fun expectedSec(serverNowSec: Double): Double {
        if (item == null || paused) return positionSec
        return positionSec + maxOf(0.0, serverNowSec - setAtSec) * (if (speed > 0) speed else 1.0)
    }
}

enum class LocalPlay { PLAYING, PAUSED, BUFFERING, OTHER }

/** The local media session we're following, or none. */
data class LocalState(
    val pkg: String?,
    val positionMs: Long,   // -1 if the app doesn't report one
    val play: LocalPlay,
    val title: String?,
    val durationMs: Long = -1,   // from the session's metadata, if it says
)

sealed class Action {
    /** Start the item on this device: send its link to its app. */
    data class Open(val link: String, val pkg: String) : Action()
    /** A Kodi item: find the title on a streaming app here (JustWatch), then open it. */
    data class Resolve(val file: String, val label: String, val identity: Identity) : Action()
    object Play : Action() { override fun toString() = "Play" }
    object Pause : Action() { override fun toString() = "Pause" }
    /** [pauseAfter]: the room is paused, so pause again once the seek has
     *  landed. A phone's Netflix app starts playing when it's seeked while
     *  paused: with the room paused, each correction set it playing again,
     *  and sync paused and seeked it every 4 s for most of a minute (logcat,
     *  Samsung phone, 2026-11-15). */
    data class SeekTo(val ms: Long, val pauseAfter: Boolean = false) : Action()
    /** Tell the room about something the user did here. */
    data class Send(val cmd: String, val positionSec: Double) : Action()
    /** A one-line note for the status screen. */
    data class Note(val text: String) : Action()
}

class SyncCore(
    private val driftThresholdSec: Double = 3.0,
) {
    companion object {
        const val SUPPRESS_MS = 4_000L          // ignore our own echo this long
        const val CORRECTION_COOLDOWN_MS = 4_000L
        const val OPEN_GRACE_MS = 20_000L       // let a launched app start up
        const val STARTED_MS = 3_000L           // playing this long = it has started
        const val USER_SEEK_JUMP_SEC = 4.0      // local jump that counts as a seek
    }

    private var openedFile: String? = null
    private var openedAtMs = 0L
    /** When the launched app was first seen playing (-1: not yet). */
    private var playingSinceMs = -1L
    private var suppressUntilMs = 0L
    private var lastCorrectionMs = 0L
    private var notPlayableFile: String? = null
    private var last: LocalState? = null
    private var lastAtMs = 0L

    var corrections = 0
        private set

    /** Forget the item, so the room's current one opens again on the next
     *  tick (this device switching from Remote only back to watching). */
    fun reset() {
        openedFile = null
        notPlayableFile = null
        last = null
    }

    /**
     * Whether the app launched for the current item is still starting up
     * (OPEN_GRACE_MS, until it has played STARTED_MS). Its buffering then
     * isn't reported to the room: the relay pauses everyone while a member
     * buffers, and a TV's Netflix flipping between buffering and playing as
     * it loaded paused the room again and again — the other device looped
     * its first seconds until the TV caught up.
     */
    fun isStarting(nowMs: Long): Boolean =
        openedFile != null && nowMs - openedAtMs < OPEN_GRACE_MS &&
            (playingSinceMs < 0 || nowMs - playingSinceMs < STARTED_MS)

    /** Is the local session on the party's item? */
    fun onItem(room: RoomState, local: LocalState): Boolean {
        val pkg = room.item?.androidPackage ?: return false
        return local.pkg == pkg && local.play != LocalPlay.OTHER
    }

    /** Called once per poll. [nowMs] is local wall-clock, [serverNowSec] is
     *  the relay's clock (local clock + measured offset). */
    fun tick(room: RoomState, local: LocalState, nowMs: Long, serverNowSec: Double): List<Action> {
        val actions = mutableListOf<Action>()
        val item = room.item
        if (item == null) {
            openedFile = null
            remember(local, nowMs)
            return actions
        }

        // 1. A new item: start it here (everyone, including whoever announced
        //    it — their app gets the same link and starts the same way).
        if (item.file != openedFile) {
            openedFile = item.file
            last = null
            val pkg = item.androidPackage
            val link = item.link
            if (pkg != null && link != null) {
                if (local.pkg == pkg && local.play == LocalPlay.PLAYING && room.setByMe) {
                    // we announced what we're already watching — don't restart it
                } else {
                    actions += Action.Open(link, pkg)
                    openedAtMs = nowMs
                    playingSinceMs = -1L
                    suppressUntilMs = nowMs + SUPPRESS_MS
                }
            } else if (item.identity?.usable == true) {
                // a Kodi title: SyncService finds it on an app here, and its
                // start-up gets the same grace as a link we open
                actions += Action.Resolve(item.file, item.label, item.identity)
                openedAtMs = nowMs
                playingSinceMs = -1L
                suppressUntilMs = nowMs + SUPPRESS_MS
            } else if (notPlayableFile != item.file) {
                notPlayableFile = item.file
                actions += Action.Note("\"${item.label.ifEmpty { item.file }}\" isn't an app link — can't play it here")
            }
            return actions
        }

        if (!onItem(room, local)) {
            remember(local, nowMs)
            return actions
        }

        val inGrace = nowMs - openedAtMs < OPEN_GRACE_MS
        // still starting up: leave the app alone until it has played a few seconds
        if (local.play == LocalPlay.PLAYING) {
            if (playingSinceMs < 0) playingSinceMs = nowMs
        } else if (inGrace) {
            playingSinceMs = -1L
        }
        if (inGrace && (playingSinceMs < 0 || nowMs - playingSinceMs < STARTED_MS)) {
            remember(local, nowMs)
            return actions
        }
        val suppressed = nowMs < suppressUntilMs
        val localSec = if (local.positionMs >= 0) local.positionMs / 1000.0 else -1.0

        // 2. The user did something here: tell the room, and let the room's
        //    answer drive everyone (including us) next tick.
        val prev = last
        if (prev != null && prev.pkg == local.pkg && !suppressed && !inGrace) {
            val wasPlaying = prev.play == LocalPlay.PLAYING
            val nowPlaying = local.play == LocalPlay.PLAYING
            if (prev.play != LocalPlay.BUFFERING && local.play != LocalPlay.BUFFERING &&
                wasPlaying != nowPlaying && nowPlaying != !room.paused
            ) {
                actions += Action.Send(if (nowPlaying) "play" else "pause", maxOf(0.0, localSec))
                suppressUntilMs = nowMs + SUPPRESS_MS
                remember(local, nowMs)
                return actions
            }
            if (localSec >= 0 && prev.positionMs >= 0) {
                val elapsed = (nowMs - lastAtMs) / 1000.0
                val predicted = prev.positionMs / 1000.0 + (if (wasPlaying) elapsed else 0.0)
                val expected = room.expectedSec(serverNowSec)
                // a jump that also moved us away from the room = the user seeked
                if (Math.abs(localSec - predicted) > USER_SEEK_JUMP_SEC &&
                    Math.abs(localSec - expected) > driftThresholdSec
                ) {
                    actions += Action.Send("seek", localSec)
                    suppressUntilMs = nowMs + SUPPRESS_MS
                    remember(local, nowMs)
                    return actions
                }
            }
        }

        // 3. Follow the room.
        if (!suppressed) {
            if (room.paused && local.play == LocalPlay.PLAYING) {
                actions += Action.Pause
                suppressUntilMs = nowMs + SUPPRESS_MS
            } else if (!room.paused && local.play == LocalPlay.PAUSED) {
                actions += Action.Play
                suppressUntilMs = nowMs + SUPPRESS_MS
            }
        }
        if (localSec >= 0 && local.play != LocalPlay.BUFFERING) {
            val expected = room.expectedSec(serverNowSec)
            if (Math.abs(localSec - expected) > driftThresholdSec &&
                nowMs - lastCorrectionMs > CORRECTION_COOLDOWN_MS
            ) {
                actions += Action.SeekTo((expected * 1000).toLong(), pauseAfter = room.paused)
                lastCorrectionMs = nowMs
                suppressUntilMs = nowMs + SUPPRESS_MS
                corrections++
            }
        }
        remember(local, nowMs)
        return actions
    }

    private fun remember(local: LocalState, nowMs: Long) {
        last = local
        lastAtMs = nowMs
    }
}
