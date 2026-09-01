# Session hub and offline state

Status: Persistence, coordinator, and first adaptive interaction UI implemented
Last verified: 2026-09-01

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
- 2,000 approval or question requests across the hub; and
- 500 cached transcript entries per session.

Retention keeps pinned sessions first, then non-archived and most recently
active sessions. Actionable approval and question records are retained before
ordinary output when either bound is reached. Drafts, activities, action
requests, and transcripts belonging to an evicted session are removed in the
same snapshot, and unread counts are recomputed from retained activity.

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
   and
10. cancels agent work and clears stale issues when a connection goes offline.

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
- debounced draft persistence; and
- failed immediate-send preservation without provider exception disclosure.

API 36 emulator verification on 2026-09-01 ran the complete instrumented suite
with 18 of 18 tests passing: 15 application UI and accessibility tests, one SSH
Android test, and two encrypted-storage Android tests. The evidence verifier
independently checked all three module reports, the explicit emulator boot
record, and the exact discovered/run/skipped/failure/error counts.

Not yet implemented are queued or offline sending, voice input, artifact
transfer, and background notification dispatch. The remaining workflows keep
the same provider-neutral capability and full-locator boundaries.
