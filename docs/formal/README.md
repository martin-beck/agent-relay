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

```bash
python scripts/ci/verify_counterexamples.py
```

These fixtures are regression evidence, not an unbounded proof. A fixture
classified as a mechanical invariant or bounded model result remains limited
to the declared finite model bounds, while `environmental-assumption` records
conditions that the model does not establish.

Run the model verifier with:

```bash
python scripts/ci/verify_formal_models.py
python scripts/ci/verify_workflow_concurrency.py
python scripts/ci/verify_formal_evidence.py
python scripts/ci/verify_counterexamples.py
```
