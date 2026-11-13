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
 *    sent to the room so everyone follows.
 */

/** A party item as the relay stores it. Android items carry their link in
 *  [file] and the app's package in ids["android"]. */
data class Item(
    val file: String,
    val label: String,
    val androidPackage: String?,
)

data class RoomState(
    val seq: Long,
    val item: Item?,
    val positionSec: Double,
    val paused: Boolean,
    val speed: Double,
    val setAtSec: Double,
    val setByMe: Boolean,
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
)

sealed class Action {
    /** Start the item on this device: send its link to its app. */
    data class Open(val link: String, val pkg: String) : Action()
    object Play : Action()
    object Pause : Action()
    data class SeekTo(val ms: Long) : Action()
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
        const val USER_SEEK_JUMP_SEC = 4.0      // local jump that counts as a seek
    }

    private var openedFile: String? = null
    private var openedAtMs = 0L
    private var suppressUntilMs = 0L
    private var lastCorrectionMs = 0L
    private var notPlayableFile: String? = null
    private var last: LocalState? = null
    private var lastAtMs = 0L

    var corrections = 0
        private set

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
            if (pkg != null && isLink(item.file)) {
                if (local.pkg == pkg && local.play == LocalPlay.PLAYING && room.setByMe) {
                    // we announced what we're already watching — don't restart it
                } else {
                    actions += Action.Open(item.file, pkg)
                    openedAtMs = nowMs
                    suppressUntilMs = nowMs + SUPPRESS_MS
                }
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
                actions += Action.SeekTo((expected * 1000).toLong())
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

    private fun isLink(file: String) =
        file.startsWith("https://") || file.startsWith("http://")
}
