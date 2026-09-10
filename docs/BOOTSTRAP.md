# Repository-local developer toolchain

`scripts/bootstrap.py` installs and validates the complete Agent Relay build
toolchain below `.agent-relay/` in the checkout. It does not use APT, Android
Studio, a machine-global JDK, or ambient build tools. The reviewed
`config/bootstrap-toolchain.json` manifest pins each immutable URL, byte size,
SHA-256 digest, version, extraction limit, and installed-file check.

The default operation is a side-effect-free readiness check. It returns nonzero
until the selected target is ready:

```bash
python3 scripts/bootstrap.py --target build
python3 scripts/bootstrap.py --target build --json
```

The initial install is explicit and requires acceptance of the linked Android
SDK license terms:

```bash
python3 scripts/bootstrap.py --target build --install --yes \
  --accept-android-sdk-license
```

`build` provisions JDK 17, Android command-line and platform tools, Platform
36, Build Tools 36.0.0, NDK 28.2.13676358, CMake 3.28.3, Ninja 1.11.1, uv, and
the exact checked-in Gradle Wrapper distribution. `quality` additionally
provisions ShellCheck, shfmt, Bats, Vale, and the locked uv quality environment.
`device` applies the build checks and requires exactly one authorized device
from the repository-local `adb`; reports contain counts but never serials.

All downloads use Python's standard library. The installer downloads to a
partial staging file, enforces the reviewed size and SHA-256, safely extracts
without archive traversal or external links, and atomically publishes each
component. Safe internal archive links are materialized as files, so the
installed tree contains no archive-controlled symbolic links. An invalid cache
or installation fails closed; repair is never implicit:

```bash
python3 scripts/bootstrap.py --target build --install --yes --repair \
  --accept-android-sdk-license
```

After one connected install, the authenticated archive cache supports an
offline reinstall or validation:

```bash
python3 scripts/bootstrap.py --target build --install --yes --offline \
  --accept-android-sdk-license
python3 scripts/bootstrap.py --target build --offline
```

Run build commands through the canonical launcher. It sets `JAVA_HOME`, Android
SDK variables, `GRADLE_USER_HOME`, uv state, and `PATH`; verifies selected tools
resolve below `.agent-relay/toolchain`; and refuses the ambient `gradle` command:

```bash
scripts/with-toolchain ./gradlew assembleDebug --stacktrace
AGENT_RELAY_TOOLCHAIN_TARGET=quality scripts/with-toolchain \
  pre-commit run --all-files --show-diff-on-failure
```

The installer writes ignored `local.properties` and a local Gradle properties
file that disables Java auto-detection and auto-download. Remove all generated
state only with explicit confirmation:

```bash
python3 scripts/bootstrap.py --clean --yes
```

The reviewed manifest currently supports Linux x86_64, including an x86_64 WSL
checkout. Other operating systems and architectures fail closed with guidance;
do not substitute older Debian Forky or Sid APT packages for the pinned tools.
The remaining host prerequisites are Python 3.12 or newer, Bash, Git, CA
certificates, network access for the first install, adequate disk space, and
the normal runtime libraries supplied by the supported Linux host.
