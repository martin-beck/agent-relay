# Extension development guide

This is the canonical guide for third-party and user-authored Agent Relay
extensions. The host remains the authority for permissions, approvals, secrets,
durable evidence, lifecycle, and revocation. An extension is a bounded,
versioned proposal, never a second scheduler or policy engine.

## Contract and manifest

An extension declares a lowercase stable `id`, display name, API and schema
versions, one or more capabilities, requested fields, triggers, actions,
permissions, lifecycle hooks, and resource budgets. The machine-readable
contract is `config/extension-manifest.schema.json` and the Kotlin API is described in the generated
[extension API reference](generated/extension-api.md).

`apiVersion.major` changes require a migration and compatibility review;
`minor` additions remain compatible when the host supports them. A schema
version change requires an explicit migration. Unknown fields, undeclared
triggers/actions, invalid bounds, and incompatible versions fail closed.

## Lifecycle, permissions, and data

Installation validates the manifest but grants no data access. At invocation,
the host narrows requested permissions to the manifest and the user grant,
checks the current revision, and records an idempotency key. Read fields are
declared by stable names; `WRITE_FIELDS` always names an action and external
effects require approval. Sensitive data cannot authorize an external effect.
Opaque store and secret references may be passed, but raw credentials,
unbounded workflow state, private keys, and prompts never cross the boundary.

Startup, health, and shutdown hooks are bounded by the declared lifecycle and
budget. Revocation rejects future invocations and invalidates active
projections. Reinstalling does not restore revoked consent.

## Sandboxing and evidence

Built-in code is signed host code; user-authored code is `SANDBOXED`. CPU,
wall-clock, memory, storage, input, output, invocation, network, subprocess,
filesystem, and model limits are selected before startup. Network hosts must be
an explicit allowlist, filesystem roots are relative and confined, and model
access is denied unless declared. Cancellation is observed; an external effect
whose outcome cannot be proven is `UNCERTAIN` and is never automatically
retried.

Evidence stores only stable IDs, SHA-256 digests, byte counts, approval state,
outcome, rejection category, and resource observations. It never stores raw
provider output, credentials, paths, location, or private workflow content.
Examples in [examples.yaml](extensions/examples.yaml) are fixtures until their
complete evidence is verified.

## Testing, compatibility, and operations

Conformance tests must cover valid/invalid manifests, version negotiation,
permission narrowing, bounds, revocation, redaction, cancellation, stale
revisions, uncertain effects, and phone/watch projection races. Use synthetic
fixtures and record evidence boundaries; a successful unit test is not proof of
an external effect.

Install by validating the signed manifest, showing requested permissions, and
recording consent. Revoke from the host settings or policy authority; verify a
subsequent invocation is rejected. Phone projections carry a revision and
consequential watch actions return to the phone for confirmation. Deprecations
keep one supported major version during migration, publish a replacement and
removal date, and reject unsupported majors before code starts.

## Runnable fixture policy

The ten examples are deliberately redacted fixtures covering provider,
repository, publication, price, location, calendar, home, vehicle, theme, and
watch scenarios. They are suitable for parser and policy tests only. They do
not claim live provider, location, calendar, home, vehicle, or market evidence.
