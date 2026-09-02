# Offline speech

## Status

Agent Relay has a pure Kotlin `:speech:api` contract and a tested
`:speech:android` model store plus hardened network and archive delivery
adapters, a generation-safe operation coordinator, and production Android PCM
microphone capture and playback boundaries. `:speech:sherpa` adds a pinned,
source-built, TTS-free sherpa-onnx online-recognition adapter. The app does not
yet ship a production model catalog, compose that adapter into its lifecycle,
or expose speech controls. No voice control should be usable until those pieces
and representative device evidence are complete.

The contract keeps speech independent from connection and agent providers.
Android model storage, sherpa inference, and future Compose layers depend on
this boundary rather than adding microphone or native-inference concerns to
session state. Package, audio, and inference adapters remain injected so
deterministic tests do not require a microphone, network, or native model.

## Contract

The foundation defines:

- stable model and operation identifiers;
- transcription and synthesis capabilities;
- model language, version, license name, optional SPDX identifier, HTTPS source,
  SHA-256 checksum, download size, and installed size;
- not-installed, downloading, ready, and redacted failure states;
- listening, transcribing, result, synthesis, playback, and failure states;
- explicit install, cancel-install, remove, start/stop/cancel listening, and
  start/stop playback operations; and
- operation ids on every active or completed action.

Operation ids are a lifecycle boundary. A stop or cancel request must name the
operation it observed. Implementations must ignore an obsolete request rather
than stopping a newer capture or playback generation.

## Implemented Android foundation

`:speech:android` now provides:

- app-private no-backup model storage with catalog-order state;
- injected package download and archive-decoder boundaries;
- a bounded HTTPS downloader that rejects credentials, fragments, non-public
  host syntax, non-default ports, downgrade redirects, redirect loops, and
  cross-host redirects outside an exact reviewed allowlist;
- exact response-length checks when metadata is present, manual redirect
  handling, finite connect/read timeouts, cancellation checks around blocking
  reads, and redacted delivery failures;
- a tar.bz2 decoder that accepts only checksum-valid, stream-contiguous regular
  files and zero-size directories, and rejects malformed paths, links, sparse
  files, devices, pipes, bad headers, and unknown entry kinds;
- a path-confined extraction sink that exposes no destination directory, accepts
  only bounded relative regular-file/directory entries, and rejects traversal,
  ambiguous paths, duplicates, and oversized payloads;
- declared storage-capacity and exact compressed-size checks before extraction;
- SHA-256 verification before extraction and a ready marker written before
  version/checksum-specific directory activation;
- atomic directory moves where supported, safe fallback moves, obsolete-version
  cleanup, crash-stale staging cleanup, cancellation, and removal;
- restart and resolution checks that reject a marker without a safe non-empty
  payload;
- bounded signed-16-bit PCM plus generation-scoped capture, inference,
  synthesis, cancellation, and playback interfaces;
- permission-gated 16 kHz mono `AudioRecord` capture that requests no broad
  storage access and retains no raw microphone audio;
- streaming PCM `AudioTrack` playback with transient speech audio focus,
  pause-on-duck interruption handling, private output-capture policy where
  supported, and operation-scoped cleanup; and
- a service coordinator that resolves only ready capability-compatible models,
  exposes explicit listening/transcribing/result and synthesis/playing states,
  detaches canceled operation ids before dependency cleanup, rejects stale
  stop/cancel requests, prevents late callbacks from replacing a newer
  generation, stops active pipelines before model removal, and maps adapter
  failures to stable redacted guidance.

The store still receives no model until a caller supplies a catalog descriptor
that passed the admission gate below. The built-in downloader and decoder are
concrete production boundaries, but no production source or redirect host is
admitted by default. Alternative implementations remain injected and require
the same review; the extraction interface cannot create a link or special-file
entry.

## Remaining implementation boundaries

The production speech path must still add:

- composition for the first licensed model and its exact reviewed hosts;
- resumable network progress and durable download restoration;
- application composition of the sherpa adapter for an admitted model;
- application-layer permission request, rationale, denial, and continuous
  capture indication;
- foreground/lifecycle composition and interruption diagnostics; and
- representative physical-device capture, routing, privacy, and latency evidence.

`:speech:sherpa` now owns the native/Kotlin sherpa-onnx adapter. It:

- accepts only a bounded streaming-transducer model layout under one canonical
  installed-model directory;
- maps the model to the upstream online recognizer without a runtime network
  client or broad filesystem access;
- normalizes signed 16-bit PCM, bounds decode work and transcript length, and
  fences stale cancellation, close, and callback generations;
- verifies pinned sherpa-onnx source and ONNX Runtime downloads before use;
- relies on the hash declarations inside that verified source for every
  transitive CMake archive;
- builds and validates `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` with
  pinned NDK, CMake, and Ninja versions; and
- packages exact dependency licenses, ONNX Runtime notices, build provenance,
  and a license hash manifest inside the AAR's `classes.jar`.

The build disables TTS, speaker diarization, executables, C API, WebSocket,
Python, PortAudio, vendor accelerators, GPU, and DirectML. See
[Third-party runtime notices](THIRD_PARTY.md) for the audit boundary.

The application layer will own:

- a discoverable model-management surface with exact size and license details;
- an explicit microphone button, visible listening/transcribing state, and
  stop/cancel controls;
- insertion of recognized text into the encrypted session draft for review,
  never automatic submission;
- an explicit speak/stop control for supported transcript entries;
- actionable offline, permission, storage, model, and audio-focus errors; and
- TalkBack, keyboard, large-text, compact/expanded, and process-restoration
  evidence.

## Privacy and safety requirements

- Request `RECORD_AUDIO` only after the user invokes voice input, and show a
  continuous in-app capture indication.
- Keep recognition and synthesis on-device after an explicitly requested model
  download.
- Do not persist raw microphone audio by default. Temporary audio must live in
  app-specific cache and be removed after success, cancellation, or failure.
- Admit only public HTTPS model hosts. Follow redirects manually and only to the
  original host or an exact catalog-reviewed host; never forward credentials or
  downgrade transport.
- Decode archives only through the confined sink. Reject absolute, ambiguous,
  traversal, control-character, link, sparse, device, pipe, malformed, and
  unknown entries before materializing them.
- Never log audio, recognized text, synthesized text, model paths, or exception
  payloads that can reveal private session content.
- Verify the declared SHA-256 digest before activating a downloaded package.
- Do not activate a partially extracted or unlicensed model.
- Query available storage before a download and keep partial cleanup
  best-effort but observable.
- Stop capture when the app loses its valid foreground microphone context.
- Treat recognition as draft preparation. The user reviews and submits it
  through the existing provider-neutral composer.

## Model admission gate

No model belongs in the production catalog until its exact release asset has:

1. a stable HTTPS source without embedded credentials;
2. an independently computed SHA-256 checksum;
3. measured compressed and installed sizes for every shipped ABI/configuration;
4. language and transcription/synthesis capability metadata;
5. model, dataset, and voice license review with a distributable notice; and
6. representative real-device latency, memory, accuracy, and cancellation
   evidence.

The first catalog should be deliberately small. Multilingual coverage does not
justify a very large default download, and a model must never be bundled merely
because an upstream demo uses it.

## Verification plan

- Current pure contract tests reject unstable ids, malformed language tags,
  unsafe sources, bad checksums, invalid sizes/progress, oversized text, and
  unredacted failure codes.
- Current model-store tests cover exact activation/restoration/removal, crash
  staging cleanup, missing ready payload, truncated/oversized downloads, digest
  mismatch, archive traversal, installed-size limits, no-space failures,
  cancellation, and checksum-version replacement. PCM/model/synthesis bounds
  are also tested.
- Current delivery tests use injected HTTP connections and real tar.bz2
  fixtures to cover timeouts, response metadata, same/cross-host redirects,
  downgrade/loop/private-host rejection, redacted I/O failures, cancellation
  after a blocking read, path traversal, link/sparse/special entries, malformed
  archives, successful decoding, and store-level failure propagation.
- Current service-coordinator tests cover reviewed transcription results,
  unavailable models and invalid playback text, stale stop ids,
  non-cooperative late recognition callbacks, synthesis/playback transitions,
  operation-scoped cancellation, active-model removal ordering, and redacted
  inference failures.
- Current Android audio tests cover permission denial before microphone access,
  owned PCM frames, stale and uncollected capture generations, partial output
  writes, empty streams, denied focus, format changes, interruption, late focus
  callbacks, replacement generations, and idempotent resource cleanup without
  requiring real audio hardware.
- Sherpa adapter tests use a fake JNI bridge and temporary bounded model layouts;
  they cover PCM normalization, endpoint/final transcripts, cleanup, path
  confinement, capability/catalog rejection, stale/current cancellation, close
  fencing, and decode bounds without network or a real model.
- The native build verifies exact inputs and packaged files, license hashes,
  expected ABIs and ELF dependencies, RELRO/NOW, non-executable stacks, no text
  relocations, 16 KiB LOAD alignment, required online JNI symbols, size bounds,
  deterministic build IDs, and absence of private build paths or TTS markers.
- Compose tests cover permission rationale/denial, no-model, download progress,
  ready, listening, transcribing, result-review, playback, interruption, and
  failure states.
- Emulator CI uses fake audio and fake inference because the Android emulator
  cannot provide representative microphone evidence. Release audits use real
  hardware for capture quality, permission, privacy indicators, routing,
  interruption, TalkBack, and background transitions.

## Primary references

- [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/)
- [ONNX Runtime Android](https://onnxruntime.ai/docs/build/android.html)
- [Agent Relay third-party runtime notices](THIRD_PARTY.md)
- [Java HttpURLConnection](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/HttpURLConnection.html)
- [sherpa-onnx Android guide](https://k2-fsa.github.io/sherpa/onnx/android/index.html)
- [sherpa-onnx Android build and packaging](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html)
- [Android AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)
- [Android AudioTrack](https://developer.android.com/reference/android/media/AudioTrack)
- [Android AudioAttributes](https://developer.android.com/reference/android/media/AudioAttributes)
- [Android audio focus](https://developer.android.com/media/optimize/audio-focus)
- [Android permission guidance](https://developer.android.com/guide/topics/permissions/overview)
- [Android sensitive-permission guidance](https://developer.android.com/training/permissions/explaining-access)
- [Android app-specific storage](https://developer.android.com/training/data-storage/app-specific)
