# OpenJiuwen metadata-probe provider

Agent Relay includes a fail-closed OpenJiuwen discovery boundary. It can verify
that the Python distribution metadata is available, but it advertises no agent
capabilities and cannot open a session. The authoritative matrix is
[the versioned contract](contracts/openjiuwen-provider-v1.json).

## Evidence boundary

The contract pins the official `openJiuwen-ai/agent-core` `develop` revision
`1c22b9f273757f7625c044c0e3842ff1c9bbb0b1` and the official Java SDK
`agent-core-java` `main` revision `27cba5733465b3ed3914fafac11d332f92a83531`.
The sources describe Python SDK agent creation, ReAct and workflow agents,
tool components, asynchronous/streaming execution, interruption and recovery,
and graph execution. Those descriptions are evidence for the contract matrix,
not proof that Agent Relay already supports those capabilities.

No OpenJiuwen gateway, wire protocol, credential format, approval handshake,
session-history schema, or changed-file manifest is assumed. The current
factory invokes only a fixed, credential-free Python metadata query with a
10-second deadline. A successful query reports the installed version as
incompatible because no reviewed Agent Relay bridge exists.

## Setup and authentication

Install Python 3 and the approved OpenJiuwen Python distribution on the selected
connection target using the pinned source revision above. Installation and
package provenance remain administrator-owned; Agent Relay neither downloads
the SDK nor executes SDK agent code.

The metadata probe accepts no endpoint, model, account, token, API key, or
credential option. Do not enter provider or model credentials for this
integration. Authentication cannot make the provider ready because Agent Relay
has no reviewed bridge or credential handoff for OpenJiuwen.

Reconnect the selected connection to run discovery again after repairing a
missing Python interpreter or distribution. A missing package, failed command,
malformed version, duplicate output, oversized output, or truncated output is
reported as missing without including command output. A valid version is still
reported as incompatible until a separately pinned bridge passes review.

## Unsupported operations

| Attempt | Current result |
| --- | --- |
| Start or resume an OpenJiuwen session | Unsupported; the factory cannot create a connection |
| Send or stream model input | Unsupported; no gateway, CLI, model, or stream protocol is invoked |
| Invoke a tool or answer an approval | Unsupported before external I/O; no approval handshake is established |
| Read provider history or changed files | Unsupported; no stable history or revisioned file manifest is established |
| Interrupt, reconnect, or replay a turn | Unsupported; uncertain effects must never be replayed automatically |

Agent Relay must not translate a successful SDK metadata probe into any of
these capabilities. The provider descriptor remains empty, so application
controls stay capability-gated.

## Integration rules

- Agent Relay remains authoritative for session identity, policy, approvals,
  persistence, leases, retries, cancellation, and completion.
- Provider options are bounded and contain only opaque credential references;
  credentials, prompts, routes, raw SDK objects, and private transcripts never
  enter fixtures or durable events.
- External tools and effects fail closed until an Agent Relay approval boundary
  is established. Unknown delivery is an explicit non-retryable outcome.
- Deterministic replay tests cover ordered and hostile metadata, duplicate and
  truncated records, unavailable effects, cancellation, repeated probes,
  cleanup, and privacy.
- Live credentials, network calls, and protected data are out of scope for the
  published evidence.

The implementation and conformance suites validate only this metadata-probe
boundary. No live OpenJiuwen engine, gateway, CLI, model, session, stream, tool,
approval, reconnect, or artifact traversal was performed. Conditional
capabilities in the contract remain design evidence, not implemented behavior.
