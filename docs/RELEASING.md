# Release process

There is no supported Agent Relay release. This document records the minimum
release gate so a debug APK is not mistaken for production software.

## Release blockers

A first release requires all of the following:

- stable application ID, versioning policy, and product name;
- connection/session runtime integrated into the Compose application;
- usable local and SSH setup, identity, error, and recovery flows;
- adaptive phone, tablet, foldable, keyboard, and accessibility validation;
- real-device Android Keystore and process lifecycle tests;
- explicit Android backup and data-extraction rules;
- production signing configured outside Git and CI logs;
- complete privacy and security review of the release commit and Git history;
- release notes, migration/uninstall behavior, and supported-version policy; and
- a successful protected pull-request build at the exact release commit.

## Candidate verification

From a clean checkout:

```bash
uv sync --locked --only-group quality
uv run pre-commit run --all-files --show-diff-on-failure
./gradlew clean spotlessCheck test lintDebug assembleDebug --stacktrace
./gradlew :storage:android:connectedDebugAndroidTest   :ssh:android:connectedDebugAndroidTest
```

Additional end-to-end device tests must exercise local and SSH connections,
provider discovery, session recovery, approvals, process death, network loss,
and data removal.

## Signing

Never commit a keystore, signing password, service-account credential, or
generated signed package. Release signing must use an external secret store and
least-privilege CI environment after the repository's publication controls are
reviewed.

The existing debug key and debug APK are development artifacts only.

## Publication

A release must be built from a reviewed commit merged through a successful pull
request. Record checksums and provenance for every published artifact. Do not
publish a GitHub release, Play Store build, or externally distributed APK until
the blockers above are closed and the owner explicitly approves publication.
