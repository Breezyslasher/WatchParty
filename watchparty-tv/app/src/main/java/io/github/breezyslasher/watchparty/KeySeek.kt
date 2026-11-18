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
 * Keys sent back to back each count (three fast-forwards: +30 s, twice; 120
 * in 3 s: 14:02 → 34:02; adb, 2026-11-18), so a seek sends the whole jump at
 * once, up to [MAX_KEYS], landing on the side a hold or run can close: one
 * burst, then one pause or play.
 */
object KeySeek {
    const val STEP_MS = 10_000L      // what one fast-forward / rewind key skips Apple TV
    const val CLOSE_MS = 1_500L      // near enough: done
    const val MAX_KEYS = 120         // skips sent together (tested: 120, 20 min)

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

    /** [gapMs]: where the room is minus where we are (positive = we're behind);
     *  [stepMs]: how far one key skips this app. */
    fun next(gapMs: Long, roomPlaying: Boolean, stepMs: Long = STEP_MS): Move {
        if (Math.abs(gapMs) <= CLOSE_MS) return Move.Done
        return if (roomPlaying) {
            when {
                // behind: skip just past it, then hold
                gapMs > 0 -> Move.Forward(keys(ceil(gapMs, stepMs)))
                // ahead: back to less than a skip ahead, then hold
                -gapMs > stepMs -> Move.Back(keys(-gapMs / stepMs))
                else -> Move.Hold(-gapMs)
            }
        } else {
            when {
                // ahead: back past it (a whole skip more than the difference), then run
                gapMs < 0 -> Move.Back(keys(ceil(-gapMs, stepMs)))
                gapMs > stepMs -> Move.Forward(keys(gapMs / stepMs))
                else -> Move.Run(gapMs)
            }
        }
    }

    /**
     * An app's skip from how far one key moved it ([movedMs], its own playing
     * time taken out), to the nearest 5 s (10, 15, 30 s are usual); null if it
     * barely moved: the key doesn't skip it.
     */
    fun stepFrom(movedMs: Long): Long? {
        val moved = Math.abs(movedMs)
        if (moved < 3_000L) return null
        return maxOf(5_000L, (moved + 2_500L) / 5_000L * 5_000L)
    }

    private fun keys(n: Long): Int = n.coerceIn(1L, MAX_KEYS.toLong()).toInt()

    /** Skips that cover [ms]. */
    private fun ceil(ms: Long, stepMs: Long): Long = (ms + stepMs - 1) / stepMs
}
