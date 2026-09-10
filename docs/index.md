# Agent Relay

Agent Relay is an Android interface for supervising local and remote coding-agent sessions. It is designed to keep routine connection, persistence, reconciliation, and recovery work in the background while bringing exceptional decisions to the user with enough context to act safely.

## Start with a user goal

The [app workflow catalogue](WORKFLOWS.md) is the fastest way to understand the product. It includes emulator-verified walkthroughs for current behavior and clearly labeled contracts for planned behavior.

Current verified journeys cover:

- a fresh install through the first usable session;
- unknown and changed host identity review;
- agent approvals and provider questions;
- session switching, draft preservation, steering, and interruption;
- checked changed-file export; and
- the cross-host attention overview.

## View the guide

1. Open [App workflows](WORKFLOWS.md) directly in GitHub for the quickest
   screenshot-backed view.
2. Run `uv run --only-group docs mkdocs serve --strict` for local navigation
   and search.
3. Download and extract the `usage-guide-site` artifact from a successful
   Android UI verification run, then open `index.html`.
4. Use the Pages URL only after the repository owner explicitly enables public
   publication.

## Product documentation

- [Use the current app](USAGE.md)
- [Install a debug build](INSTALLING.md)
- [Understand the architecture](ARCHITECTURE.md)
- [Review connection providers](CONNECTION_PROVIDERS.md)
- [Review offline LLM testing and local inference recommendations](OFFLINE_LLM_TESTING.md)
- [Run local-inference conformance](LOCAL_INFERENCE_CONFORMANCE.md)
- [Read the product roadmap](PRODUCT_ROADMAP.md)
- [Inspect quality and verification gates](QUALITY.md)
- [Follow the AI-assisted development process](DEVELOPMENT.md)

## Evidence boundary

Verified workflow screenshots are produced by Android API 36 emulator tests with synthetic data. The test fixture is available only to test code and is absent from release builds. Pull requests compare current captures with reviewed baselines before the static site can be published.
