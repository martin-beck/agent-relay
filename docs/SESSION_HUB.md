# Session hub and offline state

Status: Persistence, coordinator, and first adaptive interaction UI implemented
Last verified: 2026-09-02

The session layer is independent of both connection implementations and agent
protocol implementations. It gives the app one durable identity and state model
for sessions reached over SSH, on the Android device, or through a future
connection provider.

~~~text
ConnectionProviderId
  ConnectionProfileId
    AgentProviderId
      AgentSessionId
~~~

All four identifiers form SessionLocator. The stable storage key uses
length-prefixed values rather than separators, so user-controlled profile or
session values cannot create ambiguous keys. An SSH session and a local session
with the same agent session identifier remain distinct.

## Module boundaries

:session:api owns:

- provider-neutral session identity;
- the latest observed provider and connection state;
- local pin, archive, and notification preferences;
- one independent draft and cursor selection per session;
- unread and actionable activity records with exact event anchors;
- durable approval/question requests and redacted decision audit state;
- a bounded recent transcript cache; and
- a bounded shelf of provider-reported changed-file records;
- the transactional repository and retention policy.

:session:android owns the encrypted SessionHubStore implementation. It serializes
primitive identifier values and enum names into a versioned document rather
than requiring connection or agent modules to expose persistence annotations.

Transport lifecycle remains in ConnectionProvider. Agent discovery, transcripts,
turns, and approvals remain in AgentProvider. Compose state must derive from the
session repository instead of storing a second authoritative copy.

## Durable update rules

PersistentSessionHubRepository loads and normalizes a complete snapshot before
publishing it. Every mutation then follows one order:

1. validate the referenced session and operation;
2. build and normalize the next immutable snapshot;
3. persist the complete next snapshot; and
4. publish it through StateFlow only after the write succeeds.

A failed encrypted write therefore leaves observers on the previous consistent
snapshot. There is no window in which the UI reports a draft, unread transition,
preference, pending action, or decision that cannot be restored after process
death.

Provider event identifiers are idempotency keys scoped to the complete session
locator. Replaying an already persisted activity performs no write and does not
increment unread state again, while the same remote identifier in another
session remains distinct. Mark-read uses an event-time boundary so opening older
content cannot accidentally mark a newer event read. Approval and question
records remain visible in the inbox until resolved even if they have already
been read.

An approval or question event writes its action request and linked activity in
one snapshot. A response validates the provider-offered decision and every
question answer, records **Delivering**, and only then calls the provider.
Successful delivery records **Resolved** and resolves the linked activity. A
provider failure after **Delivering** is deliberately treated as uncertain: the
durable state is not reset to pending because an automatic retry could approve
the same operation twice. Replaying the same provider event ID does not clear
that uncertainty. Positive high-risk and session-wide approvals cannot enter
**Delivering** without explicit additional confirmation.

Session observations and local preferences are separate. A fresh provider scan
can update title, preview, execution state, timestamps, or connection labels
without clearing pin, archive, mute, unread, draft, or cached transcript state.

## Encryption and corruption behavior

AndroidEncryptedSessionHubStore stores one authenticated document under
noBackupFilesDir through :storage:android. It has a namespace isolated from SSH
profiles and credentials:

| Property | Session value |
| --- | --- |
| Directory | session-secure-store |
| AES key alias | agent-relay.session.secure-store.v1 |
| Authenticated-data prefix | agent-relay:session-store:v1 |
| Document format | session-hub-v1, version 1 |

The Android Keystore AES-GCM key, associated data, hashed file name, owner-only
permissions, and atomic replacement behavior come from the shared secure
document layer. Serialized plaintext byte arrays are zeroed after both reads
and writes. Kotlin strings created during JSON decoding cannot be reliably
zeroed, so session persistence must not be treated as a general secret vault.

Malformed JSON, an unsupported format version, invalid identifiers, invalid
enum values, duplicate sessions, activities, or action requests, duplicate
draft/transcript groups, dangling references, and duplicate transcript entries
fail closed as SecureStoreCorruptException. Unknown fields are rejected. Corrupt data is not
silently replaced with an empty hub.

## Retention

The default bounds are:

- 200 session records;
- 2,000 activity records across the hub;
- 2,000 approval or question requests across the hub;
- 500 cached transcript entries per session; and
- 4,000 changed-file records across the hub.

Retention keeps pinned sessions first, then non-archived and most recently
active sessions. Actionable approval and question records are retained before
ordinary output when either bound is reached. Drafts, activities, action
requests, transcripts, and changed-file records belonging to an evicted session
are removed in the same snapshot, and unread counts are recomputed from retained
activity. The newest changed-file records are selected within their global
bound.

These are safety bounds, not a search or export policy. A future storage format
may shard sessions while preserving the same repository contract.

## Coordinator runtime

`:session:runtime` now:

1. enumerates generic profiles without assuming SSH;
2. isolates profile discovery failures so one provider cannot hide another;
3. opens an independent controller per complete connection profile;
4. obtains `RemoteAgentRuntime` only from connected generic profiles;
5. probes compatible agent factories and discovers sessions;
6. starts sessions with provider-neutral options on ready endpoints;
7. projects sessions and live events through the complete locator;
8. persists event and action state before exposing the updated hub;
9. routes capability-checked prompts, interruption, approvals, and questions;
10. maps provider file changes into durable workspace-relative artifact records;
11. prepares revision- and SHA-256-checked file streams only through the
    connected runtime's generic file-access boundary; and
12. cancels agent work and clears stale issues when a connection goes offline.

Duplicate provider/profile identifiers and invalid descriptors fail explicitly.
Reconnects replace stale agent connections and collectors without allowing an
older collector to mutate the new state. The coordinator does not downcast a
runtime to SSH or infer that the local provider has hosts, credentials, or host
keys.

The application now constructs the encrypted repository, generic connection
registry, coordinator, and agent registry once at application scope. Its
navigation-scoped ViewModel combines coordinator and repository flows, routes
generic connect/disconnect and identity decisions, and preserves selection
across compact detail navigation. Expanded screens show the same state in a
list-detail layout. Session navigation arguments contain only a SHA-256 digest
of the complete locator, while all actions resolve back to the exact lossless
locator in current durable state.

Ready endpoints expose a provider-neutral session launcher with optional working
directory and model fields. Pending approval/question cards display execution
context, exact command and scope, provider questions, risk reasons, and only the
decisions the provider offered. Raw provider request and question identifiers
are retained only in encrypted domain state; Compose receives stable digests.
Question answers translate back to exact provider identifiers only at the
runtime boundary. Risk and session-wide grants use a second confirmation, while
**Delivering** cards disable retry and explain the uncertain-delivery boundary.

Selected session detail now maps the endpoint descriptor, observed session state,
and `can_accept_input` metadata into provider-neutral Resume, Send, Steer, and
Interrupt controls. Each callback resolves the UI digest back to the exact full
locator. Composer changes are shown immediately, debounced into the encrypted
repository, and flushed before provider I/O so failure cannot strand a draft in
UI memory. Cached transcript rows are typed as user messages, agent commentary,
final answers, plans, reasoning summaries, tools, or system events rather than
relying on color or an undifferentiated transcript label.

Connection setup is routed through each provider's optional generic profile
manager. The session hub exposes add controls only for providers advertising
`PROFILE_MANAGEMENT`, marks their connection cards editable, and refreshes the
coordinator only after a successful save or delete.

Selected session detail also exposes a **Changed files** shelf when the agent
advertises `FILE_CHANGES`. Refresh persists safe records before Compose observes
them. Deleted, outside-workspace, and unknown-workspace entries remain visible
with bounded labels but cannot be exported. A downloadable entry resolves back
to the exact encrypted locator and artifact identity, then uses the connected
runtime's `RemoteFileAccess` contract. Compose never receives a raw provider
path.

Android export uses the Storage Access Framework document picker. The transfer
controller reports progress, supports cancellation, preserves completion state,
and requests best-effort partial-document deletion for every failed or cancelled
copy.

The first notification boundary is a pure projection of durable session
activity. It emits only SHA-256 notification and navigation keys, a coarse kind,
and an event time; it never emits the activity summary, connection labels,
targets, paths, provider identifiers, session identifiers, prompts, or commands.
Notification presentation therefore cannot accidentally treat sensitive
timeline text as lock-screen-safe content.

Unresolved approval and question activity remains eligible until resolved even
when marked read. IMPORTANT_ONLY additionally includes failures;
FINAL_OUTPUT_ONLY includes turn completion; ALL_ACTIVITY includes other unread
activity. MUTED emits nothing. Successful reconnects, read non-actionable
activity, and resolved actions never produce a notification. Projection is
bounded to 64 newest items after unresolved actions are promoted. Coordinator
issues are deliberately excluded until they have a durable attention record, so
no critical recovery state exists only in process memory.

Android delivery uses separate action-required, failure, and general-update
channels with high, default, and low importance respectively. Notifications use
fixed application text, private visibility, a generic public version, and
local-only delivery. Neither title, body, intent extras, nor notification tags
contain the source summary, connection label, host, path, provider ID, session
ID, prompt, or command. Tap routing accepts only a package-scoped action whose
URI path and sole digest extra contain the same lowercase SHA-256 key, then
resolves that digest against the current durable session snapshot.

Android 13 notification permission is never requested automatically. The ready
screen explains the privacy boundary and provides the explicit request action;
after terminal denial it links to the application's notification settings. Only
a non-sensitive requested-before boolean is stored. Missing runtime permission,
globally disabled notifications, or a disabled channel cause a fixed redacted
delivery failure so the event stays eligible for retry.

A serialized reconciler diffs each durable projection against successfully
applied sink state. Stable-key show and cancellation operations must be
idempotent: failures remain pending for the next snapshot, successful operations
are not repeated, and coroutine cancellation is never converted into a retry.
Results contain only counts and digest keys, never underlying sink failures. On
foreground entry it cancels applied notifications and establishes the current
durable projection as a baseline, so old unread activity is not replayed on the
next background transition.

The application-scope notification runtime serializes lifecycle and snapshot
changes. It suppresses the initial foreground snapshot, reconciles newly durable
activity while backgrounded, suppresses again before provider reconnection, and
can retry current state after permission is granted. Provider connections are
still suspended when the application enters the background, so this slice does
not provide continuous remote monitoring while Android keeps the app in the
background; a foreground-service transport lifecycle remains future work.

## Verification

Focused verification:

~~~bash
./gradlew \
  :session:api:test \
  :session:android:testDebugUnitTest \
  :session:runtime:test \
  :app:testDebugUnitTest \
  :app:compileDebugAndroidTestKotlin \
  spotlessCheck detekt
~~~

Coverage proves:

- full-tuple identity across SSH, local, and multiple profiles;
- privacy-safe, preference-aware, bounded notification projection;
- channel isolation, fixed private notification content, disabled-delivery
  retry, digest-only PendingIntent routing, and cancellation idempotence;
- foreground baselining, background-only dispatch, lifecycle serialization,
  permission retry, and cancellation preservation;
- explicit request/rationale/settings permission UI with automated Compose
  accessibility checks;
- preferences, drafts, inbox state, transcripts, and actions across repository
  reopen;
- idempotent activity/action replay and bounded mark-read behavior;
- write-before-publish failure atomicity;
- action/activity atomicity, decision transitions, and high-risk confirmation;
- pinned/actionable retention ordering;
- encrypted-store DTO round-trip across SSH and local sessions;
- dedicated Keystore namespace and plaintext byte-array clearing;
- fail-closed malformed, version-mismatch, and duplicate-record handling;
- capability/state mapping for supported and read-only providers;
- provider-neutral session launch, exact full-locator action routing, and raw
  provider identifier isolation;
- question validation and stable-ID-to-provider-ID translation;
- artifact path classification, encrypted round-trip, retention, and atomic
  event persistence;
- local real-path and SFTP canonical-path confinement, including symlink escape
  rejection;
- pre/post revision, size, and SHA-256 source-change detection;
- checked export progress, cancellation, redacted failure, and partial cleanup;
- debounced draft persistence; and
- failed immediate-send preservation without provider exception disclosure.

API 36 emulator verification on 2026-09-02 ran the complete connected suite with
26 of 26 tests passing: 23 application UI and accessibility tests, one SSH
Android test, and two encrypted-storage Android tests. The evidence verifier
independently checked all three module reports and their exact
discovered/run/skipped/failure/error counts.

Not yet implemented are queued or offline sending, voice input, changed-file
preview/diff/batch export, and continuous foreground-service-backed remote
monitoring. The remaining workflows keep the same provider-neutral capability,
safe-path, and full-locator boundaries.
