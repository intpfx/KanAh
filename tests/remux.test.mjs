import { readFileSync, writeFileSync, mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const source=readFileSync(new URL('../app/src/main/assets/remux.js',import.meta.url),'utf8');
// Expose pure internals only in this VM; production has no test exports.
const context={window:{},console:{log(){}},Uint8Array,DataView,URL};
vm.runInNewContext(source.replace("  log('ready');",'window.test={liveDemux,liveFragment,audioInit,initSegment,unwrap33,dimensions};'),context);
const h=context.window.test;
assert.equal(h.unwrap33(100,8589934500),8589934692);
assert.equal(h.unwrap33(8589934500,100),-92);
// Mock runtime verifies ownership/global isolation without loading or impersonating CCTV code.
const adapterSource=readFileSync(new URL('../app/src/main/assets/cctv-adapter.js',import.meta.url),'utf8');
const adapterContext={location:{origin:'http://localhost'},Uint8Array,Uint32Array,Date,setTimeout,clearTimeout,
  crypto:{getRandomValues(a){a[0]=123;return a;}}};
adapterContext.window=adapterContext.self=adapterContext;
vm.runInNewContext(adapterSource,adapterContext);
await assert.rejects(adapterContext.__createCctvAdapter(),/actual https/);
adapterContext.location.origin='https://tv.cctv.com'; // Unit-test stub; no actual official runtime executes here.
adapterContext.activeURL='existing-page';adapterContext.vmpTag='existing-tag';
let uninitialized=0,allocated=0,released=0,next=16,converted=0;
const heap=new Uint8Array(1024*1024);
const mock={HEAPU8:heap,HEAP8:new Int8Array(heap.buffer),
  _jsmalloc(n){allocated++;const p=next;next+=n;return p;},_jsfree(){released++;},
  _CNTV_InitPlayer(){return 1;},_CNTV_UpdatePlayer(){return 0x77777777;},
  _CNTV_UnInitPlayer(){uninitialized++;},_CNTV_jsdecLive8(id,p,n){converted++;heap[p+1]^=128;return n;}};
adapterContext.CNTVModule=options=>{setTimeout(options.onRuntimeInitialized,0);return mock;};
const adapter=await adapterContext.__createCctvAdapter();
assert.equal(adapterContext.activeURL,'existing-page');assert.equal(adapterContext.vmpTag,'existing-tag');
assert.equal(Object.hasOwn(adapterContext,'signalUrlSend'),false);
// Official conversion applies only above 128 bytes. Keep the bridge mutation
// outside the midpoint mask so these checks independently cover segment state.
const vcl = type => { const nal=new Uint8Array(256); nal[0]=type; nal[1]=1; return nal; };
assert.equal(adapter.transform(new Uint8Array([0x67,100,1,40]))[2],0);
assert.equal(adapter.transform(vcl(0x65))[1],129);
adapter.beginSegment();
const beforePlain=converted;
// Leading pictures and an unmarked segment must not inherit the prior SPS marker.
assert.equal(adapter.transform(vcl(0x41))[1],1);
adapter.transform(new Uint8Array([0x67,100,0,40]));
assert.equal(adapter.transform(vcl(0x65))[1],1);
assert.equal(converted,beforePlain);
adapter.beginSegment();
adapter.transform(new Uint8Array([0x67,100,2,40]));
assert.equal(adapter.transform(vcl(0x65))[1],129);
// Within one segment, the official parser deliberately retains its OR semantics.
adapter.transform(new Uint8Array([0x67,100,0,40]));
assert.equal(adapter.transform(vcl(0x41))[1],129);
assert.equal(adapterContext.vmpTag,'existing-tag');
adapter.close();adapter.close();assert.equal(uninitialized,1);assert.equal(allocated,released);
assert.throws(()=>adapter.transform(vcl(0x65)),/closed/);
assert.throws(()=>adapter.beginSegment(),/closed/);
const dir=mkdtempSync(join(tmpdir(),'livetv-remux-'));
try {
  execFileSync('ffmpeg',['-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=320x180:rate=25',
    '-f','lavfi','-i','sine=frequency=1000:sample_rate=48000','-t','7','-c:v','libx264','-pix_fmt','yuv420p','-g','50',
    '-keyint_min','50','-sc_threshold','0','-bf','2','-c:a','aac','-b:a','96k','-f','hls','-hls_time','2',
    '-hls_list_size','0',join(dir,'test.m3u8')]);
  const names=readdirSync(dir).filter(n=>n.endsWith('.ts')).sort();
  assert.ok(names.length>=3);
  // Alter only the reserved marker bits in a synthetic, otherwise ordinary SPS.
  const marked=new Uint8Array(readFileSync(join(dir,names[0])));
  let markedCount=0;
  for(let i=0;i+6<marked.length;i++)if(marked[i]===0&&marked[i+1]===0&&marked[i+2]===1&&(marked[i+3]&31)===7){marked[i+5]|=1;markedCount++;}
  assert.ok(markedCount>0);
  assert.throws(()=>h.liveDemux(marked,{}),/requires its official adapter/);
  let visits=0;
  const markedTrack=h.liveDemux(marked,{},nal=>{visits++;const out=nal.slice();if((out[0]&31)===7)out[2]&=252;return out;});
  assert.ok(visits>markedTrack.video.length,'adapter receives each NAL including metadata');
  assert.ok(markedTrack.video.length>0);
  const clocks={},v=[],a=[];let epoch,seq=1,hasB=false;
  for(const name of names){
    const track=h.liveDemux(new Uint8Array(readFileSync(join(dir,name))),clocks);
    assert.ok(track.video.length>0&&track.audio.length>0);
    assert.equal(h.dimensions(track.sps).width,320);assert.equal(h.dimensions(track.sps).height,180);
    hasB ||= track.video.some(s=>s.pts!==s.dts);
    if(epoch==null){epoch=Math.min(track.video[0].dts,track.audio[0].dts);v.push(h.initSegment(track,320,180));a.push(h.audioInit(track.audioConfig));}
    v.push(h.liveFragment(track.video,1,seq,epoch,90000));
    a.push(h.liveFragment(track.audio,2,seq++,epoch,track.audioConfig.rate));
  }
  assert.ok(hasB,'fixture must exercise composition offsets');
  writeFileSync(join(dir,'video.mp4'),Buffer.concat(v));writeFileSync(join(dir,'audio.mp4'),Buffer.concat(a));
  for(const [file,type] of [['video.mp4','video'],['audio.mp4','audio']]){
    const probe=JSON.parse(execFileSync('ffprobe',['-v','error','-show_streams','-show_packets','-of','json',join(dir,file)]));
    assert.equal(probe.streams[0].codec_type,type);
    const packets=probe.packets;assert.ok(packets.length>100);
    for(let i=1;i<packets.length;i++)assert.ok(Number(packets[i].dts_time)>Number(packets[i-1].dts_time),'monotonic DTS');
    assert.ok(Number(packets.at(-1).pts_time)>6,'continuous across segments');
    execFileSync('ffmpeg',['-hide_banner','-loglevel','error','-xerror','-i',join(dir,file),'-f','null','-']);
  }
  execFileSync('ffmpeg',['-hide_banner','-loglevel','error','-i',join(dir,'video.mp4'),'-i',join(dir,'audio.mp4'),'-c','copy',join(dir,'combined.mp4')]);
  const combined=JSON.parse(execFileSync('ffprobe',['-v','error','-show_streams','-of','json',join(dir,'combined.mp4')]));
  assert.equal(combined.streams.length,2);
  assert.ok(Math.abs(Number(combined.streams[0].duration)-Number(combined.streams[1].duration))<0.15);
  console.log('remux: H264 B-frame + AAC, four TS segments, monotonic DTS, shared timeline, audio/video ffmpeg decode passed');
}finally{rmSync(dir,{recursive:true,force:true});}
