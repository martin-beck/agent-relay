# Quality and safety

Agent Relay uses complementary checks rather than treating one analyzer as a
complete security signal. The pull-request workflow runs deterministic checks;
bounded mutation fuzzing is scheduled separately.

Process-lifecycle tests deterministically verify serialized suspension and
resume, stale and duplicate request coalescing, retry after failure, and
detail-free observable failure states. Foreground-service tests separately
verify strict package-scoped actions, duplicate-safe controller transitions,
manifest type and permissions, fixed private notification content, and explicit
start/stop UI. API 36 emulator evidence proves real service start, continued
active state after the Activity backgrounds, notification stop, and notification
removal. Live-provider endurance and power/network behavior still require
representative physical-device release evidence.

## Pull-request gates

| Gate | Purpose | Failure policy |
| --- | --- | --- |
| Spotless with ktlint | Reproducible Kotlin, Gradle, Markdown, and YAML formatting | Any drift fails |
| Pre-commit hygiene and EditorConfig | Parseable text files, LF endings, final newlines, indentation, file modes, merge-marker and case-conflict safety | Any finding fails; generated wrappers are not reformatted |
| Ruff, mypy, and Radon | Python formatting, imports, defects, security checks, strict static types, McCabe complexity, and maintainability measurements | Ruff and mypy findings fail; complexity above 10 fails; Radon grades and maintainability index remain diagnostic |
| Pytest, Hypothesis, and Coverage.py | Deterministic examples and properties for repository validators, including branch coverage | Any test failure or less than 65% branch-aware production coverage fails |
| Lizard | Language-independent cyclomatic complexity for Kotlin, Java, and Python | A function above 20 fails; no warning baseline |
| markdownlint and Lychee | Portable Markdown structure plus valid local paths and anchors | Any finding fails; external network links run weekly with retries |
| Vale | Project terminology, active and concise technical prose, and seven readability formulas | Terminology errors fail; voice, wording, and readability scores are advisory |
| yamllint, Taplo, and schema checks | Deterministic YAML/TOML style and valid GitHub workflow, issue-form, and Dependabot structure | Any finding fails |
| actionlint and zizmor | GitHub Actions expressions, graph semantics, permissions, injection, and supply-chain safety | Any finding fails; audits run offline on pull requests |
| Typos and Gitleaks | Source-aware spelling and hard-coded secret detection across tracked text | Any finding fails; suppressions must identify a reviewed false positive narrowly |
| Build logic | Google Java Format, `javac -Xlint:all -Werror`, PMD, SpotBugs, Gradle plugin validation, JUnit 5, TestKit, and JaCoCo | Any finding or test failure fails; line coverage below 93% or branch coverage below 82% fails |
| Kotlin compiler | Type safety and compiler diagnostics | All warnings are errors |
| Detekt | Kotlin correctness plus cyclomatic, cognitive, nesting, length, parameter, and size limits | Any configured finding fails; cognitive complexity is ratcheted below 34 and no baseline is used |
| Android lint | Android and dependency lint checks | Errors and warnings fail; HTML, XML, and SARIF reports |
| Dependency analysis | Unused, transitive, and incorrectly scoped dependencies | Any advice fails, except one documented public-API edge |
| Dependency integrity | Gradle SHA-256 verification, configuration locks, and pinned OSV-Scanner over Gradle and uv locks | Missing or changed integrity state and unreviewed vulnerabilities fail; broad, expired, or production-reaching exceptions fail |
| Native speech runtime | Pinned source/toolchain, four-ABI ELF hardening, contents, licenses, provenance, and deterministic rebuilds | Any input, build, validation, or packaging drift fails |
| JVM tests | Unit, contract, concurrency, process-lifecycle, and persistence behavior | Any failure fails |
| Device UI tests | Semantic flows and API 34+ accessibility checks | API 36 phone fails pull requests; minimum API and tablet run weekly |
| Executable workflow guide | Scenario contracts, generated pages, reviewed emulator captures, and strict site build | Missing, orphaned, stale, oversized, malformed, or materially changed evidence fails |
| Visual regression | Deterministic Roborazzi images across state, size, theme, and font variants | Any pixel drift fails; actual/diff evidence is retained |
| Kover | Aggregate and critical-module JVM-testable line coverage | Aggregate below 70%, or a critical module below its ratcheted floor, fails |
| Kotlin ABI validation | Public provider and connection contracts from the pinned Kotlin Gradle plugin | Any unreviewed difference from the committed ABI dumps fails |
| Debug assembly | Packaging and resource integration | Any failure fails |

The dependency-analysis exception for `:session:api` is intentionally narrow:
its public ABI exposes identifiers from `:connection:api`, so that project
dependency must remain `api` even though bytecode-only analysis recommends
`implementation`.

The native speech task verifies sherpa-onnx source and ONNX Runtime downloads
before use, then relies on the hash declarations inside that verified source for
each transitive CMake archive. It builds with exact NDK, CMake, and Ninja
versions and rejects unexpected ABIs, libraries, dependencies, files, symbols,
hardening state, build paths, TTS markers, sizes, or license hashes. Generated
native outputs and provenance are cacheable only after the task succeeds. A
changed runtime must also pass a clean byte-for-byte rebuild comparison before
review.

`OldTargetApi` is the only intentionally disabled app lint issue. Lint derives
it from the newest SDK installed on a machine, while raising `targetSdk` changes
runtime behavior and API 37 requires a newer AGP/Gradle pair. The project stays
on API 36 until the Android 17 migration includes behavior review and emulator
evidence; every other configured lint warning remains fatal.

The `AndroidGradlePluginVersion`, `GradleDependency`, and
`NewerVersionAvailable` lint detectors are also disabled because they query
mutable update feeds and duplicate Dependabot. Upgrade proposals remain
individually reviewed and must pass the complete gate before merge.

Kover cannot ingest Android connected-test execution into its JVM aggregate.
`SessionActionCardKt`, `SessionCreatorDialogKt`, and `SessionDetailPaneKt` are
presentation-only Compose files covered by the required device
semantic/accessibility suite, so they are explicitly excluded from the JVM
denominator. Their ViewModel, provider-ID translation, persistence, risk,
validation, artifact-transfer, and UI-mapping logic remain included in Kover.
Add another presentation exclusion only with a
required connected test that exercises the user-visible behavior; never exclude
domain or orchestration logic to meet the percentage.

Critical JVM boundaries also enforce their own line-coverage floors. The floors
are integer ratchets immediately below the clean measured results: provider API
25% (25.68% measured), connection API 70% (70.36%), session API 89% (89.89%),
session runtime 83% (83.47%), speech API 74% (74.34%), and SSH API 86% (86.85%).
These checks run through each module's normal `koverVerify` task and do not
replace or reduce the aggregate 70% rule.

The provider and connection API modules enable the experimental ABI validator
shipped in the pinned Kotlin Gradle plugin 2.3.20. `checkKotlinAbi` compares the
compiled public contracts with the reviewable dumps under each module's `api`
directory. Run `updateKotlinAbi` only for an intentional compatible API change,
then review every dump line. A green check means the compiled ABI matches the
committed reference; it does not promise source compatibility, behavioral
compatibility, or semantic-versioning policy.

Run the full local gate:

```bash
uv sync --locked --only-group quality --only-group docs
uv run pytest
scripts/ci/install_shell_quality_tools.sh
uv run pre-commit run --all-files --show-diff-on-failure
./gradlew -p buildSrc check --stacktrace
./gradlew spotlessCheck detekt buildHealth test koverXmlReport koverVerify checkKotlinAbi lintDebug assembleDebug --stacktrace
```

This first build-logic slice covers the Java native-download and extraction
tasks. Dedicated assurance for native shell scripts, dependency provenance and
verification metadata, Python support tools, and Kotlin convention logic stays
as explicitly scoped follow-on work; their existing repository gates remain
mandatory in the meantime.

Install the fast checks as a Git hook after the first sync:

```bash
uv run pre-commit install
```

The checked-in `uv.lock` pins the pre-commit runner and every hook revision is
frozen to an immutable commit. It also pins Pytest, Hypothesis, Coverage.py,
Radon, and Lizard. CI installs Vale 3.19.0 from its official release archive
only after verifying the pinned SHA-256 checksum; developers install that same
version locally. The repository shell-tool installer pins ShellCheck 0.11.0 and
shfmt 3.14.0 release assets by SHA-256 and pins Bats 1.14.0 to its exact source
revision and archive digest. The shell tools live under the build cache or
runner temporary directory; no machine-global installation is required. Each
invocation verifies extracted ShellCheck content and Bats regular-file paths and
contents instead of trusting their reported versions, then restores altered
files from the authenticated archives. Dependabot proposes uv and pre-commit
updates;
review the upstream release notes and the generated configuration diff before
accepting them.

## Complexity and readability policy

Thresholds block changes only when the measurement has a stable interpretation
and a practical remediation. Ruff rejects Python functions above McCabe
complexity 10. Lizard independently rejects Kotlin, Java, or Python functions
above cyclomatic complexity 20. Detekt remains authoritative for Kotlin and
adds a cognitive-complexity ceiling of 34 to its existing cyclomatic and
structural limits. The initial ceiling is one point above the measured maximum
of 33, so any regression fails without adding a baseline that could mask later
changes to an existing method.

Radon reports each Python block's rank and every Python file's maintainability
index. Vale reports Automated Readability, Coleman-Liau, Flesch-Kincaid,
Flesch Reading Ease, Gunning Fog, LIX, and SMOG measurements. Those aggregate
scores are diagnostic, not pass/fail gates: identifiers, commands, protocol
names, and necessary security language can legitimately make technical text or
small support scripts score poorly. Review a regression in context instead of
rewriting accurate material to satisfy a universal grade target. Vale's narrow
terminology rules remain errors because their corrections are deterministic.

## Workflow evidence policy

Ten ordered YAML manifests define the user goal, preconditions, automatic work,
human-attention boundary, recovery behavior, and visible steps for every
catalogue scenario. The renderer rejects duplicate or unsafe identifiers,
missing required fields, screenshots on planned behavior, and verified steps
without screenshots or alt text. Generated Markdown must be committed exactly as
rendered.

Six currently implemented journeys produce 14 screenshots from semantic API 36
instrumentation. Fixtures use synthetic hosts, identities, commands, paths, and
sessions and exist only in `androidTest`. The workflow requires exactly the
manifest-declared PNG set; missing and orphaned files both fail. Each PNG must be
at least 320 by 480 pixels, valid PNG data, and no larger than 1 MiB.

The comparison tolerates only minor rasterization noise. Per-channel differences
of 16 or less are ignored. The check fails when more than 1% of pixels exceed
that tolerance or the four-channel root-mean-square difference exceeds 4.0.
These thresholds are strict because the canonical emulator, 1080 by 2400
display, 420 dpi, light theme, US locale, UTC time zone, and font scale are fixed.
A failing comparison retains the pixel diff for review.

The normal repository gate validates manifests, generated pages, baseline
integrity, Python tests, and a strict MkDocs build without an emulator. The
required API 36 UI job additionally captures and compares the running app,
retains the current images, metrics, and diffs, and publishes a downloadable
static-site artifact. A planned workflow remains text-only until its semantic
journey passes and its screenshots receive explicit review.

## Format-specific policy

The rules follow conventions used by mature Android, Python, and documentation
projects, while keeping source diffs semantic and reviewable.

- **Kotlin and Kotlin DSL:** Spotless owns formatting through ktlint. The Kotlin
  compiler treats warnings as errors, Detekt covers maintainability and likely
  defects, Lizard supplies a second language-independent complexity view, and
  dependency analysis checks module declarations.
- **Python:** Ruff owns formatting, import ordering, common defect and security
  checks, and safe modernization for Python 3.12, including a McCabe ceiling.
  Mypy runs in strict mode with unreachable code diagnostics. Radon records
  cyclomatic rank and maintainability index, while Lizard independently checks
  complexity. Pytest executes the existing unit tests plus deterministic
  Hypothesis properties for YAML and path boundaries. Coverage.py enables
  branch measurement for the four production scripts and fails below a
  ratcheted 65% combined branch-aware floor; test modules are excluded from that
  percentage. XML inputs use
  `defusedxml` so hostile entities fail closed. Formatting, typing, or metric
  success never substitutes for executing the tests.
- **Markdown:** Standalone documents use an ATX H1 followed by ordered heading
  levels, fenced code blocks with a language, consistent list indentation, and
  portable tables. Duplicate headings are permitted only under different
  parents. Line length is not enforced because URLs, tables, and copyable
  commands must remain intact. The pull-request template is intentionally a
  fragment and is excluded from the standalone-document heading rule.
  Vale rejects known project-term casing errors and advises on passive voice,
  wordiness, and readability. Fenced and inline code are excluded from prose
  scoring. Vendored style definitions make the check deterministic and their
  upstream licenses are retained beside them.
- **YAML:** Two-space indentation and a 120-column ceiling apply. A leading
  document marker is optional, and GitHub's top-level `on` key is accepted.
  Generic YAML parsing and yamllint are supplemented with vendored JSON Schemas
  for workflows, Dependabot, and issue forms. Actionlint and zizmor perform the
  GitHub-specific semantic and security checks that a YAML parser cannot.
- **TOML:** Two-space indentation, stable key order, trailing newlines, and
  deterministic arrays are enforced by Taplo. Generic TOML parsing catches
  syntax errors, while Gradle consumes and validates the version catalog during
  every normal build.
- **XML:** Four-space indentation and well-formed XML are checked generically.
  Android lint and assembly remain authoritative for manifest/resource schema,
  references, API use, and packaging semantics.
- **Properties and generated launchers:** EditorConfig and the consuming build
  tool check properties files. The checked-in Gradle wrapper scripts and JAR are
  generated artifacts: Gradle wrapper validation checks their integrity, and
  formatters must not rewrite them.
- **Repository-wide text:** Typos checks prose and identifiers with a small
  project dictionary. Gitleaks scans the complete working tree with redacted
  output. Generic hooks reject private keys, oversized accidental artifacts,
  broken symlinks, merge markers, mixed line endings, and paths that collide on
  case-insensitive systems.

The conventions and tool scopes are based on the
[Google Python style guide](https://google.github.io/styleguide/pyguide.html),
[Google Markdown style guide](https://google.github.io/styleguide/docguide/style.html),
[Ruff formatter and linter guidance](https://docs.astral.sh/ruff/),
[mypy strict-mode documentation](https://mypy.readthedocs.io/en/stable/command_line.html#cmdoption-mypy-strict),
[actionlint checks](https://github.com/rhysd/actionlint/blob/main/docs/checks.md),
[zizmor audits](https://docs.zizmor.sh/audits/),
[check-jsonschema's vendored GitHub schemas](https://check-jsonschema.readthedocs.io/en/stable/usage.html#builtin-schema-choices),
[Taplo validation and formatting](https://taplo.tamasfe.dev/cli/introduction),
and [Lychee link checking](https://lychee.cli.rs/).

Pull requests verify local Markdown paths and fragments without network access.
The scheduled `External documentation links` workflow checks remote links so a
temporary third-party outage cannot block an otherwise valid source change.
It excludes only this private repository's Actions page, workflow badge, and
security-advisory form because GitHub returns 404 for anonymous requests to
those authentication-gated endpoints.
Run that same network check explicitly with:

```bash
uv run pre-commit run lychee-online --hook-stage manual --all-files
```

Generate human-readable reports while investigating:

```bash
uv run radon cc scripts/ci scripts/docs --show-complexity --average --total-average
uv run radon mi scripts/ci scripts/docs --show
uv run lizard --CCN 20 --warnings_only .
uv run pytest
uv run pre-commit run vale --all-files
./gradlew detekt koverHtmlReport lintDebug
```

Verify the committed UI baselines separately:

```bash
./gradlew :app:verifyRoborazziDebug --stacktrace
```

Reports are written below `buildSrc/build/reports`, `build/reports/detekt`,
`build/reports/dependency-analysis`, `build/reports/kover`, and
`build/reports/problems`, plus `build/reports/quality` (including Python
branch-aware coverage XML) and each Android
module's `build/reports` directory. CI retains them for 14 days.
Repository-format findings are emitted directly in the pre-commit and GitHub
Actions logs with file and line information.

## Optional centralized analysis

The repository contains a SonarQube-compatible project definition and a
SHA-pinned scanner step. Analysis is disabled by default. This is deliberate:
enabling a hosted analyzer for a private repository sends source and metrics to
another service and therefore requires an explicit repository-owner decision.

To enable either SonarQube Server or SonarQube Cloud, configure these trusted
repository settings:

- variable `SONAR_PROJECT_KEY`;
- variable `SONAR_HOST_URL`;
- optional variable `SONAR_ORGANIZATION`; and
- secret `SONAR_TOKEN`.

The workflow validates that the token exists before scanning. It skips Sonar on
Dependabot and untrusted-fork pull requests, where repository secrets are not
available. Do not put any of those values in
`sonar-project.properties`, source files, workflow arguments, or logs. The
scanner imports the Kover XML coverage and Detekt XML findings produced earlier
in the same job. The local deterministic gates remain authoritative even when a
Sonar server is unavailable.

Gradle 9 currently produces a problems report because the pinned Detekt 1.23.8
plugin calls a reporting API scheduled for removal in Gradle 10.
The warning originates in the Detekt plugin rather than project build logic.
Agent Relay retains the report and will adopt a compatible stable Detekt release
before moving to Gradle 10; it does not replace analyzer or test failure gates.
Pre-release Detekt 2 builds are not used merely to hide this warning.

## UI usability and accessibility

Ease of use is a correctness requirement. UI work is incomplete until the
primary task is discoverable, every state has a safe next action, and tests show
that the task remains usable across Android configurations and assistive input.

Agent Relay follows a layered approach drawn from mature Compose and Android
projects:

1. Pure mapper and ViewModel tests verify state transitions, capabilities,
   errors, stale-event rejection, safe artifact labels, transfer cancellation,
   and partial-copy cleanup without an emulator.
2. Compose semantic tests use realistic provider/session test doubles and assert
   what a user can identify and do. Tests select controls through user-visible
   text, role, state, or stable semantic purpose, not layout hierarchy or pixel
   coordinates.
3. Instrumented tests exercise complete critical flows on the minimum and
   current supported APIs. API 34 or newer runs Compose Accessibility Test
   Framework checks before interactions.
4. Linux-recorded visual-regression tests cover compact, medium, and expanded
   windows, light and dark themes, large font scales, and long German strings.
   A changed baseline is evidence to review, never an automatic approval.
5. Release audits cover TalkBack reading order and announcements, keyboard and
   switch navigation, focus retention, selection, contrast, minimum touch
   targets, reduced motion, split screen, rotation, and process restoration.

The `Android UI verification` workflow runs the semantic flow suite and
Compose Accessibility Test Framework checks on an API 36 phone for every pull
request and push to `main`. The emulator script explicitly verifies completed
Android boot before starting Gradle. A separate XML parser then requires clean
JUnit evidence from the app, SSH Android, and storage Android modules, with at
least 33 discovered and 33 executed tests. This prevents a missing device,
missing module report, skipped accessibility audit, or accidentally empty suite
from appearing green.

That API 36 job also captures the six verified usage journeys, requires all 14
reviewed screenshots to remain within the documented thresholds, and performs a
strict static-site build. It retains captures, metrics, diffs, reports, and the
downloadable browsable guide for 14 days.

The workflow invokes those three device-test tasks explicitly. Native-only and
no-test Android modules remain covered by the quality and build workflow without
spending the bounded emulator job compiling unrelated native runtimes before
the required UI evidence can run.

The weekly/manual matrix runs the same suite on the minimum API phone and an
API 36 tablet. CI artifacts retain reports from every tested module plus the
Gradle problems report. The API 28 run filters out the four API 34+
accessibility-framework audits and requires all 29 remaining device tests. The
external-keyboard composer flow enters non-touch focus mode, verifies forward
Tab and reverse Shift+Tab traversal without draft mutation, activates both
focused turn controls with Enter, and restores the original touch mode. The new
profile-operation flows cover jump-host choice, install confirmation, the
key-only action, and unsaved-change disabling. Emulator console, graphics, or
teardown diagnostics can contain alarming words; CI relies on process status
and parsed JUnit evidence rather than string grep.

The connected app flow also verifies that a selected session exposes its changed
files, safe relative path, refresh action, and save callback. JVM contract tests
cover local real-path and SFTP canonical-path confinement plus source revision
and checksum checks; they do not require private hosts or credentials in CI.

The 2026-09-01 API 36 jump-host milestone run passed 18 application tests, one
SSH Android test, and two encrypted-storage Android tests. The evidence verifier
accepted all 21 discovered and executed tests with zero skips, failures, or
errors. The emulator was stopped after evidence collection.

Each screen or reusable component must have deterministic previews for the
states it owns, including empty, loading, content, error, offline, changed
identity, approval-required, destructive confirmation, and unsupported
capability where applicable. Preview parameters should cover day/night, window
width, font scale, and expansion-prone content. This follows Element X Android's
preview-to-screenshot pattern while retaining Agent Relay's own Material 3
design language.

Agent Relay uses Roborazzi with Robolectric native graphics for its first
visual-regression layer. Roborazzi was selected over Paparazzi because the
current stable Roborazzi release works with this project's AGP 9 and Compose
stack, runs on Linux without an emulator, and is already used for Android
Compose screenshot tests by Now in Android. Paparazzi's current 2.0 release is
still an alpha, while Element X Android's mature Paparazzi/Showkase pattern
remains a useful reference for preview coverage.

The 190 committed baselines under `app/src/test/screenshots` include eight
adaptive state captures, 16 independently reported session-hub locale captures,
38 profile-editor captures, 32 session-detail captures, 32 changed-file
artifact captures, 32 offline speech-install captures, and 32
notification-permission captures. Every bundled locale and both Android
pseudo-locales render the session hub, endpoint validation, and managed-key
operations at 360 x 800 dp and 1.3x font scale. Both pseudo-locales additionally
exercise password, imported-key, and passphrase-replacement branches. The profile tests cover
wrapping required labels, editable-control descriptions, field and global
errors, radio roles and selection, selectable groups, and physical RTL
Save/Close/Delete ordering. They reject off-viewport, height-overflowing, or
ellipsized visible text. Robolectric's paused main looper is idled directly
before the multi-window snapshot so dialog semantics are published without
entering the indefinite TextField-idling path.

Each locale also renders one compact session-detail question state and its
sensitive-action confirmation at 360 x 800 dp and 1.3x font scale. Those tests
require Question/Other/Submit/Cancel and dialog actions to remain fully inside
their window, reject text overflow and ellipsis, exercise radio/selectable-group
and checkbox semantics, and verify pseudo-locale keyboard behavior plus RTL
action mirroring.

The changed-file matrix renders an active checked-copy transfer with its
localized progress and cancel action, followed by verified-copy success and a
deleted-source unavailable state. It asserts live-region, progress-indicator,
button-role, enabled-action, locale-direction, and four-edge containment
semantics while rejecting horizontal text overflow and ellipsis.

The offline speech matrix renders model selection and install, then download
progress/cancel, localized failure/retry, and installed/ready controls. It
asserts actual action routing, live regions, determinate progress, radio roles
and selection, selectable groups, locale direction, and raw four-edge
containment while rejecting text overflow and ellipsis. The production UI does
not yet expose model size, license terms, or a separate confirmation surface;
the matrix therefore does not claim coverage for them.

The notification-permission matrix renders the first request and the
settings-recovery state, using the request callback and production permission
resolver for the transition. It keeps both permission and blocked background
controls visible at 360 x 800 dp and 1.3x font scale, and verifies button roles,
enabled state, touch targets, RTL end alignment, four-edge containment, and
non-ellipsized localized text.

A review of the initial baselines found a narrow-width French clipping defect;
compact header and recovery actions now stack vertically so long labels receive
the full content width. The wider baseline set still covers loading, fatal
error, empty, normal content, approval-required, compact/medium/expanded,
light/dark, and large-text states. Dynamic color is disabled so host wallpaper
state cannot change evidence. Pull requests run `:app:verifyRoborazziDebug` in a
dedicated Linux CI job and retain Roborazzi reports, test XML, and generated
actual/diff images.

Record changed baselines only after reviewing the rendered UI:

```bash
./gradlew :app:recordRoborazziDebug --stacktrace
./gradlew :app:verifyRoborazziDebug --stacktrace
```

Run record and verify as separate Gradle invocations because both modes share
the Android unit-test task. A changed baseline is evidence to inspect, never an
automatic approval or a substitute for semantic, accessibility, or device
tests.

End-to-end tests should use task-oriented screen robots, as seen in mature
Firefox and DuckDuckGo Android suites, so navigation details can evolve without
rewriting every assertion. Emulator flows remain deterministic: use app-private
local fixtures and containerized SSH endpoints, never personal hosts,
credentials, paths, or transcript data.

Automated checks are necessary but not sufficient. Android's own guidance notes
that tooling cannot find every runtime accessibility issue, so each release
requires a documented manual TalkBack and keyboard pass. The WordPress Android
TalkBack audit questions are a useful checklist for focus order, grouping,
announcements, gestures, target size, contrast, and localized descriptions.

Reference implementations and guidance:

- [Android Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)
- [Android accessibility testing](https://developer.android.com/guide/topics/ui/accessibility/testing)
- [Android adaptive display testing](https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes)
- [Now in Android testing](https://github.com/android/nowinandroid#testing)
- [Element X Android contribution and UI-test rules](https://github.com/element-hq/element-x-android/blob/develop/CONTRIBUTING.md)
- [WordPress Android TalkBack guidelines](https://github.com/wordpress-mobile/WordPress-Android/blob/trunk/docs/talkback-guidelines.md)

## Property and fuzz testing

The SSH POSIX command encoder is a high-risk boundary because incorrectly quoted
text could become remote shell syntax. Its independent test decoder verifies
that:

- every non-NUL Unicode string survives quote/decode unchanged;
- no text is emitted outside a single-quoted shell segment; and
- NUL input is rejected because it cannot be represented in a process argument.

Kotest generates 1,000 Unicode examples during the normal JVM suite. Jazzer also
runs every normal test execution in regression mode. Run its bounded 30-second
mutation campaign with:

```bash
JAZZER_FUZZ=1 ./gradlew :ssh:jsch:test \
  --tests 'dev.agentrelay.ssh.jsch.PosixCommandEncoderFuzzTest' \
  --rerun-tasks --stacktrace
```

Generated corpora are build evidence, not source, and are ignored locally. The
scheduled/manual fuzz workflow uploads its corpus and test reports for 14 days.
Add more focused targets as parsers and artifact/path boundaries become
integrated; avoid broad targets whose setup dominates the short fuzzing budget.

## Security-analysis boundaries

Detekt and Android lint emit SARIF. The private repository's workflow retains
SARIF as a downloadable artifact. Uploading SARIF or running CodeQL through
GitHub code scanning requires the repository's GitHub Code Security entitlement;
the current workflow does not claim that unavailable check.

Dependabot covers Gradle and GitHub Actions update proposals. Dependency
analysis guards declaration quality. Gradle verifies resolved bytes against
reviewed SHA-256 metadata and locks resolved versions, while pinned OSV-Scanner
checks the committed Gradle and uv locks against the current OSV database.
OSV results are time-dependent advisory data, not proof that a dependency is
safe. CI retains an unfiltered report and accepts only reviewed combinations of
vulnerability ID, ecosystem, package, and version. ID-specific, expiring
scanner exceptions then produce the filtered report. The accepted combinations
cover only non-shipped test, lint, and AGP-internal transitive tools; the
repository verifier rejects new or stale findings, a reviewed ID on another
coordinate, or any accepted coordinate that enters a production configuration.
An ID-specific exception also covers aliases that OSV associates with that ID,
so any policy or database change still requires human advisory review. Review
release notes and security advisories before merging any update.

GitHub Dependabot vulnerability alerts are repository-account state and are not
configured or asserted by this source-only gate. An authorized administrator
must inspect or enable them separately.

The current hosted workflow does not run:

- live SSH or coding-agent integration checks;
- release signing or store validation; or
- end-to-end dynamic application security testing.

Those remaining checks require controlled devices or private credentials and
must not leak their configuration or output into GitHub logs or artifacts.

## Tuning rules

- Fix actionable findings instead of adding broad suppressions or baselines.
- Keep thresholds explicit and review them when the code shape changes.
- Exclude generated code and Compose previews from coverage, not production
  behavior.
- Raise the coverage floor only after the new level is stable across clean
  builds.
- Prefer a focused property test for a stable invariant and a focused fuzz test
  for a parser, quoting, serialization, or path boundary.
- Pin GitHub Actions to immutable commit SHAs and let Dependabot propose updates.
- Treat a green analyzer as evidence for its documented scope, not proof that
  the application is secure.
