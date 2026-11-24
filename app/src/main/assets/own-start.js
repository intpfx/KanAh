(() => {
  const mark = (name, details) => {
    if (window.__liveTvMeasure !== true) return;
    const timing = window.__liveTvTiming || (window.__liveTvTiming = {});
    if (!Object.prototype.hasOwnProperty.call(timing,name)) timing[name] = {at:performance.now(), ...(details || {})};
  };
  const token = __NAV_TOKEN__;
  const channel = __EXPECTED_CHANNEL__;
  const known = __KNOWN_STREAM__;
  const canonical = id => id === 'cctvchild14' ? 'cctvchild' : id === 'cctvjilu9' ? 'cctvjilu' : id;
  const engine = window.livePlayerObjs && window.livePlayerObjs.player;
  const hls = engine && engine.video && engine.video.hls;
  if (typeof CNTVModule === 'function') mark('observedModuleFactory');
  if (window.moduleInitialized) mark('observedModuleInitialized');
  if (engine) mark('observedEngine');
  if (hls) mark('observedHls');
  if (hls && typeof hls.url === 'string' && hls.url.length) mark('observedHlsUrl');
  if (location.origin !== 'https://tv.cctv.com' || !engine || canonical(engine.t) !== canonical(channel) ||
      typeof CNTVModule !== 'function' || !window.moduleInitialized || !hls ||
      typeof hls.destroy !== 'function' || typeof window.destroyLivePlayer !== 'function') return false;
  const master = hls.url || known;
  if (typeof master !== 'string' || !/^https?:\/\//.test(master)) return false;
  window.__liveTvOwnToken = token;
  window.__live = {state:'initializing', error:null};
  mark('adapterInitStart');
  window.__createCctvAdapter().then(adapter => {
    if (window.__liveTvOwnToken !== token) { adapter.close(); return; }
    mark('adapterInitEnd');
    try {
      if (!window.__liveTvPlayerConfig) {
        const config = {};
        const keys = ['divId','w','h','t','st','et','isAutoPlay','ruleVisible','br',
          'posterImg','isLive4k','isHttps','wmode','hasBarrage','playerType',
          'webFullScreenOn','isLeftBottom','timeshiftP2p','language','others'];
        for (const key of keys) {
          if (!Object.prototype.hasOwnProperty.call(engine,key)) continue;
          const value=engine[key];
          if(value===null || ['string','number','boolean'].includes(typeof value))config[key]=value;
        }
        window.__liveTvPlayerConfig=Object.freeze(config);
      }
      // The public destroy also cancels the website's retry/audio-focus lifecycle.
      // Destroying only its Hls object lets the official controller start it again.
      mark('officialStopStart');
      window.destroyLivePlayer('player');
      mark('officialStopEnd');
      document.querySelectorAll('video').forEach(v => { v.pause(); v.muted = true; });
      const old = document.getElementById('livetv-video');
      if (old) old.remove();
      const video = document.createElement('video');
      video.id = 'livetv-video';
      video.setAttribute('data-livetv-player','');
      video.setAttribute('playsinline','');
      video.muted = true;
      video.controls = false;
      let style = document.getElementById('livetv-own-style');
      if (!style) { style=document.createElement('style'); style.id='livetv-own-style'; document.head.appendChild(style); }
      style.textContent = 'html,body{margin:0!important;padding:0!important;overflow:hidden!important;width:100%!important;height:100%!important;background:#000!important;transform:none!important;filter:none!important;contain:none!important}body *{visibility:hidden!important}#livetv-video{visibility:visible!important;position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;object-fit:contain!important;z-index:2147483647!important}';
      document.body.appendChild(video);
      window.__liveAudible = false;
      mark('runStart');
      window.__liveTvSource=master;
      window.__liveRun(master, adapter);
      window.__live.channel = channel;
      window.__live.token = token;
      window.__live.sourceAdapter = 'official-cctv-runtime';
    } catch (error) {
      adapter.close();
      window.__live = {state:'error',error:String(error)};
    }
  }).catch(error => { if(window.__liveTvOwnToken===token)window.__live={state:'error',error:String(error)}; });
  return true;
})();
