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
- Adaptive Compose session hub with connection lifecycle and SSH identity
  decisions, provider-scoped sessions, unread state, and bounded cached
  transcript detail.
- Semantic critical-flow UI tests and API 34+ automated accessibility checks,
  with API 36 phone pull-request CI and a scheduled minimum-API/tablet matrix.
- Android verification workflow and Dependabot configuration.
- Build, install, usage, architecture, contribution, security, and release
  documentation.
- Strict Kotlin warning, Detekt, Android lint, and dependency-declaration gates.
- Aggregate Kover coverage verification with a 70% line floor.
- Kotest property checks and bounded Jazzer fuzzing for POSIX command encoding.

### Changed

- Editing an SSH profile keeps stored secrets unless an explicit replacement or
  removal is selected, disconnects only after a valid save, and cleans up
  superseded credentials or agent keys.
- Formatter inputs are restricted to source trees and real Gradle scripts so
  parallel Android build output cannot cause transient formatting failures.
- UI CI now verifies completed emulator boot and independently requires clean
  multi-module JUnit evidence, uploads reports from every connected-test module,
  and retains Gradle's problems report instead of relying on failure-looking log
  text or an app-only artifact.

### Security

- Android backup is disabled while encrypted connection/session state awaits a
  final backup and device-transfer threat-model review.
- Environment-specific connection, host, path, session, model, proxy, and
  identity data were removed before private GitHub publication.
- Published history is scanned for secrets and uses the GitHub no-reply author
  identity.

### Known limitations

- The current session hub is an early control surface. Full
  provider session actions, timeline composition, approvals, and artifacts are
  not yet exposed.
- Offline speech, background recovery/notifications, and secure file transfer
  are not yet wired into the application.
- Deterministic screenshot evidence, manual TalkBack/keyboard release audits,
  and production release signing remain.
