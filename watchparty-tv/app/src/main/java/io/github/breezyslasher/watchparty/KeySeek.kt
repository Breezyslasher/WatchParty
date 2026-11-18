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
 *
 * Keys sent back to back each count (three fast-forwards: +30 s, twice,
 * 2026-11-18), so a far seek skips up to [MAX_KEYS] at a time.
 */
object KeySeek {
    const val STEP_MS = 10_000L      // what one fast-forward / rewind key skips
    const val CLOSE_MS = 1_500L      // near enough: done
    const val MAX_GAP_MS = 300_000L  // farther than this, by hand (30 skips)
    const val MAX_KEYS = 3           // skips sent together (tested: 3)

    sealed class Move {
        object Done : Move() { override fun toString() = "Done" }
        /** [keys] fast-forwards, sent together. */
        data class Forward(val keys: Int) : Move()
        /** [keys] rewinds, sent together. */
        data class Back(val keys: Int) : Move()
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
                // behind: skip to within a skip short of it, then once more past it
                gapMs > 0 -> Move.Forward(keys(maxOf(1L, gapMs / STEP_MS)))
                // ahead: back to less than a skip ahead, then hold
                -gapMs > STEP_MS -> Move.Back(keys(-gapMs / STEP_MS))
                else -> Move.Hold(-gapMs)
            }
        } else {
            when {
                // ahead: back past it (a whole skip more than the difference), then run
                gapMs < 0 -> Move.Back(keys((-gapMs + STEP_MS - 1) / STEP_MS))
                gapMs > STEP_MS -> Move.Forward(keys(gapMs / STEP_MS))
                else -> Move.Run(gapMs)
            }
        }
    }

    private fun keys(n: Long): Int = n.coerceIn(1L, MAX_KEYS.toLong()).toInt()
}
