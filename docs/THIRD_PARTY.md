# Third-party speech runtime

## Scope

Agent Relay does not redistribute the upstream all-feature sherpa-onnx Android
AAR. `:speech:sherpa` instead builds the JNI runtime from pinned source and
combines it with one pinned ONNX Runtime Android distribution. The application
adapter uses only online recognition. TTS and unrelated native features are
disabled and must remain absent from the packaged artifact.

This document explains the dependency and notice boundary. It is not a
replacement for the complete license files embedded in the generated AAR, and
it does not grant a license to Agent Relay itself.

## Pinned top-level inputs

| Component | Pin | Verified input | Packaged material |
| --- | --- | --- | --- |
| sherpa-onnx | 1.13.4 source at `142807252687d81b40d6315f23470a1512a00de3` | Source archive SHA-256 `f0dc7c9b41b8691313daee671e826eb23946fa1320559a8d37e84f8774af76b2` | Upstream license |
| ONNX Runtime Android | 1.27.0 binaries, binary workflow revision `05ffc5a560d4e74cf6a80e86dfc5800a1474cce1`, source revision `8f0278c77bf44b0cc83c098c6c722b92a36ac4b5` | Archive SHA-256 `a78f303a26b5e75c84c8b2a97fa2ddb400b2d1b5e069bec19aa229ccd3597fdb` plus per-ABI library hashes | License and complete third-party notices |

The build verifies the separately downloaded ONNX Runtime license and notices
before packaging them. It also verifies each ONNX Runtime shared library for
its ABI against a pinned SHA-256 digest.

## Source-build dependencies

The verified sherpa-onnx source pins the following transitive archives by
SHA-256 through CMake `URL_HASH`. Their complete applicable license files are
copied from the fetched source trees only after their own hashes match the
Agent Relay build policy.

| Component | Source pin | Packaged license material |
| --- | --- | --- |
| kaldi-native-fbank | 1.22.3 | `META-INF/licenses/kaldi-native-fbank/LICENSE` |
| kaldi-decoder | 0.3.0 | `META-INF/licenses/kaldi-decoder/LICENSE` |
| kaldifst | 1.8.0 | `META-INF/licenses/kaldifst/LICENSE` |
| kissfft | `febd4caeed32e33ad8b2e0bb5ea77542c40f18ec` | `META-INF/licenses/kissfft/COPYING` |
| OpenFst | 1.8.5-2026-04-11 | `META-INF/licenses/openfst/COPYING` |
| nlohmann JSON | 3.12.0 | `META-INF/licenses/nlohmann-json/LICENSE.MIT` |
| Eigen | 5.0.1 | `LICENSE`, `COPYING.APACHE`, `COPYING.BSD`, `COPYING.MINPACK`, `COPYING.MPL2`, and `COPYING.README` under `META-INF/licenses/eigen/` |
| simple-sentencepiece | 0.7 | `META-INF/licenses/simple-sentencepiece/LICENSE` |

The generated `META-INF/sherpa-onnx/license-sha256.txt` records the exact digest
of every packaged license. `THIRD_PARTY_NOTICES.md` identifies the runtime
boundary, and `runtime-provenance.json` records top-level source, toolchain, ABI,
and native-library hashes. Android packages these Java resources inside the
AAR's `classes.jar`.

## Build and artifact policy

The native task is cacheable but validates a fresh output before publishing it.
It pins Android NDK 28.2.13676358, CMake 3.28.3, Ninja 1.11.1, and the four ABIs
`arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. The expected artifact contains
exactly `libonnxruntime.so` and `libsherpa-onnx-jni.so` for each ABI.

Validation rejects unexpected dynamic dependencies, missing RELRO or immediate
binding, executable stacks, text relocations, LOAD alignment below 16 KiB,
missing online-recognizer JNI symbols, out-of-policy sizes, non-deterministic
build metadata, private build paths, TTS markers, unexpected files, and license
drift. Source and debug paths are remapped, build IDs are deterministic, and
`SOURCE_DATE_EPOCH` comes from the pinned sherpa source revision.

No speech model is bundled or admitted by this runtime work. A model requires
its own checksum, size, host, license/notice, compatibility, and representative
device review described in [Offline speech](SPEECH.md).

## Maintainer checks

```bash
./gradlew :speech:sherpa:buildSherpaAndroidRuntime
./gradlew :speech:sherpa:assembleDebug
```

Inspect `speech/sherpa/build/outputs/aar/sherpa-debug.aar`, then inspect
`classes.jar` inside it for the license and provenance resources above. A clean
second build must match the first output manifest byte for byte before a changed
runtime is accepted.
