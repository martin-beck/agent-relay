# Changelog

All notable project changes are recorded here. The project has not made a
supported release.

## Unreleased

### Added

- Generic connection-provider contract with SSH and local implementations.
- Agent adapters for Codex, OpenCode, Continue, Claude, Cline, and Aider.
- Encrypted session hub and provider-neutral runtime coordinator.
- Android application graph wiring the connection registry, local and SSH
  providers, encrypted session state, coordinator, and agent factories without
  SSH-specific UI dependencies.
- Provider-neutral connection profile management with discoverable add/edit/
  delete controls and generic text, port, secret, choice, conditional, and
  read-only fields.
- Secure Shell profile setup for passwords, imported private keys and
  passphrases, and non-exportable Android Keystore agent keys with public-key
  display.
- Secure Shell jump-host selection backed by saved SSH profiles, bounded
  multi-hop route resolution, strict host-key verification and independent
  credentials for every hop, and JSch direct-tcpip forwarding.
- Persistent per-profile Android Keystore SSH identities, explicit idempotent
  public-key installation, a real key-only login probe, and provider-neutral
  confirmed profile operations in the Compose editor.
- Adaptive Compose session hub with connection lifecycle and SSH identity
  decisions, provider-scoped sessions, unread state, and bounded cached
  transcript detail.
- Provider-aware session interactions with an encrypted per-session draft,
  full-tuple resume/send/steer/interrupt routing, failed-send preservation, and
  typed timeline rendering for user, agent, plan, reasoning, tool, and system
  entries.
- Provider-neutral session creation from ready agent endpoints with optional
  working-directory and model settings.
- Durable approval and question cards with exact execution context,
  provider-offered decisions, complete answer validation, high-risk/session-wide
  confirmation, uncertain-delivery protection, and a redacted resolved audit
  record.
- Semantic critical-flow UI tests and API 34+ automated accessibility checks,
  with API 36 phone pull-request CI and a scheduled minimum-API/tablet matrix.
- External-keyboard composer traversal that consumes Tab and Shift+Tab to move
  focus forward or backward without inserting a tab, with Enter activation
  verified on the focused turn controls.
- Deterministic Compose previews and seven Roborazzi baselines spanning loading,
  fatal error, empty, offline, changed-identity, content, approval, adaptive
  widths, dark theme, large text, and long localized content.
- Android verification workflow and Dependabot configuration.
- Build, install, usage, architecture, contribution, security, and release
  documentation.
- Strict Kotlin warning, Detekt, Android lint, and dependency-declaration gates.
- Reproducible Python, Markdown, YAML, TOML, XML, GitHub configuration,
  spelling, link, workflow-security, repository-hygiene, and secret checks that
  run through the same pinned pre-commit gate locally and in CI.
- Aggregate Kover coverage verification with a 70% line floor.
- Kotest property checks and bounded Jazzer fuzzing for POSIX command encoding.
- Auditable offline-speech contracts plus an Android app-private model store
  with injected package download/extraction, exact byte and checksum checks,
  path-confined extraction, storage-capacity checks, cancellation cleanup,
  version-safe activation, and restart validation.
- Hardened offline-model delivery with bounded HTTPS timeouts, explicit
  same-host or allowlisted redirects, public-DNS source policy, exact response
  metadata checks, cancellation-safe streaming, and a tar.bz2 decoder that
  rejects malformed paths, links, sparse files, devices, pipes, and bad headers.
- Bounded PCM, foreground capture, on-device inference, synthesis, cancellation,
  and audio-playback adapter boundaries for the future Android/sherpa runtime.

- Generation-safe offline-speech coordination for model operations,
  transcription capture and review, synthesis and playback, stale stop/cancel
  rejection, active-model removal, redacted failures, and non-cooperative late
  callbacks.

### Changed

- Editing an SSH profile keeps stored secrets unless an explicit replacement or
  removal is selected, disconnects only after a valid save, and cleans up
  superseded credentials or agent keys.
- Formatter inputs are restricted to source trees and real Gradle scripts so
  parallel Android build output cannot cause transient formatting failures.
- UI CI now verifies completed emulator boot and independently requires clean
  multi-module JUnit evidence, uploads reports from every connected-test module,
  publishes exact counts in the job summary, and retains Gradle's problems
  report instead of relying on failure-looking log text or an app-only artifact.
- Pull-request CI now verifies deterministic Roborazzi images on Linux and
  retains actual/diff evidence for review.

### Security

- Android backup is disabled while encrypted connection/session state awaits a
  final backup and device-transfer threat-model review.
- Environment-specific connection, host, path, session, model, proxy, and
  identity data were removed before private GitHub publication.
- Published history is scanned for secrets and uses the GitHub no-reply author
  identity.

### Known limitations

- The current session hub is an early control surface. Queued/offline input and
  changed-file preview, diff, and batch-export workflows are not yet exposed.
- Voice input and attachments are not yet integrated with the text composer.
- Offline speech and background recovery/notifications are not yet wired into
  the application.
- Manual TalkBack/keyboard release audits and production release signing remain.
