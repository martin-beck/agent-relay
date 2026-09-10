# Building Agent Relay

This guide describes the reproducible development build used by continuous
integration. On Linux x86_64, the supported setup is the
[repository-local toolchain](BOOTSTRAP.md).

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

The repository bootstrap can supply every listed build and quality tool without
machine-global installation. The Gradle wrapper uses the locally provisioned,
checksum-verified Gradle 9.7.1 distribution and local cache.

## Checkout

```bash
git clone https://github.com/martin-beck/agent-relay.git
cd agent-relay
```

Provision the build target and let the bootstrap write ignored
`local.properties` and local Gradle configuration:

```bash
python3 scripts/bootstrap.py --target build --install --yes \
  --accept-android-sdk-license
scripts/with-toolchain ./gradlew assembleDebug --stacktrace
```

For a manually managed environment, create `local.properties` only when it
needs an explicit SDK location. It is ignored by Git and must not be committed.

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

### Development APK identity and metadata

Every Android configuration derives a development version from the checked-out
Git commit. `versionCode` is `1000000` plus that commit's first-parent count;
`versionName` is `0.1.0-dev.COUNT+gSHORT_SHA`. This policy is monotonic only
for development artifacts selected in main's first-parent order. It does not
define a production-release version.

After `assembleDebug`, inspect and package the APK with the pinned Android
`aapt2` from the selected build-tools directory:

```bash
uv run python scripts/ci/development_apk.py package \
  --repository . \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --aapt2 "$ANDROID_SDK_ROOT/build-tools/36.0.0/aapt2" \
  --output-directory build/development-apk
```

The command rejects an APK whose application ID, version, minimum SDK, or
target SDK differs from the source contract. It emits a canonical APK name
containing the development version and full source commit, a sorted JSON
manifest, and `SHA256SUMS`. A trusted GitHub workflow also supplies positive
`--workflow-run-id` and `--workflow-run-attempt` values. Local output records
both as null; it never substitutes a machine, account, path, branch, timestamp,
credential, prompt, transcript, or provider value.

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

## Manual external documentation publication

The repository does not publish documentation externally during pushes,
schedules, or pull requests. An owner may configure a repository variable
`DOCS_PUBLICATION_URL` and secret `DOCS_PUBLICATION_TOKEN`, then explicitly
dispatch the `External documentation publication` workflow with `confirm=true`.
The workflow rebuilds the strict documentation site, creates a deterministic
archive, sends it only to the configured HTTPS destination, and retains its
SHA-256 checksum in the job summary. Missing configuration, a
non-HTTPS destination, an oversized archive, or a token found in the archive
fails closed before upload. No endpoint, credential, or account identifier is
stored in the repository.

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
uv run --only-group docs python scripts/docs/verify_evidence_reproducibility.py \
  --source-revision "$(git rev-parse HEAD)"
uv run --only-group docs python scripts/docs/verify_evidence_reproducibility.py --check
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

The Pages path is deliberately opt-in and requires explicit repository-owner
approval. All screenshots and fixtures remain synthetic even when public
publication is approved.

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

The last command checks the committed public provider, connection, and session
API dumps. After an intentional compatible contract change, run
`./gradlew :provider:api:updateKotlinAbi :connection:api:updateKotlinAbi
:session:api:updateKotlinAbi` and review the generated text before committing
it. Never update a dump merely to silence an unexplained compatibility failure.

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

The scheduled `Documentation maintenance` workflow repeats generated-document,
workflow-evidence, reproducibility, privacy, API, consistency, strict-site, and
external-link checks on the dedicated build runner pool. Stale or missing evidence
fails closed. After every authoritative check passes, the workflow attempts to
upload a browsable site for three days. That upload is optional evidence transport:
quota failure emits a warning and job-summary record without changing the validation
result. Explicit public or external publication paths remain strict.

## CI

### Public contributor checks

Pull requests run `Public contributor validation` only on the dedicated `agent-relay-public-ci`
runner label. A host-side supervisor registers one randomly named ephemeral runner inside a fresh
locked-down container, accepts at most one job, and removes the container afterward. The container
has a read-only image, job-private memory-backed work and temporary directories, a private PID and
network namespace, no host mounts or Docker socket, no Linux capabilities, and no shared caches.
The runner receives the unavoidable one-job registration credential only during setup, immediately
removes its host-side environment file, and re-executes with a clean job environment. It receives no
repository variables or secrets,
environment or publication authority, artifact exchange, or write-capable repository token.

The lane runs the locked Python suite; repository, workflow, secret, and source-header checks; and
JVM tests, Kotlin formatting, Detekt, and ABI validation. It installs the required JDK and Android
platform inside the disposable filesystem. It does not build native code, start an emulator,
assemble an APK, or claim the complete gate.

Build the pinned runner image from the repository root, then run the supervisor under a private
service account whose GitHub CLI authentication can only administer runners for this repository:

```bash
docker build --pull \
  --file scripts/ci/public_runner/Dockerfile \
  --tag agent-relay-public-runner:2.337.0 .
PUBLIC_RUNNER_REPOSITORY=owner/repository \
  scripts/ci/public_runner/supervise.sh
```

The supervisor requires GitHub CLI, jq, OpenSSL, and Docker. Its Docker command may be supplied as
`PUBLIC_RUNNER_DOCKER_COMMAND` when the service account uses a rootless or mediated Docker client.
Keep the supervisor credential outside the repository and container. Do not grant the container a
host directory, device, privileged mode, host network, Docker API, or reusable cache. The supervisor
deletes only offline registrations carrying its exact dedicated label before registering a
replacement. Operational logs must identify runners only by their random public-lane name.

Self-hosted verification, UI, and AWQ workflows do not accept `pull_request` events. After reviewing
an exact contributor commit and its workflow diff, a maintainer may push that immutable commit to a
repository-owned `trusted-ci/<commit>` branch. That push runs the complete self-hosted gates at the
new repository commit; never promote a mutable fork ref or an unreviewed workflow change. A merge to
`main` runs the same trusted gates again.

Before making the repository public, its owner must also require approval for every external
contributor workflow and restrict each persistent self-hosted runner group to trusted workflow files
pinned to the default branch, or remove those runners from the public repository. Source policy
tests cannot verify account-level runner configuration. Public forks can otherwise request a
persistent self-hosted job by changing workflow files, so repository visibility must not change
until that external control is independently verified. The disposable public pool is not a
substitute for isolating the persistent trusted pools.

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
Sonar analysis is present but opt-in because a hosted service receives
repository source and metrics. See
[Quality and safety](QUALITY.md#optional-centralized-analysis) for the required
trusted variables, secret, and fail-safe behavior.

The scheduled `.github/workflows/links.yml` workflow performs the networked
external-link check; pull requests use deterministic offline path and fragment
validation.

If a CI-only failure occurs, download the relevant report artifact from the
workflow run and reproduce the exact failing Gradle task locally.

## Actions artifact retention

The manual `Actions artifact retention` workflow inventories Actions artifacts
on the dedicated build runner and produces a deterministic dry-run plan by
default. It protects active runs, current main, open pull-request heads,
release/publication outputs, recent evidence, and artifacts with incomplete
provenance. Age, duplicate source heads, size, and explicit quota hysteresis
rank the remaining candidates.

The repository owner must supply the account's current artifact quota and a
maximum deletion count for every dispatch. Applying a reviewed plan additionally
requires the exact confirmation `DELETE_ACTIONS_ARTIFACTS`; the tool rejects
more than 100 deletions per invocation. The workflow has no schedule, does not
upload another artifact, and writes its counts to the job summary. A dry run or
source test never authorizes deletion.

Run the same planner locally with a token provided only through the environment:

```bash
uv run python scripts/ci/actions_artifact_retention.py \
  --repository owner/name --quota-mib 500 --max-deletions 25
```
