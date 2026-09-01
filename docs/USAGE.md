# Using Agent Relay

## Current application behavior

The debug application now launches an adaptive session hub backed by the real
application graph. It can:

- show Local Device and Secure Shell as separate connection providers;
- create and display the app-private local connection automatically;
- create, edit, and delete encrypted SSH connection profiles through a
  provider-owned form;
- connect and disconnect any profile through the generic connection boundary;
- require explicit review of an unknown SSH host key;
- show both old and new fingerprints before replacing a changed SSH host key;
- reject an SSH identity without silently accepting it;
- show provider probes, connection state, sanitized errors, unread counts, and
  actionable activity counts;
- list sessions discovered through Aider, Claude Code, Cline, Codex, Continue,
  and OpenCode provider adapters;
- start a session from a ready agent endpoint with provider-neutral launch
  options;
- render cached user messages, agent commentary, final answers, plans,
  reasoning summaries, tools, and system messages as distinct timeline entries;
- preserve a separate encrypted multi-line draft and cursor selection per session;
- send input to an idle session or steer a running turn when the provider
  advertises that capability;
- answer provider questions and resolve approvals through only the decisions the
  provider offered, with an additional confirmation for risky positive grants;
- resume supported saved sessions;
- interrupt running or approval-waiting sessions when the provider supports
  interruption; and
- retain an encrypted per-session shelf of provider-reported changed files;
- refresh changed files for providers advertising that capability; and
- save one checked, regular in-workspace file through Android's system document
  picker with progress, cancellation, and source-change detection;
- use focused navigation on compact screens and list-detail navigation on
  expanded screens.

Session navigation stores only a fixed-length SHA-256 identity derived from the
complete connection/provider/session locator. It does not place raw host,
workspace, or session identifiers in navigation state.

## Start a session

1. Connect a Local Device or Secure Shell profile.
2. Under **Connections**, select **Start _agent_ on _connection_** for a ready
   discovered agent endpoint.
3. Optionally enter a working directory and exact model identifier. Leave either
   field empty to use the agent provider's default.
4. Select **Start session**.

The path is interpreted by the selected connection provider. A successful
launch refreshes the durable hub, selects the provider-scoped session, and opens
its detail without putting the raw endpoint or session identifier in navigation
state.

## Work with a session

1. Select a session from **Sessions**.
2. Type or edit the message in the session's **Message** field.
3. Use the action that matches the provider-reported state:

- **Send** is available for an input-ready idle session.
- **Steer active turn** replaces Send for a running session only when the
  provider advertises active-turn steering.
- **Resume session** is available for supported saved, failed, or unknown
  sessions that are not currently loaded.
- **Interrupt turn** is available only for a supported running or approval-waiting
  state.

Draft text and cursor selection remain attached to the complete local-or-remote
session identity. Editing is reflected immediately and written securely after a
short debounce. Submission persists the exact draft before provider I/O; it is
cleared only after success and only if no newer draft replaced it. A failed send
keeps the draft and shows a sanitized error.

## Resolve approvals and questions

Pending cards under **Approvals and questions** identify the connection provider,
connection, target, agent provider, session, workspace scope, rationale, and
exact command when those values are available.

- Approval cards show only decisions advertised by the provider, such as
  **Approve once**, **Approve for session**, **Decline**, or **Cancel**.
- Question cards require an answer for every provider question. They preserve
  exact option values and allow written answers only when the provider permits
  them.
- A positive destructive, broad-filesystem, credential, network-expanding, or
  session-wide decision requires a second confirmation that repeats the scope
  and command.

The app persists the selected decision as **Delivering** before contacting the
provider. If delivery fails at that boundary, the result is uncertain and the
card remains non-retryable so a command is not approved twice. Reconnect or
inspect the provider before taking another action; a replay with the same
request identity does not clear the uncertainty. A completed response becomes a
resolved, redacted local audit record. Raw provider approval and question
identifiers remain in encrypted persistence and are not exposed as UI or
navigation keys.

## Save a changed file

1. Select a session whose connected agent advertises file changes.
2. Under **Changed files**, select **Refresh changed files** when a fresh
   provider query is needed.
3. Review the safe workspace-relative name and availability message.
4. Select **Save copy**, choose the destination in Android's system document
   picker, and resolve any name collision there.
5. Watch byte progress or select **Cancel saving**. A completed row reports that
   the source checksum was verified and permits **Save another copy**.

Only added, modified, or renamed files that normalize to a strict
workspace-relative path are export candidates. Deleted files remain history
entries. Files outside the workspace, files with an unknown workspace, and
traversal paths are unavailable. Local access resolves real paths below both the
app-controlled root and the session workspace. Secure Shell access uses
canonical SFTP real paths and blocks symlink escapes. Both providers reject a
candidate unless it resolves to a regular file.

Immediately before export, the app records source size, modification time, and
SHA-256. It streams bounded chunks, checks the size, revision, and digest during
and after the read, and stops if the source changed. Failure or cancellation
triggers best-effort deletion of the partial destination document. The system
document picker grants access only to the selected destination; Agent Relay does
not request broad shared-storage permission.

Raw provider paths stay in encrypted session persistence. The UI and navigation
receive a safe relative path or a bounded unavailability label plus fixed-length
digest keys. Reconnect the session before saving if the provider is offline.

## Set up a Secure Shell connection

1. Select **Add Secure Shell profile** under **Connections**.
2. Enter a profile name, host, port, and username.
3. Choose one authentication method:
   - **Password** stores the password in Android Keystore-backed encrypted app
     storage.
   - **Imported private key** stores the private key and optional passphrase as
     separate encrypted credentials.
   - **Android Keystore key** creates a non-exportable signing key. Save the
     profile, reopen **Edit profile**, and add the displayed public key to the
     remote account before connecting.
4. Save the profile, then use **Connect** from its connection card.

Use **Edit profile** to change an existing connection. A blank secret field
marked as already stored keeps that credential; entering a value replaces it.
Imported-key profiles explicitly choose whether to keep, remove, or replace a
passphrase. Deleting a profile removes its referenced credentials and agent key,
and removes saved host identity only when no other profile uses the endpoint.

The app never returns a stored password or private key to the editor. Provider
validation is shown beside the relevant field, while storage failures use
sanitized messages that do not reveal a host, path, credential, or exception.

## Current limitations

The hub is an early development surface, not a release-ready agent client:

- Local access does not bundle coding-agent command-line tools; a compatible
  executable must exist inside the application's sandbox before it can be
  discovered.
- Changed-file preview, diff, image rendering, attachment upload, artifact
  history management, and batch export are not yet implemented.
- Queued send, explicit retry/cancel, voice input, and attachments are not yet
  implemented.
- Timeline entries remain read-only. Rendering is bounded to 32,000 characters
  per entry and clearly marks truncation.
- Speech, notifications, and foreground/background session operation are not
  implemented.
- No production release is published.

Capability-dependent actions must remain unavailable, with an explanation, when
the selected agent or connection provider cannot implement them safely.

## Security expectations

- SSH first-use and changed host keys require explicit review.
- Credentials must stay in encrypted app-private storage and must never appear
  in diagnostics.
- Stored secrets are represented only by a boolean marker in the editor. A
  password, private key, or passphrase is never read back into Compose state.
- Android Keystore private keys are non-exportable; only their public key is displayed.
- Local access must remain inside Android's application sandbox and configured
  working root.
- Changed-file exports must use a strict workspace-relative path and a
  provider-owned canonical file-access implementation.
- A file must remain a regular file with the inspected size, modification time,
  and SHA-256 for the complete export.
- Export destinations must use Android's Storage Access Framework, never a broad
  storage permission.
- Approval decisions must display their connection, workspace, session, and
  requested scope.
- Unsupported operations must be unavailable rather than emulated.
- Initialization and connection failures must fail closed and show only
  sanitized, actionable messages.

The detailed behavior is tracked in the
[product roadmap](PRODUCT_ROADMAP.md). Build and installation instructions are
in [Building](BUILDING.md) and [Installing](INSTALLING.md).
