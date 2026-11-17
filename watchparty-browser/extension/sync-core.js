// The sync decisions, with no browser code, so node can test them. A port
// of the Android app's SyncCore.kt (watchparty-tv), which mirrors the Kodi
// addon's engine:
//  - everyone computes the expected position from the room's anchor
//    (position + elapsed server time × speed while playing);
//  - drift past the threshold is corrected by seeking, at most once per
//    CORRECTION_COOLDOWN_MS;
//  - our own commands are suppressed for SUPPRESS_MS so their echo isn't
//    mistaken for the user acting;
//  - a change the user makes here (pause, play, seek) is sent to the room;
//  - a just-opened title is left alone until it has played STARTED_MS, or
//    OPEN_GRACE_MS passes, and its loading isn't reported as buffering.
//
// The browser's "app" is the streaming site: an item's `service` (netflix,
// disneyplus, …, from links.js) plays to the tab showing that site.

export const PLAYING = 'PLAYING';
export const PAUSED = 'PAUSED';
export const BUFFERING = 'BUFFERING';
export const OTHER = 'OTHER';

export const SUPPRESS_MS = 4000;
export const CORRECTION_COOLDOWN_MS = 4000;
export const OPEN_GRACE_MS = 20000;
export const STARTED_MS = 3000;
export const USER_SEEK_JUMP_SEC = 4;

/** Where playback should be at serverNowSec. */
export function expectedSec(room, serverNowSec) {
  if (!room.item || room.paused) return room.positionSec;
  return room.positionSec + Math.max(0, serverNowSec - room.setAtSec) * (room.speed > 0 ? room.speed : 1);
}

export function isLink(file) {
  return file.startsWith('https://') || file.startsWith('http://');
}

export class SyncCore {
  constructor(driftThresholdSec = 3) {
    this.drift = driftThresholdSec;
    this.openedFile = null;
    this.openedAtMs = 0;
    this.playingSinceMs = -1;   // when the opened title was first seen playing
    this.suppressUntilMs = 0;
    this.lastCorrectionMs = 0;
    this.notPlayableFile = null;
    this.last = null;
    this.lastAtMs = 0;
    this.corrections = 0;
  }

  /** The just-opened title is still starting: its loading isn't the room's business. */
  isStarting(nowMs) {
    return this.openedFile !== null && nowMs - this.openedAtMs < OPEN_GRACE_MS &&
      (this.playingSinceMs < 0 || nowMs - this.playingSinceMs < STARTED_MS);
  }

  /** Is the player tab on the party's item (its site, with a video)? */
  onItem(room, local) {
    const service = room.item && room.item.service;
    if (!service) return false;
    return local.service === service && local.play !== OTHER;
  }

  /** Called once per poll; returns actions:
   *  {type:'open', link} {type:'resolve', file, label, identity} {type:'play'}
   *  {type:'pause'} {type:'seek', ms} {type:'send', cmd, positionSec}
   *  {type:'note', text} */
  tick(room, local, nowMs, serverNowSec) {
    const actions = [];
    const item = room.item;
    if (!item) {
      this.openedFile = null;
      this.remember(local, nowMs);
      return actions;
    }

    // 1. A new item: open it here (whoever announced it too, unless it's
    //    what they're already playing).
    if (item.file !== this.openedFile) {
      this.openedFile = item.file;
      this.last = null;
      const link = item.link === undefined ? (isLink(item.file) ? item.file : null) : item.link;
      if (link) {
        if (item.service && local.service === item.service && local.play === PLAYING && room.setByMe) {
          // we announced what we're already watching: don't restart it
        } else {
          actions.push({ type: 'open', link });
          this.openedAtMs = nowMs;
          this.playingSinceMs = -1;
          this.suppressUntilMs = nowMs + SUPPRESS_MS;
          if (!item.service) actions.push({ type: 'note', text: `Opened "${item.label || item.file}" — this site can't be synced` });
        }
      } else if (item.identity) {
        // a Kodi title: the page finds it on a streaming site (JustWatch);
        // its start-up gets the same grace as a link we open
        actions.push({ type: 'resolve', file: item.file, label: item.label, identity: item.identity });
        this.openedAtMs = nowMs;
        this.playingSinceMs = -1;
        this.suppressUntilMs = nowMs + SUPPRESS_MS;
      } else if (this.notPlayableFile !== item.file) {
        this.notPlayableFile = item.file;
        actions.push({ type: 'note', text: `"${item.label || item.file}" is a Kodi file — it can't play in a browser` });
      }
      return actions;
    }

    if (!this.onItem(room, local)) {
      this.remember(local, nowMs);
      return actions;
    }

    const inGrace = nowMs - this.openedAtMs < OPEN_GRACE_MS;
    // still starting up: leave the player alone until it has played a few seconds
    if (local.play === PLAYING) {
      if (this.playingSinceMs < 0) this.playingSinceMs = nowMs;
    } else if (inGrace) {
      this.playingSinceMs = -1;
    }
    if (inGrace && (this.playingSinceMs < 0 || nowMs - this.playingSinceMs < STARTED_MS)) {
      this.remember(local, nowMs);
      return actions;
    }
    const suppressed = nowMs < this.suppressUntilMs;
    const localSec = local.positionMs >= 0 ? local.positionMs / 1000 : -1;

    // 2. The user did something here: tell the room; its answer drives everyone.
    const prev = this.last;
    // A seek counts from the moment the title has played STARTED_MS (past
    // the start-up check above), even in the open grace and right after our
    // own command: a jump that lands away from the room isn't our seek
    // landing (that lands at the room). On a TV, skipping ahead 4 s after a
    // catch-up seek was taken for drift and seeked back, again and again.
    // Play / pause changes still wait out our own commands' echo.
    if (prev && prev.service === local.service) {
      const wasPlaying = prev.play === PLAYING;
      const nowPlaying = local.play === PLAYING;
      if (!suppressed && !inGrace && prev.play !== BUFFERING && local.play !== BUFFERING &&
          wasPlaying !== nowPlaying && nowPlaying !== !room.paused) {
        actions.push({ type: 'send', cmd: nowPlaying ? 'play' : 'pause', positionSec: Math.max(0, localSec) });
        this.suppressUntilMs = nowMs + SUPPRESS_MS;
        this.remember(local, nowMs);
        return actions;
      }
      if (localSec >= 0 && prev.positionMs >= 0) {
        const elapsed = (nowMs - this.lastAtMs) / 1000;
        const predicted = prev.positionMs / 1000 + (wasPlaying ? elapsed : 0);
        const expected = expectedSec(room, serverNowSec);
        // a jump that also moved us away from the room = the user seeked
        if (Math.abs(localSec - predicted) > USER_SEEK_JUMP_SEC && Math.abs(localSec - expected) > this.drift) {
          actions.push({ type: 'send', cmd: 'seek', positionSec: localSec });
          this.suppressUntilMs = nowMs + SUPPRESS_MS;
          this.remember(local, nowMs);
          return actions;
        }
      }
    }

    // 3. Follow the room.
    if (!suppressed) {
      if (room.paused && local.play === PLAYING) {
        actions.push({ type: 'pause' });
        this.suppressUntilMs = nowMs + SUPPRESS_MS;
      } else if (!room.paused && local.play === PAUSED) {
        actions.push({ type: 'play' });
        this.suppressUntilMs = nowMs + SUPPRESS_MS;
      }
    }
    if (localSec >= 0 && local.play !== BUFFERING) {
      const expected = expectedSec(room, serverNowSec);
      if (Math.abs(localSec - expected) > this.drift && nowMs - this.lastCorrectionMs > CORRECTION_COOLDOWN_MS) {
        actions.push({ type: 'seek', ms: Math.round(expected * 1000) });
        this.lastCorrectionMs = nowMs;
        this.suppressUntilMs = nowMs + SUPPRESS_MS;
        this.corrections++;
      }
    }
    this.remember(local, nowMs);
    return actions;
  }

  remember(local, nowMs) {
    this.last = local;
    this.lastAtMs = nowMs;
  }
}
