# Product roadmap

Status: Living document
Last updated: 2026-09-01

Agent Relay is an Android control surface for coding agents running in connected
environments. A connection can be local to the Android application, reached
through SSH, or supplied by a future connection provider. This roadmap describes
the product behavior that should remain consistent across providers, even when
an individual provider exposes fewer capabilities.

This document is a planning artifact, not a release promise. Priorities may move
when implementation evidence, provider limitations, Android platform changes, or
security findings change the cost of a feature.

## Product principles

1. **Make execution context visible.** A user should always know which
   connection, project, provider, session, and turn an action affects.
2. **Preserve user control.** Stop, steer, reconnect, retry, decline, and recover
   must remain available at the point where they matter.
3. **Keep sessions independent.** A stalled host or provider must not block
   another session or force unrelated sessions to disconnect.
4. **Treat approvals as security decisions.** Show the command, path, scope, and
   effect before asking. Never disguise a broad grant as a routine confirmation.
5. **Represent capabilities truthfully.** Unsupported provider operations are
   unavailable with an explanation, not simulated or silently ignored.
6. **Prefer local and private processing.** Credentials stay in Android Keystore,
   speech can run offline, and transcripts are not sent to another service by
   default.
7. **Design for interruption.** Mobile use is fragmented. Drafts, scroll
   position, queued input, unread state, and recovery context must survive.
8. **Use the whole device well.** The compact phone layout and expanded
   tablet/foldable layout should expose the same workflows without merely
   stretching the UI.

9. **Keep connection transport pluggable.** Agent providers consume a remote
   runtime supplied by a connection provider. SSH is the first connection
   provider, not an assumption baked into session or agent-provider APIs; local
   device access and other transports can implement the same boundary.
## Evidence

### Private history review

The Codex histories from two private development environments were analyzed on
their source machines on 2026-09-01. The audit classified session metadata into aggregate
themes. It did not copy prompts, responses, paths, repository names, credentials,
or transcript excerpts into this repository.

The two histories overlap, so they were treated as separate directional samples
rather than added together. Repeated signals were:

| Observed pattern | Product response |
| --- | --- |
| Long tasks with many explicit constraints | Durable composer drafts, reusable instructions, visible task goals, and constraint summaries |
| Frequent continue, resume, and completion requests | Reliable reattachment, recovery summaries, checkpoints, and clear completion state |
| Work spread across remote hosts and workspaces | Host/project/session hierarchy, connection health, and independent reconnection |
| Monitoring and progress checks during long work | Global activity inbox, progress timeline, background notifications, and quiet-hour controls |
| Heavy file, patch, report, and log workflows | Changed-file shelf, diffs, safe download, checksums, and artifact history |
| Review, testing, and Git collaboration | Structured tool timeline, validation summary, commit links, and output filters |
| Strong security and scope constraints | Risk-aware approvals, scoped grants, audit log, and prominent execution context |
| Parent/child and delegated sessions | Session lineage, child status rollups, and multi-agent coordination views |
| Slow or failure-prone remote operations | Heartbeats, backoff, reconnect history, offline transcript cache, and actionable diagnostics |

These are themes, not behavioral telemetry. The app must not introduce automatic
history collection to reproduce this analysis.

### Public interaction patterns

The roadmap also draws from established public products and platform guidance:

- [Codex app-server](https://developers.openai.com/codex/app-server) exposes
  conversation history, approvals, streamed events, turns, and rich-client
  integration.
- [ChatGPT Projects](https://help.openai.com/en/articles/10169521-projects-in-chatgpt)
  groups chats, files, instructions, and branches; [chat search](https://help.openai.com/en/articles/10056348-how-do-i-search-my-chat-history-in-chatgpt)
  supports returning to prior work.
- [ChatGPT Voice](https://help.openai.com/en/articles/20001274-chatgpt-voice)
  keeps speech, text, and prior messages in one conversation. [Work and
  Codex](https://help.openai.com/en/articles/20001275) makes progress,
  questions, approvals, filtering, and pinning part of long-running work.
- [Cline task management](https://docs.cline.bot/core-workflows/task-management)
  combines searchable history, favorites, usage, and resumption. [Cline
  checkpoints](https://docs.cline.bot/core-workflows/checkpoints) separate
  restoring files from restoring conversation state, while [auto
  approve](https://docs.cline.bot/features/auto-approve) scopes routine and risky
  actions.
- [GitHub Copilot agent sessions](https://docs.github.com/en/copilot/how-tos/copilot-on-github/use-copilot-agents/manage-and-track-agents)
  provide a cross-repository session panel, live logs, steering, stopping,
  archiving, sharing, usage, and traceability from commits to session logs.
- [OpenCode server APIs](https://opencode.ai/docs/server/) expose session
  children, todos, forks, aborts, diffs, reverts, permissions, and an event
  stream.
- [Continue headless mode](https://docs.continue.dev/cli/headless-mode) and
  [tool permissions](https://docs.continue.dev/cli/tool-permissions) demonstrate
  structured output, resumption, explicit tool grants, plan mode, and background
  task status.
- [Claude Code CLI](https://docs.anthropic.com/en/docs/claude-code/cli-usage)
  supports session continuation, specific-session resume, streamed structured
  input/output, permission modes, and tool allow/deny rules.
- [Aider commands](https://aider.chat/docs/usage/commands.html), [Git
  integration](https://aider.chat/docs/git.html), and [voice
  input](https://aider.chat/docs/usage/voice.html) connect diffs, undo, lint,
  tests, history, and speech to the coding conversation.
- Android's [adaptive app guidance](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps)
  recommends canonical list-detail layouts, and its [service
  guidance](https://developer.android.com/develop/background-work/services)
  requires visible foreground-service operation.
- The [usability heuristics](https://www.nngroup.com/articles/ten-usability-heuristics/)
  emphasize visible status, user control, error prevention, recognition over
  recall, and recovery.

### Similar open-source applications

The following applications are the most useful implementation benchmarks:

- [Whip](https://github.com/KaminariOS/whip) is the closest product comparison.
  It provides a multi-host attention queue, normalized Codex and OpenCode
  transcripts, expandable tool calls and diffs, cached terminal output, an
  offline per-session outbox, SFTP previews, jump hosts, biometric key
  protection, strict host keys, notifications, and speech. Agent Relay differs
  by using native Kotlin and a provider-neutral boundary that includes both
  local and SSH connections rather than a Herdr-specific React Native/Rust
  stack.
- [PocketShell](https://github.com/alexeygrigorev/pocketshell) is the strongest
  native Android reference for SSH/tmux, voice, remote files, and port
  forwarding. Its [architecture](https://github.com/alexeygrigorev/pocketshell/blob/main/docs/architecture.md)
  uses one reducer/effect authority, stale-session protection, and warm caches;
  its [testing strategy](https://github.com/alexeygrigorev/pocketshell/blob/main/docs/testing.md)
  combines Docker SSH/tmux fixtures, emulator end-to-end tests, screenshots,
  minimum-API coverage, and network fault injection.
- [Moke](https://github.com/briqt/moke) demonstrates native Compose SSH and Mosh
  sessions, a user-visible foreground service, latency display, tmux,
  mobile-oriented extra keys, text blocks, zoom, localization, and
  Android-Keystore-backed encryption.
- [Termux](https://github.com/termux/termux-app) is the reference for local
  process/session ownership, terminal modules and plugins, user-visible process
  controls, ABI-specific artifacts, and checksummed Android builds.
- [ConnectBot](https://github.com/connectbot/connectbot) provides a mature SSH
  baseline. Its [CI workflow](https://github.com/connectbot/connectbot/blob/main/.github/workflows/ci-build.yml)
  exercises emulator variants and coverage and pins third-party actions.
- [ServerBox](https://github.com/lollipopkit/flutter_server_box) shows the value
  of broad host-health and service-management views, but those are later
  operational features rather than part of Agent Relay's agent-session core.
- [ChatterUI](https://github.com/Vali-98/ChatterUI/blob/master/README.md)
  demonstrates capability-driven local and remote provider configuration,
  conversation management, and text-to-speech in a mobile client.
- Google's [Now in Android](https://github.com/android/nowinandroid) is the
  reference Compose test structure: interface-backed realistic test doubles,
  instrumented user-flow tests, Roborazzi visual regression on multiple window
  sizes, and baseline-profile/macrobenchmark coverage.
- [Element X Android](https://github.com/element-hq/element-x-android) treats a
  day/night preview as test input, generates Paparazzi screenshots, exercises
  global flows with Maestro, validates the minimum API, and requires screen
  reader review. Agent Relay should copy this layered evidence model, not its
  product layout.
- [WordPress Android's TalkBack guidance](https://github.com/wordpress-mobile/WordPress-Android/blob/trunk/docs/talkback-guidelines.md)
  combines semantic labels, grouping, headings, focus order, live-region
  announcements, minimum touch targets, manual TalkBack audits, Accessibility
  Scanner, lint, and automated Accessibility Test Framework checks.
- [DuckDuckGo Android](https://github.com/duckduckgo/Android) and
  [Firefox Android](https://github.com/mozilla-firefox/firefox/tree/main/mobile/android/fenix)
  demonstrate maintainable end-to-end tests built around named screen robots
  and user tasks instead of brittle view implementation details.

Public products are evidence for useful interaction patterns, not specifications
to copy. Agent Relay should use Android conventions and preserve provider
semantics.

## Post-initial-app benchmark milestones

These additions are explicitly sequenced after the initial application is
finished. They close concrete gaps found in the public-app review without
delaying the provider-neutral first milestone.

- Add Docker-backed OpenSSH and deterministic local-runtime fixtures. Exercise
  create, select, connect, disconnect, process death, and restore for both
  providers in emulator tests.
- Run focused fixture tests on pull requests and the full emulator, fault,
  minimum-API, screenshot, and accessibility matrix on scheduled and release
  workflows.
- Make one reducer/state-machine authority own each connection lifecycle. Tag
  asynchronous work with connection/session generations so stale events cannot
  mutate a replacement session.
- Add bounded transcript caches, a reviewable per-session offline outbox, and
  fault-injected reconnect tests for latency, loss, duplication, and reordering.
- Add an agent-native attention queue with explicit blocked, approval-required,
  working, done, idle, and failed states across providers.
- Manage known hosts explicitly, add optional biometric app/key unlock, separate
  local and SSH threat models, and privacy-safe lock-screen tests.
- Produce bounded support bundles that hash connection identifiers and exclude
  commands, prompts, credentials, terminal contents, paths, and transcripts by
  default.
- Add typed transcript entries, terminal/conversation modes, mobile extra keys,
  multi-line input, history, snippets, selection, zoom, and backpressure tests.
  Voice must use the same composer and outbox as typed input.
- Declare file and artifact capabilities per provider. Support Android share
  ingestion and safe previews generically, then add SFTP only for SSH.
- Add provider health, version, and protocol checks during onboarding; evaluate
  optional QR profile or public-key import only with a tested threat model.
- Add signing-certificate and checksum publication, permission-diff review,
  dependency/license reports, and third-party notices to release CI.
- Later, consider jump hosts, agent forwarding, port forwarding, and Mosh as a
  separate connection provider. Host-health, Docker, and systemd views remain
  deferred until the agent-session, artifact, and recovery core is complete.

## Priority rubric

Features are ordered using four questions:

1. Does this prevent lost work, an unsafe action, or a missed agent request?
2. Does it improve a workflow repeatedly seen in the history review?
3. Can it behave consistently across multiple providers?
4. Is its security, battery, and maintenance cost proportionate to its value?

`P0` is required for a trustworthy first release. `P1` is the next product
layer. `P2` contains valuable expansion work that should not destabilize the
core.

## P0: Trustworthy remote work

### Session hub and activity inbox

- Group sessions by host, project, and provider while keeping a flat "Recent"
  view for fast switching.
- Keep connection-provider and agent-provider sessions alive independently when
  the visible session changes.
- Show connection, authentication, agent, turn, approval, and unread states
  separately. "Saved", "connected", and "running" must not be conflated.
- Put unread counts on navigation destinations and individual sessions.
- Provide an all-session inbox for new output, approvals, questions, failures,
  reconnects, and completed turns.
- Support pin, mute, archive, and per-session notification priority.
- Open a notification at the exact session event, not merely the app home.

### Reliable connection lifecycle

- Route every agent provider through a generic connection-provider contract;
  connection-specific profiles and trust operations stay behind that provider.
- Support password, imported-key, and agent-backed SSH profiles without storing
  plaintext secrets.
- For SSH, require explicit host-key trust on first use and block changed keys
  until the user reviews the old and new fingerprints.
- Show latency, last heartbeat, reconnect attempt, and the last actionable error.
- Reconnect with bounded exponential backoff and preserve the transcript and
  unsent draft while offline.
- Make intentional disconnect, authentication failure, network loss, remote
  process exit, and Android background suspension distinguishable.
- Offer a connection diagnostic report with secrets and sensitive paths
  redacted.

### Provider-aware session control

- Probe installation, version, authentication, configuration, and protocol
  compatibility before offering a provider.
- Display a capability matrix for discovery, history, live output, resume,
  steering, interrupt, approvals, file changes, and fork.
- Discover running and persisted sessions without claiming that a persisted
  session is currently executing.
- Start, attach, detach, resume, steer, interrupt, and close only when the
  provider supports the operation.
- Preserve provider-specific metadata behind an expandable diagnostics view.
- Degrade to a read-only transcript when a provider cannot safely resume an old
  session.

### Agent timeline

- Render user messages, agent commentary, final answers, plans, tool calls,
  approvals, errors, and state changes as distinct timeline entries.
- Stream deltas without layout jumps and replace them with the authoritative
  completed message.
- Collapse verbose tool output by default while keeping command, status,
  duration, and output searchable.
- Maintain a sticky "new activity" affordance when the user is reading older
  output instead of forcing the scroll position.
- Show the active turn, elapsed time, latest tool, and last output time.
- Mark partial, interrupted, failed, and recovered turns explicitly.
- Cache recent transcripts locally with encryption and bounded retention.

### Composer and voice

- Preserve an independent draft and cursor position for every session.
- Support multi-line input, send, queued send, edit-before-send, cancel, and
  retry.
- Make text input available while speech is enabled; speech is an input method,
  not a separate mode.
- Use offline speech recognition and synthesis by default with explicit model
  download size, language, checksum, and deletion controls.
- Show live transcription as editable text before sending.
- Allow read-aloud per message, per session, or for new final output only.
- Stop speech immediately when the user switches session, taps stop, receives a
  call, or starts recording.
- Never read commands, secrets, reasoning summaries, or approval details aloud
  unless explicitly requested.

### Approval inbox

- Show provider, host, workspace, session, command or file scope, rationale, and
  requested duration in one review surface.
- Provide approve once, approve for session, decline, cancel, and question
  answers only when the provider advertises them.
- Require an additional confirmation for destructive commands, broad filesystem
  access, credential access, or network expansion.
- Keep approval grants narrowly scoped and visibly revocable.
- Never place a global "approve everything" action in the routine approval flow.
- Record decisions locally in a redacted audit trail and link each decision back
  to the resulting tool event.

### Changed files and artifacts

- Maintain a per-session shelf of added, modified, renamed, and deleted files.
- Preview text and images, show metadata, and provide a diff when available.
- Download through Android's Storage Access Framework with collision choices,
  progress, cancellation, checksum verification, and partial-file cleanup.
- Batch-download a selected set with a manifest containing remote path, host
  fingerprint, session, timestamp, and checksum.
- Warn when a remote file changes between preview and download.
- Treat deleted files as history entries, never as downloadable empty files.
- Keep transfer failures independent from the agent connection.

### Background operation and notifications

- Use a user-visible foreground service only while live remote sessions require
  continuous connectivity.
- Group notifications by host and session and update an existing notification
  instead of flooding the notification shade.
- Distinguish approval required, question, completed, failed, reconnected, and
  ordinary output channels.
- Support quiet hours, per-session mute, final-output-only mode, and privacy-safe
  lock-screen text.
- Persist unread state transactionally so process death cannot lose an event.
- Explain battery restrictions and degraded delivery with an actionable status,
  without pressuring the user to disable system protections.

### Accessibility and adaptive layout

- Use a list-detail layout on expanded screens and focused list or detail views
  on compact screens.
- Support TalkBack, keyboard navigation, switch access, dynamic type, high
  contrast, reduced motion, and minimum touch targets.
- Never encode host, provider, session, unread, or risk state by color alone.
- Keep long hostnames, paths, commands, and provider errors selectable and
  readable without overlapping controls.
- Treat discoverability and task completion as release behavior: a first-time
  user must be able to identify the current connection, understand status,
  recover from an error, and find the next valid action without documentation.
- Give every actionable element an accurate localized name, role, state, and
  action in the Compose semantics tree. Decorative elements must not add focus
  noise, and dynamic status changes must be announced without stealing focus.
- Test touch targets, contrast, reading/focus order, keyboard and switch access,
  long text, empty/loading/error/unsupported states, and destructive-action
  confirmation.
- Maintain deterministic semantic interaction tests for critical compact and
  expanded flows using realistic provider test doubles rather than mocks.
- Maintain Linux-recorded screenshot baselines for compact, medium, and expanded
  windows in light/dark themes, large font scales, and at least one
  expansion-prone locale such as German. Baseline updates require human review
  of the rendered result and image diff.
- Run Compose accessibility checks on API 34 or newer and device tests on both
  the minimum and current supported API. Complete a manual TalkBack and keyboard
  audit for each release because automated checks cannot validate usability.

## P1: Faster repeated work

### Search, organization, and return

- Search locally cached session titles, messages, commands, files, errors, and
  validation results with host/provider/time filters.
- Add saved views such as "Needs me", "Running", "Failed", "Unread", and
  "Recently completed".
- Support tags, favorites, project instructions, session rename, and bulk
  archive.
- Generate an on-device "since you were away" summary linked to original events;
  never replace the raw timeline.
- Show last read position and a compact resume card with current goal, pending
  decision, last change, and next expected action.

### Branching, checkpoints, and recovery

- Fork a session at a message when the provider supports it.
- Model conversation rollback and workspace rollback as separate decisions.
- Show provider-native checkpoints and Git commits in one ordered recovery view
  without pretending they are equivalent.
- Compare changes at two checkpoints before restore.
- Require a clean preview and explicit scope for destructive restore operations.
- Allow retry from a failed turn with edited input while preserving the original
  failure.

### Goals, plans, and progress

- Extract or accept a session goal and show provider-reported plan steps without
  inventing completion.
- Provide elapsed time, latest meaningful activity, blocked reason, and rough
  provider-reported usage.
- Allow a user to mark a session as waiting, blocked, or intentionally paused
  without terminating it.
- Roll child-session status into the parent while keeping individual failures
  inspectable.
- Offer a concise validation summary: commands run, pass/fail, skipped checks,
  changed files, and commits.

### Context and reusable input

- Attach local files, images, clipboard text, remote paths, prior messages, and
  URLs when supported.
- Show exactly what context will be sent and whether it leaves the remote host.
- Save prompt snippets, project instructions, and multi-step runbooks with
  parameters.
- Provide a constraint checklist for long prompts and flag contradictory
  instructions before send.
- Keep a searchable input history per project with sensitive-entry exclusion and
  a clear delete action.

### Resource and diagnostics views

- Show token/context usage, model, reasoning mode, latency, provider version, and
  local-model queue state where available.
- Separate model time, tool time, approval wait, reconnect delay, and background
  suspension.
- Provide raw protocol logs only behind developer mode, bounded and redacted.
- Include a terminal escape hatch for diagnostics with a prominent warning that
  raw terminal mode has weaker structure and accessibility.

### Export and audit

- Export a redacted transcript, event log, validation summary, changed-file
  manifest, or support bundle.
- Let the user preview every exported field and exclude tool output or paths.
- Link signed commits to the session events that produced them when the provider
  exposes that relationship.
- Support configurable local retention and a verifiable "delete local data"
  operation.

## P2: Coordinated agent work

### Multi-agent topology

- Display parent/child session trees across providers and hosts.
- Delegate a bounded task with an explicit workspace, provider, permissions,
  budget, and completion contract.
- Compare independent agent proposals or reviews without merging transcripts.
- Support a handoff package containing goal, constraints, relevant files,
  validation state, and unresolved questions.
- Detect conflicting edits across live sessions before download or integration.

### Automation and watch rules

- Trigger a saved workflow from a schedule, repository event, process exit, file
  change, or health condition.
- Require an explicit preview of host, command, provider, model, permissions, and
  notification policy before enabling a rule.
- Provide run history, next-run time, missed-run reason, pause, and kill switch.
- Default new automation to read-only and require separate approval for writes.
- Rate-limit repeated failures and notify once with a grouped diagnostic.

### Provider ecosystem

- Publish a provider SDK, contract tests, compatibility matrix, and example
  provider.
- Load only signed build-time provider modules distributed with the app or a
  trusted app update.
- Do not download executable plugin code into the Android process.
- Allow data-only provider definitions for safe command discovery when the core
  runtime can enforce all behavior.
- Track protocol/version compatibility separately from provider marketing
  version.

### Collaboration

- Share a redacted session snapshot or artifact manifest without sharing SSH
  credentials.
- Add read-only observer and approval-delegation roles only after an auditable
  identity model exists.
- Preserve the original actor for prompts, approvals, interrupts, and downloads.
- Provide comments on messages and file changes without injecting them into the
  agent context until explicitly sent.

## Explicit non-goals

- A generic full-screen SSH terminal as the primary experience.
- Automatic acceptance of changed SSH host keys.
- Cloud transcript synchronization enabled by default.
- Hidden background services or deceptive battery-optimization prompts.
- Runtime-downloaded Android executable plugins.
- Claiming feature parity when provider protocols differ.
- Reading sensitive tool output aloud automatically.
- Replacing Git, code review, or provider-native recovery with an opaque local
  snapshot format.

## Release gates

A feature leaves roadmap status only when it has:

- documented provider and failure semantics;
- unit tests plus integration tests at its trust boundary;
- accessibility behavior and content descriptions;
- semantic user-flow tests for success, empty, loading, error, offline, and
  unsupported states;
- human-reviewed compact, medium, and expanded screenshots across light/dark,
  large-font, and long-text configurations;
- automated accessibility checks plus recorded manual TalkBack and keyboard
  audits;
- process-death, reconnect, and cancellation tests where applicable;
- evidence that the primary user task is discoverable and completable without
  hidden gestures or external documentation;
- threat-model review for credentials, approvals, files, or background work;
- user-facing documentation and troubleshooting guidance;
- no secret, private path, or transcript leakage in logs, notifications, or
  exported diagnostics.

## How to propose changes

Open an issue that describes the user problem, affected providers, security and
battery implications, graceful degradation, and a testable success condition.
A proposed UI should show how it behaves for running, idle, offline, waiting,
failed, and unsupported states.
