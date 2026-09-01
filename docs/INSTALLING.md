# Installing a development build

Agent Relay does not currently publish a release, Play Store package, or signed
production APK. The only installable artifact is a development debug build.

> [!IMPORTANT]
> The current APK exposes an early session hub, not a complete client. It can
> connect existing generic profiles and display discovered sessions, but it
> cannot yet create SSH profiles, send prompts, answer approvals, or run as a
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
./gradlew installDebug
```

Or assemble the APK and install it with ADB:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, invoke `.\gradlew.bat` instead of `./gradlew`.

## Install a CI artifact

Invited collaborators can open a successful Android verification workflow run,
download the `agent-relay-debug-...` artifact, extract it, and install the APK:

```bash
adb install -r app-debug.apk
```

GitHub artifacts are ZIP archives, not directly installable APKs. Use artifacts
only from this private repository and verify that the associated workflow and
commit succeeded.

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
