/* Uses the unmodified official runtime already loaded on the real CCTV page.
 * This module does not ship or impersonate the official runtime. Every synchronous
 * native call restores shared page globals before another player can run. */
(function () {
  'use strict';
  window.__createCctvAdapter = async function () {
    if (location.origin !== 'https://tv.cctv.com') {
      throw new Error('CCTV adapter requires the actual https://tv.cctv.com page origin');
    }
    if (typeof window.CNTVModule !== 'function') throw new Error('Official CNTVModule is unavailable');
    const id = 'livetv-' + Date.now().toString(36) + '-' + crypto.getRandomValues(new Uint32Array(1))[0].toString(36);
    // The official remuxer finishes marked VCL NALs after the WASM call with
    // a per-player mask over their middle 16 bytes. Omitting it corrupts H264
    // while timestamps, frame counters and AAC audio can still advance.
    // Our generated id is ASCII, so its code units are also its UTF-8 bytes.
    const tagBytes = Array.from(id, c => c.charCodeAt(0));
    const nalMask = new Uint8Array(16);
    let seed = 2166136261 >>> 0;
    for (const byte of tagBytes) seed = Math.imul((seed ^ byte) >>> 0, 16777619) >>> 0;
    for (let i = 0; i < nalMask.length; i++) {
      seed = (seed ^ (seed << 13)) >>> 0;
      seed = (seed ^ (seed >>> 17)) >>> 0;
      seed = (seed ^ (seed << 5)) >>> 0;
      nalMask[i] = (31 * (seed & 255) + tagBytes[i % tagBytes.length]) & 255;
    }
    function finishNal(nal) {
      const middle = nal.length >> 1;
      for (let i = 0; i < Math.min(16, nal.length - middle); i++) nal[middle + i] ^= nalMask[i];
      return nal;
    }
    const keys = ['activeURL', 'vmpTag', 'signalUrlSend'];
    const own = {activeURL: location.origin, vmpTag: '', signalUrlSend: false};
    let module, marked = false, closed = false, initialized = false;
    // No asynchronous work may occur inside this scope: other players share these globals.
    function scoped(fn) {
      const saved = keys.map(key => ({key, has: Object.prototype.hasOwnProperty.call(self, key), value: self[key]}));
      for (const key of keys) self[key] = own[key];
      try { return fn(); }
      finally {
        for (const key of keys) own[key] = self[key];
        for (const entry of saved) {
          if (entry.has) self[entry.key] = entry.value;
          else delete self[entry.key];
        }
      }
    }
    function playerCall(fn) {
      const pointer = module._jsmalloc(id.length + 2048);
      if (!pointer) throw new Error('Official player allocation failed');
      try {
        module.HEAP8.fill(0, pointer, pointer + id.length + 2048);
        module.HEAP8.set(Array.from(id, c => c.charCodeAt(0)), pointer);
        return fn(pointer);
      } finally { module._jsfree(pointer); }
    }
    await new Promise((resolve, reject) => {
      let finished = false;
      const timeout = setTimeout(() => {
        if (!finished) { finished = true; closed = true; reject(new Error('Official runtime initialization timed out')); }
      }, 15000);
      try {
        module = window.CNTVModule({onRuntimeInitialized() {
          // The factory can call synchronously; wait until the returned module is assigned.
          setTimeout(() => {
            if (finished) return;
            finished = true; clearTimeout(timeout);
            try {
              scoped(() => playerCall(pointer => module._CNTV_InitPlayer(pointer)));
              initialized = true; resolve();
            } catch (error) { closed = true; reject(error); }
          }, 0);
        }});
      } catch (error) { finished = true; clearTimeout(timeout); closed = true; reject(error); }
    });
    function convert(nal) {
      const pointer = module._jsmalloc(nal.byteLength + 2048);
      if (!pointer) throw new Error('Official NAL allocation failed');
      try {
        module.HEAP8.set(nal, pointer);
        let urlLength = 0;
        if (self.activeURL && !self.signalUrlSend) {
          const url = Array.from(self.activeURL, c => c.charCodeAt(0));
          if (url.length >= 2048) throw new Error('Official origin exceeds NAL allocation');
          urlLength = url.length; module.HEAP8.set(url, pointer + nal.byteLength);
        }
        return playerCall(player => {
          const tag = self.vmpTag || '';
          // Keep the original signed hex representation and original call order.
          for (let i = 0; i < tag.length; i++) {
            if ('0123456'.includes(tag[i])) module['_CNTV_jsdecLive' + [7, 6, 5, 4, 3, 2, 1, 0, 8][i]](player, pointer, nal.byteLength, urlLength);
          }
          const length = module._CNTV_jsdecLive8(player, pointer, nal.byteLength, urlLength);
          if (length <= 0 || length > nal.byteLength + 2048) throw new Error('Official NAL transform failed');
          return module.HEAPU8.slice(pointer, pointer + length);
        });
      } finally { module._jsfree(pointer); }
    }
    return {
      beginSegment() {
        if (closed) throw new Error('CCTV adapter is closed');
        // The official TS demuxer resets isLiveDecode before each input segment.
        // A previous segment's SPS must not enable conversion before this one's SPS.
        marked = false;
      },
      transform(input) {
        if (closed) throw new Error('CCTV adapter is closed');
        if (!(input instanceof Uint8Array) || !input.length) throw new Error('Expected one nonempty H264 NAL');
        return scoped(() => {
          const tag = playerCall(pointer => module._CNTV_UpdatePlayer(pointer));
          if (tag != null && tag !== '' && tag !== 0) {
            const hex = tag.toString(16);
            self.vmpTag = hex.length < 8 ? ('00000000' + hex).slice(-8) : hex;
          }
          const nal = input.slice(), type = nal[0] & 31;
          if (type === 7) {
            if (nal.length < 4) throw new Error('Truncated SPS');
            marked = marked || (nal[2] & 3) === 1 || (nal[2] & 3) === 2;
            if (marked) convert(nal); // The official parser registers SPS, then clears its marker.
            nal[2] = 0;
          } else if (marked && nal.byteLength > 128 && (type === 1 || type === 5)) {
            return finishNal(convert(nal));
          }
          return nal;
        });
      },
      close() {
        if (closed) return;
        closed = true;
        try { if (initialized) scoped(() => playerCall(pointer => module._CNTV_UnInitPlayer(pointer))); }
        finally { initialized = false; module = null; }
      }
    };
  };
})();
