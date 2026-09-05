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
- Linux x86_64 or arm64 for the shell-quality gate, including WSL on Windows
- ShellCheck 0.11.0, shfmt 3.14.0, and Bats 1.14.0, installed by the
  checksum-verifying repository helper on that Linux host
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
uv sync --locked --only-group quality --only-group docs
uv run pytest
scripts/ci/install_shell_quality_tools.sh
uv run pre-commit run --all-files --show-diff-on-failure
./gradlew -p buildSrc check --stacktrace
./gradlew spotlessCheck detekt buildHealth test koverXmlReport koverVerify checkKotlinAbi lintDebug assembleDebug --stacktrace
```

On Windows, run the complete repository and shell gates inside x86_64 or arm64
WSL. You can run the Gradle portion from PowerShell:

```powershell
.\gradlew.bat -p buildSrc check --stacktrace
.\gradlew.bat spotlessCheck detekt buildHealth test koverXmlReport koverVerify checkKotlinAbi lintDebug assembleDebug --stacktrace
```

The pre-commit gate covers Python, first-party Bash, Markdown, YAML, TOML, XML,
properties, GitHub metadata, spelling, links, secrets, and generic repository
hygiene. Its shell slice runs pinned ShellCheck, checks canonical formatting
with `shfmt --diff`, and runs focused Bats regressions. The generated Gradle
wrapper remains governed by wrapper validation instead of being reformatted or
patched locally. The dedicated build-logic gate covers deterministic Java
formatting, strict compiler diagnostics, PMD, SpotBugs, Gradle plugin
validation, JUnit 5 and TestKit tests, and ratcheted JaCoCo line and branch
thresholds. The application Gradle tasks cover Kotlin formatting, Detekt, strict
dependency declarations, JVM unit and contract tests, aggregate coverage,
Android lint, and debug APK assembly. The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The repository gate invokes pinned Pytest, Hypothesis, Coverage.py, Radon, and
Lizard versions through uv and Vale 3.19.0 through pre-commit. The Python suite
uses deterministic property examples and rejects branch-aware coverage below
65% for the four production validators and renderers. CI verifies Vale's official
archive checksum.
`install_shell_quality_tools.sh` similarly downloads official immutable release
assets into `build/tools/shell-quality`, verifies their SHA-256 digests, and
supports `SHELL_QUALITY_OFFLINE=1` once its archive cache is populated. It does
not install or replace machine-global tools. Every invocation also verifies the
extracted ShellCheck binary and the Bats tree's regular-file paths and contents
against pinned payload digests, repairs drift from the authenticated archives,
and atomically replaces the executable entry points.

## Dependency integrity

Gradle verifies every resolved artifact and metadata file against the committed
SHA-256 values in `gradle/verification-metadata.xml`. Strict dependency locking covers
the configurations exercised by the complete verification build in each
project and in `buildSrc`. Regenerate both sets only while intentionally
updating dependencies:

```bash
./gradlew --write-verification-metadata sha256 --write-locks \
  spotlessCheck detekt buildHealth test koverXmlReport koverVerify checkKotlinAbi \
  lintDebug assembleDebug compileDebugAndroidTestKotlin verifyRoborazziDebug
python scripts/ci/verify_dependency_integrity.py
```

Review every checksum and lock change before committing it. Never use lenient
dependency verification or delete an unexpected checksum to make resolution
pass. CI installs the exact OSV-Scanner release declared in
`config/osv-scanner-release.json`, verifies its binary checksum, and scans
`uv.lock` plus every supported Gradle lockfile. The settings lock remains under
the offline integrity check because OSV-Scanner does not parse that filename.

OSV-Scanner first writes unfiltered JSON, and the repository verifier compares
every vulnerability ID, ecosystem, package, and version with the exact tuples in
`config/osv-accepted-vulnerabilities.json`. The ID-specific, expiring
`IgnoredVulns` entries in `config/osv-scanner.toml` then produce the filtered
SARIF report. Accepted tuples cover only transitive Android test, lint, and
AGP-internal tooling that is absent from shipped configurations. A new ID, a
known ID on another coordinate, a stale tuple, or movement into production
fails. Because the live OSV database changes independently, re-review both
reports and the policy whenever the result set changes.

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

To regenerate or investigate only the multilingual profile-editor matrix, keep
the same record-then-verify separation and use the test-name filter:

```bash
./gradlew :app:recordRoborazziDebug --tests '*MainScreenScreenshotTest.profile*'
./gradlew :app:verifyRoborazziDebug --tests '*MainScreenScreenshotTest.profile*'
```

Use the dedicated class filter for the compact session-detail question and
confirmation matrix:

```bash
./gradlew :app:recordRoborazziDebug --tests '*SessionDetailScreenshotTest'
./gradlew :app:verifyRoborazziDebug --tests '*SessionDetailScreenshotTest'
```

Use the artifact class filter for the compact changed-file progress, cancel,
verified-copy, and unavailable-file matrix:

```bash
./gradlew :app:recordRoborazziDebug --tests '*ArtifactScreenshotTest'
./gradlew :app:verifyRoborazziDebug --tests '*ArtifactScreenshotTest'
```

Use the speech-install class filter for the compact model-selection,
download, failure, and ready-state matrix:

```bash
./gradlew :app:recordRoborazziDebug --tests '*SpeechInstallScreenshotTest'
./gradlew :app:verifyRoborazziDebug --tests '*SpeechInstallScreenshotTest'
```

Review every changed PNG under `app/src/test/screenshots` before committing it.
Do not combine record and verify in one Gradle invocation because both
Roborazzi modes use the same Android unit-test task.

## App workflow guide

The [app workflow catalogue](WORKFLOWS.md) is the shortest route from a user goal
to current or planned behavior. GitHub renders it directly, including every
reviewed screenshot. The generated Material for MkDocs site adds navigation and
search.

The repository keeps authored intent, generated pages, executable evidence, and
reviewed output separate:

- `docs/workflows/scenarios/*.yml` contains the ordered scenario contracts;
- `docs/WORKFLOWS.md` and `docs/workflows/*.md` are generated and committed;
- `docs/assets/workflows/<scenario>/*.png` contains reviewed emulator evidence;
- `UsageJourneyTest.kt` and `UsageJourneyFixtures.kt` exercise only synthetic
  `androidTest` data that is absent from release builds;
- `scripts/docs` renders, checks, captures, and compares the guide; and
- `mkdocs.yml` defines the searchable static site.

When a user-visible capability changes, update or add its scenario contract in the
same change. Keep a scenario `planned` until a semantic Android journey and reviewed
captures exist; fixtures and model-only contracts must never be presented as verified
product evidence.

Validate the authored catalogue, reviewed images, and site without an emulator:

```bash
uv run --only-group docs python scripts/docs/render_workflows.py --check
uv run --only-group docs python scripts/docs/verify_workflows.py
uv run --only-group docs mkdocs build --strict
uv run --only-group docs mkdocs serve --strict
```

The preview is available at `http://127.0.0.1:8000/` while the final command is
running.

To compare a fresh Android API 36 emulator run with the reviewed evidence, set
`ANDROID_SDK_ROOT`, boot exactly one emulator, and run:

```bash
scripts/docs/capture_usage_workflows.sh
```

The script rejects physical devices and ambiguous device selection, applies the
canonical size, density, light theme, font scale, locale-independent UTC
formatting, executes the semantic journey, pulls all 14 images, performs the
pixel-tolerant comparison, and builds the site. It writes transient evidence
under `build/usage-guide` and the browsable site under `build/site`.

After an intentional UI change, inspect every new image first, then explicitly
replace the reviewed set and rerun comparison:

```bash
scripts/docs/capture_usage_workflows.sh --record
git diff --stat docs/assets/workflows
```

Never record a baseline merely to silence unexplained drift. Update its scenario
contract and alt text when the user-visible meaning changes.

Every pull request and push to `main` makes the catalogue available in three
places:

1. the committed Markdown entry point at `docs/WORKFLOWS.md`;
2. the `usage-guide-site` workflow artifact, which can be downloaded, extracted,
   and opened at `index.html`; and
3. the GitHub Pages URL only when the repository owner has approved public
   publication, configured Pages, and set `ENABLE_PUBLIC_PAGES=true`.

The Pages path is deliberately opt-in because this private personal repository
cannot use access-controlled Pages. All screenshots and fixtures remain
synthetic even when public publication is approved.

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
uv run pytest
uv run pre-commit run vale --all-files
uv run pre-commit run radon-complexity --all-files
uv run pre-commit run radon-maintainability --all-files
uv run pre-commit run lizard-complexity --all-files
uv run pre-commit run markdownlint-cli2 --all-files
uv run pre-commit run actionlint --all-files
uv run pre-commit run zizmor --all-files
scripts/ci/run_shell_quality.sh shellcheck
scripts/ci/run_shell_quality.sh shfmt
scripts/ci/run_shell_quality.sh test
./gradlew :connection:local:test
./gradlew :ssh:jsch:test
./gradlew :session:runtime:test
./gradlew :app:lintDebug :app:assembleDebug
./gradlew detekt buildHealth
./gradlew koverHtmlReport koverVerify
./gradlew checkKotlinAbi
```

The last command checks the committed public provider and connection API dumps.
After an intentional compatible contract change, run
`./gradlew :provider:api:updateKotlinAbi :connection:api:updateKotlinAbi` and
review the generated text before committing it. Never update a dump merely to
silence an unexplained compatibility failure.

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
  --minimum-tests 33 --minimum-executed 33 \
  --require-module app --require-module ssh/android --require-module storage/android
```

The pull-request UI workflow runs the same suite on an API 36 emulator, compares
the workflow captures with reviewed evidence, and builds the static guide. A
successful compile, JVM test, or site build does not substitute for device
execution.

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
Self-hosted build and device jobs use separate least-privilege runner pools.
Each pool retains isolated local Gradle, uv, and pre-commit caches, so those jobs
disable redundant GitHub cache archive restore and save operations while keeping
the pinned setup actions and Gradle wrapper validation.
`setup-java` continues to verify each downloaded JDK against the vendor-published
SHA-256 checksum. Its additional GPG verification is disabled on the self-hosted
pools because the action creates agent socket paths longer than Linux permits
under their runner temporary directories. Re-enable that layer when the action
or runner layout uses a shorter socket path.
Sonar analysis is present but opt-in because a hosted service receives private
source. See [Quality and safety](QUALITY.md#optional-centralized-analysis) for
the required trusted variables, secret, and fail-safe behavior.

The scheduled `.github/workflows/links.yml` workflow performs the networked
external-link check; pull requests use deterministic offline path and fragment
validation.

If a CI-only failure occurs, download the relevant report artifact from the
workflow run and reproduce the exact failing Gradle task locally.
