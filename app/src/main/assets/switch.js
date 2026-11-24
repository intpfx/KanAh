(() => {
  const token = __NAV_TOKEN__;
  const channel = __EXPECTED_CHANNEL__;
  const expectedPath = __EXPECTED_PATH__;
  const result = accepted => JSON.stringify({ token, accepted });
  // Native code serializes switches until readiness or timeout. This only starts
  // a switch: acceptance is not playback readiness. player.js must match token,
  // channel and a video different from previousVideo before revealing the page.
  // A false result (including a partial destroy/create failure) requires reload.
  try {
    const basePath = expectedPath.replace(/(?:index\.shtml|m\/)?$/, '');
    if (location.origin !== 'https://tv.cctv.com' ||
        !/^\/live\/[^/]+\/$/.test(basePath) ||
        ![basePath, basePath+'index.shtml', basePath+'m/'].includes(location.pathname) ||
        typeof channel !== 'string' || !/^cctv[a-z0-9]+$/.test(channel)) return result(false);
    if (typeof window.destroyLivePlayer !== 'function' ||
        typeof window.createLivePlayer !== 'function') return result(false);
    const current = window.livePlayerObjs && window.livePlayerObjs.player;
    const player = document.getElementById('player');
    const officialVideo = player && player.querySelector('video');
    const saved = window.__liveTvPlayerConfig;
    const ownedVideo = document.body && document.body.querySelector('video[data-livetv-player]');
    const fromOwn = !!(window.__live && window.__live.sourceAdapter === 'official-cctv-runtime' &&
      saved && Object.isFrozen(saved) && saved.divId === 'player' &&
      typeof saved.t === 'string' && /^cctv[a-z0-9]+$/.test(saved.t) && ownedVideo);
    const previousVideo = fromOwn ? ownedVideo : officialVideo;
    const root = document.documentElement;
    if ((!fromOwn && (!current || current.divId !== 'player' || typeof current.t !== 'string')) ||
        !player || !previousVideo || !root || !(root.clientWidth > 0) || !(root.clientHeight > 0)) return result(false);
    // The official creator mutates its config and adds runtime objects, timers,
    // stream URLs and callbacks. Keep only primitive initial options and pass a
    // fresh copy every time; never reuse its mutated livePlayerObjs.player.
    const keys = ['divId','w','h','t','st','et','isAutoPlay','ruleVisible','br',
        'posterImg','isLive4k','isHttps','wmode','hasBarrage','playerType',
        'webFullScreenOn','isLeftBottom','timeshiftP2p','language','others'];
    if (!window.__liveTvPlayerConfig) {
      const original = {};
      for (const key of keys) {
        if (!Object.prototype.hasOwnProperty.call(current, key)) continue;
        const value = current[key];
        if (value === null || ['string','number','boolean'].includes(typeof value)) original[key] = value;
      }
      window.__liveTvPlayerConfig = Object.freeze(original);
    }
    const clean = {};
    for (const key of keys) {
      if (!Object.prototype.hasOwnProperty.call(window.__liveTvPlayerConfig,key)) continue;
      const value=window.__liveTvPlayerConfig[key];
      if(value===null || ['string','number','boolean'].includes(typeof value)) clean[key]=value;
    }
    const params = { ...clean, divId:'player', t:channel,
      w:root.clientWidth, h:root.clientHeight };
    const state = window.__liveTvSwitch = { token, channel, previousVideo, accepted:false };
    window.__liveTvAudible = false;
    previousVideo.muted = true;
    if (current) window.destroyLivePlayer('player');
    window.createLivePlayer(params);
    state.accepted = true;
    return result(true);
  } catch (_) {
    return result(false);
  }
})();
