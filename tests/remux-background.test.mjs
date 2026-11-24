import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';

const source=readFileSync(new URL('../app/src/main/assets/remux.js',import.meta.url),'utf8');
const dir=mkdtempSync(join(tmpdir(),'livetv-remux-background-'));
after(()=>rmSync(dir,{recursive:true,force:true}));
execFileSync('ffmpeg',['-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=160x90:rate=25',
  '-f','lavfi','-i','sine=frequency=1000:sample_rate=48000','-t','6','-c:v','libx264','-g','50',
  '-keyint_min','50','-sc_threshold','0','-bf','2','-c:a','aac','-f','hls','-hls_time','2','-hls_list_size','0',join(dir,'test.m3u8')]);
const segments=['a','b','c'].map((name,index)=>[`${name}.ts`,Uint8Array.from(readFileSync(join(dir,`test${index}.ts`)))]);
const tick=()=>new Promise(resolve=>setImmediate(resolve));
async function until(predicate){for(let i=0;i<50&&!predicate();i++)await tick();assert.ok(predicate(),'expected asynchronous stage was not reached');}
function deferred(){let resolve;const promise=new Promise(yes=>{resolve=yes;});return {promise,resolve};}
function makeVideo(){
  return {paused:true,muted:false,volume:1,currentTime:0,buffered:{length:1,start:()=>0,end:()=>8},error:null,
    readyState:4,videoWidth:1280,videoHeight:720,webkitAudioDecodedByteCount:4096,listeners:{},plays:[],pauses:0,loads:0,
    getBoundingClientRect:()=>({left:0,top:0,width:960,height:540}),
    play(){this.plays.push({muted:this.muted});this.paused=false;return Promise.resolve();},
    pause(){this.pauses++;this.paused=true;},removeAttribute(){},load(){this.loads++;},
    addEventListener(event,callback){(this.listeners[event] ||= []).push(callback);},
    dispatch(event){for(const callback of this.listeners[event]||[])callback();},
    requestVideoFrameCallback(callback){this.frame=callback;return 1;},cancelVideoFrameCallback(){}};
}
function fixture({backgroundMuted=false,audible=true,waitForReveal=false,fetchHook}={}){
  const requests=[],intervals=[],timers=new Map();let nextTimer=0,mediaCount=0,closed=0,video=makeVideo(),listed=2;
  const listing=()=>`#EXTM3U\n#EXT-X-TARGETDURATION:2\n${segments.slice(0,listed).map(([name])=>`#EXTINF:2,\n${name}\n`).join('')}`;
  class BufferMock extends EventTarget {
    appendBuffer(){queueMicrotask(()=>this.dispatchEvent(new Event('updateend')));}
    remove(){queueMicrotask(()=>this.dispatchEvent(new Event('updateend')));}
  }
  class MediaMock extends EventTarget {
    constructor(){super();mediaCount++;queueMicrotask(()=>this.dispatchEvent(new Event('sourceopen')));}
    static isTypeSupported(){return true;}
    addSourceBuffer(){return new BufferMock();}endOfStream(){}
  }
  class UrlMock extends URL {static createObjectURL(){return `blob:fixture-${mediaCount}`;}static revokeObjectURL(){}}
  const window={MediaSource:MediaMock,addEventListener(){},removeEventListener(){},
    __liveBackgroundMuted:backgroundMuted,__liveAudible:audible};
  const context=vm.createContext({window,MediaSource:MediaMock,URL:UrlMock,AbortController,Uint8Array,DataView,
    setTimeout(callback,delay){const id=++nextTimer;timers.set(id,{callback,delay});return id;},clearTimeout(id){timers.delete(id);},
    setInterval(callback){intervals.push(callback);return intervals.length;},clearInterval(){},console:{log(){}},
    document:{querySelector:()=>video,getElementById:()=>video,documentElement:{clientWidth:960,clientHeight:540}},
    fetch:async(url,init)=>{
      requests.push({url,signal:init.signal});if(fetchHook)await fetchHook(url,init);
      const entry=segments.find(([name])=>url.endsWith(name));
      if(entry){const bytes=entry[1];return {ok:true,arrayBuffer:async()=>bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.byteLength)};}
      return {ok:true,text:async()=>listing()};
    }});
  vm.runInContext(source,context);
  const run=()=>window.__liveRun('https://example.test/live.m3u8',
    {beginSegment(){},transform:nal=>nal,close(){closed++;}},{waitForReveal});
  run();
  return {window,requests,get video(){return video;},set video(value){video=value;},get mediaCount(){return mediaCount;},get closed(){return closed;},
    get timerCount(){return timers.size;},run,advancePlaylist(){listed=3;},sample(){for(const callback of intervals)callback();},
    async nextTimer(){const [id,task]=timers.entries().next().value||[];assert.ok(task,'expected a scheduled live task');timers.delete(id);await task.callback();},
    tsRequests:()=>requests.filter(request=>request.url.endsWith('.ts')),stop:()=>window.__liveStop()};
}

test('remux background mute keeps pending downloads, live refresh and the same playing stream',async()=>{
  const pending=deferred();const f=fixture({fetchHook:async url=>{if(url.endsWith('b.ts'))await pending.promise;}});
  try {
    await until(()=>f.tsRequests().length===2);
    const video=f.video,live=f.window.__live,src=video.src,plays=video.plays.length;
    live.token=42;
    assert.equal(typeof f.window.__liveSetBackgroundMuted,'function');
    assert.equal(f.window.__liveSetBackgroundMuted(true),true);
    assert.equal(video.muted,true);assert.equal(video.paused,false);assert.equal(video.pauses,0);
    assert.equal(f.tsRequests()[1].signal.aborted,false,'background browsing must not cancel an in-flight segment');
    assert.equal(f.window.__liveAudible,true);assert.equal(live.userPaused,false);
    for(const event of ['volumechange','playing','play']) {
      video.muted=false;video.dispatch(event);assert.equal(video.muted,true,`${event} cannot defeat background mute`);
    }
    video.muted=false;f.sample();assert.equal(video.muted,true,'the interval must preserve the background policy');
    pending.resolve();await until(()=>live.segments===2&&f.timerCount===1);
    f.advancePlaylist();await f.nextTimer();
    assert.deepEqual(f.tsRequests().map(({url})=>url.split('/').at(-1)),['a.ts','b.ts','c.ts']);
    video.currentTime=4;video.frame();video.frame();f.sample();
    assert.equal(live.segments,3);assert.equal(live.state,'playing');assert.equal(live.frames,2);assert.equal(video.muted,true);
    assert.equal(f.window.__liveSetBackgroundMuted(false),true);
    assert.equal(video.muted,false);assert.equal(video.paused,false);assert.equal(video.currentTime,4);
    assert.equal(f.window.__live,live);assert.equal(live.token,42);assert.equal(video.src,src);
    assert.equal(f.mediaCount,1);assert.equal(f.closed,0);assert.equal(video.loads,0);assert.equal(video.pauses,0);
    assert.equal(video.plays.length,plays);
  } finally {pending.resolve();f.stop();}
});

test('preinstalled remux background mute is independent of the first-frame download acknowledgment',async()=>{
  const f=fixture({backgroundMuted:true,audible:false,waitForReveal:true});
  try {
    await until(()=>f.video.plays.length===1&&f.timerCount===1);
    assert.equal(f.window.__liveBackgroundMuted,true);assert.equal(f.video.paused,false);
    assert.deepEqual(f.video.plays,[{muted:true}]);assert.equal(f.tsRequests().length,1);
    assert.equal(typeof f.window.__liveSetBackgroundMuted,'function');
    f.window.__liveSetBackgroundMuted(false);
    assert.equal(f.video.muted,true,'foreground alone does not grant first-frame acknowledgment');
    assert.equal(f.window.__liveAudible,false);
    f.window.__liveSetBackgroundMuted(true);f.window.__liveAudible=true;
    await f.nextTimer();await until(()=>f.window.__live.segments===2);f.sample();
    assert.equal(f.tsRequests().length,2,'readiness acknowledgment allows the next TS even while background muted');
    assert.equal(f.video.muted,true);assert.equal(f.video.paused,false);
    assert.equal(f.window.__liveAudible,true);assert.equal(f.window.__liveBackgroundMuted,true);
  } finally {f.stop();}
});

test('remux background transitions cannot resume a user-paused stream',async()=>{
  const f=fixture();
  try {
    await until(()=>f.window.__live.segments===2&&f.timerCount===1);
    f.window.__liveSetPaused(true);
    const plays=f.video.plays.length,requests=f.requests.length,time=f.video.currentTime,live=f.window.__live,src=f.video.src;
    assert.equal(typeof f.window.__liveSetBackgroundMuted,'function');
    f.window.__liveSetBackgroundMuted(true);f.window.__liveSetBackgroundMuted(false);
    f.video.muted=false;f.video.dispatch('volumechange');f.sample();
    assert.equal(f.video.paused,true);assert.equal(f.video.muted,true);assert.equal(f.window.__liveUserPaused,true);
    assert.equal(live.state,'paused');assert.equal(f.timerCount,0);assert.equal(f.requests.length,requests);
    assert.equal(f.video.plays.length,plays);assert.equal(f.video.currentTime,time);
    assert.equal(f.window.__live,live);assert.equal(f.video.src,src);assert.equal(f.mediaCount,1);assert.equal(f.closed,0);
  } finally {f.stop();}
});

test('a replacement remux session inherits background mute and rejects the previous session hook',async()=>{
  const f=fixture();
  try {
    await until(()=>f.window.__live.segments===2);
    assert.equal(typeof f.window.__liveSetBackgroundMuted,'function');
    f.window.__liveSetBackgroundMuted(true);const oldHook=f.window.__liveSetBackgroundMuted;
    const replacement=makeVideo();f.video=replacement;f.run();
    await until(()=>f.window.__live.segments===2);
    assert.equal(f.window.__liveBackgroundMuted,true);assert.equal(replacement.paused,false);
    assert.deepEqual(replacement.plays,[{muted:true}]);
    assert.equal(oldHook(false),false);assert.equal(f.window.__liveBackgroundMuted,true);
    replacement.muted=false;await replacement.play();
    assert.equal(replacement.plays.at(-1).muted,true,'replacement autoplay is muted before play');
    const plays=replacement.plays.length;
    f.window.__liveSetBackgroundMuted(false);
    assert.equal(replacement.muted,false);assert.equal(replacement.paused,false);assert.equal(replacement.plays.length,plays);
    assert.equal(f.closed,1,'only the explicit replacement closes its prior adapter');
  } finally {f.stop();}
});
