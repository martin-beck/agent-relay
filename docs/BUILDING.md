# Building Agent Relay

This guide describes the reproducible development build used by continuous
integration.

## Requirements

- JDK 17
- Android SDK Platform 36
- Android NDK 28.2.13676358
- CMake 3.28.3
- Ninja 1.11.1
- Bash and standard POSIX build tools
- Git
- Vale 3.19.0, installed from the official checksum-verified release archive
- uv, used to install the repository's locked cross-language check runner
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
uv sync --locked --only-group quality
uv run pre-commit run --all-files --show-diff-on-failure
./gradlew spotlessCheck detekt buildHealth test koverXmlReport koverVerify lintDebug assembleDebug --stacktrace
```

On Windows PowerShell:

```powershell
uv sync --locked --only-group quality
uv run pre-commit run --all-files --show-diff-on-failure
.\gradlew.bat spotlessCheck detekt buildHealth test koverXmlReport koverVerify lintDebug assembleDebug --stacktrace
```

The pre-commit gate covers Python, Markdown, YAML, TOML, XML, properties,
GitHub metadata, spelling, links, secrets, and generic repository hygiene. The
Gradle tasks cover Kotlin formatting, Detekt, strict dependency declarations,
JVM unit and contract tests, aggregate coverage, Android lint, and debug APK
assembly. The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The repository gate invokes pinned Radon and Lizard versions through uv and
Vale 3.19.0 through pre-commit. CI verifies Vale's official archive checksum.

## Offline speech native build

The debug build includes a source-built sherpa-onnx online-recognition runtime.
Confirm the exact native tools before building:

```bash
cmake --version
ninja --version
grep 'Pkg.Revision = 28.2.13676358' \
  "$ANDROID_SDK_ROOT/ndk/28.2.13676358/source.properties"
```

Build and validate the four-ABI runtime directly with:

```bash
./gradlew :speech:sherpa:buildSherpaAndroidRuntime --stacktrace
```

The first uncached build normally takes several minutes. Gradle verifies the
pinned sherpa-onnx source, ONNX Runtime archive, license, and notices before
extraction. The verified sherpa source in turn pins each transitive CMake
download by SHA-256. The build rejects unpinned tool versions, unexpected files
or ELF dependencies, missing hardening, TTS markers, private build paths, and
license drift. Its cacheable output contains eight native libraries plus exact
license, notice, and provenance resources; later verification tasks reuse it.

On Windows, `bash`, CMake, and Ninja must be available on `PATH` when Gradle
invokes the native task. See [Third-party runtime notices](THIRD_PARTY.md).

Run `./gradlew spotlessApply` to repair supported formatting before repeating
the gate. Pre-commit hooks apply safe formatting repairs locally and return a
failure so the resulting diff is reviewed before the gate is repeated.

Install the fast checks as a Git hook if desired:

```bash
uv run pre-commit install
```

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
uv run pre-commit run ruff-check --all-files
uv run pre-commit run mypy --all-files
uv run pre-commit run vale --all-files
uv run pre-commit run radon-complexity --all-files
uv run pre-commit run radon-maintainability --all-files
uv run pre-commit run lizard-complexity --all-files
uv run pre-commit run markdownlint-cli2 --all-files
uv run pre-commit run actionlint --all-files
uv run pre-commit run zizmor --all-files
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
  --minimum-tests 32 --minimum-executed 32 \
  --require-module app --require-module ssh/android --require-module storage/android
```

The pull-request UI workflow runs the same suite on an API 36 emulator. A
successful compile or JVM test does not substitute for device execution.

## CI

`.github/workflows/verify.yml` runs the required quality/build and deterministic
visual-regression jobs. Actions are
pinned to immutable commit SHAs, dependency updates are proposed by Dependabot,
and repository-format, test, quality, lint, and APK evidence is retained for a
limited time. The
separate `.github/workflows/fuzz.yml` job runs on a weekly schedule and by
manual dispatch so bounded mutation fuzzing does not slow every pull request.
The `.github/workflows/ui.yml` job runs semantic UI, accessibility, SSH
Android, and encrypted-storage tests, then parses each module's JUnit XML instead
of treating emulator log text as the result.
Sonar analysis is present but opt-in because a hosted service receives private
source. See [Quality and safety](QUALITY.md#optional-centralized-analysis) for
the required trusted variables, secret, and fail-safe behavior.

The scheduled `.github/workflows/links.yml` workflow performs the networked
external-link check; pull requests use deterministic offline path and fragment
validation.

If a CI-only failure occurs, download the relevant report artifact from the
workflow run and reproduce the exact failing Gradle task locally.
