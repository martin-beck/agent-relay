# Agent Relay

[![Android verification](https://github.com/martin-beck/agent-relay/actions/workflows/verify.yml/badge.svg)](https://github.com/martin-beck/agent-relay/actions/workflows/verify.yml)

Agent Relay is an Android control surface for coding-agent sessions reached
through pluggable connection providers. SSH is one provider, local device access
is another, and future connection types can implement the same boundary.

> [!WARNING]
> Agent Relay is an early development preview. The provider, connection, secure
> storage, session coordination, and first adaptive session-hub UI are
> implemented and tested. SSH profile creation, the composer, approvals,
> artifacts, background delivery, and release hardening are not complete. The
> APK is not yet a supported release.

This is a private, invite-only project. Access to the repository does not grant
permission to redistribute source code, APKs, or project artifacts.

## Current status

| Area | Status |
| --- | --- |
| Generic connection-provider API | Implemented and unit tested |
| Local device connection provider | Implemented and unit tested |
| SSH connection provider | Implemented and unit tested; device Keystore test remains |
| Codex, OpenCode, Continue, Claude, Cline, and Aider adapters | Implemented with contract tests; live checks where available |
| Encrypted session hub and runtime coordinator | Implemented and unit tested |
| Quality gates | Detekt, strict lint/Kotlin warnings, dependency analysis, 70% aggregate coverage, property tests, and bounded fuzzing |
| Adaptive Compose UI and app integration | First provider-neutral connection/session list and transcript/activity detail implemented |
| Signed release build and distribution | Not available |

See the [product roadmap](docs/PRODUCT_ROADMAP.md) for planned behavior and
[architecture guide](docs/ARCHITECTURE.md) for module boundaries.

## Build

Required tools:

- JDK 17;
- Android SDK Platform 36; and
- Git with access to this private repository.

On Linux or macOS:

```bash
git clone https://github.com/martin-beck/agent-relay.git
cd agent-relay
./gradlew spotlessCheck detekt buildHealth test koverXmlReport koverVerify lintDebug assembleDebug
```

On Windows, replace `./gradlew` with `.\gradlew.bat`. The debug APK is
written to `app/build/outputs/apk/debug/app-debug.apk`.

For Android Studio setup, SDK configuration, focused tests, and troubleshooting,
read [Building](docs/BUILDING.md).

## Install and use

A development APK can be installed on Android 9 (API 28) or newer:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The installed app opens the adaptive session hub, automatically exposes the
app-sandboxed local profile, and lists encrypted SSH profiles when present. Read
[Installing](docs/INSTALLING.md) for artifact and device instructions and
[Usage](docs/USAGE.md) for the exact implemented user-facing behavior.

## Design

```text
Connection provider --> RemoteAgentRuntime --> Agent provider
       |                                          |
       +----------- Session coordinator ----------+
                              |
                        Compose application
```

Connection providers own how an execution environment is reached. Agent
providers own how a particular coding agent is discovered and controlled.
Session identity includes the connection provider, connection profile, agent
provider, and agent session, preventing collisions across local and remote
environments.

More detail:

- [Connection providers](docs/CONNECTION_PROVIDERS.md)
- [Provider operations](docs/PROVIDER_OPERATIONS.md)
- [Session hub](docs/SESSION_HUB.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Quality and safety](docs/QUALITY.md)

## Project guidance

- [Contributing](CONTRIBUTING.md)
- [Security](SECURITY.md)
- [Release process](docs/RELEASING.md)
- [Changelog](CHANGELOG.md)

Every functional change must update the user or developer documentation that it
makes inaccurate. Pull requests run formatting, unit and contract tests, Android
lint, static analysis, dependency analysis, aggregate coverage verification, and
debug assembly before merge.

## License and support

No public license or supported release is currently offered. This repository is
private and provided only to invited collaborators for development.
