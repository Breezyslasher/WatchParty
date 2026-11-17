package io.github.breezyslasher.watchparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun aUserSeekRightAfterOurCorrectionIsSent() {
        // Tubi: skipping ahead 4 s after a catch-up seek was seeked back
        val (core, t) = opened()
        assertEquals(listOf<Action>(Action.SeekTo(110_000)), core.tick(room(), local(125.0), t, 1010.0))
        assertTrue(core.tick(room(), local(111.0), t + 1_000, 1011.0).none { it is Action.Send })
        assertEquals(emptyList<Action>(), core.tick(room(), local(112.0), t + 2_000, 1012.0))
        assertEquals(listOf<Action>(Action.Send("seek", 1343.0)), core.tick(room(), local(1343.0), t + 3_000, 1013.0))
    }

    @Test fun aUserSeekSoonAfterOpeningIsSentOnceTheAppHasStarted() {
        val core = SyncCore()
        assertEquals(listOf<Action>(Action.Open(link, disney)), core.tick(room(), local(0.0, LocalPlay.OTHER, null), 0L, 1000.0))
        // the app comes up at its own resume point: not the user, and too early to touch
        assertEquals(emptyList<Action>(), core.tick(room(), local(1811.0), 1_000L, 1001.0))
        assertEquals(emptyList<Action>(), core.tick(room(), local(1812.0), 2_000L, 1002.0))
        // played 3 s: caught up with the room (a correction, not sent to the room)
        assertEquals(listOf<Action>(Action.SeekTo(105_000)), core.tick(room(), local(1815.0), 5_000L, 1005.0))
        assertTrue(core.tick(room(), local(106.0), 6_000L, 1006.0).none { it is Action.Send })
        assertEquals(emptyList<Action>(), core.tick(room(), local(107.0), 7_000L, 1007.0))
        // 8 s after opening (still in the grace) the user skips ahead: the room follows
        assertEquals(listOf<Action>(Action.Send("seek", 2182.0)), core.tick(room(), local(2182.0), 8_000L, 1008.0))
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

    // ── a seek the app refuses (Prime Video during an ad) ────────────────

    /** Runs the core once a second from [from] to [to] ms (room playing from
     *  100 s at server 1000, so expected = 100 + elapsed); the app reports
     *  [localAt] and its length [durationAt]. Returns the times of seeks
     *  and notes. */
    private fun run(core: SyncCore, from: Long, to: Long, localAt: (Long) -> Double,
                    durationAt: (Long) -> Long = { 7_200_000L }): Pair<List<Long>, List<Long>> {
        val seeks = mutableListOf<Long>()
        val notes = mutableListOf<Long>()
        var t = from
        while (t <= to) {
            val server = 1000.0 + (t - from) / 1000.0 + 10.0
            val l = LocalState(disney, (localAt(t) * 1000).toLong(), LocalPlay.PLAYING, "Thunderbolts*", durationAt(t))
            val actions = core.tick(room(), l, t, server)
            if (actions.any { it is Action.SeekTo }) seeks += t - from
            if (actions.any { it is Action.Note }) notes += t - from
            t += 1_000
        }
        return seeks to notes
    }

    @Test fun refusedSeeksBackOff() {
        val (core, t) = opened()
        // stuck 60 s behind for two minutes, every seek refused
        val (seeks, notes) = run(core, t, t + 120_000, { now -> 50.0 + (now - t) / 1000.0 })
        // first at once, then 10 s, 20 s, then every 30 s (plus the 1 s ticks)
        assertEquals(listOf(0L, 11_000L, 32_000L, 63_000L, 94_000L), seeks)
        assertEquals(listOf(14_000L), notes)   // said once, at the second refusal
        assertTrue(core.refusedSeeks >= 3)
    }

    @Test fun aSeekThatLandsKeepsTheNormalCooldown() {
        val (core, t) = opened()
        // behind until the first seek lands, then in step
        val (seeks, _) = run(core, t, t + 30_000, { now -> if (now - t < 2_000) 50.0 else 110.0 + (now - t) / 1000.0 })
        assertEquals(listOf(0L), seeks)
        assertEquals(0, core.refusedSeeks)
    }

    @Test fun theLengthChangingRetriesSooner() {
        val (core, t) = opened()
        // a part the app won't seek in (length 200 s: not an ad's) for 40 s; then the title (2 h)
        // is back, still behind: the next seek follows the normal cooldown
        val (seeks, _) = run(core, t, t + 60_000,
            localAt = { now -> if (now - t < 40_000) 50.0 else 50.0 + (now - t - 40_000) / 1000.0 },
            durationAt = { now -> if (now - t < 40_000) 200_000L else 7_200_000L })
        // 0, 11 s, 32 s refused; at 40 s the length changes: the next is due 4 s after 32 s
        assertEquals(listOf(0L, 11_000L, 32_000L, 40_000L), seeks.take(4))
    }

    // ── ads (Tubi: the length drops to the ad's, the position counts from 0) ──

    private fun tubi(sec: Double, lengthSec: Double, play: LocalPlay = LocalPlay.PLAYING) =
        LocalState(disney, (sec * 1000).toLong(), play, "Good Morning, Vietnam", (lengthSec * 1000).toLong())

    @Test fun anAdIsLeftAloneAndTheTitleCatchesUpAfter() {
        val core = SyncCore()
        assertEquals(listOf<Action>(Action.Open(link, disney)), core.tick(room(), local(0.0, LocalPlay.OTHER, null), 0L, 1000.0))
        // Tubi opens at its resume point, then a 15 s ad, then a 30 s one (as logged)
        val seen = mutableListOf<Pair<Long, Action>>()
        fun at(ms: Long, l: LocalState) = core.tick(room(), l, ms, 1000.0 + ms / 1000.0).forEach { seen += ms to it }
        at(1_000, tubi(2798.0, 7247.5, LocalPlay.BUFFERING))
        for (i in 0..14) at(2_000L + i * 1_000, tubi(0.1 + i, 15.0))
        for (i in 0..29) at(17_000L + i * 1_000, tubi(0.4 + i, 30.0))
        assertTrue(seen.toString(), seen.none { it.second is Action.SeekTo || it.second is Action.Send })
        assertEquals(1, seen.count { it.second is Action.Note })
        assertTrue(core.inAd)
        // the title is back where it was: a moment to settle, then catch up with the room
        at(47_000, tubi(2798.0, 7247.5))
        at(48_000, tubi(2799.0, 7247.5))
        at(49_000, tubi(2800.0, 7247.5))
        assertTrue(seen.none { it.second is Action.Send })
        at(50_000, tubi(2801.0, 7247.5))
        assertEquals(listOf<Action>(Action.SeekTo(150_000)), seen.filter { it.first == 50_000L }.map { it.second })
    }

    @Test fun duringAnAdTheRoomsPauseIsStillFollowed() {
        val (core, t) = opened()
        core.tick(room(), tubi(110.0, 7200.0), t, 1010.0)
        // an ad starts; then the room pauses (someone else)
        assertTrue(core.tick(room(), tubi(0.0, 30.0), t + 1_000, 1011.0).none { it is Action.SeekTo || it is Action.Send })
        assertEquals(listOf<Action>(Action.Pause), core.tick(room(111.0, paused = true), tubi(1.0, 30.0), t + 2_000, 1012.0))
        // the ad, paused by us, isn't the user pausing
        assertEquals(emptyList<Action>(), core.tick(room(111.0, paused = true), tubi(1.0, 30.0, LocalPlay.PAUSED), t + 3_000, 1013.0))
    }

    @Test fun aShortTitleIsNotAnAd() {
        // a 2-minute video, the room at its start: not an ad, followed as usual
        val (core, t) = opened()
        val clip = LocalState(disney, 40_000, LocalPlay.PLAYING, "Clip", 120_000)
        assertEquals(listOf<Action>(Action.SeekTo(110_000)), core.tick(room(), clip, t, 1010.0))
        assertFalse(core.inAd)
    }
}
