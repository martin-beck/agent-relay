# LLM wire mock pilot

This pilot evaluates wire emulators behind a real configurable agent CLI. It does not add an Agent Relay provider or replace native protocol tests.

## Reviewed candidates

| Tool | Revision | License | Evidence-backed strengths | Decision |
| --- | --- | --- | --- | --- |
| MockAgents | `6ddb03e54a14484e5929a19673f0cfd8a1975f07` | Apache-2.0 | Static Go binary; OpenAI/Anthropic/Gemini; SSE, tool calls, faults, replay, contracts | Candidate for loopback pilot |
| aimock | `7323e819bce1b971dc2b7907407401b7d65bbe8c` | MIT | Broad Node APIs, MCP/A2A/AG-UI, timing replay, chaos | Not selected: runtime dependency and unnecessary breadth |
| llmock | `bac44e693616254b08fcaa4ddd71cc735d5ee9f6` | MIT | Rust binary; provider envelopes, SSE faults, deterministic timing, byte replay | Not selected: no added coverage over MockAgents for this pilot |

MockAgents v0.5.0 Linux amd64 was smoke-tested from its published asset (SHA-256 `1b2e6fb9d3bb96c680f9f9c819c2669939751baf45abb35b1370c97f1d291458`). It binds loopback by default and exposes replay mode with strict misses.

## Boundary and result

The selected dependency is optional test tooling only. It must run loopback-only with outbound network denied, synthetic fixtures, bounded startup/readiness/teardown, and no real API key. The fixture exercises an OpenAI-compatible tool round and stream termination; fault scenarios cover rate limit, malformed/truncated/hanging streams, cancellation, and uncertain delivery.

No shipped CLI is installed on the current host, so the required end-to-end CLI traversal is not claimed by this checkpoint. Before adoption, run the fixture through one shipped CLI configured to its documented loopback base URL and verify normal auth-state, approval, cancellation, event, and completion mapping. If that traversal adds no coverage, reject all three emulators.

This evidence does not prove model quality, cloud authentication, or provider support.
