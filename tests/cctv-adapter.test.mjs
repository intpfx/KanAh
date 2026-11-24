import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import test from 'node:test';

const source=readFileSync(new URL('../app/src/main/assets/cctv-adapter.js',import.meta.url),'utf8');
// Captured by executing Qu from the official liveplayer_controls.js on 2026-10-21.
// Source SHA-256: 5d2194022dc6b59da24cdae1b95af30df436a64a10e157ee6e19137ac90fe94b.
// Input: 32 zero bytes; result's final 16 bytes, with each indicated mediaTagId.
// Tests need neither network access nor the proprietary runtime.
const vectors={
  'livetv-3f-3f':Uint8Array.from(Buffer.from('b96b2cb088425ea15f5d11eb1b1459f8','hex')),
  'livetv-3f-3g':Uint8Array.from(Buffer.from('1f116bbfbb26c7199155582b2130ba4a','hex')),
};

async function fixture({random=123,wasm=nal=>nal}={}) {
  const heap=new Uint8Array(2*1024*1024);
  let next=16,closed=0,converted=0;
  const ids=[];
  const module={HEAPU8:heap,HEAP8:new Int8Array(heap.buffer),
    _jsmalloc(n){const p=next;next+=n;assert.ok(next<heap.length);return p;},
    _jsfree(){},
    _CNTV_InitPlayer(p){let end=p;while(heap[end])end++;ids.push(Buffer.from(heap.subarray(p,end)).toString('ascii'));return 1;},
    _CNTV_UpdatePlayer(){return 0x77777777;},
    _CNTV_UnInitPlayer(){closed++;},
    _CNTV_jsdecLive8(player,p,n){converted++;const out=wasm(heap.slice(p,p+n));heap.set(out,p);return out.length;},
  };
  // Deliberately no TextEncoder: precomputed ASCII masks must work on old WebView.
  const context={Uint8Array,Uint32Array,Date:{now:()=>123},setTimeout,clearTimeout,
    location:{origin:'https://tv.cctv.com'},crypto:{getRandomValues(a){a[0]=random;return a;}},
    CNTVModule(options){setTimeout(options.onRuntimeInitialized,0);return module;}};
  context.window=context.self=context;
  vm.runInNewContext(source,context);
  const adapter=await context.__createCctvAdapter();
  return {adapter,ids,get converted(){return converted;},get closed(){return closed;}};
}
function marked(adapter){adapter.transform(new Uint8Array([0x67,100,1,40]));}
function nal(length,type=0x65){const out=Uint8Array.from({length},(_,i)=>i&255);out[0]=type;return out;}
function withVector(input,id){const out=input.slice(),middle=out.length>>1,mask=vectors[id];for(let i=0;i<Math.min(16,out.length-middle);i++)out[middle+i]^=mask[i];return out;}

test('marked VCL matches a fixed official vector and preserves its input',async t=>{
  const f=await fixture();t.after(()=>f.adapter.close());marked(f.adapter);
  assert.deepEqual(f.ids,['livetv-3f-3f']);
  const input=nal(256),before=input.slice(),result=f.adapter.transform(input);
  assert.deepEqual(result,withVector(input,'livetv-3f-3f'));
  assert.deepEqual(input,before);
  assert.notEqual(result,input);
  assert.equal(f.converted,2,'one SPS registration and one VCL conversion');
});

test('official short-NAL threshold skips conversion through 128 bytes',async t=>{
  const f=await fixture();t.after(()=>f.adapter.close());marked(f.adapter);
  const before=f.converted;
  for(const length of [1,2,3,15,16,17,31,32,33,127,128]){
    for(const type of [0x41,0x65]){
      const input=nal(length,type),copy=input.slice();
      assert.deepEqual(f.adapter.transform(input),copy,`length ${length}, type ${type}`);
      assert.deepEqual(input,copy);
    }
  }
  assert.equal(f.converted,before);
  assert.throws(()=>f.adapter.transform(new Uint8Array()),/nonempty/);
});

test('odd, even and large marked NALs alter only their middle 16 bytes',async t=>{
  const f=await fixture();t.after(()=>f.adapter.close());marked(f.adapter);
  for(const length of [129,255,256,257,42976]){
    const input=nal(length,0x41),result=f.adapter.transform(input),middle=length>>1;
    assert.deepEqual(result,withVector(input,'livetv-3f-3f'),`length ${length}`);
    assert.deepEqual(result.subarray(0,middle),input.subarray(0,middle));
    assert.deepEqual(result.subarray(middle+16),input.subarray(middle+16));
  }
});

test('finishing applies to WASM output, with its returned length and bytes',async t=>{
  const f=await fixture({wasm(input){if((input[0]&31)===7)return input;const out=input.slice(0,129);out[70]^=0x80;return out;}});
  t.after(()=>f.adapter.close());marked(f.adapter);
  const input=nal(256),before=input.slice(),wasmOutput=input.slice(0,129);wasmOutput[70]^=0x80;
  assert.deepEqual(f.adapter.transform(input),withVector(wasmOutput,'livetv-3f-3f'));
  assert.deepEqual(input,before);
});

test('unmarked VCL and metadata bypass postprocessing, including after a segment reset',async t=>{
  const f=await fixture();t.after(()=>f.adapter.close());
  const input=nal(256),copy=input.slice();
  assert.deepEqual(f.adapter.transform(input),copy);
  assert.equal(f.converted,0);
  marked(f.adapter);
  const before=f.converted;
  for(const type of [0x06,0x68,0x09]){
    const metadata=nal(256,type),original=metadata.slice();
    assert.deepEqual(f.adapter.transform(metadata),original);
    assert.deepEqual(metadata,original);
  }
  assert.equal(f.converted,before);
  f.adapter.beginSegment();
  assert.deepEqual(f.adapter.transform(input),copy);
  f.adapter.transform(new Uint8Array([0x67,100,0,40]));
  assert.deepEqual(f.adapter.transform(input),copy);
  assert.equal(f.converted,before);
});

test('the mask follows the exact player ID across independent runtimes',async t=>{
  const a=await fixture(),b=await fixture(),c=await fixture({random:124});
  t.after(()=>{a.adapter.close();b.adapter.close();c.adapter.close();});
  for(const f of [a,b,c])marked(f.adapter);
  const input=nal(256),first=a.adapter.transform(input);
  assert.deepEqual(first,b.adapter.transform(input));
  assert.deepEqual(c.ids,['livetv-3f-3g']);
  const different=c.adapter.transform(input);
  assert.deepEqual(different,withVector(input,'livetv-3f-3g'));
  assert.notDeepEqual(first,different);
});
