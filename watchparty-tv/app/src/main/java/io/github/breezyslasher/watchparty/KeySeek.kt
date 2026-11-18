package io.github.breezyslasher.watchparty

/**
 * Seeking an app that takes media keys but no seek from another app
 * (Apple TV's phone app: it ignores MediaController.seekTo but obeys the
 * system's fast-forward / rewind keys, a 10 s skip each, and play / pause;
 * adb, 2026-11-18). [next] picks one move toward the room at a time:
 *
 *  - far off: skip toward the room;
 *  - the room playing and us a little ahead: pause for the difference
 *    ([Hold]), then play — the room catches up;
 *  - the room paused and us a little behind: play for the difference
 *    ([Run]), then pause.
 *
 * Behind a playing room, or ahead of a paused one, only a skip moves us the
 * right way: skip past it, then hold or run back. So it lands within
 * [CLOSE_MS], not just within one skip.
 */
object KeySeek {
    const val STEP_MS = 10_000L      // what one fast-forward / rewind key skips
    const val CLOSE_MS = 1_500L      // near enough: done
    const val MAX_GAP_MS = 300_000L  // farther than this, by hand (30 skips)

    sealed class Move {
        object Done : Move() { override fun toString() = "Done" }
        object Forward : Move() { override fun toString() = "Forward" }
        object Back : Move() { override fun toString() = "Back" }
        /** Pause this long, then play. */
        data class Hold(val ms: Long) : Move()
        /** Play this long, then pause. */
        data class Run(val ms: Long) : Move()
    }

    /** [gapMs]: where the room is minus where we are (positive = we're behind). */
    fun next(gapMs: Long, roomPlaying: Boolean): Move {
        if (Math.abs(gapMs) <= CLOSE_MS) return Move.Done
        return if (roomPlaying) {
            when {
                gapMs > 0 -> Move.Forward
                -gapMs > STEP_MS -> Move.Back
                else -> Move.Hold(-gapMs)
            }
        } else {
            when {
                gapMs < 0 -> Move.Back
                gapMs > STEP_MS -> Move.Forward
                else -> Move.Run(gapMs)
            }
        }
    }
}
