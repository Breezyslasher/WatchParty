package dev.watchparty.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoreTest {

    private val disney = "com.disney.disneyplus"
    private val link = "https://www.disneyplus.com/play/f51dce23-7d8f-490a-9f0c-be1b5432c2a9"
    private val item = Item(link, "Thunderbolts*", disney)

    /** Room playing from [pos] since server time [setAt]. */
    private fun room(pos: Double = 100.0, paused: Boolean = false, setAt: Double = 1000.0,
                     it: Item? = item, mine: Boolean = false) =
        RoomState(1, it, pos, paused, 1.0, setAt, mine)

    private fun local(sec: Double, play: LocalPlay = LocalPlay.PLAYING, pkg: String? = disney) =
        LocalState(pkg, (sec * 1000).toLong(), play, "Thunderbolts*")

    /** A core that has already opened [item] long enough ago to be out of
     *  the startup grace period; returns it and the time to continue at. */
    private fun opened(): Pair<SyncCore, Long> {
        val core = SyncCore()
        val first = core.tick(room(), local(0.0, LocalPlay.OTHER, null), 0L, 1000.0)
        assertEquals(listOf<Action>(Action.Open(link, disney)), first)
        return core to SyncCore.OPEN_GRACE_MS + 1_000
    }

    @Test fun newItemOpensTheLink() {
        opened()
    }

    @Test fun nothingWhileTheAppIsStillStarting() {
        val (core, t) = opened()
        assertEquals(emptyList<Action>(), core.tick(room(), local(-1.0, LocalPlay.OTHER, null), t, 1000.0))
    }

    @Test fun inSyncDoesNothing() {
        val (core, t) = opened()
        // room: 100 s at server 1000, now server 1010 → expected 110
        assertEquals(emptyList<Action>(), core.tick(room(), local(110.5), t, 1010.0))
    }

    @Test fun driftIsCorrectedOncePerCooldown() {
        val (core, t) = opened()
        assertEquals(listOf<Action>(Action.SeekTo(110_000)), core.tick(room(), local(125.0), t, 1010.0))
        // still off a second later: cooldown, no second seek
        assertEquals(emptyList<Action>(), core.tick(room(), local(126.0), t + 1_000, 1011.0))
        assertEquals(1, core.corrections)
    }

    @Test fun roomPausedPausesUs() {
        val (core, t) = opened()
        val actions = core.tick(room(pos = 300.0, paused = true), local(300.0), t, 1010.0)
        assertEquals(listOf<Action>(Action.Pause), actions)
    }

    /** The room paused and us playing elsewhere: pause, and the seek pauses again
     *  once it lands (a phone's Netflix plays when seeked while paused). */
    @Test fun aSeekInAPausedRoomPausesAgain() {
        val (core, t) = opened()
        assertEquals(listOf(Action.Pause, Action.SeekTo(33_000, pauseAfter = true)),
            core.tick(room(pos = 33.0, paused = true), local(40.0), t, 1010.0))
        // a playing room's corrections don't
        val (core2, t2) = opened()
        assertEquals(listOf<Action>(Action.SeekTo(110_000)), core2.tick(room(), local(125.0), t2, 1010.0))
    }

    @Test fun roomPlayingResumesUs() {
        val (core, t) = opened()
        val actions = core.tick(room(), local(110.0, LocalPlay.PAUSED), t, 1010.0)
        assertEquals(listOf<Action>(Action.Play), actions)
    }

    @Test fun userPauseIsSentToTheRoom() {
        val (core, t) = opened()
        core.tick(room(), local(110.0), t, 1010.0)                       // in sync, playing
        val actions = core.tick(room(), local(111.0, LocalPlay.PAUSED), t + 1_000, 1011.0)
        assertEquals(listOf<Action>(Action.Send("pause", 111.0)), actions)
    }

    @Test fun userSeekIsSentToTheRoom() {
        val (core, t) = opened()
        core.tick(room(), local(110.0), t, 1010.0)
        val actions = core.tick(room(), local(1900.0), t + 1_000, 1011.0)
        assertEquals(listOf<Action>(Action.Send("seek", 1900.0)), actions)
    }

    @Test fun ourOwnCorrectionIsNotMistakenForAUserSeek() {
        val (core2, t2) = opened()
        assertEquals(listOf<Action>(Action.SeekTo(110_000)), core2.tick(room(), local(125.0), t2, 1010.0))
        // the app jumps to where we sent it: suppressed, not echoed back
        val echo = core2.tick(room(), local(111.0), t2 + 1_000, 1011.0)
        assertTrue(echo.none { it is Action.Send })
    }

    @Test fun someoneElsesPauseIsFollowedNotEchoed() {
        val (core, t) = opened()
        core.tick(room(), local(110.0), t, 1010.0)
        // the room pauses (another member); we pause…
        assertEquals(listOf<Action>(Action.Pause), core.tick(room(111.0, paused = true), local(111.0), t + 1_000, 1011.0))
        // …and our now-paused app must not be reported as the user pausing
        val after = core.tick(room(111.0, paused = true), local(111.0, LocalPlay.PAUSED), t + 2_000, 1012.0)
        assertEquals(emptyList<Action>(), after)
    }

    @Test fun kodiItemsAreSkippedWithOneNote() {
        val core = SyncCore()
        val kodi = Item("smb://nas/movies/Iron Man.mkv", "Iron Man", null)
        val first = core.tick(room(it = kodi), local(0.0, LocalPlay.OTHER, null), 0, 1000.0)
        assertEquals(1, first.size)
        assertTrue(first[0] is Action.Note)
        assertEquals(emptyList<Action>(), core.tick(room(it = kodi), local(0.0, LocalPlay.OTHER, null), 1_000, 1001.0))
    }

    @Test fun announcerAlreadyWatchingIsNotRestarted() {
        val core = SyncCore()
        val actions = core.tick(room(mine = true), local(100.0), 0, 1000.0)
        assertTrue(actions.none { it is Action.Open })
    }

    @Test fun pausedRoomExpectedPositionDoesNotAdvance() {
        assertEquals(300.0, room(pos = 300.0, paused = true).expectedSec(5000.0), 0.0)
        assertEquals(110.0, room().expectedSec(1010.0), 1e-9)
    }

    @Test fun aLaunchedAppIsLeftAloneWhileItStarts() {
        val core = SyncCore()
        assertEquals(listOf<Action>(Action.Open(link, disney)), core.tick(room(), local(0.0, LocalPlay.OTHER, null), 0L, 1000.0))
        // 5 s later the player appears at 0 while the room is at 105: no seek yet (HBO Max closed its player)
        assertEquals(emptyList<Action>(), core.tick(room(), local(0.0), 5_000L, 1005.0))
        // loading reports paused: no Play either
        assertEquals(emptyList<Action>(), core.tick(room(), local(0.5, LocalPlay.PAUSED), 6_000L, 1006.0))
        assertEquals(emptyList<Action>(), core.tick(room(), local(1.0), 7_000L, 1007.0))
        assertEquals(emptyList<Action>(), core.tick(room(), local(2.0), 9_000L, 1009.0))
        // playing for 3 s: started, so one seek to the room
        assertEquals(listOf<Action>(Action.SeekTo(110_000)), core.tick(room(), local(3.0), 10_000L, 1010.0))
    }

    @Test fun anAppThatNeverPlaysIsFollowedAfterTheGrace() {
        val core = SyncCore()
        core.tick(room(), local(0.0, LocalPlay.OTHER, null), 0L, 1000.0)
        assertEquals(emptyList<Action>(), core.tick(room(), local(0.0, LocalPlay.PAUSED), 10_000L, 1010.0))
        // past the grace, still paused while the room plays: resume it
        assertTrue(core.tick(room(), local(0.0, LocalPlay.PAUSED), SyncCore.OPEN_GRACE_MS + 1_000, 1021.0)
            .contains(Action.Play))
    }

    @Test fun startingUpIsNotBufferingForTheRoom() {
        val core = SyncCore()
        assertEquals(false, core.isStarting(0L))   // nothing opened yet
        core.tick(room(), local(0.0, LocalPlay.OTHER, null), 0L, 1000.0)
        assertEquals(true, core.isStarting(2_000L))
        core.tick(room(), local(0.0, LocalPlay.BUFFERING), 2_000L, 1002.0)
        assertEquals(true, core.isStarting(3_000L))
        core.tick(room(), local(0.5), 4_000L, 1004.0)   // playing from 4 s
        assertEquals(true, core.isStarting(6_000L))
        core.tick(room(), local(2.5), 6_000L, 1006.0)
        assertEquals(false, core.isStarting(7_000L))  // played 3 s: started
        // and never past the grace
        val other = SyncCore()
        other.tick(room(), local(0.0, LocalPlay.OTHER, null), 0L, 1000.0)
        assertEquals(false, other.isStarting(SyncCore.OPEN_GRACE_MS + 1))
    }

    @Test fun aKodiTitleIsLookedUpThenFollowedLikeAnAppItem() {
        val core = SyncCore()
        val identity = Identity.movie("Thunderbolts*", 2025, "986056")
        val kodi = Item("nfs://nas/films/thunderbolts.mkv", "Thunderbolts*", null, identity)
        assertEquals(listOf<Action>(Action.Resolve(kodi.file, "Thunderbolts*", identity)),
            core.tick(room(it = kodi), local(0.0, LocalPlay.OTHER, null), 0L, 1000.0))
        // only once
        assertEquals(emptyList<Action>(), core.tick(room(it = kodi), local(0.0, LocalPlay.OTHER, null), 1_000L, 1001.0))
        assertEquals(true, core.isStarting(2_000L))
        // found on Disney+ here: SyncService names the app on the item (localize); then it's followed
        val found = kodi.copy(androidPackage = disney)
        assertEquals(emptyList<Action>(), core.tick(room(it = found), local(0.0), 5_000L, 1005.0))   // playing: starting up
        // played 3 s: one seek to the room, as for a link this device opened
        assertEquals(listOf<Action>(Action.SeekTo(108_000)), core.tick(room(it = found), local(3.0), 8_000L, 1008.0))
    }

    /** Back from Remote only: the room's current item opens again. */
    @Test fun resetOpensTheCurrentItemAgain() {
        val core = SyncCore()
        val r = room()
        assertTrue(core.tick(r, local(0.0, LocalPlay.OTHER, null), 0L, 1000.0).any { it is Action.Open })
        assertTrue(core.tick(r, local(0.0, LocalPlay.OTHER, null), 1_000L, 1001.0).none { it is Action.Open })
        core.reset()
        assertTrue(core.tick(r, local(0.0, LocalPlay.OTHER, null), 2_000L, 1002.0).any { it is Action.Open })
    }
}
