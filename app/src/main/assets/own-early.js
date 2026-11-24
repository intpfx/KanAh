(() => {
  const token=__NAV_TOKEN__, channel=__EXPECTED_CHANNEL__, master=__KNOWN_STREAM__;
  const mark=(name)=>{
    if(window.__liveTvMeasure!==true)return;
    const timing=window.__liveTvTiming||(window.__liveTvTiming={});
    if(!Object.prototype.hasOwnProperty.call(timing,name))timing[name]={at:performance.now()};
  };
  window.__liveTvOwnToken=token;
  const reject=message=>{window.__live={state:'error',error:message};return false;};
  if(location.origin!=='https://tv.cctv.com')return reject('Early player requires the actual CCTV origin');
  try {
    const url=new URL(master);
    if(typeof master!=='string'||url.protocol!=='https:'||url.username||url.password)throw Error();
  }catch(_){return reject('Early player requires a valid HTTPS source');}
  if(typeof channel!=='string'||!/^cctv[a-z0-9]+$/.test(channel))return reject('Invalid CCTV channel');
  if((window.livePlayerObjs&&window.livePlayerObjs.player)||typeof window.Hls!=='undefined')
    return reject('Official full player already initialized');
  if(typeof window.__createCctvAdapter!=='function'||typeof window.__liveRun!=='function')
    return reject('Early player components unavailable');
  if(window.__liveStop)window.__liveStop();
  window.__live={state:'loading-module',error:null};
  function loadModule(){
    if(typeof window.CNTVModule==='function')return Promise.resolve();
    if(window.__liveTvModuleLoading)return window.__liveTvModuleLoading;
    mark('moduleRequest');
    window.__liveTvModuleLoading=new Promise((resolve,reject)=>{
      const script=document.createElement('script');
      script.src='https://js.player.cntv.cn/creator/live.worker.js';
      script.async=true;
      script.onload=()=>{
        if(typeof window.CNTVModule!=='function'){reject(Error('Official runtime factory missing'));return;}
        mark('moduleLoaded');resolve();
      };
      script.onerror=()=>reject(Error('Official runtime could not load'));
      document.head.appendChild(script);
    });
    return window.__liveTvModuleLoading;
  }
  try {
      if((window.livePlayerObjs&&window.livePlayerObjs.player)||typeof window.Hls!=='undefined')
        throw Error('Official controller appeared during initialization');
      const old=document.getElementById('livetv-video');if(old)old.remove();
      const video=document.createElement('video');video.id='livetv-video';
      video.setAttribute('data-livetv-player','');video.setAttribute('playsinline','');
      video.controls=false;video.muted=true;
      let style=document.getElementById('livetv-own-style');
      if(!style){style=document.createElement('style');style.id='livetv-own-style';document.head.appendChild(style);}
      style.textContent='html,body{margin:0!important;padding:0!important;overflow:hidden!important;width:100%!important;height:100%!important;background:#000!important;transform:none!important;filter:none!important;contain:none!important}body *{visibility:hidden!important}#livetv-video{visibility:visible!important;position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;object-fit:contain!important;z-index:2147483647!important}';
      document.body.appendChild(video);window.__liveAudible=false;window.__liveTvSource=master;
      const adapterReady=loadModule().then(async()=>{
        if(window.__liveTvOwnToken!==token)throw Error('Early player initialization superseded');
        mark('adapterInitStart');
        const adapter=await window.__createCctvAdapter();
        mark('adapterInitEnd');
        if((window.livePlayerObjs&&window.livePlayerObjs.player)||typeof window.Hls!=='undefined') {
          adapter.close();
          throw Error('Official controller appeared during initialization');
        }
        // The remux run owns this adapter, including cleanup if it arrives after stop.
        return adapter;
      });
      mark('runStart');
      try { window.__liveRun(master,null,{adapterReady,waitForReveal:true}); }
      catch(error) { adapterReady.then(adapter=>adapter.close(),()=>{});throw error; }
      window.__live.sourceAdapter='official-cctv-runtime';
      window.__live.startupMode='minimal-official-runtime';window.__live.channel=channel;window.__live.token=token;
  }catch(error){
    if(window.__liveTvOwnToken===token)window.__live={state:'error',error:'Early player initialization failed'};
  }
  return true;
})();
