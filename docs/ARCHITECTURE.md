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

## Phone-local AI evidence boundary

Phone-local model discovery reports capability and validation evidence separately from
the model itself. API availability, model metadata, loadability, mocks, and virtual-device
checks are useful validation signals but do not prove physical on-device inference. Only a
`PHYSICAL_DEVICE` observation at `INFERENCE_VERIFIED` may be used to route real user data
to a phone-local model. Observations contain bounded identifiers and policy data, never
paths, addresses, hardware identifiers, or model content.

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

Endpoint trust uses the separate [endpoint identity contract](ENDPOINT_IDENTITY.md).
Daemon and client identities are fixed-length digests of public signing keys;
transport addresses and implementation process IDs never define endpoint
identity. Rotation and compromise recovery are explicit trust transitions.

Pairing is an explicit ceremony between a daemon identity and a client identity.
An authenticated proof is required before a grant is issued; approved scopes may
only narrow the request, and every grant has a bounded expiry, revisioned renewal,
and explicit revocation. Unknown, expired, rejected, replayed, or revoked grants
fail closed. Pairing records carry identities and capabilities, never protected
task content or transport metadata.

The daemon protocol is transport-independent: versioned authenticated frames,
capability negotiation, and protected ciphertext are defined in `:connection:api`.
Direct, private-network, NAT-traversal, and opaque-relay adapters carry the same
frames; they cannot inspect protected payloads or change the negotiated transcript.
Negotiation selects the highest common version and intersects capabilities, while
malformed, oversized, mismatched, or downgraded messages fail before adapter I/O.

Continuity tokens preserve the daemon identity, pairing grant and protocol
generation across reconnect and transport failover. The bounded reconnect state
machine rejects expired or changed tokens. Command IDs use a durable ledger:
in-flight duplicates are suppressed, completed commands are observed idempotently,
and partial delivery becomes an explicit unknown outcome that is never replayed
automatically.

Connectivity diagnostics are a bounded projection, not workflow authority. They
retain only opaque topology labels, transport/state transitions, durations, and
fault counters; retention overflow increments explicit dropped-data counters.
Local connectivity verification uses bounded named fault scenarios (delay, reset,
truncation, partition and path switching). Each scenario is replayable from a
non-negative seed and produces only redacted, deterministic events; it never opens
an unauthenticated diagnostic endpoint or performs network I/O.
Discovery and failover evidence remains deterministic and useful while excluding
addresses, paths, credentials, task content, and protected payloads.

Incident bundles are proof artifacts rather than raw log archives. Export requires
explicit authorization plus a one-document grant. The versioned manifest carries
only redacted evidence digests, bounded self-check results, artifact hashes and
omitted-data counts; AES-GCM protects the manifest and integrity verification is
performed before export. Prompts, transcripts, credentials, routes, commands and
paths are excluded by construction.

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

Diagnostic observations are bounded, provider-neutral projections rather than
a second state authority. `DiagnosticEvent` carries stable causal identifiers,
opaque workflow references, clocks, severity, outcome, and a stable failure
category; it never carries secrets or raw host, path, credential, or token
attributes. Its schema compatibility and explicit redaction/export policy are
validated at the session API boundary. Later capture and tracing components may
retain or export these observations, but domain events and the session
repository remain authoritative.

The bounded capture store accepts observations only through an explicitly
authorized, component-scoped session. It redacts before writing, assigns a
monotonic cursor and checksum, and enforces record, byte and age limits.
Queries are read-only projections filtered by causal references; expiry,
overwrites, coalescing, truncation and rejected writes are reported as bounded
counters. Corrupt tail frames are discarded during recovery. Capture cannot
acknowledge commands, determine completion, or drop effects, failures,
approvals or evidence links from their authoritative stores.

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
sticky only after an explicit start, never starts at boot, exposes fixed
privacy-safe status only, and always provides user-visible open and stop actions.
An authenticated no-backup document stores at most 64 exact provider/profile
keys that the user requested to connect. A system recreation accepts only
Android's null restart intent, reloads configured profiles, and reconnects the
intersection; malformed explicit intents and missing or corrupt recovery state
fail closed. Explicit stop deletes the recovery document. Force-stop and Android
user Stop remain stopped until a new user action.

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

## Android diagnostic build modes

The app exposes `debug`, `diagnostic`, `profileable`, and `release` variants. The diagnostic
variant is an explicitly selected, debuggable build with bounded runtime probes and expiring
opt-in coroutine dumps. The profileable variant is release-like and enables shell profiling
without enabling capture or dumps. All variants use the same authentication, authorization,
endpoint-trust, and redaction code paths; diagnostic controls never enable capture by default.
Stable `android.os.Trace` sections cover startup and may be extended around persistence,
connection, synchronization, and rendering boundaries without recording payloads or secrets.
