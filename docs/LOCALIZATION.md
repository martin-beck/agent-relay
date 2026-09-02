# Localization

Agent Relay keeps the app interface locale separate from offline speech model
selection. Changing the interface language must never download a speech model,
and installing a speech model must never change the interface language.

## Bundled interface locales

English (United States) is the unqualified fallback. The bundled locale set is
declared once in `config/android-locales.txt`:

- English (United States)
- German
- Simplified Chinese
- Traditional Chinese
- Russian
- Spanish
- Italian
- French
- Brazilian Portuguese
- Hindi
- Arabic
- Bengali
- Indonesian
- Japanese

Android generates the application's locale configuration from these resource
directories. Android 13 and newer can therefore expose Agent Relay in system
per-app language settings. Debug builds also include Android's `en-XA` and
`ar-XB` pseudo-locales to expose expansion, hardcoded copy, and bidirectional
layout defects.

## Resource rules

App-owned interface text, accessibility semantics, validation, errors,
notifications, and operation labels belong in Android resources. User content
and provider-controlled data do not: host names, paths, commands, prompts,
transcripts, model identifiers, and provider output must remain verbatim.

Use positional format arguments and plurals instead of concatenating translated
sentences. Mark only genuine product or protocol constants as
`translatable="false"`.

`scripts/ci/verify_android_locales.py` fails when:

- a declared locale directory or catalog is absent;
- a translated catalog is missing a resource or invents an unknown one;
- a format argument's position or type differs from the English source; or
- the unqualified fallback is not `en-US`.

This structural check does not establish translation quality. Native-speaker
review remains release evidence.

## UI evidence

Each real locale and both pseudo-locales must exercise critical Compose screens
at compact width and enlarged font scale. The deterministic image and semantic
tests must cover at least:

- the connection and session hub;
- profile creation and validation;
- session detail, approval, and question flows;
- artifact saving and progress;
- offline speech installation and recording states;
- notification permission and background-service controls.

Tests must fail for clipped controls, horizontally scrolling app copy, missing
semantics, and inaccessible touch targets. Representative API 36 device tests
supplement deterministic JVM screenshots; they do not replace them.

## Installable speech languages

Interface localization and speech support remain two independent settings. A
user may run the German interface while transcribing Spanish and synthesizing
English. The app must model an installed speech package by BCP-47 language tag,
capability (STT or TTS), model or voice identity, runtime compatibility, and
version. One multilingual model may satisfy several language tags without
duplicating its files.

Agent Relay should manage offline packages itself instead of assuming that an
Android system speech service has installed a language. System STT and TTS may
be offered later as explicit alternative adapters, but their offline behavior,
privacy, language inventory, and install UI vary by device and cannot satisfy
the deterministic offline contract.

The existing speech API, verified downloader, atomic model store, capacity
checks, and resumable package handling cover much of the shared installation
foundation. The remaining production work has these planning estimates for one
experienced Android engineer:

| Slice | Estimated engineering effort | Main evidence |
| --- | ---: | --- |
| App composition, admitted catalog, language/voice picker, install/remove UI, and storage controls | 8-12 days | Hermetic catalog, download, cancellation, restart, low-space, and Compose tests |
| First production STT package and end-to-end recognition lifecycle | 7-12 days | Checksum/license audit, fake-inference CI, and physical-device accuracy/latency checks |
| Each additional STT language or multilingual model admission | 1-3 days plus language review | Corpus-based accuracy, punctuation, cancellation, memory, and device evidence |
| TTS runtime adapter and first production voice | 15-25 days | Native hardening, synthesis/playback lifecycle, license audit, and physical-device listening checks |
| Each additional TTS voice | 1-3 days plus language review | Pronunciation, intelligibility, latency, memory, and device evidence |

The combined STT-and-TTS installation feature is therefore roughly 6-10
engineer-weeks before multilingual review and release qualification. This is a
size estimate, not a delivery promise. TTS is the larger unknown because the
current pinned native build deliberately disables TTS and no production TTS
adapter is composed into the app.

Package size depends on the admitted upstream artifact. Use these conservative
capacity-planning bands, not as claims about a future chosen model:

- STT: roughly 50-500 MB to download and 100 MB-1.5 GB installed per model;
- TTS: roughly 10-250 MB to download and 20-500 MB installed per voice; and
- interface string catalogs: expected to remain well below 1 MB in total.

The production catalog must show exact measured download and installed sizes
before consent. Packages are opt-in, never silently bundled or downloaded, and
must support cancel, resume, atomic activation, removal, version replacement,
metered-network policy, and a bounded storage budget.

Most behavior is highly testable in ordinary CI:

- catalog parsing, compatibility, licenses, checksums, storage accounting,
  install/remove/restart, and error redaction use deterministic unit tests;
- Compose tests exercise selection, progress, cancellation, low-space,
  unavailable-language, and interrupted-install states in every bundled UI
  locale and both pseudo-locales;
- native adapters use small synthetic fixtures in CI and never fetch production
  packages during a pull request; and
- representative physical devices and native speakers remain release gates for
  recognition accuracy, pronunciation, audio routing, latency, memory,
  thermals, and offline behavior.

Adding UI locales is mostly code and deterministic layout work. Adding speech
languages also creates recurring package admission, license, hosting,
bandwidth, storage, acoustic-quality, and supply-chain responsibilities.

## Adding a locale

1. Add its BCP-47 tag and resource directory to
   `config/android-locales.txt`.
2. Add a complete `strings.xml` catalog with matching positional arguments.
3. Run `python scripts/ci/verify_android_locales.py`.
4. Add the locale to deterministic and device UI matrices.
5. Record native-speaker review before treating the translation as
   release-ready.

Offline STT and TTS packages are separately admitted, opt-in downloads. See
`docs/SPEECH.md` for checksum, license, size, compatibility, and
physical-device requirements.
