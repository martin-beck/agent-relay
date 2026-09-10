# OpenJiuwen provider contract

Agent Relay records the OpenJiuwen integration as a contract-only, planned
provider until an adapter and its conformance evidence exist. The authoritative
matrix is [the versioned contract](contracts/openjiuwen-provider-v1.json).

## Evidence boundary

The contract pins the official `openJiuwen-ai/agent-core` `develop` revision
`1c22b9f273757f7625c044c0e3842ff1c9bbb0b1` and the official Java SDK
`agent-core-java` `main` revision `27cba5733465b3ed3914fafac11d332f92a83531`.
The sources describe Python SDK agent creation, ReAct and workflow agents,
tool components, asynchronous/streaming execution, interruption and recovery,
and graph execution. Those descriptions are evidence for the contract matrix,
not proof that Agent Relay already supports those capabilities.

No OpenJiuwen gateway, wire protocol, credential format, approval handshake,
session-history schema, or changed-file manifest is assumed. The future adapter
must pin the exact SDK surface it invokes and must expose unsupported behavior
explicitly rather than infer it from documentation or marketing material.

## Integration rules

- Agent Relay remains authoritative for session identity, policy, approvals,
  persistence, leases, retries, cancellation, and completion.
- Provider options are bounded and contain only opaque credential references;
  credentials, prompts, routes, raw SDK objects, and private transcripts never
  enter fixtures or durable events.
- External tools and effects fail closed until an Agent Relay approval boundary
  is established. Unknown delivery is an explicit non-retryable outcome.
- Deterministic replay tests cover malformed events, stream gaps and duplicate
  sequence identities, oversized options, interruption, cleanup, and privacy.
- Live credentials, network calls, and protected data are out of scope for the
  contract milestone.

The follow-on AR-2209 adapter may implement only the capabilities marked
conditional after these invariants and the pinned SDK behavior are verified.
AR-2210 owns conformance validation; AR-2211 owns generated documentation and
publication.
