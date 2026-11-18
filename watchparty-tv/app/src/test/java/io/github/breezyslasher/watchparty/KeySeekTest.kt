package io.github.breezyslasher.watchparty

import io.github.breezyslasher.watchparty.KeySeek.Move
import org.junit.Assert.assertEquals
import org.junit.Test

/** Seeking Apple TV's phone app with 10 s skip keys and play / pause (KeySeek). */
class KeySeekTest {

    @Test fun nearEnoughIsDone() {
        assertEquals(Move.Done, KeySeek.next(1_200, roomPlaying = true))
        assertEquals(Move.Done, KeySeek.next(-1_500, roomPlaying = false))
    }

    @Test fun behindAPlayingRoomSkipsPastThenHolds() {
        // the Greyhound log: the app 6.6 s behind a playing room
        assertEquals(Move.Forward(1), KeySeek.next(6_600, roomPlaying = true))
        assertEquals(Move.Hold(3_400), KeySeek.next(6_600 - 10_000, roomPlaying = true))
    }

    @Test fun aheadOfAPlayingRoomHoldsOrRewinds() {
        assertEquals(Move.Hold(4_000), KeySeek.next(-4_000, roomPlaying = true))
        assertEquals(Move.Back(2), KeySeek.next(-25_000, roomPlaying = true))
    }

    @Test fun aPausedRoomIsReachedByPlayingTheRest() {
        // ahead of a paused room: rewind past it, then play the difference
        assertEquals(Move.Back(1), KeySeek.next(-3_000, roomPlaying = false))
        assertEquals(Move.Run(7_000), KeySeek.next(7_000, roomPlaying = false))
        assertEquals(Move.Forward(3), KeySeek.next(38_000, roomPlaying = false))
    }

    @Test fun farSeeksSkipThreeAtATime() {
        // three fast-forwards back to back: +30 s (adb, 2026-11-18)
        assertEquals(Move.Forward(3), KeySeek.next(240_000, roomPlaying = true))
        assertEquals(Move.Back(3), KeySeek.next(-240_000, roomPlaying = true))
        assertEquals(Move.Forward(2), KeySeek.next(25_000, roomPlaying = true))
    }

    @Test fun everyStartLandsCloseInAFewMoves() {
        for (playing in listOf(true, false)) {
            for (start in listOf(-250_000L, -38_000L, -4_000L, 2_500L, 6_600L, 9_999L, 10_001L, 37_000L, 250_000L)) {
                var gap = start
                var moves = 0
                while (true) {
                    val m = KeySeek.next(gap, playing)
                    if (m == Move.Done) break
                    gap = when (m) {
                        is Move.Forward -> gap - m.keys * KeySeek.STEP_MS
                        is Move.Back -> gap + m.keys * KeySeek.STEP_MS
                        is Move.Hold -> gap + m.ms
                        is Move.Run -> gap - m.ms
                        else -> gap
                    }
                    moves++
                    if (m is Move.Hold || m is Move.Run) break   // the last move
                }
                assert(Math.abs(gap) <= KeySeek.CLOSE_MS) { "start $start playing $playing ended $gap" }
                // three skips a move: a 4 min seek in about 9 moves, not 25
                assert(moves <= Math.abs(start) / (KeySeek.STEP_MS * KeySeek.MAX_KEYS) + 3) { "start $start took $moves moves" }
            }
        }
    }
}
