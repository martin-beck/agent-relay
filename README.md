# Agent Relay

[![Android verification](https://github.com/martin-beck/agent-relay/actions/workflows/verify.yml/badge.svg)](https://github.com/martin-beck/agent-relay/actions/workflows/verify.yml)
[![Android UI verification](https://github.com/martin-beck/agent-relay/actions/workflows/ui.yml/badge.svg)](https://github.com/martin-beck/agent-relay/actions/workflows/ui.yml)

Agent Relay is an Android control surface for coding-agent sessions reached
through pluggable connection providers. SSH is one provider, local device access
is another, and future connection types can implement the same boundary.

> [!WARNING]
> Agent Relay is an early development preview. The provider, connection, secure
> storage, session coordination, adaptive session hub, and capability-gated text
> composer are implemented and tested. Provider-neutral session launch and
> durable, risk-aware approval/question handling are also implemented. The
> changed-file shelf can export one checked workspace file at a time through
> Android's system document picker. SSH profiles can route through configured
> jump hosts, own a persistent non-exportable Android key, install its public
> half with explicit confirmation, and verify key-only login. Previews, diffs,
> batch export, offline speech UI and model composition, model admission,
> real-device speech evidence, and release hardening are not complete.
> Privacy-safe notification delivery, explicit foreground-service-backed
> connection mode, and bounded process-death connection recovery are implemented;
> API 36 sticky-restart/user-stop/force-stop evidence passes, while final live-
> provider and representative physical-device endurance evidence remains.
> A native speech runtime is present but is not yet exposed in the app.
> The APK is not yet a supported release.

This is a private, invite-only project. Access to the repository does not grant
permission to redistribute source code, APKs, or project artifacts.

## Current status

| Area | Status |
| --- | --- |
| Generic connection-provider API | Implemented and unit tested |
| Local device connection provider | Implemented and unit tested |
| SSH connection provider and profile setup | Encrypted credentials, strict per-hop host keys, configured jump routes, persistent Android keys, confirmed public-key installation, and key-only probes implemented; final real-device evidence remains |
| Codex, OpenCode, OpenDesk, Continue, Claude, Cline, and Aider adapters | Implemented with contract tests; live checks where available |
| Encrypted session hub and runtime coordinator | Implemented and unit tested |
| Quality gates | Detekt, Ruff, Radon, Lizard, Vale, strict compiler/lint checks, dependency analysis, 70% coverage, visual regression, property tests, and bounded fuzzing |
| Adaptive Compose UI and app integration | Provider-neutral setup and session launch, typed timeline, durable text composer, capability-gated controls, risk-aware approvals/questions, adaptive-boundary tests, and deterministic UI baselines implemented |
| Changed files and safe export | Encrypted per-session shelf plus checked single-file export for local and SSH workspaces; previews, diffs, and batch export remain |
| Offline speech | Verified model delivery/audio boundaries plus a pinned, source-built, TTS-free sherpa-onnx online-recognition adapter for four Android ABIs; the first admitted model, app composition/UI, and device evidence remain |
| Background operation | Durable privacy-safe alerts plus explicit sticky foreground connection mode and encrypted process-death recovery intent; final provider/device endurance evidence remains |
| Signed release build and distribution | Not available |

Start with the [app workflow catalogue](docs/WORKFLOWS.md) for screenshot-backed
current journeys and clearly labeled planned behavior. See the
[product roadmap](docs/PRODUCT_ROADMAP.md) for delivery order and the
[architecture guide](docs/ARCHITECTURE.md) for module boundaries.

## Build

Required tools:

- JDK 17;
- Android SDK Platform 36;
- Android NDK 28.2.13676358;
- CMake 3.28.3 and Ninja 1.11.1;
- Bash plus standard POSIX build tools;
- Vale 3.19.0 for Markdown terminology and readability checks;
- uv for the locked cross-language repository checks; and
- Git with access to this private repository.

On Linux or macOS:

```bash
git clone https://github.com/martin-beck/agent-relay.git
cd agent-relay
uv sync --locked --only-group quality --only-group docs
uv run pre-commit run --all-files --show-diff-on-failure
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
app-sandboxed local profile, and can create or edit encrypted SSH profiles with
password, imported-key, or Android Keystore authentication. An SSH profile can
select another saved SSH profile as a jump host. Every saved SSH profile owns a
persistent Android Keystore key whose public half remains visible in the editor.
Provider-owned profile actions can install that public key using the saved
authentication and can make a real key-only login probe; installation always
requires an explicit remote-mutation confirmation. Session detail provides a
typed timeline and one encrypted draft per provider-scoped session;
resume, send, steering, and interruption appear only when the selected provider
and session state support them. Ready agent endpoints can start sessions with
provider-neutral options. Approval and question cards expose only provider-
offered decisions, retain a redacted audit record, and require an extra
confirmation for broad or high-risk grants. Sessions whose providers report
file changes expose a durable changed-file shelf. A connected Local Device or
Secure Shell profile can save a checked regular file inside its workspace
through Android's system document picker, with progress, cancellation, source
revision and SHA-256 verification, and best-effort partial-copy cleanup. The app
requests no broad storage permission. Read [Installing](docs/INSTALLING.md) for
APK and device instructions and [Usage](docs/USAGE.md) for the exact implemented
behavior. The [app workflow catalogue](docs/WORKFLOWS.md) shows the critical
journeys with emulator-captured screens.

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
- [Offline speech architecture](docs/SPEECH.md)
- [Third-party runtime notices](docs/THIRD_PARTY.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Quality and safety](docs/QUALITY.md)

## Project guidance

- [Contributing](CONTRIBUTING.md)
- [Security](SECURITY.md)
- [Release process](docs/RELEASING.md)
- [Changelog](CHANGELOG.md)

Every functional change must update the user or developer documentation that it
makes inaccurate. Pull requests run formatting, unit and contract tests, Android
lint, static analysis, dependency analysis, aggregate coverage verification,
deterministic visual regression, and debug assembly before merge.

## License and support

No public license or supported release is currently offered. This repository is
private and provided only to invited collaborators for development.
