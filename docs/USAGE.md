# Using Agent Relay

## Current application behavior

The application is not yet usable as an agent client. Launching the current
debug APK displays `Hello Android!`. There is no connection picker, SSH profile
editor, local provider selector, session list, transcript, composer, or approval
surface in the UI.

The implemented connection, agent-provider, encrypted-storage, and session
runtime modules are library foundations. They are covered by tests but are not
yet instantiated by `:app`.

## Intended workflow

The first usable workflow will allow a user to:

1. choose a connection provider, such as local device or SSH;
2. select or create a provider-specific connection profile;
3. review identity and authentication challenges when required;
4. discover available coding-agent providers and sessions;
5. open a session timeline and send supported actions; and
6. retain drafts, unread activity, and recent transcript state across reconnects.

This section describes the product direction, not behavior available in the
current APK. Capability-dependent actions will be shown only when the selected
agent and connection providers implement them.

## Security expectations

When the UI is integrated:

- SSH first-use and changed host keys must require explicit review.
- Credentials must stay in encrypted app-private storage and must never appear
  in diagnostics.
- Local access must remain inside Android's application sandbox and configured
  working root.
- Approval decisions must display their connection, workspace, session, and
  requested scope.
- Unsupported operations must be unavailable rather than emulated.

The detailed behavior is tracked in the
[product roadmap](PRODUCT_ROADMAP.md). Build and installation instructions are
in [Building](BUILDING.md) and [Installing](INSTALLING.md).
