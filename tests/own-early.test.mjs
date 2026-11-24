import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const template=readFileSync(new URL('../app/src/main/assets/own-early.js',import.meta.url),'utf8');
const tick=()=>new Promise(resolve=>setImmediate(resolve));
function fixture(){
  const nodes=new Map(),scripts=[],runs=[],pending=[];let closes=0,stops=0;
  const window={__createCctvAdapter:()=>new Promise(resolve=>pending.push(()=>resolve({close(){closes++;}}))),
    __liveRun(source,adapter,options){const run={source,adapter,options};runs.push(run);run.result=options.adapterReady.then(value=>({value}),error=>({error}));window.__live={state:'starting',error:null};},__liveStop(){stops++;}};
  function element(tag){return {tag,setAttribute(){},remove(){nodes.delete(this.id);}};}
  const append=e=>{if(e.id)nodes.set(e.id,e);if(e.tag==='script')scripts.push(e);};
  const context=vm.createContext({window,URL,location:{origin:'https://tv.cctv.com'},
    document:{head:{appendChild:append},body:{appendChild:append},createElement:element,getElementById:id=>nodes.get(id)}});
  return {window,context,nodes,scripts,runs,pending,get closes(){return closes;},get stops(){return stops;},
    run(token=1,source='https://example.test/live.m3u8',channel='cctv13'){
      return vm.runInContext(template.replace('__NAV_TOKEN__',JSON.stringify(token))
        .replace('__EXPECTED_CHANNEL__',JSON.stringify(channel)).replace('__KNOWN_STREAM__',JSON.stringify(source)),context);
    }};
}
test('rejects wrong origin, nonHTTPS sources and existing official controllers',()=>{
  for(const change of [f=>f.context.location.origin='https://other.test',f=>f.window.Hls=function(){},
    f=>f.window.livePlayerObjs={player:{} }]){const f=fixture();change(f);assert.equal(f.run(),false);assert.equal(f.window.__live.state,'error');assert.equal(f.scripts.length,0);}
  for(const source of ['http://example.test/live','not a URL','https://user:secret@example.test/live']){
    const f=fixture();assert.equal(f.run(1,source),false);assert.equal(f.scripts.length,0);assert.ok(!f.window.__live.error.includes('secret'));
  }
});
test('loads only official runtime and starts without moduleInitialized',async()=>{
  const f=fixture();assert.equal(f.run(),true);assert.equal(f.scripts.length,1);
  assert.equal(f.runs.length,1);assert.equal(f.pending.length,0);assert.ok(f.nodes.has('livetv-video'));
  assert.equal(f.runs[0].adapter,null);assert.equal(f.runs[0].options.waitForReveal,true);
  assert.equal(f.scripts[0].src,'https://js.player.cntv.cn/creator/live.worker.js');
  f.window.CNTVModule=function(){};f.scripts[0].onload();await tick();f.pending.shift()();await tick();
  assert.equal(f.runs.length,1);assert.equal(f.window.__live.startupMode,'minimal-official-runtime');
  assert.equal(f.window.__live.channel,'cctv13');assert.equal(f.window.__live.token,1);assert.ok(f.nodes.has('livetv-video'));
  assert.equal(f.window.__liveTvTiming,undefined);
});
test('late superseded adapter ownership is passed to remux and existing factory is reused',async()=>{
  const f=fixture();f.window.CNTVModule=function(){};
  f.run();await tick();f.run(2,'https://example.test/other.m3u8','cctv12');await tick();
  f.pending.shift()();const first=await f.runs[0].result;
  assert.ok(first.value);assert.equal(f.closes,0); // Only remux owns late-adapter cleanup.
  first.value.close();assert.equal(f.closes,1);
  f.pending.shift()();assert.ok((await f.runs[1].result).value);
  assert.deepEqual(f.runs.map(r=>r.source),['https://example.test/live.m3u8','https://example.test/other.m3u8']);
  assert.equal(f.window.__live.channel,'cctv12');assert.equal(f.window.__live.token,2);assert.equal(f.scripts.length,0);assert.equal(f.stops,2);
});
test('concurrent module waits share one script and only latest token creates an adapter',async()=>{
  const f=fixture();f.run();f.run(2);assert.equal(f.scripts.length,1);
  f.window.CNTVModule=function(){};f.scripts[0].onload();await tick();
  assert.match((await f.runs[0].result).error.message,/superseded/);
  assert.equal(f.pending.length,1);f.pending.shift()();assert.ok((await f.runs[1].result).value);
});
test('module error is handed to both remux runs without creating adapters',async()=>{
  const f=fixture();f.run();f.run(2);f.scripts[0].onerror();
  for(const run of f.runs)assert.match((await run.result).error.message,/could not load/);
  assert.equal(f.pending.length,0);
});
test('official controller appearing during initialization closes adapter before rejection',async()=>{
  const f=fixture();f.window.CNTVModule=function(){};f.run();await tick();
  f.window.Hls=function(){};f.pending.shift()();
  assert.match((await f.runs[0].result).error.message,/Official controller appeared/);
  assert.equal(f.closes,1);
});
