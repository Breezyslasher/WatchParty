// Runs on the streaming sites: reports the main video's state to the Watch
// Party room page and carries out its play / pause / seek. Most sites are
// driven through their <video> element; Netflix through its own player
// (netflix-main.js), since setting a Netflix video's currentTime directly
// breaks its player.
(() => {
  if (window.__watchPartyContent) return;
  window.__watchPartyContent = true;

  const isNetflix = /(^|\.)netflix\.com$/.test(location.hostname);

  /** Every <video>, including inside open shadow roots (some players use them). */
  function allVideos() {
    const found = [...document.querySelectorAll('video')];
    if (found.length) return found;
    const out = [];
    const walk = root => {
      for (const el of root.querySelectorAll('*')) {
        if (el.tagName === 'VIDEO') out.push(el);
        if (el.shadowRoot) walk(el.shadowRoot);
      }
    };
    walk(document);
    return out;
  }

  /** The title's video: one with media, the largest on screen (not an ad or preview tile). */
  function mainVideo() {
    let best = null;
    let bestScore = -1;
    for (const v of allVideos()) {
      if (!(v.currentSrc || v.src || v.srcObject) && v.readyState === 0) continue;
      const r = v.getBoundingClientRect();
      const area = Math.max(0, r.width) * Math.max(0, r.height);
      const score = area + (v.duration > 60 ? 1e7 : 0) + (v.paused ? 0 : 1e6);
      if (score > bestScore) {
        best = v;
        bestScore = score;
      }
    }
    return best;
  }

  function state() {
    const v = mainVideo();
    const meta = navigator.mediaSession && navigator.mediaSession.metadata;
    const title = (meta && meta.title) || document.title;
    if (!v) return { play: 'OTHER', positionMs: -1, durationMs: -1, title };
    let play;
    if (v.paused || v.ended) play = 'PAUSED';
    else if (v.seeking || v.readyState < 3) play = 'BUFFERING';   // < HAVE_FUTURE_DATA
    else play = 'PLAYING';
    return {
      play,
      positionMs: Math.round(v.currentTime * 1000),
      durationMs: Number.isFinite(v.duration) ? Math.round(v.duration * 1000) : -1,
      title,
    };
  }

  /** Netflix's own player (in the page's world); false if it isn't there. */
  function netflix(cmd, ms) {
    if (!isNetflix) return false;
    document.documentElement.setAttribute('data-watchparty-ok', '');
    document.dispatchEvent(new CustomEvent('watchparty:netflix', { detail: JSON.stringify({ cmd, ms }) }));
    return document.documentElement.getAttribute('data-watchparty-ok') === '1';
  }

  function control(cmd, ms) {
    if (netflix(cmd, ms)) return true;
    const v = mainVideo();
    if (!v) return false;
    if (cmd === 'play') v.play().catch(() => {});
    else if (cmd === 'pause') v.pause();
    else if (cmd === 'seek') v.currentTime = Math.max(0, ms / 1000);
    return true;
  }

  chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
    if (!msg || typeof msg.wp !== 'string') return;
    if (msg.wp === 'state') reply(state());
    else reply({ ok: control(msg.wp, msg.ms) });
  });
})();
