import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';

const source=readFileSync(new URL('../app/src/main/assets/remux.js',import.meta.url),'utf8');
const dir=mkdtempSync(join(tmpdir(),'livetv-live-start-'));
after(()=>rmSync(dir,{recursive:true,force:true}));
execFileSync('ffmpeg',['-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=160x90:rate=25',
  '-f','lavfi','-i','sine=frequency=1000:sample_rate=48000','-t','4','-c:v','libx264','-g','50',
  '-keyint_min','50','-sc_threshold','0','-bf','2','-c:a','aac','-f','hls','-hls_time','2','-hls_list_size','0',join(dir,'test.m3u8')]);
const segments=[0,1].map(n=>Uint8Array.from(readFileSync(join(dir,`test${n}.ts`))));
const listing='#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\na.ts\n#EXTINF:2,\nb.ts\n#EXT-X-ENDLIST\n';
const tick=()=>new Promise(resolve=>setImmediate(resolve));
async function until(predicate){for(let i=0;i<30&&!predicate();i++)await tick();assert.ok(predicate(),'expected asynchronous stage was not reached');}
function fixture(options,hooks={}){
  const requests=[],calls=[],logs=[],intervals=[],removed=[];let fulfill,reject,conversionCalls=0,closed=0,frameCallback;
  const playPromise=new Promise((resolve,no)=>{fulfill=resolve;reject=no;});
  const ranges={length:1,start:()=>0,end:()=>8};
  const rect={left:0,top:0,width:960,height:540};
  const video={paused:true,muted:true,currentTime:0,buffered:ranges,error:null,
    readyState:0,videoWidth:1280,videoHeight:720,webkitAudioDecodedByteCount:0,listeners:{},
    getBoundingClientRect:()=>rect,
    play(){calls.push('play');if(calls.filter(call=>call==='play').length===1)return playPromise;this.paused=false;return Promise.resolve();},
    pause(){calls.push('pause');this.paused=true;},removeAttribute(){calls.push('remove-src');},load(){calls.push('load');},
    addEventListener(event,callback){(this.listeners[event] ||= []).push(callback);},
    dispatch(event){for(const callback of this.listeners[event]||[])callback();},
    requestVideoFrameCallback(callback){frameCallback=callback;return 1;},cancelVideoFrameCallback(){}};
  class BufferMock extends EventTarget {
    appendBuffer(){if(hooks.append)hooks.append(video,requests);queueMicrotask(()=>this.dispatchEvent(new Event('updateend')));}
    remove(start,end){removed.push([start,end]);queueMicrotask(()=>this.dispatchEvent(new Event('updateend')));}
  }
  class MediaMock extends EventTarget {
    constructor(){super();queueMicrotask(()=>this.dispatchEvent(new Event('sourceopen')));}
    static isTypeSupported(){return true;}
    addSourceBuffer(){return new BufferMock();}endOfStream(){calls.push('end');}
  }
  class UrlMock extends URL {static createObjectURL(){return 'blob:mock';}static revokeObjectURL(){}}
  const window={MediaSource:MediaMock,addEventListener(){},removeEventListener(){},...hooks.window};
  const context=vm.createContext({window,MediaSource:MediaMock,URL:UrlMock,AbortController,Uint8Array,DataView,
    setTimeout:hooks.clock?.setTimeout||setTimeout,clearTimeout:hooks.clock?.clearTimeout||clearTimeout,
    setInterval(callback){intervals.push(callback);return intervals.length;},clearInterval(){},console:{log(message){logs.push(message);}},
    document:{querySelector:()=>video,getElementById:()=>video,documentElement:{clientWidth:960,clientHeight:540}},
    fetch:async (url,init)=>{
      requests.push(url);
      if(hooks.fetch){const response=await hooks.fetch(url,init);if(response)return response;}
      if(url.endsWith('.ts')){const bytes=segments[url.endsWith('a.ts')?0:1];return {ok:true,arrayBuffer:async()=>bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.byteLength)};}
      return {ok:true,text:async()=>listing};
    }});
  vm.runInContext(source,context);
  const adapter={beginSegment(){},transform(nal){conversionCalls++;return nal;},close(){closed++;}};
  window.__liveRun('https://example.test/live.m3u8',options?.adapterReady?null:adapter,options);
  return {window,calls,logs,video,rect,adapter,requests,removed,sample(){for(const callback of intervals)callback();},frame(){assert.equal(typeof frameCallback,"function");frameCallback();},get conversionCalls(){return conversionCalls;},get closed(){return closed;},
    tsRequests:()=>requests.filter(url=>url.endsWith('.ts')),resolve(){video.paused=false;fulfill();},reject,
    stop:()=>window.__liveStop()};
}

test('first play resolution gates the second TS request and conversion',async()=>{
  const f=fixture();try{
    await until(()=>f.calls.includes('play'));
    const firstConversions=f.conversionCalls;assert.ok(firstConversions>0);
    await tick();await tick();assert.equal(f.tsRequests().length,1);assert.equal(f.conversionCalls,firstConversions);
    f.resolve();await until(()=>f.window.__live.segments===2);
    assert.equal(f.tsRequests().length,2);assert.ok(f.conversionCalls>firstConversions);
  }finally{f.stop();}
});

test('stop during pending first play prevents further segment work even after resolution',async()=>{
  const f=fixture();await until(()=>f.calls.includes('play'));
  const firstConversions=f.conversionCalls;f.stop();f.resolve();await tick();await tick();
  assert.equal(f.tsRequests().length,1);assert.equal(f.conversionCalls,firstConversions);assert.equal(f.closed,1);
});

test('rejected first play reports blocked and does not start the second TS',async()=>{
  const f=fixture();try{
    await until(()=>f.calls.includes('play'));const firstConversions=f.conversionCalls;
    f.reject(Error('autoplay denied'));await until(()=>f.window.__live.blocked===true);
    await tick();assert.equal(f.tsRequests().length,1);assert.equal(f.conversionCalls,firstConversions);
    assert.equal(f.window.__live.state,'blocked');
  }finally{f.stop();}
});


test('reveal acknowledgment keeps second TS deferred after play resolves',async()=>{
  const f=fixture({waitForReveal:true});try{
    await until(()=>f.calls.includes('play'));const firstConversions=f.conversionCalls;
    f.resolve();await new Promise(resolve=>setTimeout(resolve,60));
    assert.equal(f.tsRequests().length,1);assert.equal(f.conversionCalls,firstConversions);
    f.window.__liveAudible=true;
    await new Promise(resolve=>setTimeout(resolve,40));
    await until(()=>f.window.__live.segments===2);
    assert.equal(f.tsRequests().length,2);
  }finally{f.stop();}
});

test('stop while awaiting reveal acknowledgment cancels subsequent segment work',async()=>{
  const f=fixture({waitForReveal:true});await until(()=>f.calls.includes('play'));
  f.resolve();await new Promise(resolve=>setTimeout(resolve,35));
  const firstConversions=f.conversionCalls;f.stop();f.window.__liveAudible=true;
  await new Promise(resolve=>setTimeout(resolve,40));
  assert.equal(f.tsRequests().length,1);assert.equal(f.conversionCalls,firstConversions);assert.equal(f.closed,1);
});

test('stopping a previously ready player invalidates reveal readiness and keeps errors',async()=>{
  const f=fixture();await until(()=>f.calls.includes('play'));
  Object.assign(f.window.__live,{ready:4,paused:false,layout:true,frames:99,time:10});
  f.stop();
  assert.equal(f.window.__live.ready,0);assert.equal(f.window.__live.paused,true);
  assert.equal(f.window.__live.layout,false);assert.equal(f.window.__live.state,'stopped');
  const g=fixture();await until(()=>g.calls.includes('play'));
  g.window.__live.error='decode failure';g.window.__live.state='error';g.stop();
  assert.equal(g.window.__live.error,'decode failure');assert.equal(g.window.__live.state,'error');
});


test('actual video frame refreshes reveal report before sending native progress hint',async()=>{
  const f=fixture();try{
    await until(()=>f.calls.includes('play'));
    const reports=[];
    f.window.__live.token=42;
    f.window.SmartTvPlayback={onProgress(token){reports.push({bridgeToken:token,...f.window.__live});}};
    Object.assign(f.video,{readyState:4,paused:false,currentTime:1.25,webkitAudioDecodedByteCount:4096});
    assert.equal(f.window.__live.frames,0);assert.equal(reports.length,0);
    f.frame();
    assert.equal(reports.length,1);
    assert.equal(reports[0].bridgeToken,'42');
    assert.equal(reports[0].ready,4);assert.equal(reports[0].paused,false);
    assert.equal(reports[0].frames,1);assert.equal(reports[0].audioBytes,4096);
    assert.equal(reports[0].layout,true);assert.equal(reports[0].time,1.25);
    let bridgeToken;f.window.SmartTvPlayback.onProgress=token=>{bridgeToken=token;};
    f.frame();assert.equal(bridgeToken,'42');
  }finally{f.stop();}
});

test('native reveal hint requires audio, fullscreen layout, readiness and active playback',async()=>{
  const f=fixture();try{
    await until(()=>f.calls.includes('play'));
    f.window.__live.token=7;
    const hints=[];f.window.SmartTvPlayback={onProgress:token=>hints.push(token)};
    Object.assign(f.video,{readyState:4,paused:false,webkitAudioDecodedByteCount:0});
    f.frame();assert.equal(hints.length,0,'video alone cannot reveal');
    f.video.webkitAudioDecodedByteCount=1024;f.rect.width=480;
    f.frame();assert.equal(hints.length,0,'partial page video cannot reveal');
    f.rect.width=960;f.video.readyState=2;
    f.frame();assert.equal(hints.length,0,'insufficient video data cannot reveal');
    f.video.readyState=4;f.video.paused=true;
    f.frame();assert.equal(hints.length,0,'paused frame cannot reveal');
    f.video.paused=false;f.frame();assert.equal(hints.length,1);
    f.window.__liveAudible=true;f.frame();assert.equal(hints.length,1,'no redundant hints after reveal');
    f.window.__liveAudible=false;f.stop();f.frame();assert.equal(hints.length,1,'queued stale frame cannot hint');
    assert.equal(f.window.__live.ready,0);assert.equal(f.window.__live.layout,false);
  }finally{f.stop();}
});

test('missing, non-callable or throwing progress bridge cannot break playback',async()=>{
  const f=fixture();try{
    await until(()=>f.calls.includes('play'));
    f.window.__live.token=8;
    Object.assign(f.video,{readyState:4,paused:false,webkitAudioDecodedByteCount:1024});
    assert.doesNotThrow(()=>f.frame());
    f.window.SmartTvPlayback={onProgress:true};assert.doesNotThrow(()=>f.frame());
    let attempted=0;
    f.window.SmartTvPlayback={onProgress(){attempted++;throw Error('native bridge unavailable');}};
    assert.doesNotThrow(()=>f.frame());
    assert.equal(attempted,1);assert.equal(f.window.__live.frames,3);assert.equal(f.window.__live.error,null);
    f.resolve();await until(()=>f.window.__live.segments===2);
    assert.equal(f.tsRequests().length,2);
  }finally{f.stop();}
});


function deferred(){let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};}

test('first TS downloads while adapter is pending, but conversion and play wait',async()=>{
  const ready=deferred(),f=fixture({adapterReady:ready.promise});try{
    await until(()=>f.tsRequests().length===1);await tick();
    assert.equal(f.conversionCalls,0);assert.equal(f.calls.includes('play'),false);
    ready.resolve(f.adapter);await until(()=>f.calls.includes('play'));
    assert.ok(f.conversionCalls>0);assert.equal(f.tsRequests().length,1);
    f.resolve();await until(()=>f.window.__live.segments===2);
  }finally{f.stop();}assert.equal(f.closed,1);
});

test('adapter arriving before first TS waits for network bytes without premature playback',async()=>{
  const ready=deferred(),network=deferred();
  const f=fixture({adapterReady:ready.promise},{fetch:async url=>{if(url.endsWith('a.ts'))await network.promise;}});
  try{
    ready.resolve(f.adapter);await until(()=>f.tsRequests().length===1);await tick();
    assert.equal(f.conversionCalls,0);assert.equal(f.calls.includes('play'),false);
    network.resolve();await until(()=>f.calls.includes('play'));
    assert.ok(f.conversionCalls>0);
  }finally{f.stop();network.resolve();}assert.equal(f.closed,1);
});

test('cancellation closes late adapter exactly once and never resumes conversion',async()=>{
  const ready=deferred(),f=fixture({adapterReady:ready.promise});
  await until(()=>f.tsRequests().length===1);f.stop();
  ready.resolve(f.adapter);await tick();await tick();f.stop();
  assert.equal(f.closed,1);assert.equal(f.conversionCalls,0);
  assert.equal(f.calls.includes('play'),false);assert.equal(f.tsRequests().length,1);
});

test('adapter rejection is handled immediately and aborts pending network',async()=>{
  const ready=deferred(),network=deferred();let signal;
  const f=fixture({adapterReady:ready.promise},{fetch:async (url,init)=>{signal=init.signal;await network.promise;}});
  try{
    await until(()=>signal!=null);ready.reject(Error('converter init failed'));
    await until(()=>f.window.__live.error==='converter init failed');await tick();
    assert.equal(signal.aborted,true);assert.equal(f.window.__live.state,'error');
    assert.equal(f.conversionCalls,0);assert.equal(f.calls.includes('play'),false);
  }finally{f.stop();network.resolve();await tick();}
});

test('network failure closes an adapter that arrives after error without playing',async()=>{
  const ready=deferred(),f=fixture({adapterReady:ready.promise},{fetch:async()=>({ok:false,status:503})});
  await until(()=>f.window.__live.state==='error');
  ready.resolve(f.adapter);await tick();await tick();f.stop();
  assert.equal(f.window.__live.error,'HTTP 503');assert.equal(f.closed,1);
  assert.equal(f.conversionCalls,0);assert.equal(f.calls.includes('play'),false);
});


test('console wake fallback requires current ready frame and stops after reveal or cancellation',async()=>{
  const f=fixture();try{
    await until(()=>f.calls.includes('play'));
    const hints=()=>f.logs.filter(message=>message.startsWith('SMARTTV_FRAME:'));
    Object.assign(f.video,{readyState:4,paused:false,webkitAudioDecodedByteCount:1024});
    f.frame();assert.equal(hints().length,0,'missing token cannot wake native checker');
    f.window.__live.token=23;f.video.webkitAudioDecodedByteCount=0;
    f.frame();assert.equal(hints().length,0,'audio must be decoded');
    f.video.webkitAudioDecodedByteCount=1024;f.rect.top=20;
    f.frame();assert.equal(hints().length,0,'layout must fill viewport');
    f.rect.top=0;f.video.readyState=2;
    f.frame();assert.equal(hints().length,0,'video must be ready');
    f.video.readyState=4;f.video.paused=true;
    f.frame();assert.equal(hints().length,0,'video must be playing');
    f.video.paused=false;f.frame();assert.deepEqual(hints(),['SMARTTV_FRAME:23']);
    f.window.__liveAudible=true;f.frame();assert.equal(hints().length,1);
    f.window.__liveAudible=false;f.stop();f.frame();assert.equal(hints().length,1);
  }finally{f.stop();}
});

test('available native bridge handles wake without duplicate console fallback',async()=>{
  const f=fixture();try{
    await until(()=>f.calls.includes('play'));
    let hints=0;f.window.__live.token=24;
    f.window.SmartTvPlayback={onProgress(){hints++;}};
    Object.assign(f.video,{readyState:4,paused:false,webkitAudioDecodedByteCount:1024});
    f.frame();assert.equal(hints,1);
    assert.equal(f.logs.some(message=>message.startsWith('SMARTTV_FRAME:')),false);
  }finally{f.stop();}
});

function manualClock(){
  const tasks=new Map();let next=0;
  return {setTimeout(callback,delay){const id=++next;tasks.set(id,{callback,delay});return id;},
    clearTimeout(id){tasks.delete(id);},get size(){return tasks.size;},
    async next(){const [id,task]=tasks.entries().next().value||[];assert.ok(task,'expected a scheduled live cycle');tasks.delete(id);await task.callback();}};
}

test('long user pause stops segment work, autoplay, sound and frame progress without destroying MSE',async()=>{
  const clock=manualClock(),liveListing=listing.replace('#EXT-X-ENDLIST\n','');
  const f=fixture(undefined,{clock,fetch:async url=>url.endsWith('.m3u8')?{ok:true,text:async()=>liveListing}:null});
  try {
    await until(()=>f.calls.includes('play'));f.resolve();await until(()=>f.window.__live.segments===2);
    assert.equal(clock.size,1);
    f.window.__liveAudible=true;f.sample();f.window.__liveSetPaused(true);
    const requests=f.requests.length,conversions=f.conversionCalls,plays=f.calls.filter(call=>call==='play').length;
    const time=f.video.currentTime,frames=f.window.__live.frames;
    for(let interval=0;interval<3600;interval++)f.sample(); // 12 minutes of 200 ms progress samples.
    for(let attempt=0;attempt<10;attempt++) {
      await f.video.play();f.video.paused=false;f.video.dispatch('playing');
      f.video.muted=false;f.video.dispatch('volumechange');f.frame();
    }
    assert.equal(clock.size,0);assert.equal(f.requests.length,requests);assert.equal(f.conversionCalls,conversions);
    assert.equal(f.calls.filter(call=>call==='play').length,plays);
    assert.equal(f.video.paused,true);assert.equal(f.video.muted,true);assert.equal(f.video.currentTime,time);
    assert.equal(f.window.__live.frames,frames);assert.equal(f.window.__live.state,'paused');
    assert.equal(f.window.__live.error,null);assert.equal(f.window.__live.blocked,false);
    assert.equal(f.closed,0);assert.equal(f.calls.includes('remove-src'),false);assert.equal(f.calls.includes('load'),false);
  } finally {f.stop();}
});

test('resume fetches the current live tail, restores sound and trims old data using the same MSE element',async()=>{
  const clock=manualClock();let advanced=false;
  const f=fixture(undefined,{clock,fetch:async url=>url.endsWith('.m3u8')?{ok:true,text:async()=>advanced
    ?'#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nexpired.ts\n#EXTINF:2,\nc.ts\n#EXTINF:2,\nd.ts\n'
    :listing.replace('#EXT-X-ENDLIST\n','')}:null,
    append(video,requests){if(requests.some(url=>url.endsWith('c.ts')))video.buffered={length:1,start:()=>100,end:()=>108};}});
  try {
    await until(()=>f.calls.includes('play'));f.resolve();await until(()=>f.window.__live.segments===2);
    const video=f.video,src=video.src;f.window.__liveAudible=true;f.window.__liveSetPaused(true);advanced=true;
    f.window.__liveUserPaused=false;assert.equal(f.window.__liveSetPaused(false),true);
    const plays=f.calls.filter(call=>call==='play').length;
    f.window.__liveSetPaused(false);assert.equal(f.calls.filter(call=>call==='play').length,plays);
    await clock.next();f.sample();
    assert.deepEqual(f.tsRequests().map(url=>url.split('/').at(-1)),['a.ts','b.ts','c.ts','d.ts']);
    assert.equal(video.currentTime,105);assert.equal(video.paused,false);assert.equal(video.muted,false);
    assert.equal(video.src,src);assert.equal(f.closed,0);assert.equal(f.calls.includes('load'),false);
    assert.ok(f.removed.length>=2);assert.ok(f.removed.every(range=>range[1]===90));
    assert.equal(f.window.__live.error,null);assert.equal(f.window.__live.blocked,false);
  } finally {f.stop();}
});

test('pause interruption of an outstanding play promise never blocks the stream and can resume',async()=>{
  const clock=manualClock(),f=fixture(undefined,{clock});
  try {
    await until(()=>f.calls.includes('play'));f.window.__liveSetPaused(true);
    f.reject(Object.assign(Error('interrupted by pause'),{name:'AbortError'}));await tick();await tick();
    assert.equal(f.tsRequests().length,1);assert.equal(f.window.__live.blocked,false);
    assert.equal(f.window.__live.error,null);assert.equal(clock.size,0);
    f.window.__liveSetPaused(false);await clock.next();
    assert.equal(f.window.__live.segments,2);assert.equal(f.video.paused,false);
    assert.equal(f.window.__live.error,null);assert.equal(f.window.__live.blocked,false);
  } finally {f.stop();}
});

test('a late play resolution cannot undo user pause or issue the next segment',async()=>{
  const f=fixture();try {
    await until(()=>f.calls.includes('play'));f.window.__liveSetPaused(true);f.resolve();await tick();await tick();
    assert.equal(f.video.paused,true);assert.equal(f.video.muted,true);assert.equal(f.tsRequests().length,1);
    assert.equal(f.window.__live.blocked,false);assert.equal(f.window.__live.error,null);
  } finally {f.stop();}
});

test('a pending segment failure during pause is deferred and resumed with a fresh playlist',async()=>{
  const clock=manualClock(),network=deferred();let block=true;
  const f=fixture(undefined,{clock,fetch:async url=>{if(url.endsWith('b.ts')&&block)await network.promise;}});
  try {
    await until(()=>f.calls.includes('play'));f.resolve();await until(()=>f.tsRequests().length===2);
    f.window.__liveSetPaused(true);network.reject(Error('expired while paused'));await tick();await tick();
    assert.equal(f.window.__live.error,null);assert.equal(f.closed,0);assert.equal(clock.size,0);
    block=false;f.window.__liveSetPaused(false);await clock.next();
    assert.equal(f.window.__live.segments,2);assert.equal(f.window.__live.error,null);
  } finally {f.stop();}
});

test('pause cancels only the pending download so resume does not wait for an expired request',async()=>{
  const clock=manualClock();let block=true,pendingSignal;
  const f=fixture(undefined,{clock,fetch:async (url,init)=>{
    if(url.endsWith('b.ts')&&block) {
      pendingSignal=init.signal;
      await new Promise((resolve,reject)=>init.signal.addEventListener('abort',()=>reject(Error('download aborted')),{once:true}));
    }
  }});
  try {
    await until(()=>f.calls.includes('play'));f.resolve();await until(()=>pendingSignal!=null);
    f.window.__liveSetPaused(true);assert.equal(pendingSignal.aborted,true);await tick();await tick();
    assert.equal(f.window.__live.error,null);assert.equal(f.closed,0);
    block=false;f.window.__liveSetPaused(false);await clock.next();
    assert.equal(f.window.__live.segments,2);assert.equal(f.video.paused,false);
    assert.equal(f.calls.includes('load'),false);assert.equal(f.closed,0);
  } finally {f.stop();}
});

test('initial pause intent prevents autoplay and a stopped hook cannot change later session intent',async()=>{
  const clock=manualClock(),f=fixture(undefined,{clock,window:{__liveUserPaused:true}});
  try {
    await until(()=>f.requests.length===1);await tick();f.sample();
    assert.equal(f.tsRequests().length,0);assert.equal(f.calls.includes('play'),false);
    assert.equal(f.window.__live.state,'paused');
    const hook=f.window.__liveSetPaused;f.stop();f.window.__liveUserPaused=false;
    assert.equal(f.window.__liveSetPaused,null);assert.equal(hook(true),false);
    assert.equal(f.window.__liveUserPaused,false);assert.equal(f.closed,1);
  } finally {f.stop();}
});
