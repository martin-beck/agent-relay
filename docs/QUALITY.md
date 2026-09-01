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
`build/reports/dependency-analysis`, and `build/reports/kover`, plus each
Android module's `build/reports` directory. CI retains them for 14 days.

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

- Android emulator/device instrumentation;
- live SSH or coding-agent integration checks;
- release signing or store validation; or
- end-to-end dynamic application security testing.

Those checks require controlled devices or private credentials and must not leak
their configuration or output into GitHub logs or artifacts.

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
