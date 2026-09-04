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

Run the model verifier with:

```bash
python scripts/ci/verify_formal_models.py
python scripts/ci/verify_workflow_concurrency.py
```
