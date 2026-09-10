# Local-inference conformance matrix

This matrix tests real local model endpoints below the shipped Agent Relay provider CLI. Engines are LLM endpoints, not Agent Relay providers. The machine-readable tuple is `config/local-inference-conformance-v1.json`.

## Current evidence

The pinned research revisions are Ollama `86f7292`, llama.cpp `1945e09`, vLLM `385dce3`, and
LocalAI `bf93008`. The four-engine conformance matrix remains incomplete and every engine row keeps
the result `unverified`. A health endpoint or model list would not change that classification.

One narrower `local-model` observation has passed. OpenCode 1.18.23 traversed the production
`anomaly.opencode` adapter to Ollama 0.33.1 and the immutable qwen3:0.6b Q4_K_M model on an x86_64
CPU worker after outbound network access was denied. The run verified project discovery, session
creation, a model-qualified prompt, a marker-bearing assistant transcript, a mapped live event, and
clean teardown. The machine-readable observation records the exact source revisions, binary and
model digests, template digest, sampling settings, hardware class, network boundary, and reviewed
PR and merge revisions without retaining environment identifiers or model output.

That observation does not verify llama.cpp, vLLM, LocalAI, another CLI, tool or approval traversal,
active-inference cancellation, malformed real-CLI or timeout behavior, performance, cloud, mobile,
or accelerator behavior. The model configuration did not specify a seed, so determinism is not
claimed.

## Required run

Run only on a scheduled or manually approved self-hosted runner after staging immutable engine and model digests. Set `AGENT_RELAY_LOCAL_INFERENCE_ENABLE=1` and keep outbound network denied. The runner must bind the endpoint to loopback, use a dummy credential that cannot leave loopback, execute a tiny synthetic session through a real shipped CLI and Agent Relay adapter, and record redacted JUnit evidence.

Each tuple must test streaming, tool calls, approvals, cancellation, history/resume, malformed output, timeout, and teardown. Classify API shape, agent protocol, model behavior, performance, and infrastructure failures separately. Do not generalize a passing tuple to other models, templates, hardware, CLIs, cloud APIs, or Android.

The helper refuses to run when network isolation, explicit model/engine/CLI digests, or the real-CLI gate is absent. It never downloads models, contacts an upstream service, or silently falls back to a real provider.
