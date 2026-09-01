# Building Agent Relay

This guide describes the reproducible development build used by continuous
integration.

## Requirements

- JDK 17
- Android SDK Platform 36
- Git
- An account invited to the private repository

Android Studio may supply the JDK and SDK, or they can be installed separately.
The Gradle wrapper downloads the repository's pinned Gradle 9.1.0 distribution.

## Checkout

```bash
git clone https://github.com/martin-beck/agent-relay.git
cd agent-relay
```

Create `local.properties` only when your environment needs an explicit SDK
location. It is ignored by Git and must not be committed.

```properties
sdk.dir=/path/to/Android/Sdk
```

## Verification build

Run the same gate as GitHub Actions:

```bash
./gradlew spotlessCheck detekt buildHealth test koverXmlReport koverVerify lintDebug assembleDebug --stacktrace
```

On Windows PowerShell:

```powershell
.\gradlew.bat spotlessCheck detekt buildHealth test koverXmlReport koverVerify lintDebug assembleDebug --stacktrace
```

The tasks cover formatting, Detekt, strict dependency declarations, JVM unit and
contract tests, aggregate coverage, Android lint, and debug APK assembly. The
APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Run `./gradlew spotlessApply` to repair supported formatting before repeating
the gate.

## Visual regression

Verify the committed deterministic Compose images on Linux:

```bash
./gradlew :app:verifyRoborazziDebug --stacktrace
```

When a deliberate UI change requires new baselines, record and then verify them
in separate Gradle invocations:

```bash
./gradlew :app:recordRoborazziDebug --stacktrace
./gradlew :app:verifyRoborazziDebug --stacktrace
```

Review every changed PNG under `app/src/test/screenshots` before committing it.
Do not combine record and verify in one Gradle invocation because both
Roborazzi modes use the same Android unit-test task.

## Android Studio

1. Open the repository root as an existing project.
2. Select JDK 17 for the Gradle JDK.
3. Allow Gradle sync to install missing Android SDK components.
4. Select the `app` run configuration and a device running Android 9 or newer.

Do not add machine-specific IDE files, SDK paths, signing material, endpoints,
credentials, host aliases, or provider state to Git.

## Focused checks

Examples:

```bash
./gradlew :connection:local:test
./gradlew :ssh:jsch:test
./gradlew :session:runtime:test
./gradlew :app:lintDebug :app:assembleDebug
./gradlew detekt buildHealth
./gradlew koverHtmlReport koverVerify
```

The opt-in provider and SSH live checks are disabled in normal builds. Their
private environment variables and prerequisites are documented in
[Provider operations](PROVIDER_OPERATIONS.md) and
[Connection providers](CONNECTION_PROVIDERS.md). Never add live values or raw
outputs to the repository or CI logs.

## Device tests

The Android Keystore instrumentation coverage requires an emulator or physical
device:

```bash
./gradlew connectedDebugAndroidTest
```

Confirm that every expected module produced clean JUnit evidence:

```bash
python3 scripts/ci/verify_connected_tests.py --root . \
  --minimum-tests 24 --minimum-executed 24 \
  --require-module app --require-module ssh/android --require-module storage/android
```

The pull-request UI workflow runs the same suite on an API 36 emulator. A
successful compile or JVM test does not substitute for device execution.

## CI

`.github/workflows/verify.yml` runs the required quality/build and deterministic
visual-regression jobs. Actions are
pinned to immutable commit SHAs, dependency updates are proposed by Dependabot,
and test, quality, lint, and APK artifacts are retained for a limited time. The
separate `.github/workflows/fuzz.yml` job runs on a weekly schedule and by
manual dispatch so bounded mutation fuzzing does not slow every pull request.
The `.github/workflows/ui.yml` job runs semantic UI, accessibility, SSH
Android, and encrypted-storage tests, then parses each module's JUnit XML instead
of treating emulator log text as the result.

If a CI-only failure occurs, download the relevant report artifact from the
workflow run and reproduce the exact failing Gradle task locally.
