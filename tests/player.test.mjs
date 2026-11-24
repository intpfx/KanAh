import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const template = readFileSync(new URL('../app/src/main/assets/player.js', import.meta.url), 'utf8');
function makeVideo() {
  return { nodeType:1, tagName:'VIDEO', muted:false, defaultMuted:false, volume:1,
    videoWidth:1280, videoHeight:720, currentTime:3, readyState:4, paused:false,
    error:null, parentElement:null, plays:[], callbacks:[], listeners:{}, pauses:0,
    setAttribute() {}, matches:s => s === 'video', querySelectorAll:() => [],
    play() { this.plays.push({ muted:this.muted }); this.paused=false; return Promise.resolve(); },
    pause() { this.pauses++; this.paused=true; },
    addEventListener(event, callback) { (this.listeners[event] ||= []).push(callback); },
    dispatch(event) { for (const callback of this.listeners[event] || []) callback(); },
    requestVideoFrameCallback(cb) { this.callbacks.push(cb); },
    getBoundingClientRect:() => ({ left:0, top:0, width:960, height:540 }) };
}
function fixture() {
  let video = makeVideo();
  const nodes = new Map();
  const observers = [];
  const body = { querySelectorAll:() => [video] };
  const player = { querySelector:() => video };
  const window = { livePlayerObjs:{ player:{ t:'cctv13' } },
    __liveTvSwitch:{ token:7, channel:'cctv13', previousVideo:makeVideo(), accepted:true } };
  const stored = new Map();
  const storage = { getItem:key => stored.get(key) ?? null,
    setItem:(key,value) => stored.set(key,value) };
  const context = vm.createContext({ window, localStorage:storage,
    location:{ origin:'https://tv.cctv.com', pathname:'/live/cctvamerica/' },
    MutationObserver:class {
      constructor(callback) { this.callback=callback; observers.push(this); }
      observe(target, options) { this.target=target; this.options=options; }
    },
    document:{ body, head:{ appendChild(style) { style.isConnected=true; nodes.set(style.id,style); } },
      documentElement:{ clientWidth:960, clientHeight:540 },
      createElement:() => ({}), querySelectorAll:() => [video],
      getElementById:id => id === 'player' ? player : nodes.get(id) } });
  return { context, window, observers, storage,
    get video() { return video; }, set video(next) { video=next; },
    run({ token=7, samePage=true, audible=false, expectedChannel='cctv13' }={}) {
      const source=template.replaceAll('__NAV_TOKEN__',JSON.stringify(token))
        .replaceAll('__EXPECTED_CHANNEL__',JSON.stringify(expectedChannel))
        .replaceAll('__EXPECTED_PATH__',JSON.stringify('/live/cctvamerica/'))
        .replaceAll('__SAME_PAGE__',JSON.stringify(samePage))
        .replaceAll('__AUDIBLE__',JSON.stringify(audible));
      const raw=vm.runInContext(source,context);
      return raw === null ? null : JSON.parse(raw);
    } };
}

test('matching document cannot reveal or play a different engine channel', () => {
  const f=fixture(); f.window.livePlayerObjs.player.t='cctv12';
  assert.deepEqual(f.run(),{ token:7, missing:true });
  assert.equal(f.video.plays.length,0);
});

test('same-page readiness rejects the old video or missing/mismatched operation', () => {
  for (const mutate of [ f => f.window.__liveTvSwitch.previousVideo=f.video,
    f => delete f.window.__liveTvSwitch, f => delete f.window.__liveTvSwitch.accepted,
    f => f.window.__liveTvSwitch.accepted=false, f => delete f.window.__liveTvSwitch.token,
    f => f.window.__liveTvSwitch.token=6, f => f.window.__liveTvSwitch.channel='cctv12' ]) {
    const f=fixture(); mutate(f);
    assert.deepEqual(f.run(),{ token:7, missing:true });
    assert.equal(f.video.plays.length,0);
  }
});

test('new video reports geometry and frames, then resets instance on replacement', () => {
  const f=fixture(); const first=f.run();
  assert.equal(first.instance,1); assert.equal(first.layout,true);
  assert.equal(first.frames,0); assert.equal(first.frameCallback,true);
  f.video.callbacks.shift()();
  assert.equal(f.run().frames,1);
  assert.equal(f.video.plays.length,1);
  f.video=makeVideo();
  const replaced=f.run();
  assert.equal(replaced.instance,2); assert.equal(replaced.frames,0);
});

test('old video frame callbacks cannot advance a new generation', () => {
  const f=fixture(); f.run();
  const staleState=f.window.__liveTv;
  const staleCallback=f.video.callbacks.shift();
  f.window.__liveTvSwitch.token=8;
  f.run({ token:8 });
  staleCallback();
  assert.equal(staleState.frames,0);
  assert.equal(f.window.__liveTv.frames,0);
  f.video.callbacks.shift()();
  assert.equal(f.window.__liveTv.frames,1);
});

test('muting happens before playback and audible state can change without replay', () => {
  const f=fixture(); f.run();
  assert.deepEqual(f.video.plays,[{ muted:true }]);
  assert.equal(f.window.__liveTvAudible,false);
  f.run({ audible:true });
  assert.equal(f.video.muted,false);
  assert.equal(f.video.plays.length,1);
});

test('one observer mutes newly inserted videos and nested videos while hidden', () => {
  const f=fixture(); f.run(); f.run();
  assert.equal(f.observers.length,1);
  const observer=f.observers[0];
  assert.equal(observer.options.subtree,true);
  assert.equal(observer.options.childList,true);
  const direct=makeVideo(), nested=makeVideo();
  const wrapper={ nodeType:1, tagName:'DIV', matches:() => false,
    querySelectorAll:() => [nested] };
  observer.callback([{ addedNodes:[direct,wrapper,{ nodeType:3 }] }]);
  assert.equal(direct.muted,true);
  assert.equal(nested.muted,true);
  f.run({ audible:true });
  const audible=makeVideo(); audible.muted=true;
  observer.callback([{ addedNodes:[audible] }]);
  assert.equal(audible.muted,false);
});


test('reveal repairs volume zero persisted by official sound bar during transition', () => {
  const f=fixture();
  for (let cycle=0;cycle<3;cycle++) {
    f.run({ audible:false });
    // Reproduce LiveSoundBar initializing while our loading mute is set.
    if (f.video.muted) {
      f.video.volume=0;
      f.window.livePlayerObjs.player.volume=0;
      f.storage.setItem('cctv_live_volume','0');
    }
    f.run({ audible:true });
    assert.equal(f.video.muted,false);
    assert.equal(f.video.volume,1);
    assert.equal(f.window.livePlayerObjs.player.volume,1);
    assert.equal(f.storage.getItem('cctv_live_volume'),'1');
  }
});

test('storage failure cannot prevent audible playback after a temporary mute', () => {
  const f=fixture(); f.video.volume=0;
  f.storage.getItem=()=>{throw Error('storage denied');};
  f.run({ audible:true });
  assert.equal(f.video.volume,1);
  assert.equal(f.video.muted,false);
});


test('CCTV-9 and CCTV-14 official aliases preserve document and same-page identity', () => {
  for (const pair of [['cctvjilu9','cctvjilu'],['cctvchild14','cctvchild']]) {
    for (const [expectedChannel, actual] of [pair,[...pair].reverse()]) {
      for (const samePage of [false,true]) {
        const f=fixture();f.window.livePlayerObjs.player.t=actual;
        f.window.__liveTvSwitch.channel=actual;
        const report=f.run({expectedChannel,samePage});
        assert.equal(report.layout,true);assert.equal(report.missing,undefined);
      }
    }
  }
});

test('channel aliases never merge other channels or approximate suffixes', () => {
  for (const [expectedChannel, actual] of [['cctvjilu9','cctvchild'],['cctvchild14','cctv13'],
    ['cctvchild14','cctvchild1'],['cctvjilu9','cctvjilu99'],['cctv13','cctv130']]) {
    const f=fixture();f.window.livePlayerObjs.player.t=actual;f.window.__liveTvSwitch.channel=expectedChannel;
    assert.deepEqual(f.run({expectedChannel}),{token:7,missing:true});
    assert.equal(f.video.plays.length,0);
  }
  const f=fixture();f.window.livePlayerObjs.player.t='cctvchild';f.window.__liveTvSwitch.channel='cctvjilu9';
  assert.deepEqual(f.run({expectedChannel:'cctvchild14'}),{token:7,missing:true});
});

test('official pause survives polling, autoplay and volume recovery without advancing frames', async () => {
  const f=fixture();f.run({audible:true});const frame=f.video.callbacks.shift();
  f.window.__liveUserPaused=true;assert.equal(f.window.__liveSetPaused(true),true);
  const plays=f.video.plays.length, time=f.video.currentTime;
  assert.equal(f.video.paused,true);assert.equal(f.video.muted,true);
  for(let i=0;i<20;i++) {
    await f.video.play();
    f.video.paused=false;f.video.dispatch('playing');
    f.video.muted=false;f.video.dispatch('volumechange');
    const report=f.run({audible:true});
    assert.equal(report.paused,true);assert.equal(report.userPaused,true);
  }
  frame();assert.equal(f.window.__liveTv.frames,0);
  assert.equal(f.video.plays.length,plays);assert.equal(f.video.currentTime,time);
  assert.equal(f.video.listeners.play.length,1,'repeated injection installs one guard');
});

test('official resume is idempotent and rejoins an available live range without replacing video', () => {
  const f=fixture();f.run({audible:true});const video=f.video;
  f.window.__liveSetPaused(true);
  video.buffered={length:1,start:()=>100,end:()=>108};
  f.window.__liveUserPaused=false;assert.equal(f.window.__liveSetPaused(false),true);
  assert.equal(f.video,video);assert.equal(video.currentTime,105);
  assert.equal(video.paused,false);assert.equal(video.muted,false);
  const plays=video.plays.length;
  f.window.__liveSetPaused(false);f.run({audible:true});
  assert.equal(video.plays.length,plays);
});

test('paused official replacement videos cannot autoplay and an old hook cannot control a new token', async () => {
  const f=fixture();f.run({audible:true});f.window.__liveSetPaused(true);
  const oldHook=f.window.__liveSetPaused, replacement=makeVideo();f.video=replacement;
  f.observers[0].callback([{addedNodes:[replacement]}]);
  assert.equal(replacement.paused,true);assert.equal(replacement.muted,true);
  await replacement.play();assert.equal(replacement.plays.length,0);
  f.run({audible:true});assert.equal(f.video.plays.length,0);
  assert.equal(oldHook(false),false);
  assert.equal(f.window.__liveUserPaused,true);
  f.window.__liveUserPaused=false;f.window.__liveTvSwitch.token=8;
  f.run({token:8,audible:false});
  assert.equal(f.video.plays.length,1);assert.equal(f.window.__liveTv.userPaused,false);
});

test('resume targets a website replacement inserted while native readiness polling is suspended', () => {
  const f=fixture();f.run({audible:true});f.window.__liveSetPaused(true);
  const previous=f.video,replacement=makeVideo();f.video=replacement;
  f.observers[0].callback([{addedNodes:[replacement]}]);
  replacement.volume=0;
  f.window.__liveUserPaused=false;assert.equal(f.window.__liveSetPaused(false),true);
  assert.equal(previous.paused,true);assert.equal(replacement.paused,false);
  assert.equal(replacement.muted,false);assert.equal(replacement.volume,1);
  assert.equal(replacement.plays.length,1);
});

test('official bootstrap preserves a preinstalled background mute while continuing playback', () => {
  const f=fixture();f.window.__liveBackgroundMuted=true;
  const report=f.run({audible:true});
  assert.equal(f.window.__liveBackgroundMuted,true);
  assert.equal(f.window.__liveTvAudible,true,'background silence must not revoke readiness');
  assert.equal(report.userPaused,false);assert.equal(report.paused,false);
  assert.deepEqual(f.video.plays,[{muted:true}]);
  assert.equal(typeof f.window.__liveSetBackgroundMuted,'function');
});

test('official background mute survives site audio events and polling then restores the same stream', async () => {
  const f=fixture();f.run({audible:true});
  const video=f.video,state=f.window.__liveTv,plays=video.plays.length;
  assert.equal(typeof f.window.__liveSetBackgroundMuted,'function');
  assert.equal(f.window.__liveSetBackgroundMuted(true),true);
  assert.equal(video.muted,true);assert.equal(video.paused,false);assert.equal(video.pauses,0);
  for (const event of ['volumechange','playing','play']) {
    video.muted=false;video.dispatch(event);
    assert.equal(video.muted,true,`${event} must preserve background silence`);
    assert.equal(video.paused,false);
  }
  video.callbacks.shift()();
  const report=f.run({audible:true});
  assert.equal(report.frames,1);assert.equal(video.muted,true);
  assert.equal(f.window.__liveTvAudible,true);assert.equal(f.window.__liveUserPaused===true,false);
  video.volume=0;f.window.livePlayerObjs.player.volume=0;f.storage.setItem('cctv_live_volume','0');
  assert.equal(f.window.__liveSetBackgroundMuted(false),true);
  assert.equal(video.muted,false);assert.equal(video.volume,1);assert.equal(f.window.livePlayerObjs.player.volume,1);
  assert.equal(f.storage.getItem('cctv_live_volume'),'1');
  assert.equal(f.video,video);assert.equal(f.window.__liveTv,state);assert.equal(state.instance,1);
  assert.equal(video.plays.length,plays);assert.equal(video.pauses,0);
  assert.equal(video.listeners.volumechange.length,1);
});

test('background official replacement stays silent before and after autoplay without a readiness poll', async () => {
  const f=fixture();f.run({audible:true});
  assert.equal(typeof f.window.__liveSetBackgroundMuted,'function');
  f.window.__liveSetBackgroundMuted(true);
  const replacement=makeVideo();f.video=replacement;
  f.observers[0].callback([{addedNodes:[replacement]}]);
  assert.equal(replacement.muted,true);assert.equal(replacement.paused,false);
  replacement.muted=false;await replacement.play();
  assert.deepEqual(replacement.plays,[{muted:true}]);
  replacement.muted=false;replacement.dispatch('volumechange');assert.equal(replacement.muted,true);
  const plays=replacement.plays.length;
  f.window.__liveSetBackgroundMuted(false);
  assert.equal(replacement.muted,false);assert.equal(replacement.paused,false);
  assert.equal(replacement.plays.length,plays);
});

test('leaving the background never resumes a user-paused official stream or bypasses reveal mute', () => {
  const f=fixture();f.run({audible:true});f.window.__liveSetPaused(true);
  assert.equal(typeof f.window.__liveSetBackgroundMuted,'function');
  const plays=f.video.plays.length,time=f.video.currentTime;
  f.window.__liveSetBackgroundMuted(true);f.window.__liveSetBackgroundMuted(false);
  f.video.muted=false;f.video.dispatch('volumechange');
  assert.equal(f.video.paused,true);assert.equal(f.video.muted,true);
  assert.equal(f.window.__liveUserPaused,true);assert.equal(f.video.plays.length,plays);assert.equal(f.video.currentTime,time);
  f.window.__liveSetBackgroundMuted(true);f.window.__liveSetPaused(false);
  assert.equal(f.video.paused,false);assert.equal(f.video.muted,true,'explicit user resume still obeys background mute');
  f.run({audible:false});f.window.__liveSetBackgroundMuted(false);
  assert.equal(f.window.__liveTvAudible,false);assert.equal(f.video.muted,true,'foreground cannot bypass first-frame reveal');
});
