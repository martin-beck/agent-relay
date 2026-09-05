# Developer bootstrap

`scripts/bootstrap.py` detects the host and validates the pinned Agent Relay
toolchain without changing the host. It supports Linux, WSL, and Windows
diagnostics from a repository checkout.

Run a human-readable report:

```bash
python scripts/bootstrap.py
```

Use `--json` for a redacted machine-readable report suitable for attaching to
an issue or feeding into a local setup tool:

```bash
python scripts/bootstrap.py --json
```

The report distinguishes `build-ready`, `quality-gate-ready`, and
`emulator-test-ready`. It checks the pinned JDK, Gradle wrapper, `uv`, CMake,
Ninja, ShellCheck, shfmt, Bats, Android Platform 36, and NDK
28.2.13676358. Paths are redacted to repository, home, or path tokens.

Detection is intentionally side-effect free. Installation is never implicit;
`--install` requires `--yes`, and future installers must remain idempotent,
checksum-verified, user-scoped where possible, and safe for offline caches.
Runner-only variables such as `RUNNER_TEMP` and `GITHUB_PATH` are not required
by this developer-facing diagnostic.
