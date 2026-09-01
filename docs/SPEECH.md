# Offline speech

## Status

Agent Relay has a pure Kotlin `:speech:api` foundation. It does not yet request
microphone permission, download a model, capture audio, transcribe speech, or
play synthesized audio. No voice control should be presented as usable until
the Android and inference implementations plus device evidence are complete.

The contract keeps speech independent from connection and agent providers.
Future Android, sherpa-onnx, model-storage, and Compose layers depend on this
boundary rather than adding microphone or native-inference concerns to session
state.

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

## Planned implementation boundaries

`:speech:android` will own:

- foreground-only microphone permission and `AudioRecord` capture;
- app-specific model and temporary-audio storage;
- storage-capacity checks, resumable progress, cancellation, and cleanup;
- checksum verification before atomic package activation;
- path-confined archive extraction that rejects links and traversal;
- audio focus, playback routing, and interruption handling; and
- Android lifecycle and permission-denial diagnostics.

`:speech:sherpa` will own the pinned native/Kotlin sherpa-onnx adapter and map
only verified installed model directories into recognizer or synthesizer
configuration. It must expose no network client. Native artifacts, build source,
ABI coverage, notices, and licenses require the same dependency audit as other
release inputs.

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

- Pure contract tests reject unstable ids, malformed language tags, unsafe
  sources, bad checksums, invalid sizes/progress, oversized text, and unredacted
  failure codes.
- Model-store tests cover truncated downloads, digest mismatch, archive
  traversal, links, no-space failures, cancellation, replacement, and cleanup.
- Reducer/service tests inject late callbacks to prove operation generations
  cannot affect replacements.
- Inference adapter tests use small licensed fixtures and never require network
  access.
- Compose tests cover permission rationale/denial, no-model, download progress,
  ready, listening, transcribing, result-review, playback, interruption, and
  failure states.
- Emulator CI uses fake audio and fake inference because the Android emulator
  cannot provide representative microphone evidence. Release audits use real
  hardware for capture quality, permission, privacy indicators, routing,
  interruption, TalkBack, and background transitions.

## Primary references

- [sherpa-onnx Android guide](https://k2-fsa.github.io/sherpa/onnx/android/index.html)
- [sherpa-onnx Android build and packaging](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html)
- [Android AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)
- [Android permission guidance](https://developer.android.com/guide/topics/permissions/overview)
- [Android sensitive-permission guidance](https://developer.android.com/training/permissions/explaining-access)
- [Android app-specific storage](https://developer.android.com/training/data-storage/app-specific)
