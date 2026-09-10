# Installing a development build

Agent Relay does not currently publish a release, Play Store package, or signed
production APK. The only installable artifact is a development debug build.

> [!IMPORTANT]
> The current APK exposes an early session hub, not a complete client. It can
> create encrypted SSH profiles, connect generic profiles, and display
> discovered sessions, but it cannot yet send prompts, answer approvals, or run as a
> supported background service.

## Compatibility

- Android 9 (API 28) or newer
- An emulator or device with USB or wireless debugging enabled
- Android Platform Tools (`adb`)

The application ID is `com.example.agentrelay`. It is explicitly a
development-only identity and can change before the first supported release.
The standard debug certificate, `0.1.0-dev.*` version line, and this package ID
must not be treated as future production identities.

## Install a local build

Build and install with Gradle:

```bash
python3 scripts/bootstrap.py --target device --install --yes \
  --accept-android-sdk-license
AGENT_RELAY_TOOLCHAIN_TARGET=device scripts/with-toolchain ./gradlew installDebug
```

Or assemble the APK and install it with ADB:

```bash
scripts/with-toolchain ./gradlew assembleDebug
AGENT_RELAY_TOOLCHAIN_TARGET=device scripts/with-toolchain \
  adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, invoke `.\gradlew.bat` instead of `./gradlew`.

To create a traceable local bundle, run the metadata command documented in
[Building](BUILDING.md). It inspects the APK and writes these files under
`build/development-apk`:

- `agent-relay-development-VERSION-COMMIT-debug.apk`;
- `agent-relay-development-manifest.json`; and
- `SHA256SUMS`.

The manifest records the APK SHA-256 and byte size, application ID, minimum and
target SDKs, build type, development version, full source commit, optional
GitHub workflow run identity, and explicit unsupported/debug limitations.

## Install a CI artifact

A GitHub Actions artifact is retained build evidence attached to one successful
workflow run. If a trusted workflow provides a development APK bundle, download
the complete ZIP, verify `SHA256SUMS` and the manifest's source commit, extract
it, and install its canonically named APK:

```bash
sha256sum --check SHA256SUMS
adb install -r agent-relay-development-*-debug.apk
```

GitHub artifacts are ZIP archives, not releases, and are not directly
installable APKs. A future GitHub development prerelease may retain the same
inspected files longer and make them easier to discover, but remains an
unsupported debug build. Neither channel is a supported release.

## Remove the development build

```bash
adb uninstall com.example.agentrelay
```

Uninstalling removes app-private data. There is no migration or backup guarantee
during development.

## Release status

A future supported release requires a stable production application ID and
version policy, complete P0 UI, device testing, external signing configuration,
and a completed security review. Development metadata does not close those
blockers. See [Releasing](RELEASING.md).
