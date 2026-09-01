## Outcome

Describe the user-facing or architecture outcome.

## Validation

List the exact local checks and any device or opt-in live evidence.

- [ ] `./gradlew spotlessCheck test lintDebug assembleDebug --stacktrace`
- [ ] Relevant focused tests
- [ ] Device tests when Android platform behavior changed

## Privacy and security

- [ ] I reviewed the complete diff for credentials, keys, host or network
      details, machine paths, private model/provider configuration, session IDs,
      prompts, transcripts, logs, and generated artifacts.
- [ ] New commands, storage, paths, protocols, and approvals remain bounded and
      fail safely.
- [ ] No live value or secret was added to test data, documentation, or CI.

## Documentation

- [ ] README, user/developer guides, architecture/security notes, roadmap, and
      changelog are updated where behavior changed.
- [ ] Implemented and planned behavior are clearly distinguished.

## Limitations and follow-up

Record known gaps or use `None`.
