# Software design authority

This page is the canonical integration view for Agent Relay design. It owns
terminology, document links, decision status, and traceability. It does not
duplicate subsystem contracts: each linked document remains authoritative for
its own boundary.

## System context

Agent Relay is an Android client for supervising local and remote coding-agent
sessions. The product keeps routine connection, persistence, reconciliation,
and recovery work in the background while presenting exceptional decisions with
enough context to act safely.

- Product direction and roadmap: [PRODUCT_ROADMAP.md](PRODUCT_ROADMAP.md).
- Module boundaries, runtime flow, security, and state ownership:
  [ARCHITECTURE.md](ARCHITECTURE.md).
- User-facing workflow contracts: [WORKFLOWS.md](WORKFLOWS.md).

## Component and data-flow authority

The dependency direction is UI -> application/session state -> domain and
provider APIs -> transport and persistence. Provider-specific code may depend
on provider API contracts, but providers do not own shared session or policy
state. The architecture guide defines the module graph and runtime flow;
connection providers and session hub documents define their detailed seams.

- Connection enrollment, identity, routing, and command safety:
  [CONNECTION_PROVIDERS.md](CONNECTION_PROVIDERS.md).
- Durable session state, encryption, retention, and coordinator runtime:
  [SESSION_HUB.md](SESSION_HUB.md).
- Provider operations, live verification, and integration boundaries:
  [PROVIDER_OPERATIONS.md](PROVIDER_OPERATIONS.md).
- Endpoint identity, rotation, recovery, and threats:
  [ENDPOINT_IDENTITY.md](ENDPOINT_IDENTITY.md).
- Offline speech admission, lifecycle, privacy, and verification:
  [SPEECH.md](SPEECH.md).

## State, events, and recovery

State authority follows the owning contract rather than the UI. Durable records
are written before externally visible effects, revisions fence stale writers,
and uncertain effects remain visible until observation resolves them. Recovery
must preserve identity and context without replaying non-idempotent operations.

The detailed rules live in [SESSION_HUB.md](SESSION_HUB.md),
[ARCHITECTURE.md](ARCHITECTURE.md), and the provider operation contracts. New
state or events must identify their owner, revision rule, persistence boundary,
recovery behavior, and redaction policy in the relevant subsystem document.

## Security and privacy

The security boundary is explicit: endpoint identity, encrypted durable state,
provider trust, permission decisions, notification redaction, and offline model
admission each require their own evidence. Raw credentials, provider secrets,
and identifying host data must not appear in UI captures, workflow pages, or
retained test evidence.

See [ENDPOINT_IDENTITY.md](ENDPOINT_IDENTITY.md),
[SESSION_HUB.md](SESSION_HUB.md), [SPEECH.md](SPEECH.md), and the privacy
requirements in [QUALITY.md](QUALITY.md).

## Extension and decision rules

New providers, connectors, transports, or device capabilities must link their
public contract, state owner, threat boundary, failure behavior, and tests.
Design decisions are recorded in the owning subsystem document until a
dedicated ADR index exists. Superseded decisions must link to the replacement;
unresolved conflicts are blockers, not implicit choices.

The implementation and review process is defined by
[DEVELOPMENT.md](DEVELOPMENT.md). Workflow claims are classified as verified
only when the required semantic or hardware evidence exists; otherwise they
remain planned in [WORKFLOWS.md](WORKFLOWS.md).

## Traceability matrix

| Design claim | Authority | Implementation and verification evidence |
| --- | --- | --- |
| Module direction and state ownership | [ARCHITECTURE.md](ARCHITECTURE.md) | Module build checks, API compatibility, and architecture tests |
| Durable updates and recovery | [SESSION_HUB.md](SESSION_HUB.md) | Session hub tests, persistence tests, and recovery workflows |
| Provider trust and command safety | [CONNECTION_PROVIDERS.md](CONNECTION_PROVIDERS.md) | Provider API tests and connected-provider verification |
| Endpoint identity and rotation | [ENDPOINT_IDENTITY.md](ENDPOINT_IDENTITY.md) | Identity contract tests and redacted evidence checks |
| Speech privacy and model admission | [SPEECH.md](SPEECH.md) | Offline speech tests, quality gates, and UI evidence |
| Human workflow outcomes | [WORKFLOWS.md](WORKFLOWS.md) | Scenario manifests, generated pages, semantic UI tests, and reviewed captures |

## Change control

Before changing a cross-cutting design claim, update this index and the owning
subsystem document together. A review must identify the affected state owner,
contract, tests, formal assumptions, workflow evidence, and any superseded
decision. The repository quality and documentation checks must pass before the
claim is treated as implemented.
