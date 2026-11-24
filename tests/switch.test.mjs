import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const template = readFileSync(new URL('../app/src/main/assets/switch.js', import.meta.url), 'utf8');
function fixture() {
  const calls = [];
  const oldVideo = { name:'old' };
  let video = oldVideo;
  const current = { divId:'player', t:'cctvamerica', w:'100%', h:'100%',
    isAutoPlay:true, language:'zh', others:{ runtime:true }, video:oldVideo,
    streamUrl:'private', playedTimer:99, posterImg:null, ruleVisible:false };
  const window = { livePlayerObjs:{ player:current } };
  window.destroyLivePlayer = id => { calls.push(['destroy', id]); video = null; };
  window.createLivePlayer = params => {
    calls.push(['create', { ...params }]);
    window.livePlayerObjs.player = params;
    params.language = 'mutated';
    video = { name:params.t };
  };
  const context = vm.createContext({ window,
    location:{ origin:'https://tv.cctv.com', pathname:'/live/cctvamerica/' },
    document:{ body:{querySelector:()=>null}, documentElement:{ clientWidth:960, clientHeight:540 },
      getElementById:id => id === 'player' ? { querySelector:() => video } : null } });
  return { context, window, calls, oldVideo, current,
    run(token=7, channel='cctv13', path='/live/cctvamerica/') {
      return JSON.parse(vm.runInContext(template.replace('__NAV_TOKEN__', JSON.stringify(token))
        .replace('__EXPECTED_CHANNEL__', JSON.stringify(channel))
        .replace('__EXPECTED_PATH__', JSON.stringify(path)), context));
    } };
}

test('destroys before creating, whitelists config and records previous video identity', () => {
  const f = fixture();
  assert.deepEqual(f.run(), { token:7, accepted:true });
  assert.equal(f.calls[0][0], 'destroy');
  assert.equal(f.calls[0][1], 'player');
  assert.equal(f.calls[1][0], 'create');
  const params = f.calls[1][1];
  assert.equal(params.t, 'cctv13');
  assert.equal(params.w, 960);
  assert.equal(params.h, 540);
  assert.equal(params.isAutoPlay, true);
  assert.equal(params.ruleVisible, false);
  assert.equal(params.posterImg, null);
  for (const key of ['video','streamUrl','playedTimer','others']) assert.equal(key in params, false);
  assert.equal(f.window.__liveTvSwitch.previousVideo, f.oldVideo);
  assert.equal(f.window.__liveTvSwitch.channel, 'cctv13');
  assert.equal(f.window.__liveTvSwitch.token, 7);
  assert.equal(f.window.__liveTvSwitch.accepted, true);
});

test('snapshot stays immutable across creator mutations and subsequent switches', () => {
  const f = fixture();
  f.run();
  const original = f.window.__liveTvPlayerConfig;
  assert.equal(Object.isFrozen(original), true);
  assert.equal(original.language, 'zh');
  const nextPrevious = f.window.livePlayerObjs.player;
  f.run(8, 'cctv12');
  assert.equal(f.window.__liveTvPlayerConfig, original);
  assert.equal(f.calls[3][1].language, 'zh');
  assert.equal(f.calls[3][1].t, 'cctv12');
  assert.notEqual(f.window.livePlayerObjs.player, nextPrevious);
  assert.equal(original.t, 'cctvamerica');
  assert.equal(f.window.__liveTvSwitch.previousVideo.name, 'cctv13');
});

test('accepts official path variations but rejects wrong origin or document', () => {
  for (const suffix of ['', 'index.shtml', 'm/']) {
    const f = fixture();
    f.context.location.pathname += suffix;
    assert.equal(f.run().accepted, true);
  }
  for (const patch of [ { origin:'http://tv.cctv.com' },
    { origin:'https://tv.cctv.com.evil.test' }, { pathname:'/live/cctv12/' },
    { pathname:'/live/cctvamerica/other' } ]) {
    const f = fixture();
    Object.assign(f.context.location, patch);
    assert.equal(f.run().accepted, false);
    assert.equal(f.calls.length, 0);
  }
});

test('missing APIs, config or video fail without attempting mutation', () => {
  for (const breakFixture of [ f => delete f.window.createLivePlayer,
    f => delete f.window.destroyLivePlayer, f => delete f.window.livePlayerObjs,
    f => delete f.current.t, f => f.current.divId = 'other',
    f => f.context.document.getElementById = () => null,
    f => f.context.document.documentElement.clientWidth = 0 ]) {
    const f = fixture();
    breakFixture(f);
    assert.deepEqual(f.run(), { token:7, accepted:false });
    assert.equal(f.calls.length, 0);
  }
});

test('exceptions return rejection and never mark partial creation accepted', () => {
  for (const api of ['destroyLivePlayer','createLivePlayer']) {
    const f = fixture();
    f.window[api] = () => { throw new Error('official player failure'); };
    assert.deepEqual(f.run(), { token:7, accepted:false });
    assert.equal(f.window.__liveTvSwitch.accepted, false);
    assert.equal(f.window.__liveTvSwitch.previousVideo, f.oldVideo);
  }
});


function ownFixture() {
  const f=fixture();
  f.window.__live={sourceAdapter:'official-cctv-runtime'};
  f.window.__liveTvPlayerConfig=Object.freeze({divId:'player',t:'cctvamerica',language:'zh',isAutoPlay:true,
    video:{},streamUrl:'private',others:{runtime:true}});
  f.context.document.body.querySelector=()=>f.oldVideo;
  f.context.document.getElementById=()=>({querySelector:()=>null});
  delete f.window.livePlayerObjs.player;
  return f;
}

test('owned video and immutable snapshot restart a fully destroyed official player',()=>{
  const f=ownFixture();assert.deepEqual(f.run(),{token:7,accepted:true});
  assert.equal(f.calls.length,1);assert.equal(f.calls[0][0],'create');
  assert.equal(f.calls[0][1].t,'cctv13');assert.equal(f.calls[0][1].language,'zh');
  assert.equal(f.window.__liveTvSwitch.previousVideo,f.oldVideo);
  for(const key of ['video','streamUrl','others'])assert.equal(key in f.calls[0][1],false);
});

test('owned restart destroys a surviving official instance before creation',()=>{
  const f=ownFixture();f.window.livePlayerObjs.player={divId:'player'};
  assert.equal(f.run().accepted,true);assert.deepEqual(f.calls.map(c=>c[0]),['destroy','create']);
});

test('owned restart rejects missing provenance, mutable snapshot, missing video and wrong page',()=>{
  for(const mutate of [f=>delete f.window.__live,f=>f.window.__live.sourceAdapter='other',
    f=>f.window.__liveTvPlayerConfig={divId:'player',t:'cctv13'},
    f=>f.window.__liveTvPlayerConfig=Object.freeze({divId:'other',t:'cctv13'}),
    f=>f.context.document.body.querySelector=()=>null,
    f=>f.context.document.getElementById=()=>null,
    f=>f.context.location.origin='https://other.test']){
    const f=ownFixture();mutate(f);assert.equal(f.run().accepted,false);assert.equal(f.calls.length,0);
  }
});
