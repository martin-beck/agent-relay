# Offline LLM testing and local inference recommendation

## Decision

Agent Relay should build deterministic tests at the boundary it owns first:
`RemoteAgentRuntime` and each provider's native CLI or app-server protocol. It should then pilot
one LLM HTTP emulator behind a real configurable provider CLI, add privacy-safe replay, and run real
local inference only as separate scheduled or manual conformance evidence.

An LLM server is not an Agent Relay `AgentProviderFactory` or `ConnectionProvider`. Agent Relay
controls coding-agent programs. Those programs may call an LLM endpoint, but they expose their own
sessions, events, approvals, interruption, history, and file-change protocols to Agent Relay. A
direct OpenAI-compatible mock cannot exercise that whole boundary.

This research creates AR-2219 through AR-2226. It adds no simulator dependency and claims no new
provider support.

## Evidence snapshot

The review was refreshed on 2026-09-09 against Agent Relay main
`6806a8876107b9b2091df0f3e6832bdd77123421`. Implementation tasks must repin these fast-moving
projects and recheck licenses, releases, checksums, behavior, and security.

| Source | Reviewed revision | Relevant advertised surface and exact evidence |
| --- | --- | --- |
| [MockAgents](https://github.com/mockagents/mockagents/tree/6ddb03e54a14484e5929a19673f0cfd8a1975f07) | [`6ddb03e`](https://github.com/mockagents/mockagents/commit/6ddb03e54a14484e5929a19673f0cfd8a1975f07) | Go binary; OpenAI, Anthropic, Gemini, SSE, tools, and faults ([README lines 10-20](https://github.com/mockagents/mockagents/blob/6ddb03e54a14484e5929a19673f0cfd8a1975f07/README.md#L10-L20)); replay and contracts ([263-267](https://github.com/mockagents/mockagents/blob/6ddb03e54a14484e5929a19673f0cfd8a1975f07/README.md#L263-L267)) |
| [CopilotKit aimock](https://github.com/CopilotKit/aimock/tree/7323e819bce1b971dc2b7907407401b7d65bbe8c) | [`7323e81`](https://github.com/CopilotKit/aimock/commit/7323e819bce1b971dc2b7907407401b7d65bbe8c) | LLM APIs, MCP, A2A, AG-UI, replay, drift, and chaos ([README lines 61-82](https://github.com/CopilotKit/aimock/blob/7323e819bce1b971dc2b7907407401b7d65bbe8c/README.md#L61-L82)) |
| [llmock](https://github.com/larsakerlund/llmock/tree/bac44e693616254b08fcaa4ddd71cc735d5ee9f6) | [`bac44e6`](https://github.com/larsakerlund/llmock/commit/bac44e693616254b08fcaa4ddd71cc735d5ee9f6) | Provider wire shapes, SSE timing and faults, deterministic mode, and byte replay ([README lines 26-37](https://github.com/larsakerlund/llmock/blob/bac44e693616254b08fcaa4ddd71cc735d5ee9f6/README.md#L26-L37)) |
| [Ollama](https://github.com/ollama/ollama/tree/86f72929348d384336b6f0adc129e71b2122abdc) | [`86f7292`](https://github.com/ollama/ollama/commit/86f72929348d384336b6f0adc129e71b2122abdc) | OpenAI-compatible responses, streaming, and tools with statefulness limits ([compatibility lines 282-306](https://github.com/ollama/ollama/blob/86f72929348d384336b6f0adc129e71b2122abdc/docs/api/openai-compatibility.mdx#L282-L306)) |
| [llama.cpp](https://github.com/ggml-org/llama.cpp/tree/1945e092030f8668ff93382799502d01490e564d) | [`1945e09`](https://github.com/ggml-org/llama.cpp/commit/1945e092030f8668ff93382799502d01490e564d) | Quantized inference ([README lines 35-61](https://github.com/ggml-org/llama.cpp/blob/1945e092030f8668ff93382799502d01490e564d/README.md#L35-L61)) and OpenAI/Anthropic-compatible serving ([server lines 9-18](https://github.com/ggml-org/llama.cpp/blob/1945e092030f8668ff93382799502d01490e564d/tools/server/README.md#L9-L18)) |
| [vLLM](https://github.com/vllm-project/vllm/tree/385dce36bcee42309924a5ece951a96db3dce7f2) | [`385dce3`](https://github.com/vllm-project/vllm/commit/385dce36bcee42309924a5ece951a96db3dce7f2) | Multi-accelerator inference ([README lines 24-51](https://github.com/vllm-project/vllm/blob/385dce36bcee42309924a5ece951a96db3dce7f2/README.md#L24-L51)) and OpenAI-compatible serving ([server lines 1-28](https://github.com/vllm-project/vllm/blob/385dce36bcee42309924a5ece951a96db3dce7f2/docs/serving/online_serving/openai_compatible_server.md#L1-L28)) |
| [LocalAI](https://github.com/mudler/LocalAI/tree/bf93008ef3d6662d6a69447ca121a2af14c741f9) | [`bf93008`](https://github.com/mudler/LocalAI/commit/bf93008ef3d6662d6a69447ca121a2af14c741f9) | Multi-backend local engine with compatible API surfaces ([README lines 41-54](https://github.com/mudler/LocalAI/blob/bf93008ef3d6662d6a69447ca121a2af14c741f9/README.md#L41-L54)) |
| [TapeAgents](https://github.com/ServiceNow/TapeAgents/tree/e22d5e39ee043fcfe759902df4748d7e937d8aa0) | [`e22d5e3`](https://github.com/ServiceNow/TapeAgents/commit/e22d5e39ee043fcfe759902df4748d7e937d8aa0) | Structured replayable tapes used as resumable state and audit material ([README lines 15-31](https://github.com/ServiceNow/TapeAgents/blob/e22d5e39ee043fcfe759902df4748d7e937d8aa0/README.md#L15-L31)) |

Literature inputs are
[TapeAgents](https://arxiv.org/abs/2412.08445v1),
[AgentRR](https://arxiv.org/abs/2505.17716v1), and
[Deterministic Replay for AI Agent Systems](https://arxiv.org/abs/2607.16200v1).
They are design inputs, not validation of Agent Relay behavior.

## Actual architecture and test seams

The runtime flow is:

1. A `ConnectionProvider` opens a local or Secure Shell connection.
2. The connected profile supplies a `RemoteAgentRuntime`.
3. A provider factory launches its coding-agent program with `execute` or `openProcess`.
4. The adapter parses the native protocol and exposes an `AgentProviderConnection`.
5. The session runtime projects provider events into durable state and delivers checked actions.

The first test target is the native protocol between Agent Relay and Codex app-server, Cline ACP,
Claude streaming JSON, OpenCode, OpenDesk, Continue, or Aider. The second target is the LLM HTTP
protocol between one of those programs and its model endpoint. Native-protocol testing is mandatory.
LLM API emulation is useful only when a real CLI supports a loopback endpoint and the test still
traverses its normal Agent Relay adapter.

## Public mock assessment

The comparisons below are Agent Relay-specific inferences from the exact, immutable evidence linked
in the table above. They are recommendations to validate in the follow-on ARs, not upstream claims.

MockAgents is the strongest initial Linux CI pilot when a static checksum-pinned binary is
preferred. Its scenarios, tool calls, SSE, failure injection, replay, and contract extraction map
well to upstream-CLI tests. Its correct limitation is that it mocks wire behavior, not model quality.

aimock covers the broadest set, including MCP, A2A, and AG-UI. Its timing-aware fixtures and drift
checks are useful in a Node environment, but Agent Relay does not need that breadth by default.
A pilot must reject remote fixtures and proxy-on-miss, bind to loopback, pin the package, and prove
startup and teardown before a provider SDK or CLI is constructed.

llmock focuses on faithful OpenAI, Anthropic, and Gemini envelopes, realistic streaming, malformed,
truncated, and hanging streams, and byte replay. That is useful for timeout and parser behavior.
Raw byte cassettes can preserve secrets and volatile fields, so they are never acceptable repository
fixtures without the separate sanitization and admission pipeline.

AR-2223 must test all three with the same synthetic scenario and select at most one. If no emulator
adds coverage beyond scripted native-protocol tests, the correct result is to adopt none.

## Local inference assessment

Local inference is a real-model check, not a deterministic mock.

- Ollama is the easiest developer path, but its Responses compatibility is non-stateful, so
  `previous_response_id` and conversation support cannot be assumed.
- llama.cpp is compact and supports quantized CPU/GPU models, several compatible APIs, structured
  output, and tool calling. Results depend on the model and chat template.
- LocalAI provides one API layer over several backends, adding its own configuration and lifecycle.
- vLLM is suited to accelerator throughput. It is too heavy for normal PR CI, may download models,
  and documents endpoints outside its API-key-protected path prefixes.

A health check, model list, direct completion, fixed seed, or temperature zero does not prove Agent
Relay compatibility. Evidence must name the exact engine, model, quantization, template, CLI,
provider adapter, hardware class, and immutable revisions.

## Replay lessons

TapeAgents demonstrates the value of structured steps with provenance between configuration, LLM
calls, actions, and observations. Agent Relay should borrow the trace discipline, while its existing
session and workflow stores remain authoritative.

AgentRR proposes replayed experience plus a check function. The Agent Relay translation is a
versioned fixture schema, complete-consumption check, semantic request matcher, explicit
preconditions, and post-replay invariant checks. Replay must never authorize an effect or fill an
unmatched request.

Deterministic transport replay emphasizes strict isolation, explicit request keys, and narrow
classification of volatile noise. Globally ignoring IDs, timestamps, headers, or frame boundaries
would hide real provider regressions.

## Recommended layers

| Layer | Boundary | Execution tier | Limitation |
| --- | --- | --- | --- |
| Pure contracts | Provider/session value objects and state machines | Every PR | No process proof |
| Scripted runtime | `RemoteAgentRuntime`, duplex process, native adapter | Every PR | Synthetic process |
| LLM wire emulator | Real CLI backed by selected mock server | Selected PR or scheduled | No model-quality proof |
| Sanitized replay | Reviewed synthetic cassette and contract drift | Every PR after admission | Recorded branches only |
| Local inference | Real CLI plus Ollama, llama.cpp, LocalAI, or vLLM | Scheduled/manual self-hosted | One pinned tuple |
| Genuine provider | Existing opt-in live provider checks | Release/manual | Cost and nondeterminism |

The scripted runtime has the highest immediate value because every provider uses that seam.

## Required workflow coverage

Native-protocol suites must cover probe, authentication classification, session discovery, attach,
start, input, text streams, steering, interruption, approvals, questions, history, tools, files,
turn completion, error recovery, close, and reconnect.

Hostile cases include split or coalesced frames, unknown fields, invalid JSON, oversized messages,
wrong IDs, duplicates, gaps, out-of-order events, EOF, nonzero exit, timeout, hang, cancellation,
late frames, and uncertain delivery. Assertions cover normalized `AgentEvent`, session state,
capabilities, `ProviderOutcome`, causal traces, and confined changed-file paths.

The optional LLM-wire layer adds tool-call and result rounds, rate limits, authentication envelopes,
stream termination, malformed or truncated streams, hangs, and cancellation. It must not assert
that generated prose is good.

## Fixture, privacy, and CI requirements

The fixture schema must be versioned and smaller than vendor schemas. It represents semantic
expectations, scripted frames, virtual time, outcomes, and complete consumption.

Replay must:

- deny outbound network and bind helper services only to loopback;
- fail closed for unmatched, ambiguous, or leftover interactions;
- bound bodies, frames, events, time, processes, and retries;
- make clocks, seeds, IDs, and scheduling explicit;
- validate exact tools, arguments, approvals, events, and terminal outcomes;
- keep uncertain effects terminal and non-retryable; and
- emit only bounded redacted JUnit and summary evidence.

Only synthetic fixtures may be committed directly. Opt-in recording goes first to untracked,
access-restricted temporary storage and is deleted after review. Before admission, content is
irreversibly replaced with synthetic data, scanned for secrets and private data, bound to source,
tool, and schema revisions, and reviewed by a person.

Raw prompts, transcripts, reasoning, credentials, routes, hostnames, addresses, private paths,
model content, and personal data are prohibited in Git and CI artifacts. A mock must never fall
through to a real upstream. Dummy credentials are allowed only when tests prove they cannot leave
loopback.

Normal PR CI runs pure contracts and scripted runtime suites. A selected wire emulator may run only
where AR-2223 proves added coverage. Local inference runs on scheduled or manual labeled CPU or GPU
self-hosted runners with pre-staged immutable models, hard budgets, serialization, and cleanup.
Fork code must not execute on self-hosted runners; use the repository's same-repository PR guard.
Genuine-provider checks remain opt-in and release-scoped.

The `Local inference conformance` workflow is the fail-closed entry point for those local-model
runs. Its `agent-relay-local-inference-cpu` and `agent-relay-local-inference-gpu` labels keep the
hardware class explicit, while workflow concurrency serializes each class. Checkout and the
repository-local bootstrap complete before the conformance step enters a fresh Bubblewrap network
namespace. The runner compares the namespace inode with its parent, requires one exact staged tuple
covering engine and CLI identity, versions, revisions and digests, model and template digests,
protocol, quantization, sampling, adapter, and hardware class, and invokes a pre-staged driver as a
JSON argument array without a shell.
The driver must start and stop its loopback engine and real CLI inside that namespace and write one
bounded JSON evidence object to `AGENT_RELAY_LOCAL_INFERENCE_EVIDENCE`.

Only the validated redacted object is uploaded. Driver output is size-bounded and suppressed on
failure; the evidence schema rejects extra fields and permits only the declared checks and failure
classes (`api-shape`, `agent-protocol`, `model-behavior`, `performance`, or `infrastructure`). The
repository variables supply the driver argument array and staged tuple JSON, so normal PR jobs neither
download a model nor execute local inference. A scheduled run defaults to CPU; a maintainer may
select the GPU-labelled pool manually.

### Admitted CPU matrix

The manifest records five exact local-model observations in addition to the earlier bounded Ollama
observation. Each matrix engine and CLI ran together in a fresh network namespace with loopback and
no default route. The observations bind the engine, backend where applicable, CLI, model, template,
configuration, sampling, hardware class, source revision, and content digests. The broad engine
declarations remain `unverified`; an observation proves only its named tuple.

| Engine | Model | CLI and adapter | Verified through the adapter |
| --- | --- | --- | --- |
| Ollama 0.33.1 | Qwen3 0.6B, Q4_K_M | OpenCode 1.18.23, `anomaly.opencode` | Basic prompt, stream, cancellation, history/resume, teardown |
| llama.cpp build 1 | Qwen3 0.6B, Q4_K_M | OpenCode 1.18.23, `anomaly.opencode` | Basic prompt, stream, cancellation, history/resume, teardown |
| LocalAI `bf93008` | Qwen3 0.6B, Q4_K_M | OpenCode 1.18.23, `anomaly.opencode` | Basic prompt, stream, cancellation, history/resume, teardown |
| vLLM 0.28.1 development build | Qwen3 0.6B, float32 | OpenCode 1.18.23, `anomaly.opencode` | Basic prompt, stream, cancellation, history/resume, teardown |
| vLLM 0.28.1 development build | Qwen3 0.6B, float32 | Aider 0.86.2, `aider.cli` | Basic prompt and no-file-change teardown |

A direct vLLM API request produced the expected tool-call shape, but OpenCode did not complete the
tool/approval path within the bound. The manifest therefore records that API-shape check separately
and does not claim adapter tool or approval support. Real malformed-output and timeout traversal,
cloud behavior, mobile execution, accelerator execution, and prompt quality remain unverified.
Seeds and zero-temperature settings do not support a determinism claim.

The retained timing values are smoke metadata from earlier runs whose network isolation was not
proven. Their output token counts differ, so they are not comparative performance evidence. Raw
prompts, transcripts, logs, machine identities, addresses, and private paths are not admitted.

Evidence labels must identify the boundary: `synthetic-runtime`, `mock-llm-wire`,
`sanitized-replay`, `local-model`, or `live-provider`. Mock or local-model success cannot
satisfy cloud authentication, general model quality, physical-device inference, or untested
capability claims.

## Dependency-ordered work

| Task | Outcome | Dependencies |
| --- | --- | --- |
| AR-2219 | Publish this research record and durable recommendations | AR-1610, AR-2103 |
| AR-2220 | Define fixture, matching, virtual time, faults, privacy, and evidence | AR-2219 |
| AR-2221 | Build scripted `RemoteAgentRuntime` and duplex-process test utilities | AR-2220 |
| AR-2222 | Add native-protocol suites for every shipped provider adapter | AR-2221 |
| AR-2223 | Pilot MockAgents, aimock, and llmock behind a real provider CLI | AR-2220 |
| AR-2224 | Add capture, sanitization, admission, replay, and contract drift checks | AR-2220, AR-2223 |
| AR-2225 | Run the real local inference conformance matrix | AR-2222, AR-2223 |
| AR-2226 | Integrate evidence tiers into PR, scheduled/manual, and release CI | AR-2222, AR-2224, AR-2225 |

This ordering defines contracts before harnesses, validates native protocols before spending model
resources, and delays CI publication until privacy, provenance, and evidence limits are explicit.

## Explicit non-goals

- Do not create a generic OpenAI-compatible Agent Relay provider under these tasks.
- Do not replace native provider protocol tests with HTTP mock tests.
- Do not commit recordings from real users, providers, workspaces, or models.
- Do not treat replay as proof of prompt quality, safety, or unexplored behavior.
- Do not add multiple overlapping mock servers after the bounded pilot.
- Do not download models or contact upstream services from normal PR CI.
- Do not claim a provider capability unless its named adapter workflow passed at an immutable
  revision.
