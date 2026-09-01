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

- Formatter inputs are restricted to source trees and real Gradle scripts so
  parallel Android build output cannot cause transient formatting failures.

### Security

- Android backup is disabled while encrypted connection/session state awaits a
  final backup and device-transfer threat-model review.
- Environment-specific connection, host, path, session, model, proxy, and
  identity data were removed before private GitHub publication.
- Published history is scanned for secrets and uses the GitHub no-reply author
  identity.

### Known limitations

- The current session hub is an early read-only shell. Profile editing, full
  provider session actions, timeline composition, approvals, and artifacts are
  not yet exposed.
- Offline speech, background recovery/notifications, and secure file transfer
  are not yet wired into the application.
- Deterministic screenshot evidence, manual TalkBack/keyboard release audits,
  and production release signing remain.
