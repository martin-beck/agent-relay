# Formal domain models

The bounded models in this directory are executable specifications of the
provider-neutral workflow contracts in `session/api`. They deliberately model
identifiers as opaque atoms: no host, path, account, prompt, or provider data
is copied into model traces.

`WorkflowDomain.tla` is the transition model for tasks, runs, steps, leases,
events, attention items, and projections. `WorkflowDomain.alloy` expresses the
same safety boundary in Alloy's relational notation. The checked-in Python
verifier runs the finite transition relation and checks the model declarations
against the versioned Kotlin contracts. It is a deterministic bounded result,
not an unbounded proof.

`WorkflowConcurrency.tla` and `WorkflowConcurrency.alloy` extend that boundary
with worker leases, bounded attempts, crash recovery, checkpoints, projections,
and uncertain effects. `scripts/ci/verify_workflow_concurrency.py` exhaustively
explores the finite interleavings and replays named hostile traces. The tests
retain duplicate-claim, crash-recovery, and uncertain-effect regressions; the
model intentionally assumes serialized journal writes, monotonic clocks, and
durable atomic commits.

## Evidence classes

| Class | Meaning |
| --- | --- |
| Mechanical invariant | Checked for every state in the finite verifier's reachable state set. |
| Bounded model result | Checked only for the declared finite bounds. |
| Contract test | Checked by the Kotlin unit tests and constructor guards. |
| Environmental assumption | Scheduler serialization, monotonic clocks, and durable atomic writes are outside this model. |

The current bounds are two tasks, two runs, two steps, four event sequence
positions, and one active lease per step. Increasing a bound requires reviewing
runtime and memory budgets and does not change the domain contract by itself.

`config/formal-evidence-manifest.json` is the CI evidence contract. It pins the
verifier/checker metadata, hashes every checked-in model, names each obligation's
evidence class, records reviewed environmental assumptions, and defines the
counterexample reproducibility policy. CI rejects stale model hashes or missing
assumption metadata before running the bounded verifiers.

## Counterexample interchange and replay

Formal reports use the versioned JSON format in
`tests/formal/counterexamples/`. Each fixture contains an opaque operation
trace, its expected accepted or rejected outcome, an evidence class, and
SHA-256 digests for both the canonical trace input and the replay
implementation. The input digest is computed from the compact JSON trace, so
whitespace or object-key changes cannot silently alter the replay input.

The importer accepts only the bounded operations implemented by the
concurrency model. It rejects paths, identifiers, and arbitrary payloads so
counterexample traces cannot carry host, account, prompt, or provider data.
Every retained trace is replayed before the formal evidence is accepted; a
changed rejection, implementation, or trace digest fails the quality gate.

`CrossDeviceContinuity.tla` captures the companion-device safety boundary:
the authority sequence only advances, acknowledgements cannot move backwards
or beyond authority, and revocation prevents later acceptance. The executable
connection API tests cover the same finite transitions, including offline
replay, reconnect, and replacement enrollment.

`ResourceBudget.tla` states the companion resource-control invariants used by
the executable workflow API contract: reservations are admitted only while
the projected usage fits every declared dimension, reconciliation is
monotonic, and an observed overrun enters a terminal hard stop. Unknown money
prices are rejected or require explicit approval according to the policy; the
Kotlin race tests provide the finite executable evidence for serialized
reservations.

`CollaborationAuthority.tla` captures shared-project authority: compare-and-set
mutations advance one durable revision, audit entries never disappear, and a
revoked lease remains unable to authorize later work. `CollaborationModelsTest`
provides executable evidence for scope-limited delegation, stale conflicts,
expiry, revocation, and redacted audit comments.

```bash
python scripts/ci/verify_counterexamples.py
```

These fixtures are regression evidence, not an unbounded proof. A fixture
classified as a mechanical invariant or bounded model result remains limited
to the declared finite model bounds, while `environmental-assumption` records
conditions that the model does not establish.

## FM-Agent pilot

The FM-Agent pilot is a specification-only, privacy-safe experiment over one
isolated Python verifier and one Java build-logic class. It runs with pinned
checkout inputs, a per-module byte bound, no network access, and no source
upload. The pilot records hashes and bounded syntax/structure observations;
the independent validator recomputes every observation before accepting the
report. It does not claim model-reasoning results, bug absence, or a security
proof. Run it with:

```bash
python scripts/ci/verify_fm_agent_pilot.py
```

Run the model verifier with:

```bash
python scripts/ci/verify_formal_models.py
python scripts/ci/verify_workflow_concurrency.py
python scripts/ci/verify_formal_evidence.py
python scripts/ci/verify_counterexamples.py
```
