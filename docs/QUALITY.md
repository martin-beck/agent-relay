# Quality and safety

Agent Relay uses complementary checks rather than treating one analyzer as a
complete security signal. The pull-request workflow runs deterministic checks;
bounded mutation fuzzing is scheduled separately.

## Pull-request gates

| Gate | Purpose | Failure policy |
| --- | --- | --- |
| Spotless with ktlint | Reproducible Kotlin, Gradle, Markdown, and YAML formatting | Any drift fails |
| Kotlin compiler | Type safety and compiler diagnostics | All warnings are errors |
| Detekt | Kotlin correctness, complexity, and maintainability findings | Any configured finding fails; no baseline |
| Android lint | Android and dependency lint checks | Errors and warnings fail; HTML, XML, and SARIF reports |
| Dependency analysis | Unused, transitive, and incorrectly scoped dependencies | Any advice fails, except one documented public-API edge |
| JVM tests | Unit, contract, concurrency, and persistence behavior | Any failure fails |
| Device UI tests | Semantic flows and API 34+ accessibility checks | API 36 phone fails pull requests; minimum API and tablet run weekly |
| Kover | Aggregate JVM line coverage across modules | Less than 70% fails |
| Debug assembly | Packaging and resource integration | Any failure fails |

The dependency-analysis exception for `:session:api` is intentionally narrow:
its public ABI exposes identifiers from `:connection:api`, so that project
dependency must remain `api` even though bytecode-only analysis recommends
`implementation`.

`OldTargetApi` is the only intentionally disabled app lint issue. Lint derives
it from the newest SDK installed on a machine, while raising `targetSdk` changes
runtime behavior and API 37 requires a newer AGP/Gradle pair. The project stays
on API 36 until the Android 17 migration includes behavior review and emulator
evidence; every other configured lint warning remains fatal.

The `AndroidGradlePluginVersion`, `GradleDependency`, and
`NewerVersionAvailable` lint detectors are also disabled because they query
mutable update feeds and duplicate Dependabot. Upgrade proposals remain
individually reviewed and must pass the complete gate before merge.

Run the full local gate:

```bash
./gradlew spotlessCheck detekt buildHealth test koverXmlReport koverVerify lintDebug assembleDebug --stacktrace
```

Generate human-readable reports while investigating:

```bash
./gradlew detekt koverHtmlReport lintDebug
```

Reports are written below `build/reports/detekt`,
`build/reports/dependency-analysis`, `build/reports/kover`, and
`build/reports/problems`, plus each Android module's `build/reports` directory.
CI retains them for 14 days.

Gradle 9 currently produces a problems report because Detekt 1.23.8, the latest
stable Detekt release, calls a reporting API scheduled for removal in Gradle 10.
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
   errors, stale-event rejection, and labels without an emulator.
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
least 12 discovered and 10 executed tests. This prevents a missing device,
missing report, or accidentally empty suite from appearing green.

The weekly/manual matrix runs the same suite on the minimum API phone and an
API 36 tablet. CI artifacts retain reports from every tested module plus the
Gradle problems report. The API 28 run skips only the two API 34+ accessibility-
framework audits; at least ten semantic and secure-storage tests still execute.
Emulator console, graphics, or teardown diagnostics can contain alarming words;
CI relies on process status and parsed JUnit evidence rather than string grep.

Each screen or reusable component must have deterministic previews for the
states it owns, including empty, loading, content, error, offline, changed
identity, approval-required, destructive confirmation, and unsupported
capability where applicable. Preview parameters should cover day/night, window
width, font scale, and expansion-prone content. This follows Element X Android's
preview-to-screenshot pattern while retaining Agent Relay's own Material 3
design language.

The first visual-regression implementation should evaluate Roborazzi, used by
Google's Now in Android project, against Paparazzi, used by Element X Android.
Prefer the smallest stable tool that:

- runs deterministically on Linux without a hardware emulator for PR feedback;
- renders the Compose and Material versions used by this project accurately;
- produces reference, actual, and diff artifacts;
- supports the required window, theme, font, and locale matrix; and
- does not require checking generated machine-specific data into source.

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

Dependabot covers Gradle and GitHub Actions update proposals. Dependency analysis
guards declaration quality, but neither tool proves that a dependency is free of
vulnerabilities. Review dependency release notes and security advisories before
merging an update.

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
