// Netflix, in the page's own world: play, pause and seek through Netflix's
// player API (netflix.appContext…videoPlayer), which the content script asks
// for with a "watchparty:netflix" event. Setting the <video>'s currentTime
// directly breaks Netflix's player, so seeking must go this way. Sets
// data-watchparty-ok="1" on <html> when the player took the command.
(() => {
  document.addEventListener('watchparty:netflix', e => {
    try {
      const { cmd, ms } = JSON.parse(e.detail);
      const api = window.netflix.appContext.state.playerApp.getAPI().videoPlayer;
      const ids = api.getAllPlayerSessionIds();
      const id = ids.find(i => i.startsWith('watch')) || ids[0];
      const player = api.getVideoPlayerBySessionId(id);
      if (!player) return;
      if (cmd === 'play') player.play();
      else if (cmd === 'pause') player.pause();
      else if (cmd === 'seek') player.seek(Math.max(0, Math.round(ms)));
      else return;
      document.documentElement.setAttribute('data-watchparty-ok', '1');
    } catch (err) {
      // not on a watch page, or Netflix changed its player: the content
      // script falls back to the <video> element
    }
  });
})();
