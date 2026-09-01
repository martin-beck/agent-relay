# Changelog

All notable project changes are recorded here. The project has not made a
supported release.

## Unreleased

### Added

- Generic connection-provider contract with SSH and local implementations.
- Agent adapters for Codex, OpenCode, Continue, Claude, Cline, and Aider.
- Encrypted session hub and provider-neutral runtime coordinator.
- Android verification workflow and Dependabot configuration.
- Build, install, usage, architecture, contribution, security, and release
  documentation.

### Security

- Environment-specific connection, host, path, session, model, proxy, and
  identity data were removed before private GitHub publication.
- Published history is scanned for secrets and uses the GitHub no-reply author
  identity.

### Known limitations

- The Compose application still displays a placeholder screen.
- The runtime and storage foundations are not wired into the application.
- Connected Android Keystore tests and production release signing remain.
