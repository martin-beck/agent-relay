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

The application ID is temporarily `com.example.agentrelay` and can change
before the first supported release.

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

## Install a CI artifact

When a development artifact is available, open its successful Android
verification workflow run, download the `agent-relay-debug-...` artifact,
extract it, and install the APK:

```bash
adb install -r app-debug.apk
```

GitHub Actions artifacts are ZIP archives, not directly installable APKs, and
may require GitHub authentication. Verify that the associated workflow and
exact source commit succeeded. A later publication task owns anonymous APK
delivery; this source-clearance change does not publish an artifact.

## Remove the development build

```bash
adb uninstall com.example.agentrelay
```

Uninstalling removes app-private data. There is no migration or backup guarantee
during development.

## Release status

A production release requires a stable application ID, complete P0 UI, device
testing, external signing configuration, version policy, and a completed
security review. See [Releasing](RELEASING.md).

The development APK and its embedded Agent Relay license are covered by the
repository's [MIT License](../LICENSE). Packaged third-party components retain
their own terms and notices.
