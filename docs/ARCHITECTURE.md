# Architecture

Agent Relay separates how an execution environment is reached from how a coding
agent is controlled. Neither the session layer nor an agent adapter assumes SSH.

## Dependency direction

```text
                        :app
                          |
                   :session:runtime
                    /             \
         :connection:api       :provider:api
          /          \              |
:connection:local   :ssh:api   :provider:*
                         |
                :ssh:jsch + :ssh:android

:storage:android is shared by Android persistence implementations.
:session:api and :session:android persist provider-neutral session state.
```

Dependencies point toward contracts. Agent providers consume
`RemoteAgentRuntime`; they do not cast it to an SSH or local implementation.
Connection providers do not know agent protocols. A provider can optionally
expose a `ConnectionProfileManager`; the app renders its generic bounded field
schema without importing SSH configuration types.

## Modules

| Module | Responsibility |
| --- | --- |
| `:app` | Android entry point and Compose presentation |
| `:connection:api` | Generic connection providers, provider-owned profile forms, lifecycle, identity challenges, and runtime access |
| `:connection:local` | App-local process execution and workspace confinement |
| `:ssh:api` | SSH profiles, credentials, host keys, retry policy, and generic adapter |
| `:ssh:jsch` | Maintained JSch transport and bounded POSIX command runtime |
| `:ssh:android` | Android SSH persistence, credentials, and non-exportable agent keys |
| `:provider:api` | Agent descriptors, sessions, events, capabilities, and actions |
| `:provider:*` | Codex, OpenCode, Continue, Claude, Cline, and Aider adapters |
| `:session:api` | Durable provider-neutral session identity and repository |
| `:session:android` | Encrypted Android session-hub document store |
| `:session:runtime` | Profile discovery, connection lifecycle, agent discovery, event projection, and actions |
| `:storage:android` | Namespaced authenticated Android Keystore document encryption |

## Session identity

A session is addressed by the complete tuple:

```text
ConnectionProviderId
  + ConnectionProfileId
  + AgentProviderId
  + AgentSessionId
```

The tuple prevents identical provider session IDs from colliding across SSH,
local access, profiles, or future connection types.

## Runtime flow

1. A `ConnectionProvider` enumerates generic profile summaries.
2. The coordinator opens a `ManagedConnection`.
3. A connected profile supplies a `RemoteAgentRuntime`.
4. Compatible agent factories probe and open agent sessions through that runtime;
5. the coordinator starts sessions and projects agent events into the durable
   session repository; and
6. the Compose layer observes durable state and sends capability-checked actions.

All six layers are now connected for the first interactive session-hub slice.
An application-scoped graph owns encrypted store construction and registers both
Local and Secure Shell plus all implemented agent factories. A navigation-scoped
ViewModel combines coordinator and durable repository snapshots so destinations
do not create duplicate runtimes or state authorities.
Composer edits use an in-memory projection for immediate feedback while the
ViewModel debounces writes to the encrypted session repository. Submission
flushes the exact draft before provider I/O and clears it only after successful
delivery; a failed action retains the durable draft and a sanitized UI error.

Approval and question events enter the same repository as an atomic action/
activity pair. Responses move to durable **Delivering** state before provider
I/O and to **Resolved** only after the provider accepts them. An uncertain
delivery is never made retryable automatically. The runtime translates hashed
UI question keys back to exact encrypted provider identifiers at the final
boundary, validates every offered decision and answer, and requires explicit
additional confirmation for positive high-risk or session-wide grants.

Profile setup follows a separate provider-neutral path: Compose edits generic
text, port, secret, choice, and read-only fields; the selected provider validates
them and owns persistence. SSH maps those fields to encrypted credential
references or non-exportable Android Keystore agent keys. Successful edits
invalidate an existing managed connection and refresh coordinator profiles.

## Security boundaries

- Android's application sandbox is the local-process boundary.
- Local working directories are canonicalized below an app-controlled root.
- SSH server identity is accepted only through explicit trust decisions.
- Android Keystore keys encrypt namespaced authenticated documents.
- Credentials stay outside command arguments, events, and diagnostic snapshots.
- Stored secrets are never returned by a profile manager; replacement values
  cross one wipeable `CharArray`/byte-array boundary before encrypted storage.
- Agent-provided file paths are normalized against the known workspace.
- Output, protocol lines, retention, retries, and process lifetimes are bounded.

See [Connection providers](CONNECTION_PROVIDERS.md) and
[Session hub](SESSION_HUB.md) for detailed invariants.

## State ownership

Connection lifecycle belongs to connection providers. Agent discovery and
protocol state belong to agent providers. Durable drafts, unread activity,
preferences, and transcript cache belong to the session repository. Compose is
a projection of these sources and must not become a second authority.

UI keys are derived from the complete provider-scoped locator. Session
navigation uses a fixed SHA-256 digest so saved navigation state cannot expose a
host, path, profile identifier, or unbounded agent-session identifier. Domain
state continues to use the lossless locator rather than the digest.
Approval and question UI keys follow the same one-way digest rule.

## Verification

Contract tests use fake runtimes and providers to validate the boundaries
without live credentials. Opt-in live checks verify installed tools in private
disposable environments. Android Keystore behavior additionally requires
connected device tests. The complete hosted gate is documented in
[Building](BUILDING.md).
