# Architecture

Agent Relay separates how an execution environment is reached from how a coding
agent is controlled. Neither the session layer nor an agent adapter assumes SSH.

## Dependency direction

```text
                        :app
                          |
                   :session:runtime
                    /             \
         :connection:api       :provider:api
          /          \              |
:connection:local   :ssh:api   :provider:*
                         |
                :ssh:jsch + :ssh:android

:speech:api <- :speech:android <- :speech:sherpa is an independent on-device
speech stack. The Android layer owns verified app-private model activation and
injectable audio boundaries; the sherpa layer supplies native online inference.
No connection or agent provider depends on the speech stack.
:storage:android is shared by Android persistence implementations.
:session:api and :session:android persist provider-neutral session state.
```

Dependencies point toward contracts. Agent providers consume
`RemoteAgentRuntime`; they do not cast it to an SSH or local implementation.
Connection providers do not know agent protocols. A provider can optionally
expose a `ConnectionProfileManager`; the app renders its generic bounded field
schema without importing SSH configuration types.

## Modules

| Module | Responsibility |
| --- | --- |
| `:app` | Android entry point and Compose presentation |
| `:connection:api` | Generic connection providers, provider-owned profile forms and operations, lifecycle, identity challenges, and runtime access |
| `:connection:local` | App-local process execution plus canonical workspace-confined file access |
| `:ssh:api` | SSH profiles, jump routes, credentials, managed-key enrollment and probes, host keys, retry policy, and generic adapter |
| `:speech:api` | Auditable offline model metadata plus generation-safe download, recognition, and playback contracts |
| `:speech:android` | Verified private model delivery, generation-safe coordination, and production Android PCM capture/playback with injected native inference |
| `:speech:sherpa` | Pinned TTS-free sherpa-onnx online-recognition JNI runtime and generation-safe Android inference adapter |
| `:ssh:jsch` | Maintained JSch transport, direct-tcpip jump chaining, bounded POSIX commands, and canonical SFTP file access |
| `:ssh:android` | Android SSH persistence, credentials, and non-exportable agent keys |
| `:provider:api` | Agent descriptors, sessions, events, capabilities, actions, and the generic checked-file contract |
| `:provider:*` | Codex, OpenCode, OpenDesk, Continue, Claude, Cline, and Aider adapters |
| `:session:api` | Durable provider-neutral session identity and repository |
| `:session:android` | Encrypted Android session-hub document store |
| `:session:runtime` | Profile discovery, connection lifecycle, agent discovery, event projection, and actions |
| `:storage:android` | Namespaced authenticated Android Keystore document encryption |

## Session identity

A session is addressed by the complete tuple:

```text
ConnectionProviderId
  + ConnectionProfileId
  + AgentProviderId
  + AgentSessionId
```

The tuple prevents identical provider session IDs from colliding across SSH,
local access, profiles, or future connection types.

## Runtime flow

1. A `ConnectionProvider` enumerates generic profile summaries.
2. The coordinator opens a `ManagedConnection`.
3. A connected profile supplies a `RemoteAgentRuntime`.
4. Compatible agent factories probe and open agent sessions through that runtime;
5. the coordinator starts sessions and projects agent events into the durable
   session repository; and
6. the Compose layer observes durable state and sends capability-checked actions.

All six layers are now connected for the first interactive session-hub slice.
An application-scoped graph owns encrypted store construction and registers both
Local and Secure Shell plus all implemented agent factories. A navigation-scoped
ViewModel combines coordinator and durable repository snapshots so destinations
do not create duplicate runtimes or state authorities.
Composer edits use an in-memory projection for immediate feedback while the
ViewModel debounces writes to the encrypted session repository. Submission
flushes the exact draft before provider I/O and clears it only after successful
delivery; a failed action retains the durable draft and a sanitized UI error.

Approval and question events enter the same repository as an atomic action/
activity pair. Responses move to durable **Delivering** state before provider
I/O and to **Resolved** only after the provider accepts them. An uncertain
delivery is never made retryable automatically. The runtime translates hashed
UI question keys back to exact encrypted provider identifiers at the final
boundary, validates every offered decision and answer, and requires explicit
additional confirmation for positive high-risk or session-wide grants.

Profile setup follows a separate provider-neutral path: Compose edits generic
text, port, secret, choice, and read-only fields; the selected provider validates
them and owns persistence. Saved profiles can also expose generic operations
with provider-owned labels, supporting text, confirmation requirements, and
actionable redacted results. SSH maps those fields and operations to encrypted
credential references, jump-host selection, persistent non-exportable Android
keys, confirmed public-key installation, and key-only authentication probes.
Successful edits invalidate an existing managed connection and refresh
coordinator profiles.

Speech remains independent of agent and connection providers. The first
foundation exposes auditable model package metadata, explicit install/remove
operations, and separate recognition and playback state. Every active operation
has an opaque monotonically generated id, so a delayed stop or cancel event
cannot affect a replacement microphone or playback operation. Android now owns
verified private model storage, hardened HTTPS and tar.bz2 delivery adapters,
a coordinator that detaches canceled generations before potentially late
callbacks, permission-gated 16 kHz mono microphone capture, and speech-focused
streaming PCM playback. Audio focus, interruption, stale callbacks, and platform
resource cleanup remain operation-scoped. The sherpa module maps only a strict
streaming-transducer model layout into a bounded online recognizer and fences
stale cancellation, close, decode, and callback generations. Its reproducible
source build emits checked native libraries for four Android ABIs with embedded
licenses and provenance while excluding TTS. A production model catalog,
application permission/lifecycle composition, Compose controls, and physical-
device evidence are not yet implemented.

SSH routing stays below that generic boundary. The SSH provider resolves a
destination's configured profile references into an outermost-to-innermost
route, then JSch authenticates each hop with that hop's credential and host-key
set. Each later session opens through direct-tcpip on the preceding session.
Cycles, missing profiles, and excessive depth fail before transport access.

Changed files follow a separate read-only path. An agent provider reports file
changes; the coordinator classifies each path against the session workspace and
persists the result. Only a strict relative path can become a
`RemoteFileReference`. The selected connection runtime supplies
`RemoteFileAccess`: local access resolves canonical filesystem paths, while SSH
uses canonical SFTP paths. Both inspect a regular file with SHA-256 immediately
before export and stream bounded chunks while rechecking the revision and
digest. The app writes those chunks only to a user-selected Storage Access
Framework document, reports progress, and attempts to remove an incomplete
document on failure or cancellation. This contract allows future connection
providers to implement file access without adding transport assumptions to the
session or UI layers.

## Security boundaries

- Android's application sandbox is the local-process boundary.
- Local working directories are canonicalized below an app-controlled root.
- Every SSH hop's server identity is accepted only through an explicit trust
  decision.
- App-managed SSH private keys remain non-exportable in Android Keystore; public
  key installation sends only a normalized public key and requires an informed
  confirmation.
- Android Keystore keys encrypt namespaced authenticated documents.
- Credentials stay outside command arguments, events, and diagnostic snapshots.
- Stored secrets are never returned by a profile manager; replacement values
  cross one wipeable `CharArray`/byte-array boundary before encrypted storage.
- Raw agent-provided file paths remain in encrypted state and are normalized
  against the known workspace before presentation or transfer.
- Local and SFTP canonical-path checks block traversal and symlink escapes; only
  regular files are readable.
- Source size, modification time, and SHA-256 must match the inspected revision
  for the whole export.
- Android export uses a one-document SAF grant, not broad storage permission.
- Output, protocol lines, retention, retries, and process lifetimes are bounded.

See [Connection providers](CONNECTION_PROVIDERS.md) and
[Session hub](SESSION_HUB.md) for detailed invariants.

## State ownership

Connection lifecycle belongs to connection providers. Agent discovery and
protocol state belong to agent providers. Durable drafts, unread activity,
preferences, transcript cache, and changed-file records belong to the session
repository. Transfer progress belongs to the short-lived UI controller. Compose
is a projection of these sources and must not become a second authority.

The process lifecycle is a provider-neutral composition boundary. Moving the
app to the background serially suspends an already-created session runtime
unless the user explicitly started background connection mode. Returning to the
foreground resumes it only after any earlier suspension has finished. Lifecycle
events never initialize the runtime on their own. If the runtime is first
requested while the process is already backgrounded, it is suspended before
being returned unless the foreground service owns its connection lifetime.
Duplicate and obsolete transitions are coalesced, cancellation remains
structured, and failures become a detail-free observable lifecycle state.
Provider error text and connection details are never placed in that state or
the Android log.

The non-exported foreground service uses Android's remoteMessaging type for the
user-initiated relay of text between the phone and remote agent host. It is
non-sticky, never starts at boot, exposes fixed privacy-safe status only, and
always provides user-visible open and stop actions.

UI keys are derived from the complete provider-scoped locator. Session
navigation uses a fixed SHA-256 digest so saved navigation state cannot expose a
host, path, profile identifier, or unbounded agent-session identifier. Domain
state continues to use the lossless locator rather than the digest.
Approval and question UI keys follow the same one-way digest rule.

## Verification

Contract tests use fake runtimes and providers to validate the boundaries
without live credentials. Opt-in live checks verify installed tools in private
disposable environments. Android Keystore behavior additionally requires
connected device tests. The complete hosted gate is documented in
[Building](BUILDING.md).
