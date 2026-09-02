# Provider operations and live verification

This document records how Agent Relay's provider integrations are verified
against real command-line agents. It is evidence for readiness and protocol
work, not a claim that every advertised provider capability is implemented.

## Verification policy

- Run on the remote host in a disposable directory, never in the Agent Relay
  repository.
- Use the configured production binary and model rather than substituting a
  curl reachability check for a real agent invocation.
- Run providers sequentially with a 900-second outer timeout.
- Use read-only, plan, no-tool, no-Git, or dry-run controls where the client
  exposes them.
- Capture exit status, structured output, stderr, elapsed time, and tool use.
- Keep provider capabilities disabled until unit, contract, malformed-output,
  timeout, interruption, and disconnect tests demonstrate them.
- Do not store tokens, raw private prompts, or machine-specific secrets here.

## Private verification boundary

Live provider checks run only when explicitly enabled in a private, disposable
development environment. Host aliases, addresses, ports, accounts, local model
aliases, executable paths, durable session identifiers, raw transcripts, and
provider credentials are deliberately excluded from this repository.

## Live results

Each provider was exercised through its production command-line interface with
a bounded request for exactly `READY`, no tool use, and no file changes. The
repository records only the resulting capability boundary:

| Provider surface | Recorded result |
| --- | --- |
| Claude Code stream JSON | Exact final response, no tools, clean shutdown |
| OpenCode server and SSE | Typed lifecycle/events and clean server shutdown |
| OpenDesk HTTP/SSE with local Ollama | Session discovery, assistant completion, token deltas, clean shutdown |
| Continue headless/server modes | Read-only response and loopback-only lifecycle |
| Cline ACP | Plan-mode session lifecycle with auto-approval disabled |
| Aider safe helper | Structured response, no changes, clean descendant shutdown |

These checks do not publish environment-specific timing, token counts, model
configuration, stored-session metadata, or network topology. Unit and contract
tests remain the authoritative evidence for malformed output, interruption,
approvals, resume behavior, file boundaries, and concurrent sessions.

## OpenCode server integration

The OpenCode module starts `opencode serve` on a random loopback port with a
fresh 256-bit password, uses authenticated HTTP for session operations, and
opens directory-scoped SSE streams for live events. Passwords and request
bodies are passed outside process arguments. The startup health request has a
one-second HTTP deadline so a connection accepted during OpenCode
initialization cannot consume the normal 55-second operation allowance.

The module uses `/project`, `/session`, `/session/status`, session detail,
message, asynchronous prompt, abort, permission reply, and diff endpoints plus
`/event`. It requires `bash`, `curl`, and `python3` on the remote host in
addition to OpenCode.

Unit and contract tests cover discovery across worktrees, history mapping,
model-qualified prompts, interruption, approvals, diffs, malformed SSE data,
event-stream replacement, failed startup cleanup, and secret handling. Run the
read-only installed-binary integration check explicitly:

```bash
AGENT_RELAY_LIVE_OPENCODE=1 ./gradlew \
  :provider:opencode:test \
  --tests dev.agentrelay.provider.opencode.OpenCodeLiveIntegrationTest
```

The opt-in live check verifies these boundaries without recording host
identifiers, account names, endpoint details, session identifiers, or raw
transcripts in this repository.

## OpenDesk server integration

The OpenDesk module starts `opendesk serve --mode opencode` on a random
loopback port with a fresh 256-bit password. It uses the OpenCode-compatible
HTTP surface where the protocols match and an explicit OpenDesk dialect where
they do not. The executable lookup supports ordinary `PATH` installs,
`~/.local/bin`, and the newest npm installation under nvm. Node's executable
directory is added only to the launched OpenDesk process.

OpenDesk session discovery uses
`/experimental/session?scope=project`, then obtains status and opens an SSE
stream for each session directory. Session creation, detail, history,
asynchronous prompts, and interruption use the corresponding `/session`
routes. Permission and question events map to durable Agent Relay approvals;
responses use `/permission/:id/reply`, `/question/:id/reply`, and
`/question/:id/reject`. OpenDesk token events use
`message.part.delta`, which the shared protocol core maps separately from
OpenCode's part updates.

The adapter advertises discovery, start, resume, history, live streaming,
interruption, and approvals. It does not advertise active-turn steering or
forking. OpenDesk 0.3.5 returns an empty placeholder from its diff endpoint, so
the adapter also does not advertise provider-reported file changes.

Production startup uses OpenDesk's normal application data directory. Tests
and controlled embeddings can supply a separate config directory, which is
passed through OpenDesk's supported `--config-directory` option. Configure
the selected OpenDesk profile with an enabled OpenAI-compatible provider that
targets the local Ollama service, then select a model exposed by that service.
Provider credentials and request bodies remain outside process arguments.

The installed-binary inference test creates an isolated OpenDesk profile and
workspace, exercises the real local Ollama model, verifies global discovery,
waits for a marker-bearing assistant transcript, requires a mapped SSE token
event, and checks that all child processes and loopback listeners stop. Set the model
alias privately when the default validation model is unavailable:

```bash
AGENT_RELAY_LIVE_OPENDESK=1 \
AGENT_RELAY_LIVE_OPENDESK_MODEL='<local-model-alias>' \
  ./gradlew :provider:opendesk:test \
  --tests dev.agentrelay.provider.opendesk.OpenDeskLiveIntegrationTest
```

The remote environment must provide `bash`, `curl`, `python3`, Node.js,
OpenDesk, a reachable loopback Ollama service, and the selected model. The test
is opt-in and does not publish its temporary paths, session identifiers,
transcript, model selection, or timing.

## Continue server integration

The Continue module discovers durable sessions with `cn ls --json` and starts
one `cn serve --id` process per attached session on a random port. Continue
1.5.47 does not expose a bind-address option or HTTP authentication, so the
module injects a Node preload that forces numeric server listeners to
`127.0.0.1`. The preload removes `NODE_OPTIONS` after installation so tools
started by the agent do not inherit it. Startup also inspects the actual Linux
listener through `/proc/net/tcp*` and refuses the connection if it is absent or
not loopback-only. The remote SSH account and host remain the security boundary:
other users able to access that host's loopback network are outside the
module's threat model.

The module uses `/state`, `/message`, `/permission`, `/pause`, `/diff`, and
`/exit`. Each attached session has an independent server and polling job, so
multiple sessions can remain attached concurrently. State is polled every 750
milliseconds to publish state, completed-message, tool, approval, turn, and
file-change events. Continue's server does not expose token deltas: "live
streaming" here means live state and completed-item delivery. `/message` queues
input rather than steering the active turn, permission decisions are one-shot,
and this module does not advertise active-turn steering or session forking.
`/diff` is Continue's repository diff against the main branch, not a
turn-scoped change set.

Request bodies are passed outside process arguments. In addition to Continue,
the remote host must provide `bash`, `curl`, `python3`, and Linux `/proc`
listener tables. Optional start settings support `config`, `agent`, `model`,
`readonly`, and `auto`; if both permission modes are requested, `readonly`
wins.

Run the non-mutating installed-binary integration check explicitly:

```bash
AGENT_RELAY_LIVE_CONTINUE=1 ./gradlew \
  :provider:continue:test \
  --tests dev.agentrelay.provider.continuecli.ContinueLiveIntegrationTest
```

The opt-in live check verifies these boundaries without recording host
identifiers, account names, endpoint details, session identifiers, or raw
transcripts in this repository.

## Claude Code stream integration

The Claude Code module discovers durable sessions from main-chain message rows
under `~/.claude/projects` and opens one bidirectional `stream-json` process
per attached session. It uses Claude's control requests for initialization,
turn interruption, and tool permissions. Session start uses a generated UUID;
attachment uses `--resume`. Multiple attached sessions retain independent
processes and event collectors.

Prompts and permission responses travel over standard input rather than process
arguments. Reusable approval is offered only when Claude supplies explicit
`permission_suggestions`; otherwise the UI exposes one-shot approval and
decline. The module rejects `bypassPermissions` and accepts only the documented
non-bypass permission modes. It does not advertise active-turn steering or
session forking.

History maps user, assistant, reasoning, tool-use, and tool-result records.
Changed-file reporting is deliberately limited to direct `Write`, `Edit`,
`MultiEdit`, and `NotebookEdit` tool inputs. Paths are normalized and kept
only when they remain inside the session's known absolute working directory;
arbitrary filesystem changes made by shell commands are not inferred. The
remote host must provide `python3` in addition to an authenticated Claude Code
installation.

Set `AGENT_RELAY_LIVE_CLAUDE_SESSION_ID` to a private durable smoke
session, then run the non-mutating installed-binary check explicitly:

```bash
AGENT_RELAY_LIVE_CLAUDE=1 ./gradlew \
  :provider:claude:test \
  --tests dev.agentrelay.provider.claude.ClaudeLiveIntegrationTest
```

The opt-in live check verifies these boundaries without recording host
identifiers, account names, endpoint details, session identifiers, or raw
transcripts in this repository.

## Cline ACP integration

The Cline module uses the standard Agent Client Protocol exposed by
`cline --acp`. Each attached session has an independent ACP process, while the
connection can keep multiple sessions active concurrently. New sessions use
`session/new`; stored sessions use `session/load`; prompts, cancellation,
streamed agent/reasoning chunks, tools, and permission requests remain on the
JSON-RPC standard-input/output channel. Prompts and API-key overrides are not
placed in process arguments, and tool auto-approval is always disabled.

Durable discovery uses `cline history --json`, and transcripts are read from
the exact `messagesPath` returned by that history record after constraining it
to the standard `~/.cline` state tree. Tool permissions map ACP's offered
`allow_once`, `allow_always`, and reject options rather than inventing
provider decisions. Changed-file events accept only ACP edit/delete/move tool
kinds and only paths that normalize inside the session's known absolute
workspace. The module does not advertise active-turn steering or session
forking.

Some Cline configurations omit locally configured providers from the ACP
catalog.
Agent Relay treats native resume as unavailable when Cline cannot resolve the
stored provider during `session/load`; it does not replace native resume with
transcript injection. The opt-in live test receives any required provider,
model, workspace, and durable session selection through untracked environment
variables.

Set the private `AGENT_RELAY_LIVE_CLINE_SESSION_ID`, `_DIRECTORY`,
`_MODEL`, and `_PROVIDER` values, then run the non-mutating installed-binary
check explicitly:

```bash
AGENT_RELAY_LIVE_CLINE=1 ./gradlew \
  :provider:cline:test \
  --tests dev.agentrelay.provider.clinecli.ClineLiveIntegrationTest
```

The opt-in live check verifies these boundaries without recording host
identifiers, account names, endpoint details, session identifiers, or raw
transcripts in this repository.

## Aider safe helper integration

Aider 0.86.2 has no structured remote-control or approval protocol. Its public
one-shot CLI accepts prompts in process arguments, mixes announcements with
assistant output, and can answer confirmation prompts with their default when
standard input reaches EOF. Agent Relay therefore does not scrape the
interactive terminal or invoke `--yes-always`.

The Aider module launches the installed tool's own Python interpreter with a
signed, build-time helper. One helper and coder instance is retained per
attached session. Prompts travel as line-delimited JSON over standard input,
and only structured ready, result, error, and changed-file records return over
standard output. The helper forces Aider's confirmation policy to `false`
before any prompt and disables auto-commits, dirty commits, shell suggestions,
automatic lint/test execution, URL handling, browser/GUI behavior, update
checks, analytics, notifications, and Git-ignore mutation. Explicit editable
files are accepted only as a JSON string array and are normalized inside the
known workspace.

Durable state is stored under
`~/.local/state/agent-relay/aider/<session-id>`, with private directory and
metadata permissions. Discovery accepts only UUID-shaped direct children and
constrains transcript reads to that state root with an 8 MiB limit. Aider's
own chat-history grammar is mapped to typed user, agent, and tool transcript
entries. Changed-file events come from Aider's `aider_edited_files` result
and are normalized inside the session workspace; their durable kind is limited
to added, modified, or deleted.

The module advertises discovery, start, native history restoration, history
reads, interruption by process shutdown, and changed files. It deliberately
does not advertise live token streaming, approvals, active-turn steering, or
forking. Responses arrive as completed messages because Aider exposes no stable
structured streaming surface. Confirmation requests are declined rather than
presented as approvals the provider cannot faithfully round-trip. The
readiness probe verifies the installed Python API signatures used by the
helper and reports an incompatible version instead of guessing after upstream
API drift.

Set `AGENT_RELAY_LIVE_AIDER_MODEL` for both checks. If Aider is not on
`PATH`, also set `AGENT_RELAY_LIVE_AIDER_EXECUTABLE`. Run the installed-binary
startup check without inference:

```bash
AGENT_RELAY_LIVE_AIDER=1 ./gradlew \
  :provider:aider:test \
  --tests dev.agentrelay.provider.aider.AiderLiveIntegrationTest
```

Run the separately gated, bounded local-model prompt:

```bash
AGENT_RELAY_LIVE_AIDER_INFERENCE=1 ./gradlew \
  :provider:aider:test \
  --tests \
  dev.agentrelay.provider.aider.AiderLiveIntegrationTest.installedAiderCompletesBoundedPromptThroughSafeHelper
```

The opt-in live check verifies these boundaries without recording host
identifiers, account names, endpoint details, session identifiers, or raw
transcripts in this repository.

## Safe smoke commands

Run live checks only from a disposable directory. Supply provider-specific
model, account, workspace, and session selections through untracked environment
variables or private machine configuration. Never commit those values, raw
provider output, credentials, host aliases, addresses, or endpoint details.

The opt-in Gradle integration tests above enforce bounded execution and verify
that processes, temporary captures, and loopback listeners are cleaned up.
