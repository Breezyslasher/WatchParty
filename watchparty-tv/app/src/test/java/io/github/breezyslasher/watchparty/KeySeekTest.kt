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
        assertEquals(Move.Forward(3), KeySeek.next(38_000, roomPlaying = false))   // then run 8 s
    }

    @Test fun aWholeJumpGoesAtOnce() {
        // 120 fast-forwards in 3 s: +20 min exactly (adb, 2026-11-18)
        assertEquals(Move.Forward(24), KeySeek.next(240_000, roomPlaying = true))   // to 6 s past, then hold
        assertEquals(Move.Back(24), KeySeek.next(-240_000, roomPlaying = true))     // to 0 s, done
        assertEquals(Move.Forward(3), KeySeek.next(25_000, roomPlaying = true))
        assertEquals(Move.Hold(5_000), KeySeek.next(25_000 - 30_000, roomPlaying = true))
        assertEquals(Move.Forward(120), KeySeek.next(3_600_000, roomPlaying = false))   // an hour: 20 min a burst
    }

    @Test fun everyStartLandsCloseInAFewMoves() {
        for (playing in listOf(true, false)) {
            for (start in listOf(-3_600_000L, -250_000L, -38_000L, -4_000L, 2_500L, 6_600L, 9_999L, 10_001L, 37_000L, 250_000L, 1_200_000L, 5_000_000L)) {
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
                // one burst and one hold or run (more only past 20 min)
                assert(moves <= 2 + Math.abs(start) / (KeySeek.STEP_MS * KeySeek.MAX_KEYS)) { "start $start took $moves moves" }
            }
        }
    }
}
