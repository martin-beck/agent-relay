# Offline provider fixtures

`config/offline-provider-fixture-v1.schema.json` defines the provider-neutral,
synthetic contract used by offline runtime tests. A fixture is evidence only;
it never owns session, workflow, approval, or retry truth.

## Contract

Every fixture declares `schemaVersion: 1`, a stable local `fixtureId`, and
`evidence` with tier `synthetic-runtime`, `synthetic: true`, and the source
revision that reviewed it. `limits` bound body bytes, frames, events, virtual
time, and retries. Exceeding any bound fails closed.

`matching.requestFields` names the semantic request fields that must match.
`matching.normalization` is explicit per field: `exact`, `trim`, `lowercase`,
`redact-id`, `redact-timestamp`, or `sort-keys`. Volatile IDs, timestamps,
chunking, and transport headers are never ignored globally. A request is
unmatched unless all declared fields match exactly after their named
normalization; duplicate matching entries are ambiguous and fail closed.

`timeline` is ordered virtual-time input. Each frame has a non-negative
`atMillis`, direction, supported semantic kind, and bounded object payload.
`faults` inject disconnect, truncation, malformed frames, rate limits, process
exit, cancellation, duplicate/gap delivery, or uncertain delivery. Faults may
carry a bounded deterministic seed. The harness must reject time travel,
unbounded payloads, leftover frames, and impossible event order.

`outcome` is normalized and terminal: success, cancelled, failed, or uncertain.
Uncertain delivery is never automatically retried. Tool names, arguments,
approvals, event order, and terminal status are asserted exactly by consumers.
Replay binds no network sockets except an explicitly loopback test helper and
must deny outbound network access.

The contract is intentionally independent of any provider adapter. The next
ARs consume it to build scripted runtime doubles and evaluate wire emulators;
they must not widen this schema to encode vendor-specific workflow authority.

Only synthetic fixtures may be committed. Recordings go to restricted,
untracked storage and require the later AR-2224 sanitization/provenance
admission pipeline before review. Prompts, transcripts, credentials,
hostnames, addresses, private paths, and personal data are prohibited.

The validator and its tests are intentionally dependency-free. They enforce
the schema's closed-world shape plus cross-field limits, deterministic ordering,
matcher ambiguity, secret-like content, unsupported versions, and repeatable
validation. They do not claim provider quality or live authentication.
