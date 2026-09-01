# Remote Agent Terminal product roadmap

- Status: Living document
- Last updated: 2026-09-01
- External edit timestamp: 2026-09-01T22:31:53+02:00 (Europe/Berlin)
- Edit origin: This roadmap revision was made outside the current autonomous development workflow.

## Product direction

Remote Agent Terminal is a project-oriented control plane for agent-assisted work on local and remote machines. Its primary interface is not a terminal transcript. It is the answer to three questions:

1. What needs my attention now?
2. Why does it need my attention?
3. What is the safest valid action I can take?

Everything that does not require human judgment must run reliably in the background. The application should discover capabilities, establish secure connections, maintain durable state, recover from ordinary failures, validate outcomes, and suppress routine noise without requiring the Android client to remain open.

The terminal remains available as an evidence and intervention surface. It must not be the only way to understand or operate a project.

## Human-attention contract

The product may request attention only when at least one of these conditions is true:

- a requested operation requires approval or expands a permission scope;
- multiple valid choices require human judgment;
- automatic recovery has exhausted a declared retry or time budget;
- an outcome contract failed or cannot be proven;
- an operation has an uncertain external effect and must not be repeated automatically;
- concurrent work produced a conflict that cannot be resolved deterministically;
- a cost, time, resource, or policy budget is close to or beyond its limit;
- a trusted identity, endpoint, capability, or security posture changed unexpectedly;
- a workflow explicitly requires review, authorship, or final acceptance.

Routine progress, transient reconnects, successful retries, expected validation, deduplicated notifications, bookkeeping, indexing, and completed background work must not interrupt the user.

Every attention item must contain:

- a stable identifier and deduplication key;
- the affected project, machine, workflow, and step;
- the reason human attention is required;
- risk and reversibility classifications;
- the smallest decision being requested;
- the exact authority or permission that would be granted;
- relevant evidence and a concise impact summary;
- a safe default, including what happens if the user does nothing;
- an expiry or next review time when applicable;
- a resolution record that can be audited later.

Attention items are stateful records, not transient notifications. A notification is only one projection of an attention item.

## Product principles

1. **Ask only for judgment.** Automation performs routine detection, preparation, execution, verification, retry, and cleanup.
2. **Never invent success.** Completion is shown only when declared evidence and outcome contracts are satisfied.
3. **Make scope visible.** Every operation exposes its machine, project root, execution target, identity, permissions, and expected effects.
4. **Prefer deterministic safety.** Idempotency keys, compare-and-set transitions, fencing tokens, bounded retries, and explicit uncertain states are mandatory where effects can be repeated.
5. **Treat capability claims as untrusted.** Discovery proposes candidates; authenticated handshakes and live probes establish usable capabilities.
6. **Keep background work independent of the phone.** The host daemon owns durable execution. Android observes and controls it.
7. **Preserve privacy.** Discovery announcements contain no secrets, prompts, repository contents, or credentials. Relays cannot read end-to-end encrypted payloads.
8. **Design for interruption.** Process death, app suspension, network changes, reboots, duplicate delivery, and out-of-order events are normal operating conditions.
9. **Bound resource use.** Background work has explicit CPU, memory, storage, concurrency, cost, and time budgets.
10. **Fail closed at trust boundaries.** Identity or authorization ambiguity stops privileged work and creates one actionable attention item.
11. **Use the UI as a projection of durable state.** No critical state may exist only in a screen, notification, WebSocket, or process memory.
12. **Retain a complete evidence path.** Every significant conclusion links back to commands, events, artifacts, checks, and approvals.

## Implemented foundation

The current foundation includes:

- local and SSH connection providers;
- persistent session and project state;
- streamed remote-agent events;
- explicit remote-agent lifecycle control;
- checked workspace file access;
- safe changed-file artifact capture and export;
- an Android application capable of hosting the interaction surface.

All subsequent work must preserve these capabilities while moving state authority and long-running operations into durable services.

## Target architecture

```text
Android application
  Presentation
    Needs my attention
    Projects and workflows
    Background activity
    Evidence, artifacts, and terminal intervention
  Application services
    Attention service
    Workflow service
    Policy and approval service
    Pairing and device-trust service
    Connection and discovery service
    Evidence service
  Durable client repositories
    Cached projections
    Command outbox
    Event cursor and acknowledgement state
    Android-keystore identity and paired-daemon grants
  Daemon connection provider
    Transport-independent authenticated protocol
    Peer transport and path selection
      |-- direct LAN or VPN TLS/QUIC -------------------------|
      |-- NAT-traversed QUIC when available ------------------|
      `-- outbound WSS/443 --> Shared rendezvous/opaque relay |
                                   ^                         |
                                   `-- daemon outbound WSS --|
                                                             v
Paired host daemon
  Peer transport with the same stable identity on every path
  Identity, pairing, and device grants
  Durable append-only event journal
  Workflow and recovery engine
  Policy enforcement and permission leases
  Discovery and capability registry
  Evidence and artifact store
  Execution-target adapters
    Host process
    Container
    Cluster workload
    Future target adapters
  Agent runtime adapters
    Native remote-agent runtime
    Generic Agent Client Protocol runtime
             |
             v
Local tools, repositories, agents, model services, CI systems, and runtimes
```

The shared relay accepts outbound connections from both endpoints and routes pairing and operational ciphertext. It never owns workflow state, device grants, or plaintext.

### Required domain boundaries

The implementation should converge on explicit modules or packages with stable interfaces:

- `attention`: attention-item lifecycle, deduplication, routing, urgency, expiry, and resolution;
- `event`: versioned normalized event schemas, ordering, replay, redaction, and projection;
- `workflow`: durable definitions, step state machines, recovery policy, budgets, and outcome contracts;
- `policy`: risk classification, approvals, permission leases, and enforcement decisions;
- `evidence`: artifacts, validation results, provenance, retention, and export bundles;
- `connection`: endpoint identity, transport lifecycle, health, reconnect, and multiplexing;
- `discovery`: candidate detection, endpoint verification, trust enrollment, and capability refresh;
- `pairing`: one-time pairing windows, QR payloads, transcript verification, reciprocal confirmation, grants, revocation, and rotation;
- `peer-transport`: direct, NAT-traversed, and relayed paths behind one authenticated stream contract;
- `relay-protocol`: outbound registration, rendezvous, opaque routing, quotas, health, and relay failover;
- `daemon-protocol`: version negotiation, commands, events, acknowledgements, snapshots, and compatibility;
- `daemon-runtime`: journal, scheduler, recovery engine, local service supervision, and resource control;
- `execution-target`: host, container, and cluster execution semantics;
- `provider-api`: stable extension interface for connection, execution, and agent runtime adapters;
- `provider-acp`: generic Agent Client Protocol integration;
- `local-intelligence`: private indexing, search, summarization, and optional voice preparation.

Module boundaries must prevent UI code from becoming the state authority and prevent a transport implementation from defining workflow semantics.

### State authorities

| State | Authoritative owner | Android responsibility |
|---|---|---|
| Workflow definition and run state | Host daemon | Cache and render projections; submit commands through the outbox |
| Command intent before delivery | Android durable outbox | Persist before sending; retain until terminal acknowledgement |
| Command acceptance and effects | Host daemon journal | Display acknowledged state and reconcile by command ID |
| Session and agent events | Host daemon journal | Consume by cursor; rebuild projections after gaps |
| Attention items | Host daemon for remote work; local repository for device-only work | Present, filter, notify, and submit resolutions |
| Approvals and permission leases | Policy service on the enforcing host | Display exact scope; collect explicit decision |
| Artifacts and evidence | Host evidence store with checked client cache/export | Verify metadata and expose safe export/share actions |
| Endpoint trust | Paired identity store on both endpoints | Pin identity and surface unexpected change |
| Pairing window and one-time secret | Host daemon | Display only during explicit enrollment; never persist after consumption or expiry |
| Phone private identity key | Android Keystore | Prove possession without exporting the key |
| Device grant and permission scope | Host policy store | Cache the signed grant; request expansion explicitly |
| Active route and candidate addresses | Connection provider | Treat as replaceable transport state, never as endpoint identity |

## Event, state, and operation model

### Normalized event model

The application must move beyond parsing terminal text. All providers should map into versioned typed events such as:

- workflow created, queued, started, paused, resumed, completed, failed, or cancelled;
- step started, progress updated, blocked, retrying, recovered, or finished;
- command proposed, approved, started, output produced, effect uncertain, or completed;
- file inspected, changed, conflicted, validated, captured, or exported;
- check started, passed, failed, skipped with reason, or became stale;
- artifact produced, verified, superseded, expired, or removed;
- permission requested, granted, denied, expired, revoked, or consumed;
- connection discovered, paired, connected, degraded, lost, or recovered;
- capability detected, verified, changed, withdrawn, or rejected;
- child task spawned, waiting, merged, conflicting, or terminated;
- budget updated, threshold reached, exhausted, or replenished;
- attention item opened, updated, snoozed, escalated, resolved, or invalidated.

Every event must include:

- schema version, event ID, correlation ID, and causation ID;
- project, workflow, run, step, endpoint, and actor identities where applicable;
- daemon-local monotonic sequence plus wall-clock timestamp;
- sensitivity and redaction classification;
- retry, risk, and reversibility classifications when relevant;
- compact summary plus structured payload;
- links to associated evidence and prior state.

Providers may retain provider-specific payloads, but product behavior must rely on normalized fields.

### Durable journal and delivery semantics

The host daemon must:

- append an accepted command and its idempotency key to durable storage before acknowledging it;
- use checksummed, versioned records and periodic atomic snapshots;
- recover incomplete workflows after daemon or host restart;
- expose cursor-based replay and bounded snapshots for a newly connected client;
- compact only after preserving required audit and evidence retention;
- detect corrupt or incomplete journal tails and recover to the last verified boundary;
- record clock source and avoid using wall-clock ordering as the only ordering mechanism.

Transport delivery is at least once. Effect execution is exactly once where the target supports an idempotent operation, and otherwise is guarded by an explicit state machine:

```text
Pending -> Delivering -> Acknowledged -> Resolved
                    \-> Uncertain ----> Resolved by observation or human decision
Pending or Delivering -> Cancelled, only before an irreversible effect begins
```

Rules:

- Every mutating command has an idempotency key and expected prior-state version.
- Duplicate commands return the recorded result without repeating the effect.
- A generation or fencing token prevents an old worker from acting after leadership or reconnect changes.
- An operation with an uncertain non-idempotent effect is never blindly retried.
- Recovery first observes target state, then either proves completion, safely resumes, or opens one attention item.
- Backpressure is explicit. Low-value progress may be coalesced; decisions, effects, errors, approvals, and evidence may not be dropped.
- Cancellation is a state transition with a proven outcome, not merely a request to kill a process.

### Completion contracts

A workflow is complete only when its contract is satisfied. A contract can require:

- a clean or intentionally changed worktree with an enumerated change set;
- named checks with freshness timestamps and exact versions;
- expected artifacts with hashes, sizes, provenance, and retention policy;
- no unresolved critical findings or attention items;
- resource and cost budgets remaining within policy;
- a delivery state such as local-only, committed, pushed, deployed, or explicitly not delivered;
- human acceptance where authorship or judgment cannot be delegated.

Contracts are machine-readable, versioned, inherited from project policy, and shown before execution. The final summary is generated from contract evidence rather than narrative claims.

## Secure connectivity and autonomous discovery

### Provider taxonomy

Three concepts must remain separate:

1. **Connection provider:** how the application reaches and authenticates an endpoint.
2. **Execution target:** where a command or workflow runs after connection.
3. **Agent runtime provider:** which agent protocol and capabilities execute agent work.

This separation avoids duplicating SSH, pairing, policy, and state logic for every runtime target.

### Connection providers, in implementation order

1. **Local provider** — existing on-device development and testing path.
2. **SSH provider** — existing general remote bootstrap and fallback path. It
   includes strict per-hop host keys and credentials, bounded ProxyJump-style
   direct-tcpip routes, reconnect and keepalive, multiplexed runtimes, persistent
   app-managed keys, confirmed public-key installation, and key-only probes.
   Agent forwarding remains deferred behind an explicit policy.
3. **Paired daemon provider** — the primary durable path. It supports direct LAN/VPN access and relay-assisted access without changing endpoint identity.
4. **Generic ACP provider** — interoperable structured agent sessions through Agent Client Protocol, behind the same event, policy, and workflow abstractions.
5. **Agentless Windows provider** — PowerShell remoting over SSH or HTTPS only after the provider can meet the same identity, cancellation, quoting, encoding, filesystem, and outcome contracts.
6. **ADB provider** — only when concrete Android-device workflows justify its distinct lifecycle and security model.

SSH jump hosts, VPN routes, direct-versus-relay selection, and port forwarding are connection features, not independent providers.

WSL environments should use the paired daemon inside the selected distribution or a Windows-daemon execution-target adapter. Termux environments should use loopback SSH or the paired daemon. These environments must reuse the same connection and execution contracts rather than introduce address-form-specific providers.

### Execution targets, in implementation order

1. Host process execution on Linux, macOS, and Windows.
2. Docker and Podman containers through an execution-target adapter.
3. Kubernetes workloads through an execution-target adapter.
4. Restricted sandbox or ephemeral workspace targets driven by project policy.

Execution-target identity must be explicit. A container ID, image digest, cluster identity, namespace, pod UID, and working root are part of the operation scope. Restarted or replaced workloads receive a new fencing generation. Direct container or cluster sockets are treated as highly privileged; daemon mediation is preferred.

### Reachability and transport model

The Android device and host daemon are not required to accept connections from the public Internet. They must either be directly routable on a shared LAN or VPN, or both must be able to create outbound connections to at least one common rendezvous and relay service.

QR enrollment establishes identity and authorization; it does not create network reachability. The connection provider must therefore:

1. establish an immediately usable outbound relay path;
2. concurrently test verified LAN, VPN, IPv6, and NAT-traversed candidates;
3. select a healthier direct path when available without changing endpoint or session identity;
4. fall back to the relay without repeating application effects; and
5. keep WSS over TCP 443 available when UDP or direct traffic is blocked.

The direct and relayed paths carry the same authenticated, versioned application protocol. Commands, event cursors, idempotency, and workflow state remain above the path-selection layer.

### Paired host daemon protocol

The paired daemon must provide:

- stable device and daemon signing identities;
- explicit, expiring QR or short-code pairing windows;
- mutual endpoint authentication with forward-secret session keys;
- certificate or public-key pinning with an explicit rotation workflow;
- end-to-end authenticated encryption for relayed connections so the relay sees only routing metadata, timing, and ciphertext size;
- protocol and capability negotiation with backward-compatible schema evolution;
- independent logical streams for commands, events, artifacts, and health;
- resumable event cursors and chunked, hash-verified artifact transfer;
- revocable device enrollment and least-privilege per-device grants;
- rate limits, replay protection, bounded message sizes, and audit records;
- no bearer secret in discovery broadcasts, logs, deep links, notifications, or diagnostic exports.

The relay must not become a state authority. It forwards opaque frames and may retain encrypted delivery envelopes only within a declared window. The daemon remains authoritative while the phone is disconnected.

### QR pairing contract

The daemon creates a QR code only after an explicit pairing request and after registering at least one usable direct or relay route. Use a compact, canonical, versioned payload containing:

- protocol and QR-format versions;
- daemon stable public identity and display name;
- a random pairing-slot identifier of at least 128 bits;
- a random single-use pairing secret of at least 256 bits;
- creation and expiry times, normally limiting the window to two to five minutes;
- an ordered set of acceptable relay locations;
- optional LAN or VPN address hints, marked as untrusted and replaceable;
- the requested permission template;
- a signed capability-manifest hash; and
- a daemon signature binding every field.

The QR contains no private key, permanent bearer credential, repository data, user credential, or authority beyond the expiring pairing request. It is scanned inside the application rather than opened as a normal web URL. The daemon must never log the encoded payload and must clear terminal-rendered QR data after expiry as far as the terminal permits.

A high-entropy scanned secret is used with a reviewed PSK-authenticated key-exchange construction. A human-typed short-code fallback must use a reviewed password-authenticated key exchange; it must never be converted directly into an encryption key. The authenticated transcript binds both endpoint keys, the slot ID, both fresh nonces, protocol version, relay set, expiry, and requested permission scope.

### Pairing procedure

1. The user runs an explicit daemon pairing command with a permission template and expiry.
2. The daemon creates or loads its stable identity, reserves a one-time slot, registers reachable routes, and displays the QR plus a redacted fingerprint.
3. Android scans and validates the payload, expiry, signature, supported version, relay scheme, and bounds before performing network activity.
4. Android creates a non-exportable phone identity in Android Keystore and connects outbound through the named relay or directly through a verified candidate.
5. Both endpoints complete the authenticated key exchange through the opaque route. Android proves possession of its new key; the daemon proves the stable identity encoded in the QR.
6. Android displays the daemon identity, relay domain, requested permissions, and security implications.
7. The daemon displays the phone label, fingerprint, permission scope, and a short transcript-derived authentication value. The user reciprocally confirms the phone on the host.
8. The daemon atomically consumes the slot, stores the phone public key, and issues a signed, revocable, least-privilege device grant. The phone stores its key reference, daemon pin, grant, and profile transactionally.
9. Both sides exchange key-confirmation messages and report success only after durable storage completes. Partial failure leaves no reusable pairing slot.
10. Later connections use the durable identities and grant, resume by cursor, and require no new QR while identity and scope remain valid.

An unattended enrollment mode may be added only as an explicit daemon option that accepts exactly one device within the short window under a fixed restrictive permission template. It must not be the default.

### Operational connection and Android background behavior

- The paired daemon profile has one stable identity regardless of direct, VPN, NAT-traversed, or relayed path.
- The daemon maintains bounded outbound relay registrations and, when high availability is enabled, simultaneous registrations with at least two independent configured relays.
- Android connects on demand, immediately uses the relay, and probes better paths in parallel. Path migration occurs below the command and event protocol.
- mDNS and rendezvous data provide candidates only. Every selected path must prove the pinned endpoint identity.
- Android process death, Doze, or network change is recovered through durable client state and event-cursor replay; the daemon continues working independently.
- Optional push contains only an opaque, collapsible wake hint. Commands, decisions, project details, and evidence are fetched through the end-to-end encrypted daemon connection.
- Without an available push service, synchronization on app open remains correct; continuous monitoring requires an explicit user-visible foreground service.
- Relay or direct-path loss is background recovery. Attention is requested only when all allowed routes exhaust their budgets, endpoint identity changes, or an operation outcome becomes uncertain.

### LAN discovery and service enrollment

Discovery finds candidates; it never grants trust.

Use DNS-SD over mDNS as the primary LAN mechanism for project-owned services. The paired daemon advertises a service type such as `_agent-relay._tcp.local` with only non-sensitive metadata:

- protocol major version;
- stable public identity fingerprint or truncated pairing identifier;
- capability-manifest path;
- pairing-required flag;
- optional human-readable device label chosen by the owner.

The client resolves the address, establishes an authenticated handshake, compares identity, reads the signed capability manifest, and then either reconnects to an enrolled device or offers an explicit pairing action.

For existing model services such as Ollama:

- first accept manually configured endpoints and daemon-reported localhost services;
- support a user-initiated, bounded LAN discovery window as a fallback because a model server may not advertise itself;
- scan only local routed subnets and a small allowlist of configured ports, initially TCP 11434;
- use short connection timeouts, strict concurrency limits, cancellation, and per-network rate limits;
- verify a candidate using harmless version and capability endpoints before displaying it;
- never submit prompts, model names from private policy, credentials, or repository data during discovery;
- show the detected address, server identity information available, transport security, and trust implications before enrollment;
- default remote Ollama access to daemon mediation or an authenticated TLS reverse proxy; do not treat an unauthenticated plain-HTTP listener as trusted;
- cache negative and positive results with expiry, and invalidate them on network change;
- allow discovery to be disabled globally or per network.
- never change an Ollama bind address, firewall rule, reverse proxy, or authentication setting merely to make discovery succeed.

SSDP or manually imported service manifests may be optional compatibility inputs. They are candidate sources only and must pass the same verification and enrollment flow.

The signed capability manifest should describe:

- endpoint and protocol versions;
- supported agent runtimes and model services;
- execution targets;
- filesystem and artifact limits;
- interactive and background capabilities;
- policy and approval features;
- maximum message and transfer sizes;
- health and expiry information.

Capabilities are periodically revalidated. Any security-sensitive drift suspends privileged automation until acknowledged.

## Prioritized roadmap

Priority is determined by the dependencies needed to deliver trustworthy unattended work and a useful attention interface. A later priority must not bypass an incomplete safety dependency from an earlier priority.

## P0 — Complete the trustworthy interactive foundation

### Goal

Make every existing local and SSH session understandable, recoverable, and safe enough to serve as the fallback for future automation.

### Deliverables

- Finish the checked artifact pipeline: changed-file inventory, provenance, safe preview, export, stale detection, and cleanup.
- Replace raw transcript-only status with a structured session timeline built from normalized events.
- Add connection profiles with explicit host identity, project root, default agent, reconnect policy, and last verified capability state.
- Implement strict SSH host-key handling, clear first-use enrollment, key-change attention items, keepalive, reconnect, and bounded retry.
- Persist draft input, pending commands, active session identity, scroll position, and event cursor across Android process death.
- Add scoped notification channels: approval required, recovery exhausted, contract failure, and optional completion summaries.
- Provide accessible controls, readable event summaries, reliable clipboard behavior, and foreground-service handling only while Android itself must maintain an active operation.
- Add structured error categories and remediation actions instead of generic connection errors.
- Establish redaction rules for logs, notifications, exported evidence, and crash reports.

### Exit criteria

- Killing and restarting the Android app does not lose accepted input or create duplicate remote effects.
- Temporary loss of network or SSH reconnects without user intervention and reconstructs the same timeline.
- Host-key changes cannot be silently accepted.
- Every exported artifact is tied to a project, session, source path, content hash, and capture time.
- Existing local and SSH behavior has automated lifecycle, reconnect, process-death, and artifact tests.

## P1 — Introduce the project-first attention control plane

### Goal

Make `Needs my attention` the primary daily surface and demote terminal interaction to evidence and intervention.

### Deliverables

- Add top-level views: `Needs me`, `Background`, `Projects`, `Recently completed`, `Failed and recovering`, and `Connections`.
- Introduce durable project, workflow, run, step, evidence, and attention-item entities.
- Implement the normalized event schema and provider-to-event mapping for local and SSH sessions.
- Build deterministic attention rules, deduplication, aggregation, snooze, expiry, escalation, and resolution.
- Add return-to-work summaries generated from state changes since the user’s last acknowledged cursor.
- Show project health using verified facts: active work, blocked work, latest checks, pending decisions, changed files, resource use, and delivery state.
- Permit one-tap safe actions when no further input is needed: approve once, deny, retry after observation, cancel before effect, open evidence, or resume intervention.
- Add explicit quiet-hours and urgency policy without hiding expired approvals or security events.
- Allow users to declare which workflow events deserve notification; all others remain in the background timeline.

### Exit criteria

- A user returning after hours can understand all material changes without opening a terminal transcript.
- Duplicate underlying events create one attention item.
- Every attention request shows why automation stopped and the exact consequence of each action.
- No workflow can mark itself complete without a recorded completion contract result.

## P2 — Add the paired host daemon and fault-tolerant background control

### Goal

Run long-lived sessions and workflows independently of Android connectivity while preserving secure, resumable control.

### Deliverables

- Build a small installable daemon for Linux first, followed by macOS and Windows.
- Add `:connection:daemon-api` and `:connection:daemon-android` behind the existing generic connection-provider contract; direct and relay paths must not appear as separate profiles.
- Add `:pairing:api`, `:transport:api`, `:transport:wss-relay`, and host-side daemon-protocol/runtime packages with no UI or agent-provider dependency.
- Implement the complete signed QR-pairing state machine, reciprocal confirmation, transactional grant persistence, revocation, and authenticated identity rotation.
- Establish an outbound WSS/443 opaque-relay baseline before adding direct-path optimization.
- Add direct LAN/VPN connections using the same authenticated protocol and race them against the already-working relay path.
- Evaluate a maintained QUIC/NAT-traversal transport through a bounded conformance spike; adopt it only if Android packaging, lifecycle, key storage, relay fallback, and resource-use gates pass.
- Add the append-only journal, atomic snapshots, command idempotency, fencing, and cursor replay.
- Add durable scheduling, resource budgets, restart recovery, process supervision, and log retention.
- Support direct LAN/VPN discovery through DNS-SD and explicit endpoint configuration.
- Add bounded relay registration, health, failover, quotas, abuse controls, and end-to-end encryption with the same pinned endpoint identity.
- Add optional opaque push wake hints while preserving correct synchronize-on-open behavior without push.
- Implement verified capability manifests and live capability refresh.
- Add bounded, user-initiated discovery of existing Ollama endpoints and secure enrollment guidance.
- Bootstrap daemon installation over SSH with a preview of files, service configuration, ports, identity, and rollback steps.
- Keep SSH as a recovery and bootstrap path even when the daemon is installed.

### Exit criteria

- A new installation pairs through one QR scan and one reciprocal host confirmation without opening a router port or manually entering an address.
- The same pairing flow works when the common network is the public Internet or a shared VPN.
- Expired, replayed, concurrently redeemed, malformed, downgraded, or partially persisted pairing attempts fail closed and cannot create a grant.
- The relay cannot decrypt pairing completion, commands, events, artifacts, or credentials.
- A workflow continues through Android suspension, phone reboot, network switch, and multi-hour disconnect.
- Daemon restart recovers every accepted command to a proven terminal state without duplicate effects.
- Direct and relay paths preserve the same endpoint identity and event sequence.
- Pairing, identity rotation, device revocation, relay failover, replay protection, protocol downgrade, path switching, and Android process death have automated fault and security tests.
- Discovery never enrolls or authorizes a service without an authenticated user-visible trust step.

## P3 — Make outcomes provable

### Goal

Replace narrative completion claims with declared contracts and compact evidence.

### Deliverables

- Add project-level completion-contract templates and per-run overrides.
- Capture commands, exit status, environment fingerprints, check versions, artifacts, changed files, and delivery state as linked evidence.
- Detect stale evidence when source files, dependencies, target identity, or policy changes.
- Produce a final proof bundle containing summary, contract, results, approvals, artifacts, hashes, unresolved risks, and a replayable event index.
- Add deterministic validation steps and provider-specific validation adapters.
- Distinguish `completed`, `completed with accepted exceptions`, `failed`, `cancelled`, and `outcome uncertain`.
- Add retention, redaction, encryption, export, and deletion policies for evidence.

### Exit criteria

- Every completed workflow can explain which contract was met and link to its proof.
- Editing relevant source invalidates affected stale checks automatically.
- Evidence export is hash-verified and excludes secrets according to policy.
- The UI never presents an uncertain effect as success or harmless failure.

## P4 — Add scoped permission leases

### Goal

Allow routine, low-risk work to proceed unattended without granting unlimited authority.

### Deliverables

- Define permission dimensions: project, path, target, command class, network destination, credential use, side-effect class, duration, count, resource budget, and delivery boundary.
- Support one-time approvals and time-, count-, or workflow-bounded leases.
- Classify operations by risk and reversibility before execution.
- Show a human-readable policy explanation and the exact machine-readable scope.
- Enforce policy on the host daemon, not only in Android.
- Revoke leases immediately, expire them deterministically, and record every use.
- Require new approval when target identity, project root, command semantics, or effect scope changes.
- Provide project policy templates for read-only inspection, local edits, validation, artifact export, network access, commit, push, deployment, and destructive cleanup.

### Exit criteria

- A lease cannot authorize work outside its declared project, target, duration, or effect class.
- Privilege expansion always opens a new attention item.
- Revoked or expired authority cannot be used by an offline or stale worker.
- Audit records identify the policy decision and lease use for every privileged effect.

## P5 — Add durable host-side workflows and watch rules

### Goal

Automate recurring work as event-driven workflows whose normal path is silent.

### Deliverables

- Define declarative, versioned workflow files with triggers, inputs, steps, policies, budgets, recovery, and completion contracts.
- Support scheduled, filesystem, repository, service-health, CI, webhook, and manual triggers.
- Debounce and deduplicate triggers; serialize or merge runs according to project policy.
- Add bounded retry with exponential backoff and jitter for transient failures.
- Add checkpoints and resumable steps for long operations.
- Implement compensation only when it is safe and explicitly declared.
- Add dry-run, simulation, pause, resume, cancel, and run-history inspection.
- Ship initial workflow templates:
  - inspect and summarize a new failure;
  - reproduce, diagnose, patch, and validate locally;
  - review changed files and produce an evidence bundle;
  - watch a repository or CI result and escalate only actionable failures;
  - maintain a local model-service inventory and report capability or trust drift;
  - prepare a delivery, stopping before commit, push, or deploy unless policy permits it.

### Exit criteria

- Repeated triggers cannot create duplicate non-idempotent work.
- Transient infrastructure failures recover within policy without notifying the user.
- Exhausted recovery produces one contextual attention item with evidence and safe options.
- Workflow upgrades preserve or explicitly migrate in-flight state.

## P6 — Add open provider and runtime extensibility

### Goal

Support additional agents, connections, and execution targets without coupling them to product state or UI behavior.

### Deliverables

- Publish a versioned provider API with lifecycle, capability, event, cancellation, artifact, health, and error contracts.
- Add a generic ACP runtime provider.
- Add compatibility and conformance tests, including reconnect, duplicate delivery, cancellation, backpressure, malformed events, and capability drift.
- Isolate providers by process or equivalent boundary when they handle untrusted data or optional dependencies.
- Require providers to declare permissions, protocol versions, limits, and sensitive-data behavior.
- Provide a provider diagnostic report that can be attached without exposing secrets.
- Add execution-target adapters for Docker/Podman, then Kubernetes, after the target identity and fencing contracts are complete.
- Add Windows and ADB connection providers only when they pass the same provider conformance suite.

### Exit criteria

- A provider cannot bypass policy enforcement or directly mutate attention state.
- Provider failure is contained and recoverable without losing authoritative workflow state.
- Unsupported capabilities are disabled based on live negotiation, not assumed from provider type.
- New providers can be added without changing core workflow or presentation logic.

## P7 — Add isolated multi-agent and multi-target workflows

### Goal

Coordinate parallel work while preserving ownership, isolation, merge safety, and a single coherent attention surface.

### Deliverables

- Model parent-child tasks, dependencies, budgets, deadlines, and evidence inheritance.
- Give each mutating worker an isolated worktree, container, branch, or equivalent workspace.
- Detect overlapping files and semantic conflicts before integration.
- Support reviewer, implementer, tester, and investigator roles with different leases.
- Aggregate child progress into one workflow summary and deduplicated attention stream.
- Add deterministic merge gates and post-merge validation contracts.
- Fence abandoned or superseded workers so they cannot publish late effects.
- Permit execution across multiple paired hosts while recording target identity and data movement.

### Exit criteria

- Parallel workers cannot silently overwrite one another.
- A late or disconnected worker cannot act after its lease or generation is superseded.
- Integration failure preserves all isolated evidence and asks one bounded human question.
- The user can understand the combined outcome without reading every child transcript.

## P8 — Add private local intelligence and low-friction entry points

### Goal

Make project context and control immediately accessible without sending private history to an external service unless explicitly configured.

### Deliverables

- Build an encrypted local index of projects, events, evidence, artifacts, decisions, and run summaries.
- Add natural-language search with links back to authoritative records.
- Add compact on-device or enrolled-LAN-model summarization with provenance and confidence boundaries.
- Let users choose a discovered Ollama service per project or task, with explicit data-routing policy.
- Add Android shortcuts, widgets, share targets, and notification actions for common attention decisions.
- Add optional voice capture that prepares a draft intent and scope for confirmation before execution.
- Support commands such as “show what needs me,” “summarize overnight changes,” and “prepare a validated fix,” mapped to visible workflows rather than opaque actions.
- Keep indexing, embedding, and summarization resource-bounded and resumable.

### Exit criteria

- Search results link to exact evidence rather than presenting unsupported answers.
- No project content reaches a discovered or remote model without matching data-routing policy.
- Voice and natural-language requests show the interpreted scope before privileged execution.
- Index rebuild and model unavailability degrade gracefully without blocking core workflow control.

## P9 — Add auditable collaboration

### Goal

Allow multiple authorized people and devices to participate without losing accountability or creating conflicting authority.

### Deliverables

- Add user, device, team, and service identities with role-based and project-scoped policy.
- Attribute every command, approval, lease, and resolution to an authenticated actor.
- Support shared attention queues, assignment, handoff, and acknowledgement.
- Add optimistic concurrency and explicit conflict handling for simultaneous decisions.
- Provide encrypted project sharing and evidence access with revocation.
- Add signed audit export and retention controls.
- Keep personal notification policy separate from authoritative workflow state.

### Exit criteria

- Concurrent decisions cannot silently overwrite one another.
- Revoked users and devices lose access without invalidating retained audit history.
- Every externally meaningful effect has an attributable authority chain.

## Typical user workflows

### 1. Pair a machine and start the first project

1. The user installs the daemon locally or bootstraps it over an existing SSH connection.
2. The user requests a two-minute pairing window and selects the initial permission template.
3. The daemon registers an outbound route, creates a stable identity and single-use slot, and displays the signed QR code.
4. Android scans the code, creates its non-exportable identity, connects outbound, and completes the authenticated exchange through a direct or opaque-relay path.
5. Android shows the daemon fingerprint, relay domain, and requested permissions; the daemon shows the phone fingerprint and matching authentication value.
6. The user confirms the phone once on the host. Both endpoints transactionally store the pinned identities and revocable device grant, and the slot is consumed.
7. The daemon detects available repositories, agent runtimes, container runtimes, and local model services using harmless probes.
8. Android shows verified candidates. The user selects a project root and completion defaults.
9. Later connections use the relay immediately, test direct paths in parallel, and resume by identity and event cursor without re-pairing.

Only steps 2, 5, 6, and 8 require attention. Route registration, key confirmation, capability probes, journal setup, path selection, reconnection, and cursor replay run in the background.

### 2. Return after several hours

1. The daemon continued the approved workflow while the phone was offline.
2. Transient network and process failures were retried within budget and recorded without notification.
3. Android reconnects, presents its last acknowledged cursor, and receives the missing event range or a verified snapshot.
4. `Needs me` shows only unresolved decisions: for example, a failed outcome contract or approval to publish.
5. The project card summarizes completed steps, current changes, validation, resource use, and delivery state.
6. The user can open exact evidence or the terminal only when deeper inspection is useful.

### 3. Grant routine validation authority

1. A workflow proposes read-only inspection, edits under one project root, and named test commands.
2. The user grants a lease limited to that project, command classes, two hours, and no external delivery.
3. The daemon executes, checkpoints, retries transient failures, and validates results without further prompts.
4. A new request to access a credential, another path, the network, commit, push, or deploy creates a separate attention item.
5. The lease expires automatically and its uses remain in the audit trail.

### 4. Investigate a CI failure

1. A signed webhook or scheduled poll detects a new failure and deduplicates repeated reports for the same revision.
2. A read-only workflow collects the failure metadata, checks out the exact revision in isolation, and attempts bounded reproduction.
3. Expected infrastructure failures are retried silently.
4. If the failure is reproducible, the daemon prepares a causal summary and evidence.
5. Attention is requested only if policy does not authorize a fix, reproduction is ambiguous, or required credentials or resources are unavailable.
6. After approval, an isolated worker prepares and validates the change; delivery remains governed by a separate lease.

### 5. Prepare a fix with a delivery gate

1. The user asks for a validated fix and selects a completion contract.
2. The workflow records the clean starting state, creates an isolated workspace, implements the change, and runs required checks.
3. The evidence service captures the exact diff, commands, results, artifacts, and remaining risks.
4. If checks fail, recovery uses remaining budget. Deterministic failures return to the implementation step without notifying the user.
5. Once the contract passes, the workflow opens a single review item containing the change summary and evidence.
6. Commit, push, or deploy occurs only if an active policy explicitly covers that effect; otherwise the workflow stops at the declared delivery boundary.

### 6. Recover a long-running task

1. The host reboots during a workflow.
2. On startup, the daemon verifies its journal and snapshot, fences the old worker generation, and observes target state.
3. Completed idempotent steps are not repeated. Interrupted resumable steps continue from checkpoints.
4. An uncertain external effect is inspected using target-specific evidence.
5. If the effect can be proven, the workflow proceeds. If it cannot, one attention item presents the evidence and safe choices.
6. Android may have been offline throughout; it receives the coherent event history on reconnect.

### 7. Compare independent reviews safely

1. A workflow creates independent read-only review tasks with bounded context and budgets.
2. Each reviewer produces structured findings linked to evidence.
3. The workflow deduplicates equivalent findings, identifies disagreement, and runs deterministic checks where possible.
4. Only unresolved, material disagreement is placed in `Needs me`.
5. Any approved implementation runs in a separate isolated workspace and passes the normal completion contract.

### 8. Use a discovered local model service

1. The user opens `Connections` and starts a bounded LAN discovery window.
2. DNS-SD candidates appear immediately; a rate-limited probe checks configured local subnets for Ollama on TCP 11434.
3. Candidates are verified using harmless capability requests and shown with address, security posture, and discovery source.
4. The user enrolls one endpoint and selects which projects may send which data classes to it.
5. The daemon revalidates the endpoint before use and routes requests according to project policy.
6. Address changes with the same pinned identity recover automatically. Identity or security changes suspend use and request attention.

### 9. Export a proof bundle

1. The user selects a completed workflow and requests export.
2. The evidence service applies retention and redaction policy, then creates a manifest with hashes and provenance.
3. Transfer resumes by chunk after interruption and verifies the complete bundle before exposing it to Android sharing.
4. The export record remains linked to the workflow and identifies omitted or redacted data.

## Autonomous detection and recovery policy

| Condition | Automatic behavior | Attention threshold |
|---|---|---|
| Temporary network loss | Reconnect with bounded exponential backoff and jitter; resume by cursor | Retry/time budget exhausted or identity changes |
| Android process death | Restore projections and durable outbox; reconcile command IDs | Local durable state is corrupt and cannot be rebuilt |
| Daemon restart or host reboot | Verify journal, load snapshot, fence old workers, observe targets, resume safe steps | Uncertain non-idempotent effect or failed recovery contract |
| Duplicate or out-of-order event | Deduplicate by event ID and reorder within sequence rules; request replay on a gap | Authoritative history cannot satisfy the gap |
| Agent process crash | Capture evidence, restart within workflow policy, restore checkpoint if supported | Crash budget exhausted or repeated deterministic crash |
| Disk pressure | Compact eligible state, enforce retention, pause artifact-heavy work | Required evidence would be lost or safe minimum cannot be maintained |
| Capability drift | Re-probe, update non-security projections, adapt optional features | Identity, authorization, protocol security, or required capability changed |
| Ollama endpoint disappears | Mark unavailable, retry within expiry, use an explicitly configured allowed fallback | Workflow requires a model and no permitted endpoint is available |
| Container or pod replacement | Detect new target identity and fencing generation; rebind only when policy permits | Operation scope or persisted state cannot be proven equivalent |
| Stale validation | Invalidate dependent completion evidence and rerun if authorized | Rerun needs new authority or cannot meet budget |
| Conflicting file changes | Preserve isolated work, calculate overlap, attempt only deterministic safe resolution | Semantic or ownership conflict remains |
| Permission lease expiry | Stop initiating covered effects; allow safe observation and checkpointing | A decision is required to continue toward the declared goal |
| Relay unavailable | Prefer verified direct path; queue bounded encrypted envelopes | Delivery deadline is at risk and no path is available |
| Corrupt state tail | Quarantine corrupt tail, recover last checksummed boundary, retain diagnostics | Accepted operation outcome cannot be reconstructed |

Recovery attempts must be observable in `Background` but silent by default. Repeated symptoms sharing one root cause must update a single attention item.

## Reliability, security, and usability targets

These targets become release gates once their measurement infrastructure exists:

- No accepted mutating command is lost after acknowledgement.
- No duplicate external effect occurs during reconnect, replay, process death, daemon restart, or leader replacement in the conformance suite.
- Every uncertain effect is explicitly represented and never auto-retried without proof.
- Event projection converges to daemon state after offline gaps, duplicate delivery, and out-of-order transport.
- Routine transient failure recovery requires no human action within configured budgets.
- An actionable attention item is available within 30 seconds of an unrecoverable workflow state while a path to the user exists.
- At least 95% of completed routine workflows require no interaction after initial intent and policy selection.
- At least 90% of notifications correspond to an unresolved human decision, with completion notifications opt-in.
- A return-to-work summary for 10,000 events renders from a bounded snapshot without loading full transcripts.
- Pairing, revocation, identity rotation, replay, downgrade, and lost-device scenarios pass automated security tests.
- Secrets never appear in discovery payloads, notification text, diagnostic bundles, or default exported evidence.
- Accessibility checks cover all attention decisions and critical status without requiring color or terminal-text interpretation.
- Battery and data use are measured for idle, reconnect, active stream, artifact transfer, and background-summary scenarios.

## Release sequence and gates

### Gate A — Interactive integrity

- Local and SSH lifecycle tests pass across process death and network interruption.
- Artifact capture and export are checked, provenance-linked, resumable, and safe.
- Host identity and project scope are visible for every operation.
- Normalized event projection covers all existing critical session states.

### Gate B — Attention integrity

- Attention items are durable, deduplicated, auditable, and reconstructible from events.
- Return-to-work summaries are complete and linked to evidence.
- No notification-only state exists.
- Completion-contract status is visible for every workflow.

### Gate C — Daemon integrity

- Journal recovery, snapshot migration, idempotency, fencing, and cursor replay pass fault-injection tests.
- Direct and relay transports preserve identity and ordering semantics.
- QR payload parsing, pairing-slot consumption, authenticated transcript binding, reciprocal confirmation, grant persistence, rotation, and revocation pass independent security review.
- Test matrices cover same-LAN, shared VPN, separate NATs, CGNAT, IPv4, IPv6, UDP-blocked WSS fallback, relay outage, network switching, Doze, and Android process death.
- Relay operators can observe only bounded routing metadata and ciphertext; logs and diagnostics contain no QR secret, grant, command, project data, or credential.
- Android may disconnect for the entire run without preventing safe completion.

### Gate D — Automation integrity

- Workflow triggers deduplicate correctly.
- Retry and compensation behavior is bounded and tested.
- Permission leases are enforced on the host and survive client disconnect.
- Outcome contracts and proof bundles cover every built-in workflow.

### Gate E — Extensibility integrity

- Provider conformance tests cover lifecycle, capability negotiation, cancellation, errors, and recovery.
- Execution targets have explicit identity and fencing behavior.
- Provider crashes cannot corrupt workflow authority.
- Unsupported features are safely disabled through negotiation.

### Gate F — Coordination integrity

- Parallel work is isolated and conflict-safe.
- Child events and evidence aggregate without losing provenance.
- Late workers are fenced from publishing effects.
- Multi-user decisions are attributable and concurrency-safe.

## Explicit non-goals

- A permanently connected phone is not required for host work to continue.
- A raw terminal transcript is not the authoritative workflow state.
- LAN discovery does not imply trust, authorization, or safe remote exposure.
- Unauthenticated remote model APIs are not treated as secure merely because they are on a private network.
- Provider type is not used as proof of a live capability.
- Blind retry is not used for operations with uncertain external effects.
- Commit, push, deployment, credential access, destructive cleanup, or expanded network access is not inferred from permission to edit and test locally.
- The relay is not allowed to decrypt project content or become the workflow state authority.
- Autonomous work is not allowed to hide unresolved risk, stale evidence, or policy exceptions.
- Background reliability is not achieved by suppressing errors; it is achieved by recovery, proof, and precise escalation.

## Roadmap change protocol

Every roadmap proposal must state:

1. the human-attention condition it removes, improves, or makes safer;
2. the durable state owner and recovery behavior;
3. the identity, authorization, privacy, and threat-boundary effects;
4. the event and evidence changes required;
5. failure modes, retry safety, uncertain-effect handling, and resource bounds;
6. migration and compatibility behavior;
7. measurable acceptance criteria and fault-injection coverage;
8. which earlier priority and release gate it depends on.

Features that add automation without a durable state model, secure authority boundary, outcome contract, and bounded recovery policy are incomplete by definition.
