/*
 * Proof of concept: play one segment of the stream ourselves.
 *
 * The website's player takes about eight seconds of cpu on this television before it asks for anything,
 * and the goal is a picture in three. MSE here takes fragmented mp4 but not transport streams -
 * MediaSource.isTypeSupported('video/mp2t; codecs="avc1.64001f"') is false, 'video/mp4; codecs=...' is
 * true - so the transport stream has to be repackaged. That is all this does: demux the transport
 * stream, and write the h264 samples into an mp4 that MSE will accept.
 *
 * The historical POC entry points remain below. __liveRun uses separate video/audio MSE buffers
 * with an H264/AAC demuxer, shared source timestamps, and bounded live buffering.
 */
(function () {
  'use strict';

  var state = {log: [], frames: 0, ready: false, error: null};
  window.__poc = state;

  function log(message) {
    state.log.push(message);
    console.log('[poc] ' + message);
  }

  /* ---------------------------------------------------------------- boxes */

  function u8(n) {
    return new Uint8Array(n);
  }

  function fourcc(target, at, text) {
    for (var i = 0; i < 4; i++) target[at + i] = text.charCodeAt(i);
  }

  function concat(parts) {
    var size = 0, i;
    for (i = 0; i < parts.length; i++) size += parts[i].length;
    var out = new Uint8Array(size), at = 0;
    for (i = 0; i < parts.length; i++) {
      out.set(parts[i], at);
      at += parts[i].length;
    }
    return out;
  }

  function box(type) {
    var parts = Array.prototype.slice.call(arguments, 1);
    var body = concat(parts);
    var out = new Uint8Array(8 + body.length);
    new DataView(out.buffer).setUint32(0, out.length);
    fourcc(out, 4, type);
    out.set(body, 8);
    return out;
  }

  function full(type, version, flags) {
    var parts = Array.prototype.slice.call(arguments, 3);
    var head = new Uint8Array(4);
    new DataView(head.buffer).setUint32(0, (version << 24) | flags);
    return box.apply(null, [type, head].concat(parts));
  }

  function u32(value) {
    var out = new Uint8Array(4);
    new DataView(out.buffer).setUint32(0, value >>> 0);
    return out;
  }

  function u16(value) {
    var out = new Uint8Array(2);
    new DataView(out.buffer).setUint16(0, value & 0xffff);
    return out;
  }

  function bytes(list) {
    return new Uint8Array(list);
  }

  /* ------------------------------------------------------- transport stream */

  function parsePat(payload) {
    var table = payload.subarray(payload[0] + 1);      // skip the pointer field
    var length = ((table[1] & 0x0f) << 8) | table[2];
    for (var i = 8; i + 4 <= 3 + length - 4; i += 4) {
      var program = (table[i] << 8) | table[i + 1];
      if (program !== 0) return ((table[i + 2] & 0x1f) << 8) | table[i + 3];
    }
    return -1;
  }

  function parsePmt(payload) {
    var table = payload.subarray(payload[0] + 1);
    var length = ((table[1] & 0x0f) << 8) | table[2];
    var programInfoLength = ((table[10] & 0x0f) << 8) | table[11];
    var at = 12 + programInfoLength;
    var end = 3 + length - 4;
    while (at + 5 <= end) {
      var type = table[at];
      var pid = ((table[at + 1] & 0x1f) << 8) | table[at + 2];
      var infoLength = ((table[at + 3] & 0x0f) << 8) | table[at + 4];
      if (type === 0x1b) return pid;                   // h264
      at += 5 + infoLength;
    }
    return -1;
  }

  /** Reads one 90kHz pts out of a pes header, or -1. */
  function pesPts(pes) {
    if (pes.length < 14 || pes[0] !== 0 || pes[1] !== 0 || pes[2] !== 1) return -1;
    var flags = pes[7];
    if ((flags & 0x80) === 0) return -1;               // no pts
    return ((pes[9] & 0x0e) * 536870912) + ((pes[10] & 0xff) << 22)
        + ((pes[11] & 0xfe) << 14) + ((pes[12] & 0xff) << 7) + ((pes[13] & 0xfe) >> 1);
  }

  function pesPayload(pes) {
    var headerLength = pes[8];
    return pes.subarray(9 + headerLength);
  }

  function demux(ts) {
    var pmtPid = -1, videoPid = -1;
    var chunks = [], flushAt = -1;
    var samples = [], sps = null, pps = null;
    var lastPts = -1, timescale = 90000;

    function flush() {
      if (!chunks.length) return;
      var pes = concat(chunks);
      chunks = [];
      var pts = pesPts(pes);
      var payload = pesPayload(pes);
      var nals = splitNals(payload);
      if (!nals.length) return;
      var key = false, body = [], header = [];
      for (var i = 0; i < nals.length; i++) {
        var nal = nals[i];
        var type = nal[0] & 0x1f;
        if (type === 7) { sps = nal; continue; }
        if (type === 8) { pps = nal; continue; }
        if (type === 9) continue;                       // access unit delimiter
        if (type === 5) key = true;
        if (type === 1 || type === 5) body.push(nal);
      }
      if (!body.length) return;
      var sample = concat(body);
      var out = new Uint8Array(sample.length + 4 * body.length);
      var at = 0;
      for (var j = 0; j < body.length; j++) {
        new DataView(out.buffer).setUint32(at, body[j].length);
        out.set(body[j], at + 4);
        at += 4 + body[j].length;
      }
      samples.push({data: out, pts: pts >= 0 ? pts : lastPts + 3600, key: key});
      if (pts >= 0) lastPts = pts;
    }

    for (var i = 0; i + 188 <= ts.length; i += 188) {
      if (ts[i] !== 0x47) continue;
      var pid = ((ts[i + 1] & 0x1f) << 8) | ts[i + 2];
      var start = (ts[i + 1] & 0x40) !== 0;
      var adaptation = (ts[i + 3] & 0x30) >> 4;
      if (adaptation === 2) continue;
      var at = i + 4;
      if (adaptation === 3) at += ts[i + 4] + 1;
      if (at >= i + 188) continue;
      var payload = ts.subarray(at, i + 188);
      if (pid === 0) {
        pmtPid = parsePat(payload);
      } else if (pid === pmtPid) {
        videoPid = parsePmt(payload);
      } else if (pid === videoPid) {
        if (start) {
          flush();
          chunks = [payload];
        } else if (chunks.length) {
          chunks.push(payload);
        }
      }
    }
    flush();
    return {samples: samples, sps: sps, pps: pps, timescale: timescale};
  }

  function splitNals(data) {
    var nals = [], start = -1, i;
    for (i = 0; i + 3 <= data.length; i++) {
      var three = data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1;
      var four = !three && data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 0
          && i + 3 < data.length && data[i + 3] === 1;
      if (!three && !four) continue;
      if (start >= 0) {
        var end = i;
        while (end > start && data[end - 1] === 0) end--;
        if (end > start) nals.push(data.subarray(start, end));
      }
      start = i + (three ? 3 : 4);
      if (four) i++;
    }
    if (start >= 0 && start < data.length) nals.push(data.subarray(start));
    return nals;
  }

  /* ------------------------------------------------------------------- mp4 */

  function avcC(sps, pps) {
    return box('avcC', bytes([
      1, sps[1], sps[2] & 0xfc, sps[3], 0xff, 0xe1,
      (sps.length >> 8) & 0xff, sps.length & 0xff
    ]), sps, bytes([1, (pps.length >> 8) & 0xff, pps.length & 0xff]), pps);
  }

  function initSegment(track, width, height) {
    var timescale = track.timescale;
    var avc1 = box('avc1',
        bytes([0, 0, 0, 0, 0, 0, 0, 1]),                     // reserved + data reference index
        bytes([0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
        u16(width), u16(height),
        u32(0x00480000), u32(0x00480000), u32(0), u16(1),
        bytes([0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
               0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
        u16(0x0018), u16(0xffff),
        avcC(track.sps, track.pps));
    var stsd = full('stsd', 0, 0, u32(1), avc1);
    var stbl = box('stbl', stsd, full('stts', 0, 0, u32(0)),
        full('stsc', 0, 0, u32(0)), full('stsz', 0, 0, u32(0), u32(0)),
        full('stco', 0, 0, u32(0)));
    var dinf = box('dinf', full('dref', 0, 0, u32(1), full('url ', 0, 1)));
    var minf = box('minf', full('vmhd', 0, 1, u16(0), u16(0), u16(0), u16(0)), dinf, stbl);
    var hdlr = full('hdlr', 0, 0, u32(0), bytes([0x76, 0x69, 0x64, 0x65]), u32(0), u32(0), u32(0),
        bytes([0]));
    // Duration zero, deliberately. Declaring two hours here once, to see whether a duration helped the
    // element start: it did not, and it left the element believing its buffer ran to 7200 seconds, so it
    // played straight past the data that existed and froze on the first frame with a play button over it.
    var mdhd = full('mdhd', 0, 0, u32(0), u32(0), u32(timescale), u32(0), u16(0x55c4), u16(0));
    var mdia = box('mdia', mdhd, hdlr, minf);
    var tkhd = full('tkhd', 0, 7, u32(0), u32(0), u32(1), u32(0), u32(0),
        u32(0), u32(0), u32(0), u32(0),
        bytes([0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
               0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
               0x40, 0, 0, 0]), u32(width << 16), u32(height << 16));
    var trak = box('trak', tkhd, mdia);
    var mvhd = full('mvhd', 0, 0, u32(0), u32(0), u32(1000), u32(0), u32(0x00010000),
        u16(0x0100), u16(0), u32(0), u32(0),
        bytes([0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
               0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
               0x40, 0, 0, 0]), u32(0), u32(0), u32(0), u32(0), u32(0), u32(0), u32(2));
    // Fragmented mp4 declares itself in mvex; without a trex here the media element refuses the whole
    // thing with "no supported source", which is what it did.
    var mvex = box('mvex', full('trex', 0, 0, u32(1), u32(1), u32(0), u32(0), u32(0)));
    // ftyp and moov are siblings. Passing moov as another part of ftyp nested the whole movie header
    // inside the file type box, which is why the demuxer could not open the file at all.
    return concat([
      box('ftyp', bytes([0x69, 0x73, 0x6f, 0x6d]), u32(512),
          bytes([0x69, 0x73, 0x6f, 0x6d, 0x69, 0x73, 0x6f, 0x36, 0x6d, 0x70, 0x34, 0x31])),
      box('moov', mvhd, trak, mvex)
    ]);
  }

  /**
   * One moof and its mdat.
   *
   * The trun payload is sample_count, then data_offset, then three words per sample - and the offset
   * field has to be written past, not over: putting the first sample at offset four landed its duration
   * in the data offset and shifted the whole table. The tfdt is version 1, which means a 64 bit time,
   * and it was being given four bytes.
   */
  function moofFor(sequence, baseTime, samples, dataOffset) {
    var payload = new Uint8Array(8 + samples.length * 12);
    var view = new DataView(payload.buffer);
    view.setUint32(0, samples.length);
    view.setUint32(4, dataOffset);
    var at = 8;
    for (var i = 0; i < samples.length; i++) {
      var next = i + 1 < samples.length ? samples[i + 1].pts : samples[i].pts + 3600;
      view.setUint32(at, Math.max(1, next - samples[i].pts)); at += 4;
      view.setUint32(at, samples[i].data.length); at += 4;
      view.setUint32(at, samples[i].key ? 0x02000000 : 0x01010000); at += 4;
    }
    return box('moof', full('mfhd', 0, 0, u32(sequence)),
        box('traf', full('tfhd', 0, 0x020000, u32(1)),
            full('tfdt', 1, 0, u32(0), u32(baseTime)),
            full('trun', 0, 0x000701, payload)));
  }

  function mediaSegment(track, samples, sequence, baseTime) {
    var measure = moofFor(sequence, baseTime, samples, 0);
    var moof = moofFor(sequence, baseTime, samples, measure.length + 8);
    var mdat = box('mdat', concat(samples.map(function (s) { return s.data; })));
    void track;
    return concat([moof, mdat]);
  }

  /* ------------------------------------------------------------------ flow */

  function variants(master, base) {
    var lines = master.split('\n');
    var best = null;
    for (var i = 0; i < lines.length; i++) {
      if (lines[i].indexOf('#EXT-X-STREAM-INF') !== 0) continue;
      var bandwidth = /BANDWIDTH=(\d+)/.exec(lines[i]);
      var resolution = /RESOLUTION=(\d+)x(\d+)/.exec(lines[i]);
      var url = (lines[i + 1] || '').trim();
      if (!url || url[0] === '#') continue;
      var value = bandwidth ? parseInt(bandwidth[1], 10) : 0;
      if (!best || value > best.bandwidth) {
        best = {
          url: new URL(url, base).href,
          bandwidth: value,
          width: resolution ? parseInt(resolution[1], 10) : 1280,
          height: resolution ? parseInt(resolution[2], 10) : 720
        };
      }
    }
    if (!best) best = {url: base, bandwidth: 0, width: 1280, height: 720};
    return best;
  }

  function segmentUrls(media, base) {
    var out = [];
    media.split('\n').forEach(function (line) {
      var trimmed = line.trim();
      if (trimmed && trimmed[0] !== '#') out.push(new URL(trimmed, base).href);
    });
    return out;
  }

  function status(text) {
    state.status = text;
    var element = document.getElementById('status');
    if (element) element.textContent = text;
  }

  function play(track, width, height) {
    // The page can ask twice - the webview loads it more than once - and a second MediaSource
    // on the same element removes the first one's SourceBuffer, which then fails on append.
    if (state.started) { log('already started, ignoring'); return; }
    state.started = true;
    var video = document.getElementById('video');
    var mediaSource = new MediaSource();
    video.src = URL.createObjectURL(mediaSource);
    mediaSource.addEventListener('sourceopen', function () {
      if (state.source) { log('sourceopen again, ignoring'); return; }
      // The codec string is a hint, and MSE is strict about which hints it knows. The constraint byte
      // from the sps (0x01 here) is not one it accepts, so try the canonical form first and fall back.
      var profile = hex(track.sps[1]);
      var level = hex(track.sps[3]);
      var candidates = [
        'video/mp4; codecs="avc1.' + profile + '00' + level + '"',
        'video/mp4; codecs="avc1.640028"',
        'video/mp4; codecs="avc1.4d401f"',
        'video/mp4; codecs="avc1.42e01e"'
      ];
      var mime = null;
      for (var c = 0; c < candidates.length; c++) {
        log('codec ' + candidates[c] + ' supported=' + MediaSource.isTypeSupported(candidates[c]));
        if (!mime && MediaSource.isTypeSupported(candidates[c])) mime = candidates[c];
      }
      if (!mime) {
        state.error = 'no supported codec';
        status('no supported codec');
        return;
      }
      var source = mediaSource.addSourceBuffer(mime);
      state.source = source;
      // One append instead of a queue driven by updateend: less machinery, and a segment is small
      // enough to hand over whole.
      // Each fragment is appended on its own updateend. Handing over one buffer holding five moofs is
      // legal and was not what the element wanted; the standard shape is init, then a fragment per
      // append, driven by the event that says the previous one is in.
      var parts = [initSegment(track, width, height)];
      var sequence = 1;
      var origin = track.samples[0].pts;
      for (var i = 0; i < track.samples.length; i += 25) {
        var chunk = track.samples.slice(i, i + 25);
        parts.push(mediaSegment(track, chunk, sequence++, chunk[0].pts - origin));
      }
      var at = 0;
      function elementState(where) {
        var quality = video.getVideoPlaybackQuality ? video.getVideoPlaybackQuality() : null;
        log(where + ' readyState=' + video.readyState + ' paused=' + video.paused
            + ' t=' + video.currentTime.toFixed(2) + ' decodedFrames='
            + (quality ? quality.totalVideoFrames : 'n/a')
            + ' error=' + (video.error ? video.error.code + ':' + video.error.message : 'none'));
      }
      var started = false;
      function begin() {
        if (started) return;
        started = true;
        elementState('before play');
        video.muted = true;
        video.play().then(function () {
          log('playing ' + video.videoWidth + 'x' + video.videoHeight
              + ' readyState=' + video.readyState);
          setTimeout(function () { elementState('after 2s'); }, 2000);
        }).catch(function (e) {
          log('play rejected ' + e);
        });
      }
      /**
       * The other way round: give the file to the server and let the element fetch it as a stream that
       * is still arriving. That is the pipeline that already plays these bytes, with the media source
       * extension taken out of it.
       */
      function handToElement() {
        var whole = concat(parts);
        log('uploading ' + whole.length + ' bytes');
        fetch('/upload', {method: 'POST', body: whole}).then(function (r) {
          return r.text();
        }).then(function (answer) {
          log('server took ' + answer);
          // First the whole file with a length, then, six seconds in, the same bytes as a stream that is
          // still arriving. One run, both answers.
          video.src = '/file.mp4?t=' + Date.now();
          video.muted = true;
          video.play().catch(function (e) { log('stream play rejected ' + e); });
          var ticks = 0;
          var timer = setInterval(function () {
            ticks++;
            var quality = video.getVideoPlaybackQuality ? video.getVideoPlaybackQuality() : null;
            var which = ticks <= 6 ? 'file' : 'stream';
            log(which + ' readyState=' + video.readyState + ' t=' + video.currentTime.toFixed(2)
                + ' decoded=' + (quality ? quality.totalVideoFrames : 'n/a')
                + ' buffered=' + (video.buffered.length
                    ? video.buffered.end(0).toFixed(2) : 'none')
                + ' error=' + (video.error ? video.error.code + ':' + video.error.message : 'none'));
            if (ticks === 6) {
              log('switching to the chunked stream');
              video.src = '/stream.mp4?t=' + Date.now();
              video.play().catch(function (e) { log('stream play rejected ' + e); });
            }
            if (ticks >= 14) clearInterval(timer);
          }, 1000);
        }).catch(function (e) {
          log('upload failed ' + e);
        });
      }

      function step() {
        if (at >= parts.length) {
          log('all ' + parts.length + ' pieces appended');
          handToElement();
          return;
        }
        var piece = parts[at];
        try {
          source.appendBuffer(piece);
          at++;
        } catch (e) {
          log('append threw at ' + at + ': ' + e);
          state.error = String(e);
        }
      }
      source.addEventListener('updateend', function () {
        log('appended ' + at + '/' + parts.length + ' buffered='
            + (source.buffered.length ? source.buffered.end(0).toFixed(2) : 'none'));
        step();   // and it handles the end: the last updateend is what starts playback
      });
      source.addEventListener('error', function () {
        log('source error ' + (source.error ? source.error.message : '?'));
      });
      step();
    });
  }

  /** Walks the box tree so the structure can be read rather than guessed at. */
  function dump(data, depth, into) {
    var at = 0;
    while (at + 8 <= data.length) {
      var size = new DataView(data.buffer, data.byteOffset + at).getUint32(0);
      var type = String.fromCharCode(data[at + 4], data[at + 5], data[at + 6], data[at + 7]);
      if (size < 8 || at + size > data.length) {
        into.push(new Array(depth + 1).join('  ') + type + ' size=' + size + ' OUT OF RANGE');
        return;
      }
      into.push(new Array(depth + 1).join('  ') + type + ' (' + size + ')');
      var inner = ['moov', 'trak', 'mdia', 'minf', 'stbl', 'dinf', 'mvex', 'moof', 'traf'];
      if (inner.indexOf(type) >= 0) dump(data.subarray(at + 8, at + size), depth + 1, into);
      if (type === 'stsd') dump(data.subarray(at + 16, at + size), depth + 1, into);
      if (type === 'avc1') dump(data.subarray(at + 8 + 78, at + size), depth + 1, into);
      at += size;
    }
  }

  function hex(value) {
    var text = (value & 0xff).toString(16);
    return text.length === 1 ? '0' + text : text;
  }

  window.__pocBlob = function (url) {
    fetch(url).then(function (r) {
      return r.text();
    }).then(function (master) {
      var chosen = variants(master, url);
      return fetch(chosen.url).then(function (r) {
        return r.text();
      }).then(function (media) {
        var urls = segmentUrls(media, chosen.url);
        return fetch(urls[0]).then(function (r) {
          return r.arrayBuffer();
        }).then(function (buffer) {
          var track = demux(new Uint8Array(buffer));
          var parts = [initSegment(track, chosen.width, chosen.height)];
          for (var i = 0; i < track.samples.length; i += 25) {
            parts.push(mediaSegment(track, track.samples.slice(i, i + 25), i / 25 + 1,
                track.samples[i].pts));
          }
          var structure = [];
          dump(parts[0], 0, structure);
          log('init: ' + structure.join(' | '));
          var mov = [];
          dump(parts[1], 0, mov);
          log('segment: ' + mov.join(' | '));
          var blob = new Blob(parts, {type: 'video/mp4'});
          var video = document.getElementById('video');
          video.src = URL.createObjectURL(blob);
          video.play().then(function () {
            log('blob playing ' + video.videoWidth + 'x' + video.videoHeight);
          }).catch(function (e) {
            log('blob play rejected ' + e);
          });
          video.addEventListener('error', function () {
            log('blob error ' + (video.error ? video.error.code + ':' + video.error.message : '?'));
          });
          setTimeout(function () {
            log('blob after 3s readyState=' + video.readyState + ' ' + video.videoWidth
                + 'x' + video.videoHeight);
          }, 3000);
        });
      });
    }).catch(function (e) {
      log('blob failed ' + e);
    });
  };

  // Live path: TS demux into separate H264/AAC fragmented MP4 SourceBuffers.
  // Both tracks retain their source clock relative to one shared epoch.
  function unwrap33(value, reference) {
    if (reference == null) return value;
    return value + Math.round((reference - value) / 8589934592) * 8589934592;
  }
  function timestamp(p, at) {
    return (p[at] & 14) * 536870912 + p[at + 1] * 4194304
      + (p[at + 2] & 254) * 16384 + p[at + 3] * 128 + (p[at + 4] >> 1);
  }
  function liveDemux(ts, clocks, transform) {
    var pmt = -1, vp = -1, ap = -1, pending = {}, video = [], audio = [];
    var sps, pps, audioConfig;
    function flush(pid) {
      if (!pending[pid]) return;
      var p = concat(pending[pid]); delete pending[pid];
      if (p.length < 14 || p[2] !== 1 || !(p[7] & 128)) throw Error('Invalid PES timestamp');
      var length = p[4] * 256 + p[5];
      if (length) p = p.subarray(0, 6 + length);
      var dts = timestamp(p, (p[7] & 64) ? 14 : 9);
      var ref = pid === vp ? clocks.video : clocks.audio;
      if (ref == null) ref = clocks.reference;
      dts = unwrap33(dts, ref);
      var pts = unwrap33(timestamp(p, 9), dts);
      if (clocks.reference == null) clocks.reference = dts;
      var data = p.subarray(9 + p[8]);
      if (pid === vp) {
        clocks.video = dts;
        var body = [], key = false;
        splitNals(data).forEach(function (nal) {
          if (transform) nal = transform(nal);
          if (!(nal instanceof Uint8Array) || !nal.length) throw Error('Invalid transformed H264 NAL');
          var type = nal[0] & 31;
          if (type === 7 && (nal[2] & 3)) throw Error('Marked CCTV source requires its official adapter');
          if (type === 7) sps = nal;
          else if (type === 8) pps = nal;
          else if (type === 1 || type === 5 || type === 6) {
            body.push(u32(nal.length), nal); if (type === 5) key = true;
          }
        });
        if (body.length) video.push({data: concat(body), pts: pts, dts: dts, key: key});
      } else {
        clocks.audio = dts;
        var rates = [96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350];
        for (var at = 0, index = 0; at < data.length; index++) {
          if (at + 7 > data.length || data[at] !== 255 || (data[at + 1] & 246) !== 240)
            throw Error('Unsupported or incomplete ADTS frame');
          var profile = (data[at + 2] >> 6) + 1, frequency = (data[at + 2] >> 2) & 15;
          var channels = ((data[at + 2] & 1) << 2) | (data[at + 3] >> 6);
          var size = ((data[at + 3] & 3) << 11) | (data[at + 4] << 3) | (data[at + 5] >> 5);
          var header = (data[at + 1] & 1) ? 7 : 9;
          if (profile !== 2 || !rates[frequency] || !channels || (data[at + 6] & 3)
              || size <= header || at + size > data.length) throw Error('Unsupported AAC configuration');
          audioConfig = {rate: rates[frequency], channels: channels,
            asc: bytes([(profile << 3) | (frequency >> 1), ((frequency & 1) << 7) | (channels << 3)])};
          audio.push({data: data.slice(at + header, at + size), dts: pts + index * 1024 * 90000 / audioConfig.rate,
            pts: pts + index * 1024 * 90000 / audioConfig.rate, duration: 1024, key: true});
          at += size;
        }
      }
    }
    for (var i = 0; i + 188 <= ts.length; i += 188) {
      if (ts[i] !== 71 || (ts[i + 1] & 128)) throw Error('Invalid TS packet');
      var pid = ((ts[i + 1] & 31) << 8) | ts[i + 2];
      var start = !!(ts[i + 1] & 64), adaptation = (ts[i + 3] >> 4) & 3;
      if (adaptation === 0 || adaptation === 2) continue;
      var at = i + 4 + (adaptation === 3 ? 1 + ts[i + 4] : 0);
      if (at >= i + 188) continue;
      var payload = ts.subarray(at, i + 188);
      if (pid === 0 && start) pmt = parsePat(payload);
      else if (pid === pmt && start) {
        var table = payload.subarray(payload[0] + 1);
        var end = 3 + ((table[1] & 15) << 8) + table[2] - 4;
        for (var j = 12 + ((table[10] & 15) << 8) + table[11]; j + 5 <= end; ) {
          var type = table[j], trackPid = ((table[j + 1] & 31) << 8) | table[j + 2];
          if (type === 27) vp = trackPid;
          if (type === 15) ap = trackPid;
          j += 5 + ((table[j + 3] & 15) << 8) + table[j + 4];
        }
      } else if (pid === vp || pid === ap) {
        if (start) { flush(pid); pending[pid] = []; }
        if (pending[pid]) pending[pid].push(payload);
      }
    }
    Object.keys(pending).forEach(function (pid) { flush(Number(pid)); });
    if (!video.length || !audio.length || !sps || !pps || !audioConfig)
      throw Error('Live source requires H264 with SPS/PPS and AAC ADTS');
    for (var k = 0; k < video.length; k++) {
      video[k].duration = k + 1 < video.length ? video[k + 1].dts - video[k].dts
        : (k ? video[k].dts - video[k - 1].dts : 3600);
      if (video[k].duration <= 0) throw Error('Non-monotonic video DTS');
    }
    return {video: video, audio: audio, sps: sps, pps: pps, timescale: 90000, audioConfig: audioConfig};
  }
  function dimensions(sps) {
    var raw=[];
    for(var i=1;i<sps.length;i++) {
      if(i>2&&sps[i]===3&&sps[i-1]===0&&sps[i-2]===0)continue;
      raw.push(sps[i]);
    }
    var bit=0;
    function read(n){var v=0;while(n--){if(bit>=raw.length*8)throw Error('Truncated SPS');v=v*2+((raw[bit>>3]>>(7-(bit&7)))&1);bit++;}return v;}
    function ue(){var z=0;while(!read(1)){if(++z>30)throw Error('Invalid SPS');}return Math.pow(2,z)-1+read(z);}
    function se(){var v=ue();return(v&1)?(v+1)/2:-v/2;}
    var profile=read(8);read(16);ue();var chroma=1,separate=0;
    if([100,110,122,244,44,83,86,118,128,138,139,134,135].indexOf(profile)>=0){
      chroma=ue();if(chroma===3)separate=read(1);ue();ue();read(1);
      if(read(1))for(var k=0;k<(chroma===3?12:8);k++)if(read(1)){
        var last=8,next=8;for(var j=0;j<(k<6?16:64);j++){if(next)next=(last+se()+256)%256;last=next||last;}
      }
    }
    ue();var order=ue();if(order===0)ue();else if(order===1){read(1);se();se();var count=ue();for(var q=0;q<count;q++)se();}
    ue();read(1);var w=(ue()+1)*16,h=ue()+1,frame=read(1);if(!frame)read(1);read(1);
    var left=0,right=0,top=0,bottom=0;if(read(1)){left=ue();right=ue();top=ue();bottom=ue();}
    var format=separate?0:chroma,cx=format===1||format===2?2:1,cy=(format===1?2:1)*(2-frame);
    return {width:w-cx*(left+right),height:h*16*(2-frame)-cy*(top+bottom)};
  }
  function audioInit(config) {
    var desc = function (tag, body) { return concat([bytes([tag, body.length]), body]); };
    var esds = full('esds', 0, 0, desc(3, concat([u16(2), bytes([0]),
      desc(4, concat([bytes([64,21,0,0,0]), u32(0), u32(0), desc(5, config.asc)])), desc(6, bytes([2]))])));
    var entry = box('mp4a', bytes([0,0,0,0,0,0,0,1]), u32(0), u32(0), u16(config.channels),
      u16(16), u16(0), u16(0), u32(config.rate * 65536), esds);
    var stbl = box('stbl', full('stsd',0,0,u32(1),entry), full('stts',0,0,u32(0)),
      full('stsc',0,0,u32(0)),full('stsz',0,0,u32(0),u32(0)),full('stco',0,0,u32(0)));
    var matrix = concat([u32(65536),u32(0),u32(0),u32(0),u32(65536),u32(0),u32(0),u32(0),u32(1073741824)]);
    var tkhd = full('tkhd',0,7,u32(0),u32(0),u32(2),u32(0),u32(0),u32(0),u32(0),u16(0),u16(0),u16(256),u16(0),matrix,u32(0),u32(0));
    var mdia = box('mdia',full('mdhd',0,0,u32(0),u32(0),u32(config.rate),u32(0),u16(0x55c4),u16(0)),
      full('hdlr',0,0,u32(0),bytes([115,111,117,110]),u32(0),u32(0),u32(0),bytes([0])),
      box('minf',full('smhd',0,0,u16(0),u16(0)),box('dinf',full('dref',0,0,u32(1),full('url ',0,1))),stbl));
    var mvhd = full('mvhd',0,0,u32(0),u32(0),u32(1000),u32(0),u32(65536),u16(256),u16(0),u32(0),u32(0),matrix,
      u32(0),u32(0),u32(0),u32(0),u32(0),u32(0),u32(3));
    return concat([box('ftyp',bytes([105,115,111,109]),u32(512),bytes([105,115,111,109,105,115,111,54,109,112,52,49])),
      box('moov',mvhd,box('trak',tkhd,mdia),box('mvex',full('trex',0,0,u32(2),u32(1),u32(0),u32(0),u32(0))))]);
  }
  function liveFragment(samples, id, sequence, epoch, rate) {
    var base = Math.round((samples[0].dts - epoch) * rate / 90000);
    if (base < 0) throw Error('Negative decode timestamp');
    function moof(offset) {
      var entries = [u32(samples.length),u32(offset)];
      samples.forEach(function (s) { entries.push(u32(s.duration),u32(s.data.length),
        u32(s.key ? 0x02000000 : 0x01010000),u32(Math.round((s.pts - s.dts) * rate / 90000))); });
      return box('moof',full('mfhd',0,0,u32(sequence)),box('traf',full('tfhd',0,0x020000,u32(id)),
        full('tfdt',1,0,u32(Math.floor(base / 4294967296)),u32(base)),full('trun',1,0x000f01,concat(entries))));
    }
    var measure = moof(0);
    return concat([moof(measure.length + 8),box('mdat',concat(samples.map(function(s){return s.data;})))]);
  }

  window.__liveRun = function (master, adapter, options) {
  const mark = (name, details) => {
    if (window.__liveTvMeasure !== true) return;
    const timing = window.__liveTvTiming || (window.__liveTvTiming = {});
    if (!Object.prototype.hasOwnProperty.call(timing,name)) timing[name] = {at:performance.now(), ...(details || {})};
  };
    const measuring = window.__liveTvMeasure === true;
    if (window.__liveStop) window.__liveStop();
    var video = document.querySelector('video[data-livetv-player], video#livetv-video, video#video') || document.querySelector('video');
    if (!video) {
      if (adapter) adapter.close();
      throw Error('Live video element is missing');
    }
    var controller = new AbortController();
    var live = window.__live = {state:'starting',paused:true,userPaused:window.__liveUserPaused===true,ready:0,width:0,height:0,frames:0,time:0,t:0,
      audioBytes:0,error:null,blocked:false,buffered:0,segments:0};
    var stopped = false, timer, observer, frameId, objectUrl, ms, buffers, epoch, sequence = 1;
    var clocks = {}, seen = new Set(), configuration, joined=false;
    var pauseRevision=0, pendingPlayback=null, cycleRunning=false, resumeCycle=null, resumeJoin=false, requests=new Set();
    function userPaused(){return window.__liveUserPaused===true;}
    function shouldMute(){return userPaused()||window.__liveBackgroundMuted===true||window.__liveAudible!==true;}
    function enforcePause(){
      if(shouldMute()&&!video.muted)video.muted=true;
      if(userPaused()&&!video.paused)video.pause();
    }
    // Both AAC and video are rendered by this element. Guard even a cached
    // controller's autoplay or volume event while keeping MSE and the frame alive.
    if(!video.__livePauseGuard) {
      var originalPlay=video.play;
      video.play=function(){
        enforcePause();
        if(userPaused())return Promise.resolve();
        return originalPlay.apply(this,arguments);
      };
      ['play','playing','volumechange'].forEach(function(event){video.addEventListener(event,enforcePause);});
      video.__livePauseGuard=enforcePause;
    }
    function requestPlayback(){
      if(stopped||userPaused()||live.blocked)return Promise.resolve(false);
      if(pendingPlayback)return pendingPlayback;
      var revision=pauseRevision, attempt;
      mark('playRequest');
      try {attempt=video.play();} catch(error) {attempt=Promise.reject(error);}
      var pending=Promise.resolve(attempt).then(function(){
        if(stopped||userPaused()||revision!==pauseRevision){enforcePause();return false;}
        mark('playResolved');return true;
      },function(error){
        // pause() rejects an outstanding play() with AbortError. It is an
        // intentional interruption, not a blocked or failed stream.
        if(stopped||userPaused()||revision!==pauseRevision)return false;
        live.blocked=true;live.error=String(error);live.state='blocked';return false;
      }).then(function(value){if(pendingPlayback===pending)pendingPlayback=null;return value;});
      pendingPlayback=pending;return pending;
    }
    function setPaused(paused){
      if(stopped||window.__live!==live)return false;
      var changed=live.userPaused!==(paused===true);
      window.__liveUserPaused=live.userPaused=paused===true;
      if(changed){pauseRevision++;pendingPlayback=null;}
      if(userPaused()) {
        resumeJoin=true;clearTimeout(timer);enforcePause();live.state='paused';
        requests.forEach(function(request){request.abort();});
      } else if(changed) {
        live.blocked=false;live.error=null;live.state='buffering';
        if(joined&&video.buffered.length)requestPlayback();
        if(resumeCycle&&!cycleRunning){clearTimeout(timer);timer=setTimeout(resumeCycle,0);}
      }
      sampleProgress();return true;
    }
    window.__liveSetPaused=setPaused;
    function setBackgroundMuted(muted){
      if(stopped||window.__live!==live||window.__liveSetBackgroundMuted!==setBackgroundMuted)return false;
      // Keep the first-frame acknowledgement and user pause intent independent of app navigation.
      window.__liveBackgroundMuted=muted===true;
      enforcePause();video.muted=shouldMute();return true;
    }
    window.__liveSetBackgroundMuted=setBackgroundMuted;
    function stop() {
      if (stopped) return;
      stopped = true; live.paused=true; live.ready=0; live.layout=false;
      if(!live.error)live.state="stopped";
      controller.abort(); clearTimeout(timer); clearInterval(observer);
      if (frameId && video.cancelVideoFrameCallback) video.cancelVideoFrameCallback(frameId);
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      video.pause(); video.removeAttribute('src'); video.load();
      if(window.__liveSetPaused===setPaused)window.__liveSetPaused=null;
      if(window.__liveSetBackgroundMuted===setBackgroundMuted)window.__liveSetBackgroundMuted=null;
      window.removeEventListener('pagehide',stop);
      if (adapter) { var ownedAdapter=adapter; adapter=null; ownedAdapter.close(); }
    }
    window.__liveStop = stop; window.addEventListener('pagehide',stop);
    function fail(e) {
      if (stopped) return;
      live.error = String(e.message || e); live.state = 'error'; stop();
    }
    // Own the pending converter immediately, including failure and late cancellation.
    var adapterReady = options && options.adapterReady ? Promise.resolve(options.adapterReady).then(function(value) {
      if(stopped) { if(value)value.close(); return; }
      if(!value || typeof value.transform!=='function') { if(value && value.close)value.close(); fail(Error('Converter unavailable')); return; }
      adapter=value;
    },function(error){fail(error);}) : null;
    async function request(url,body) {
      // Suspend only network work on pause. The session controller and MSE
      // buffers stay alive, so resuming never waits on an obsolete TS download.
      var operation=new AbortController(), abort=function(){operation.abort();};
      requests.add(operation);controller.signal.addEventListener('abort',abort);
      try {
        var r=await fetch(url,{signal:operation.signal});
        if(!r.ok)throw Error('HTTP '+r.status);
        return await r[body]();
      } finally {requests.delete(operation);controller.signal.removeEventListener('abort',abort);}
    }
    function update(sb, action) {
      return new Promise(function(resolve,reject) {
        function cleanup(){sb.removeEventListener('updateend',done);sb.removeEventListener('error',bad);controller.signal.removeEventListener('abort',bad);}
        function done(){cleanup();resolve();}
        function bad(){cleanup();reject(Error('SourceBuffer aborted or failed'));}
        sb.addEventListener('updateend',done);sb.addEventListener('error',bad);controller.signal.addEventListener('abort',bad);
        try {action();} catch(e){cleanup();reject(e);}
      });
    }
    function sampleProgress() {
      if(stopped)return;
      enforcePause();video.muted=shouldMute();
      live.userPaused=userPaused();
      live.audioBytes=video.webkitAudioDecodedByteCount||0;
      live.paused=video.paused;live.ready=video.readyState;live.width=video.videoWidth;live.height=video.videoHeight;
      var rect=video.getBoundingClientRect(),dw=document.documentElement.clientWidth,dh=document.documentElement.clientHeight;
      live.layout=Math.abs(rect.left)<2&&Math.abs(rect.top)<2&&Math.abs(rect.width-dw)<3&&Math.abs(rect.height-dh)<3;
      live.time=live.t=video.currentTime;live.buffered=video.buffered.length?video.buffered.end(video.buffered.length-1):0;
      if(video.error&&!userPaused()){fail(Error('Video decode '+video.error.code));return;}
      live.state=userPaused()?'paused':live.blocked?'blocked':(!video.paused&&live.frames>1?'playing':'buffering');
    }
    function onFrame() {
      if(stopped)return;
      if(userPaused()){
        sampleProgress();frameId=video.requestVideoFrameCallback(onFrame);return;
      }
      live.frames++;
      if(live.frames===1)mark('firstVideoFrame');
      if(window.__liveAudible!==true) {
        sampleProgress();
        // This only wakes the native checker. It still independently samples the
        // current token/channel, successive frames, audio and geometry before reveal.
        if(!stopped && live.ready>=3 && !live.paused && live.audioBytes>0 && live.layout &&
            live.token!=null) {
          try {
            if(window.SmartTvPlayback && typeof window.SmartTvPlayback.onProgress==='function')window.SmartTvPlayback.onProgress(String(live.token));
            else console.log('SMARTTV_FRAME:'+live.token);
            mark('firstProgressSignal');
          }
          catch(_) { /* The periodic native check remains the fallback. */ }
        }
      }
      if(!stopped)frameId=video.requestVideoFrameCallback(onFrame);
    }
    if(video.requestVideoFrameCallback)frameId=video.requestVideoFrameCallback(onFrame);
    observer=setInterval(sampleProgress,200);
    async function start() {
      if(!window.MediaSource)throw Error('MediaSource unavailable');
      ms=new MediaSource();objectUrl=URL.createObjectURL(ms);video.muted=true;
      var opened=new Promise(function(resolve){ms.addEventListener('sourceopen',resolve,{once:true});});
      video.src=objectUrl;await opened;if(stopped)return;mark('mediaSourceOpen');
      mark('masterRequest');
      var text=await request(master,'text');if(stopped)return;
      mark('masterBodyEnd');
      if(/#EXT-X-KEY:(?!METHOD=NONE)/.test(text))throw Error('Encrypted HLS unsupported');
      var media=variants(text,master).url;
      async function cycle() {
        if(stopped||userPaused()||cycleRunning)return;
        cycleRunning=true;
        var revision=pauseRevision, delay=2000, complete=false;
        function interrupted(){return stopped||userPaused()||revision!==pauseRevision;}
        try {
          mark('playlistRequest');
          var listing=await request(media,'text');if(interrupted())return;
          mark('playlistBodyEnd');
          if(/#EXT-X-KEY:(?!METHOD=NONE)/.test(listing)||/#EXT-X-MAP:/.test(listing))throw Error('Unsupported encrypted or non-TS HLS');
          if(/#EXT-X-DISCONTINUITY(?:\r?\n)/.test(listing))throw Error('HLS discontinuity requires fallback');
          var resuming=resumeJoin;resumeJoin=false;
          var urls=segmentUrls(listing,media), wanted=epoch==null||resuming?urls.slice(-2):urls;
          if(epoch==null)urls.slice(0,-2).forEach(function(u){seen.add(u);});
          for(var u of wanted) {
            if(interrupted())return;
            if(seen.has(u))continue;
            mark('firstTsRequest');
            var tsBytes=new Uint8Array(await request(u,'arrayBuffer'));
            if(interrupted())return;
            mark('firstTsBodyEnd',{bytes:tsBytes.byteLength});
            if(adapterReady)await adapterReady;
            if(interrupted())return;
            var demuxStarted=measuring && live.segments===0 ? performance.now() : 0;
            if(adapter && adapter.beginSegment)adapter.beginSegment();
            var track=liveDemux(tsBytes,clocks,adapter?function(nal){return adapter.transform(nal);}:null);
            if(demuxStarted)mark('firstDemuxEnd',{durationMs:performance.now()-demuxStarted,includesNalConversion:!!adapter});
            var config=Array.from(track.sps)+':'+Array.from(track.pps)+':'+Array.from(track.audioConfig.asc);
            if(configuration && config!==configuration)throw Error('Codec configuration changed');
            if(!buffers) {
              configuration=config;
              var codec='avc1.'+[track.sps[1],track.sps[2],track.sps[3]].map(function(x){return x.toString(16).padStart(2,'0');}).join('');
              var vm='video/mp4; codecs="'+codec+'"',am='audio/mp4; codecs="mp4a.40.2"';
              live.codecs={video:vm,audio:am,videoSupported:MediaSource.isTypeSupported(vm),audioSupported:MediaSource.isTypeSupported(am)};
              if(!live.codecs.videoSupported||!live.codecs.audioSupported)throw Error('Unsupported MSE codecs '+codec);
              buffers=[ms.addSourceBuffer(vm),ms.addSourceBuffer(am)];
              epoch=Math.min(track.video[0].dts,track.audio[0].dts);
              var size=dimensions(track.sps);
              mark('initAppendStart');
              await update(buffers[0],function(){buffers[0].appendBuffer(initSegment(track,size.width,size.height));});
              mark('videoInitAppendEnd');
              await update(buffers[1],function(){buffers[1].appendBuffer(audioInit(track.audioConfig));});
              mark('audioInitAppendEnd');
            }
            var fragmentStarted=measuring && live.segments===0 ? performance.now() : 0;
            var vf=liveFragment(track.video,1,sequence,epoch,90000);
            var af=liveFragment(track.audio,2,sequence++,epoch,track.audioConfig.rate);
            if(fragmentStarted)mark('firstFragmentsBuilt',{durationMs:performance.now()-fragmentStarted});
            await update(buffers[0],function(){buffers[0].appendBuffer(vf);});
            mark('firstVideoAppendEnd');
            await update(buffers[1],function(){buffers[1].appendBuffer(af);});
            mark('firstAudioAppendEnd');
            live.audioAppendedBytes=(live.audioAppendedBytes||0)+track.audio.reduce(function(n,s){return n+s.data.length;},0);seen.add(u);live.segments++;
            if(interrupted())return;
            // B-frame presentation offsets can leave a small initial gap at zero.
            // Start at the first common audio/video range, never at an unbuffered time.
            if(!joined && video.buffered.length) {
              video.currentTime=video.buffered.start(0);
              joined=true;
            }
            if(resuming && video.buffered.length) {
              var range=video.buffered.length-1, begin=video.buffered.start(range), end=video.buffered.end(range);
              if(video.currentTime<begin||video.currentTime>=end||end-video.currentTime>15)
                video.currentTime=Math.max(begin,end-3);
              resuming=false;
            }
            if(video.paused&&!live.blocked&&!userPaused()) {
              // Give the first buffered video time to start before synchronously
              // converting the next TS segment on the same WebView thread.
              if(!await requestPlayback()||interrupted())return;
              // Native reveal uses current frame/audio progress. Keep this thread
              // free until its acknowledgement, instead of delaying it with TS #2.
              if(options && options.waitForReveal) {
                while(!interrupted() && window.__liveAudible!==true)
                  await new Promise(function(resolve){setTimeout(resolve,25);});
                if(interrupted())return;
              }
            }
            for(var sb of buffers)if(video.currentTime>20)await update(sb,function(){sb.remove(0,video.currentTime-15);});
          }
          // Bound deduplication memory to the current sliding playlist.
          seen=new Set(urls.filter(function(u){return seen.has(u);}));
          if(/#EXT-X-ENDLIST/.test(listing)){ms.endOfStream();complete=true;return;}
          var duration=/#EXT-X-TARGETDURATION:(\d+)/.exec(listing);
          delay=Math.max(500,Math.min(3000,(duration?Number(duration[1]):4)*500));
        }catch(e){if(!interrupted())fail(e);}
        finally {
          cycleRunning=false;
          if(!stopped&&!userPaused()&&!live.blocked&&!complete) {
            clearTimeout(timer);timer=setTimeout(cycle,revision!==pauseRevision?0:delay);
          }
        }
      }
      resumeCycle=cycle;
      await cycle();
    }
    start().catch(fail);
  };

  window.__pocRun = function (url) {
    status('fetching master');
    log('master ' + url);
    fetch(url).then(function (r) {
      return r.text();
    }).then(function (master) {
      var chosen = variants(master, url);
      log('variant ' + chosen.url + ' ' + chosen.width + 'x' + chosen.height);
      return fetch(chosen.url).then(function (r) {
        return r.text();
      }).then(function (media) {
        var urls = segmentUrls(media, chosen.url);
        log(urls.length + ' segments in the playlist');
        if (!urls.length) throw new Error('no segments');
        return fetch(urls[0]).then(function (r) {
          return r.arrayBuffer();
        }).then(function (buffer) {
          var data = new Uint8Array(buffer);
          log('segment ' + data.length + ' bytes');
          var track = demux(data);
          log('demuxed ' + track.samples.length + ' samples, sps=' + !!track.sps + ' pps=' + !!track.pps);
          if (!track.sps || !track.pps || !track.samples.length) throw new Error('nothing usable');
          play(track, chosen.width, chosen.height);
        });
      });
    }).catch(function (e) {
      state.error = String(e);
      log('failed ' + e);
      status('failed ' + e);
    });
  };

  log('ready');
})();
