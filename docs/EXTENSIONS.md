# Extension platform

Agent Relay extensions are declarative, versioned capabilities. They add
providers, workflows, presentation modules, or bounded phone and Wear
projections without creating a second authority. The phone and daemon remain
responsible for policy, approvals, secrets, durable evidence, and revocation.

## Delivery slices

The platform is delivered through these dependency-ordered slices:

| Slice | Contract boundary |
| --- | --- |
| AR-1391 | Versioned manifests, lifecycle, capabilities, budgets, compatibility, and revocation |
| AR-1392 | Provider-neutral connector and agent SDK |
| AR-1393 | Declarative workflows with bounded triggers, reads, proposals, approvals, and actions |
| AR-1394 | Workflow-scoped permissions, secret references, consent, retention, and external-effect policy |
| AR-1395 | Sandboxed execution for untrusted or user-authored extension code |
| AR-1396 | Accessible theme, sound, haptic, and notification presentation contracts |
| AR-1397 | Redacted phone and Wear projections with phone confirmation for consequential actions |
| AR-1398 | Canonical documentation, generated API descriptions, schemas, and runnable fixtures |

Each slice must depend on the contracts before it, preserve the existing
provider and workflow authorities, and publish focused tests and evidence.

## Boundary rules

An extension must declare a stable identifier, API and schema versions,
capabilities, requested data fields, permissions, triggers, actions, resource
budgets, and lifecycle hooks. The host rejects unknown or incompatible
versions, undeclared access, missing bounds, and invalid migrations before
extension code runs. Revocation is fail-closed and takes effect for future
invocations as well as active projections.

Extensions receive opaque references to approved stores and bounded,
redacted inputs. They never receive raw credentials, unrestricted network or
filesystem access, private workflow state, or a way to bypass approval and
uncertain-effect handling. A connector is inactive unless a user-selected
workflow requests its declared fields; installation alone grants no access.

## Sandboxed execution

Built-in extensions are signed host code and remain subject to the same
manifest, permission, approval, and evidence checks as sandboxed code. User-
authored or untrusted code is always admitted as `SANDBOXED` through the
provider API boundary. Its policy fixes CPU and wall time, memory, storage,
input/output, and invocation budgets before startup. Network access is denied
unless a workflow supplies an explicit host allowlist; subprocesses are denied
by default, filesystem roots are relative and confined, and model access is
denied unless declared. A built-in extension cannot enable external effects on
its own.

An extension-backed agent receives only the approved invocation and an opaque
store reference. External effects remain pending until explicit approval;
denial, cancellation, stale revisions, resource violations, and authority
requests fail closed. A result that may have crossed an external-effect
boundary is `UNCERTAIN` and is never retried automatically. The host adapter
must observe cancellation and report a proven `CANCELLED` outcome where no
effect occurred.

Execution evidence is deterministic and redacted: it contains the extension
and idempotency identifiers, SHA-256 digests of canonical input, output, and
policy, byte counts, approval state, outcome, and a stable rejection category.
It never stores prompts, credentials, raw provider output, paths, or private
workflow content. The provider API conformance tests cover authority denial,
approval admission, cancellation, uncertain effects, deterministic evidence,
and invalid resource boundaries.

Watch projections contain only the minimum alert, proposal, or safe action
needed for the current context. Secrets, full workflow state, and privileged
credentials remain on the phone or daemon. Consequential actions require
phone confirmation and carry a revision so stale projections cannot execute.

## Required evidence

Every extension slice must include conformance tests for valid and invalid
manifests, version negotiation, permission narrowing, budget enforcement,
revocation, redaction, cancellation, and stale projection handling. Fixtures
are labelled as illustrative until their complete evidence is verified.
Documentation and generated schemas must remain synchronized; undocumented
public contracts, broken examples, and stale generated output are release
failures.
