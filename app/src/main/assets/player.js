(() => {
  const token = __NAV_TOKEN__;
  const expectedChannel = __EXPECTED_CHANNEL__;
  // Official engine identifiers omit these historical numeric suffixes.
  const channelIdentity = value => value === 'cctvjilu9' ? 'cctvjilu'
    : value === 'cctvchild14' ? 'cctvchild' : value;
  const samePage = __SAME_PAGE__;
  const expectedPath = __EXPECTED_PATH__;
  const basePath = expectedPath.replace(/(?:index\.shtml)?$/, '');
  if (location.origin !== 'https://tv.cctv.com' ||
      ![basePath, basePath+'index.shtml', basePath+'m/'].includes(location.pathname)) return null;
  if (!document.head || !document.body) return null;
  window.__liveTvAudible = __AUDIBLE__;
  // Native owns this intent for the current navigation. Polling and website
  // autoplay must never reset it; a new channel clears it before switching.
  const userPaused = () => window.__liveUserPaused === true;
  // Browsing another app page only silences audio; readiness and playback keep advancing.
  const shouldMute = () => userPaused() || window.__liveBackgroundMuted === true || !window.__liveTvAudible;
  function guardVideo(video) {
    if (video.__livePauseGuard) return video.__livePauseGuard;
    const play = video.play;
    const guard = () => {
      if (shouldMute() && !video.muted) video.muted = true;
      if (userPaused() && !video.paused) video.pause();
    };
    video.play = function () {
      guard();
      if (userPaused()) return Promise.resolve();
      return play.apply(this, arguments);
    };
    for (const event of ['play','playing','volumechange']) video.addEventListener(event,guard);
    video.__livePauseGuard = guard;
    return guard;
  }
  function restoreVolume(video, engine) {
    video.volume = 1;
    if (engine) engine.volume = 1;
    try {
      if (localStorage.getItem('cctv_live_volume') !== '1') localStorage.setItem('cctv_live_volume','1');
    } catch (_) { /* Playback still works when browser storage is unavailable. */ }
  }
  const setBackgroundMuted = muted => {
    if (window.__liveSetBackgroundMuted !== setBackgroundMuted) return false;
    window.__liveBackgroundMuted = muted === true;
    const engine = window.livePlayerObjs && window.livePlayerObjs.player;
    for (const video of document.querySelectorAll('video')) {
      guardVideo(video)();
      if (!shouldMute()) restoreVolume(video, engine);
      video.muted = shouldMute();
    }
    return true;
  };
  window.__liveSetBackgroundMuted = setBackgroundMuted;
  // Native can set the flag before this script or the current video is ready.
  if (window.__liveBackgroundMuted === true) {
    for (const video of document.querySelectorAll('video')) guardVideo(video)();
  }
  if (!window.__liveTvAudioObserver) {
    window.__liveTvAudioObserver = new MutationObserver(records => {
      for (const record of records) for (const node of record.addedNodes) {
        if (node.nodeType !== 1) continue;
        const videos = node.matches('video') ? [node] : node.querySelectorAll('video');
        for (const v of videos) { guardVideo(v)(); v.muted = shouldMute(); }
      }
    });
    window.__liveTvAudioObserver.observe(document.body, { childList:true, subtree:true });
  }
  let state = window.__liveTv;
  if (!state || state.token !== token) {
    state = window.__liveTv = { token, instance: 0, video: null, frames: 0, userPaused:userPaused() };
  }
  let style = document.getElementById('livetv-cinema');
  if (!style) {
    style = document.createElement('style');
    style.id = 'livetv-cinema';
    style.textContent = `
      html,body { margin:0!important; padding:0!important; width:100%!important;
        height:100%!important; overflow:hidden!important; background:#000!important; }
      body * { visibility:hidden!important; }
      #player { position:fixed!important; inset:0!important; width:100vw!important;
        height:100vh!important; max-width:none!important; margin:0!important;
        z-index:2147483000!important; }
      #player video { visibility:visible!important; position:fixed!important;
        inset:0!important; width:100vw!important; height:100vh!important;
        max-width:none!important; max-height:none!important; object-fit:contain!important;
        margin:0!important; padding:0!important; transform:none!important; }
    `;
    document.head.appendChild(style);
  }
  const player = document.getElementById('player');
  const video = player && player.querySelector('video');
  const engine = window.livePlayerObjs && window.livePlayerObjs.player;
  const operation = window.__liveTvSwitch;
  if (!video || !engine || channelIdentity(engine.t) !== channelIdentity(expectedChannel) ||
      (samePage && (!operation || !operation.accepted || operation.token !== token ||
        channelIdentity(operation.channel) !== channelIdentity(expectedChannel) || operation.previousVideo === video))) {
    return JSON.stringify({ token, missing:true });
  }
  const enforcePause = guardVideo(video);
  const setPaused = paused => {
    if (window.__liveTv !== state || window.__liveSetPaused !== setPaused) return false;
    const currentPlayer = document.getElementById('player');
    const active = currentPlayer && currentPlayer.querySelector('video');
    const currentEngine = window.livePlayerObjs && window.livePlayerObjs.player;
    if (!active || !currentEngine || channelIdentity(currentEngine.t) !== channelIdentity(expectedChannel)) return false;
    const changed = state.userPaused !== (paused === true);
    window.__liveUserPaused = state.userPaused = paused === true;
    const pauseGuard = guardVideo(active);
    active.muted = shouldMute();
    if (userPaused()) { pauseGuard(); return true; }
    if (changed) {
      // A long pause may outlive the live window. Rejoin an available range
      // without replacing the element, MediaSource or decoder.
      const ranges = active.buffered && active.buffered.length ? active.buffered : active.seekable;
      if (ranges && ranges.length) {
        const start = ranges.start(ranges.length-1), end = ranges.end(ranges.length-1);
        if (active.currentTime < start || active.currentTime >= end || end-active.currentTime > 15)
          active.currentTime = Math.max(start, end-3);
      }
      if (!shouldMute()) restoreVolume(active, currentEngine);
      if (active.paused) active.play().catch(() => {});
    }
    return true;
  };
  window.__liveSetPaused = setPaused;
  // The fixed video must be relative to the viewport, not a transformed page wrapper.
  for (let p=video.parentElement; p; p=p.parentElement) {
    p.style.setProperty('transform','none','important');
    p.style.setProperty('filter','none','important');
    p.style.setProperty('contain','none','important');
    p.style.setProperty('perspective','none','important');
  }
  // The website's sound bar interprets our temporary mute as volume=0 and
  // persists it during initialization. Restore gain as well as the mute flag.
  // Remote volume keys control Android's media volume; the hidden web control
  // stays at unity gain and must not retain our transition silence.
  if (!shouldMute()) restoreVolume(video, engine);
  video.muted = shouldMute();
  state.userPaused = userPaused();
  enforcePause();
  if (state.video !== video) {
    state.video = video;
    state.instance++;
    state.frames = 0;
    video.setAttribute('playsinline','');
    video.controls = false;

    if (!userPaused()) video.play().catch(() => {});
    if (video.requestVideoFrameCallback) {
      const onFrame = () => {
        if (window.__liveTv !== state || state.video !== video) return;
        if (!userPaused()) state.frames++;
        if (state.frames < 3) video.requestVideoFrameCallback(onFrame);
      };
      video.requestVideoFrameCallback(onFrame);
    }
  }
  const box = video.getBoundingClientRect();
  const full = style.isConnected && video.videoWidth > 0 && video.videoHeight > 0 &&
    Math.abs(box.left) < 3 && Math.abs(box.top) < 3 &&
    Math.abs(box.width-document.documentElement.clientWidth) < 3 &&
    Math.abs(box.height-document.documentElement.clientHeight) < 3;
  return JSON.stringify({ token, instance:state.instance, layout:full,
    time:video.currentTime, ready:video.readyState, paused:video.paused, userPaused:userPaused(),
    error:video.error ? video.error.code : 0, width:video.videoWidth,
    frames:state.frames, frameCallback:!!video.requestVideoFrameCallback });
})();
