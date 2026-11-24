# Apple TV remote protocol component

This notice applies to the archived development sources in `native/atv/` and the
optional historical build script. Apple TV remote support, its JNI receiver and
dadb are not compiled or packaged in the current Android application. The archive
is retained for source attribution and reproducibility; the sections below
describe that historical component.

Live vendors `crates/atv-core` and `crates/atv-android` from [corvofeng/atv-core](https://github.com/corvofeng/atv-core), tag `v1.0.2`, commit `8a4ada29bb7cad31e0315c3387b277eb9ab8c988`, under `native/atv/`.

Upstream declares MIT in its root Cargo workspace and README. This release has no standalone LICENSE file or explicit copyright notice. The standard MIT terms are reproduced below; attribution remains to corvofeng and the upstream contributors. The protocol code describes its origin as the pyatv `fake_atv.py` reference implementation and the SRP implementation as ported from srptools.

Live changes: Android JNI package, private application identity storage, random private keys and ephemeral key exchanges, authenticated paired-controller verification, a local-only limited pairing window, disabled inspector HTTP endpoint, Android native build script, and security regression tests. No upstream application UI or CLI is bundled.

## MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Rust dependency notices

The exact transitive dependency versions and registry checksums are pinned in `native/atv/Cargo.lock`. Their individual licenses remain applicable; package source license files are available in the Cargo registry cache after running the native build. Major dependencies include Tokio (MIT), mdns-sd (Apache-2.0 OR MIT), ed25519-dalek (BSD-3-Clause), x25519-dalek (BSD-3-Clause), RustCrypto chacha20poly1305/sha2/hkdf (Apache-2.0 OR MIT), jni (Apache-2.0 OR MIT), rand (Apache-2.0 OR MIT), num-bigint/num-traits (Apache-2.0 OR MIT), uuid (Apache-2.0 OR MIT), thiserror/tracing/once_cell (Apache-2.0 OR MIT), and hex (Apache-2.0 OR MIT).

## Local ADB transport

The archived receiver used [dadb](https://github.com/mobile-dev-inc/dadb), Maven artifact `dev.mobile:dadb:1.2.6`, copyright (c) 2021 mobile.dev inc., under the [Apache License 2.0](https://github.com/mobile-dev-inc/dadb/blob/master/LICENSE.txt). Its sources were unmodified. The full Apache License 2.0 text is preserved in `native/atv/DEPENDENCY_LICENSES.txt`; neither dadb nor this receiver is packaged in the current APK.
