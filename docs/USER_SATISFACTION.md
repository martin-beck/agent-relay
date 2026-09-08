# User satisfaction assurance

This page is generated from
[`user-satisfaction-v1.json`](contracts/user-satisfaction-v1.json).
It separates Android journey evidence from contract-only assurance so a planned
surface is never presented as verified user behavior.

## Verified Android journeys

| Journey | Satisfaction phase | User value | Semantic evidence |
| --- | --- | --- | --- |
| [Start a first useful session](workflows/fresh-start-first-session.md) | state-outcome | Reach a usable agent session from a clean app state with reviewed identity and scope. | `com.example.agentrelay.ui.main.UsageJourneyTest#capturesVerifiedJourneys` |
| [Review scope and answer once](workflows/attention-approval.md) | approve-decision | Understand a provider request, its risk, and its exact scope before responding. | `com.example.agentrelay.ui.main.UsageJourneyTest#capturesVerifiedJourneys` |
| [Recover after interruption](workflows/durable-recovery.md) | recover-interruption | Resume useful work without duplicate effects or hidden uncertainty. | `com.example.agentrelay.ui.main.UsageJourneyTest#capturesVerifiedJourneys` |
| [Explain what needs attention](workflows/attention-overview.md) | explain-result | See exceptional work first and distinguish required action from routine progress. | `com.example.agentrelay.ui.main.UsageJourneyTest#capturesVerifiedJourneys` |

## Integrated child contracts

Every row below is executed by the normal JVM gate. Contract verification proves
the named invariant; it does not substitute for a semantic Android journey, human
usability review, authenticated OEM pairing, or live provider evidence.

| Contract | Satisfaction phase | Assurance | Remaining evidence |
| --- | --- | --- | --- |
| AR-2160: Outcome-first onboarding | state-outcome | Contract verified | The reducer is tested, but its dedicated outcome-entry screen has no semantic Android journey yet. |
| AR-2161: Natural-language workflow authoring | state-outcome | Contract verified | Deterministic parsing and review boundaries are tested; production UI composition remains unverified. |
| AR-2162: Autonomy and policy controls | approve-decision | Contract verified | Policy, escalation, revocation, and pause-all contracts are tested without a dedicated Android surface. |
| AR-2163: Progress and completion proof | explain-result | Contract verified | Proof projection is tested; a complete production workflow result journey is still planned. |
| AR-2164: Permissioned integrations and connectors | review-scope | Contract verified | Capability, token, retry, and revocation contracts are tested without live third-party credentials. |
| AR-2165: Cross-device continuity | recover-interruption | Contract verified | Replay and replacement-device invariants are tested; authenticated OEM phone/Wear evidence remains pending. |
| AR-2166: Shared project collaboration | review-scope | Contract verified | Authority and conflict contracts are tested; multi-person Android interaction evidence remains planned. |
| AR-2167: Explainable attention personalization | explain-result | Contract verified | Quiet-hours and escalation projections are tested; personalized Android rendering remains unverified. |
| AR-2168: Cost and resource controls | review-scope | Contract verified | Budget reservations and hard stops are tested; live provider cost reconciliation remains unavailable. |
| AR-2169: Trust and privacy controls | approve-decision | Contract verified | Retention, deletion, export, and rotation contracts are tested; their integrated Android journey is planned. |

## Publication invariant

The generator fails closed unless AR-2160 through AR-2169 appear exactly once,
each child names existing production and test evidence, every satisfaction phase is
covered, and every journey in the first table points to a verified scenario with a
semantic Android test, reviewed screenshots, and non-empty alternative text.
The documentation privacy gate separately rejects credential-like or identifying
fixture content. Human-review results remain aggregate-only under
[the usability protocol](USABILITY.md).
