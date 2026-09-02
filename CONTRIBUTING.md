# Contributing

Agent Relay is a private, invite-only project. Contributions are accepted only
from collaborators who have been granted repository access.

## Workflow

1. Update local `main`.
2. Create a focused branch, normally `feature/<topic>` or `fix/<topic>`.
3. Make small, signed commits using a conventional prefix such as `feat:`,
   `fix:`, `docs:`, `test:`, or `ci:`.
4. Run the local verification gate.
5. Push only the focused branch and open a pull request.
6. Merge only after all required Android verification checks succeed.

Do not push development changes directly to `main`. The pull-request author
may merge their own focused PR after successful checks and a final diff/privacy
review. Delete the branch after merge.

## Required checks

```bash
uv sync --locked --only-group quality
uv run pre-commit run --all-files --show-diff-on-failure
./gradlew spotlessCheck detekt buildHealth test koverXmlReport koverVerify lintDebug assembleDebug --stacktrace
```

Verify deterministic UI baselines separately:

```bash
./gradlew :app:verifyRoborazziDebug --stacktrace
```

The repository check applies format-aware parsing, formatting, static analysis,
schema validation, link validation, spelling, workflow security, and secret
scanning to every tracked text format. It also enforces stable complexity
ceilings and reports advisory maintainability and prose-readability metrics.
Do not waive an objective complexity error merely to preserve a large function,
and do not distort accurate technical language to optimize an advisory score.
Run relevant focused tests while
developing. Device-dependent changes also need the appropriate
`connectedDebugAndroidTest` evidence before release. UI changes must add or
update the relevant deterministic preview, semantic test, and reviewed
Roborazzi baseline. Run `:app:recordRoborazziDebug` and
`:app:verifyRoborazziDebug` as separate invocations. Changes to input parsing,
remote command construction, path handling, or protocol decoding should add a
focused property or fuzz regression when practical.

Run the bounded Jazzer target locally after changing POSIX command encoding:

```bash
JAZZER_FUZZ=1 ./gradlew :ssh:jsch:test \
  --tests 'dev.agentrelay.ssh.jsch.PosixCommandEncoderFuzzTest' \
  --rerun-tasks
```

## Pull request content

A pull request should explain:

- the user or architecture outcome;
- important design and security decisions;
- tests that were run;
- current limitations or follow-up work; and
- documentation changed by the behavior.

Keep unrelated changes in separate PRs. Do not mix dependency updates with
feature work unless the feature requires them.

## Privacy and secrets

Before every push, inspect the complete diff and added files. Never commit:

- credentials, tokens, passwords, private keys, signing keys, or keystores;
- host aliases, addresses, ports, usernames, or private network topology;
- machine-specific paths, Android SDK paths, or executable locations;
- durable agent session IDs, private prompts, transcripts, or raw live output;
- private model names, endpoints, proxy configuration, or provider state; or
- generated APKs, reports, unreviewed captures, `local.properties`, or IDE
  workspace data. The reviewed deterministic PNG baselines under
  `app/src/test/screenshots` are the only capture exception.

Use explicit opt-in environment variables for live checks. Test fixtures must
use synthetic identifiers such as `example-host`, `example-model`, and
documentation-only address ranges.

If sensitive data enters Git history, stop publication, rotate affected secrets,
and rewrite the private history before any further push.

## Documentation

Documentation is part of the change. Update `README.md`, user guides,
architecture/security documents, the roadmap, and `CHANGELOG.md` whenever a
change makes them inaccurate. Clearly separate implemented behavior from planned
behavior.

## Code conventions

- Preserve the connection-provider and agent-provider boundary.
- Keep platform-independent contracts out of Android implementation modules.
- Advertise only capabilities proven by the adapter.
- Bound input, output, retries, storage, and process lifetime.
- Fail closed for malformed secure data and identity changes.
- Add regression tests for behavior and security invariants.

See [Building](docs/BUILDING.md) and [Architecture](docs/ARCHITECTURE.md).
