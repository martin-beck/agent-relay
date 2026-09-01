# Using Agent Relay

## Current application behavior

The debug application now launches an adaptive session hub backed by the real
application graph. It can:

- show Local Device and Secure Shell as separate connection providers;
- create and display the app-private local connection automatically;
- load existing encrypted SSH connection profiles;
- connect and disconnect any profile through the generic connection boundary;
- require explicit review of an unknown SSH host key;
- show both old and new fingerprints before replacing a changed SSH host key;
- reject an SSH identity without silently accepting it;
- show provider probes, connection state, sanitized errors, unread counts, and
  actionable activity counts;
- list sessions discovered through Aider, Claude Code, Cline, Codex, Continue,
  and OpenCode provider adapters;
- display cached transcript and activity detail and mark a selected session
  read; and
- use focused navigation on compact screens and list-detail navigation on
  expanded screens.

Session navigation stores only a fixed-length SHA-256 identity derived from the
complete connection/provider/session locator. It does not place raw host,
workspace, or session identifiers in navigation state.

## Current limitations

The hub is an early development surface, not a release-ready agent client:

- SSH profiles cannot yet be created or edited in the UI.
- Local access does not bundle coding-agent command-line tools; a compatible
  executable must exist inside the application's sandbox before it can be
  discovered.
- Starting, attaching, composing, steering, interrupting, approving, and file
  transfer are not yet exposed by the app UI.
- Transcript detail is currently read-only. Rendering is bounded to the most
  recent 32,000 characters and clearly marks truncation.
- Speech, notifications, foreground/background session operation, and artifact
  workflows are not implemented.
- No production release is published.

Capability-dependent actions must remain unavailable, with an explanation, when
the selected agent or connection provider cannot implement them safely.

## Security expectations

- SSH first-use and changed host keys require explicit review.
- Credentials must stay in encrypted app-private storage and must never appear
  in diagnostics.
- Local access must remain inside Android's application sandbox and configured
  working root.
- Approval decisions must display their connection, workspace, session, and
  requested scope.
- Unsupported operations must be unavailable rather than emulated.
- Initialization and connection failures must fail closed and show only
  sanitized, actionable messages.

The detailed behavior is tracked in the
[product roadmap](PRODUCT_ROADMAP.md). Build and installation instructions are
in [Building](BUILDING.md) and [Installing](INSTALLING.md).
