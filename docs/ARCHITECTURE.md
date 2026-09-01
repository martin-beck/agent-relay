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
Connection providers do not know agent protocols.

## Modules

| Module | Responsibility |
| --- | --- |
| `:app` | Android entry point and Compose presentation |
| `:connection:api` | Generic connection providers, profiles, lifecycle, identity challenges, and runtime access |
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
4. compatible agent factories probe and open agent sessions through that runtime;
5. the coordinator projects agent events into the durable session repository;
   and
6. the Compose layer observes durable state and sends capability-checked actions.

The first five layers are implemented. The application currently does not
construct the registries, coordinator, encrypted stores, or session UI, so this
flow is not yet user accessible.

## Security boundaries

- Android's application sandbox is the local-process boundary.
- Local working directories are canonicalized below an app-controlled root.
- SSH server identity is accepted only through explicit trust decisions.
- Android Keystore keys encrypt namespaced authenticated documents.
- Credentials stay outside command arguments, events, and diagnostic snapshots.
- Agent-provided file paths are normalized against the known workspace.
- Output, protocol lines, retention, retries, and process lifetimes are bounded.

See [Connection providers](CONNECTION_PROVIDERS.md) and
[Session hub](SESSION_HUB.md) for detailed invariants.

## State ownership

Connection lifecycle belongs to connection providers. Agent discovery and
protocol state belong to agent providers. Durable drafts, unread activity,
preferences, and transcript cache belong to the session repository. Compose is
a projection of these sources and must not become a second authority.

## Verification

Contract tests use fake runtimes and providers to validate the boundaries
without live credentials. Opt-in live checks verify installed tools in private
disposable environments. Android Keystore behavior additionally requires
connected device tests. The complete hosted gate is documented in
[Building](BUILDING.md).
