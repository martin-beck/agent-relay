# Local-inference conformance matrix

This matrix tests real local model endpoints below the shipped Agent Relay provider CLI. Engines are LLM endpoints, not Agent Relay providers. The machine-readable tuple is `config/local-inference-conformance-v1.json`.

## Current evidence

The pinned engine revisions are Ollama `86f7292`, llama.cpp `1945e09`, vLLM `385dce3`, and LocalAI `bf93008`. The current remote host has no installed engine or shipped CLI, no staged model, and no real conformance result; every tuple remains `unverified`. A health endpoint or model list would not change that classification.

## Required run

Run only on a scheduled or manually approved self-hosted runner after staging immutable engine and model digests. Set `AGENT_RELAY_LOCAL_INFERENCE_ENABLE=1` and keep outbound network denied. The runner must bind the endpoint to loopback, use a dummy credential that cannot leave loopback, execute a tiny synthetic session through a real shipped CLI and Agent Relay adapter, and record redacted JUnit evidence.

Each tuple must test streaming, tool calls, approvals, cancellation, history/resume, malformed output, timeout, and teardown. Classify API shape, agent protocol, model behavior, performance, and infrastructure failures separately. Do not generalize a passing tuple to other models, templates, hardware, CLIs, cloud APIs, or Android.

The helper refuses to run when network isolation, explicit model/engine/CLI digests, or the real-CLI gate is absent. It never downloads models, contacts an upstream service, or silently falls back to a real provider.
