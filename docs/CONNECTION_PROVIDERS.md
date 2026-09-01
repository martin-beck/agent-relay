# Connection providers

Status: Implemented foundation
Last verified: 2026-09-01

Agent Relay has two independent extension boundaries:

~~~text
Connection provider -- supplies RemoteAgentRuntime --> Agent provider
  SSH, local device                               Codex, Claude, Aider, ...
~~~

An agent provider knows how to discover and control a coding agent. A
connection provider knows how to reach the environment where that agent runs.
SSH is one connection provider; it is not embedded in the agent-provider API.
The local-device provider supplies the same RemoteAgentRuntime without
implementing SSH profiles, credentials, host keys, or reconnect behavior.

## Generic contract

:connection:api owns the app-facing contract:

- stable provider, profile, and challenge identifiers;
- provider descriptors and capability discovery;
- profile summaries without provider-specific configuration;
- preparation, transport, identity, authentication, connected, reconnecting,
  failed, and disconnected states;
- generic server-identity challenges and explicit trust decisions;
- redacted diagnostic snapshots;
- lifecycle controls and access to a connected RemoteAgentRuntime; and
- a registry that can host unrelated connection providers together.

Connection-specific configuration stays in the implementation. The SSH adapter
maps its internal state into this generic contract. Adding local access should
therefore require a new ConnectionProvider, not SSH-shaped placeholder profiles
or conditional branches in agent providers.

## SSH provider layout

| Module | Responsibility |
| :storage:android | Transport-neutral, namespaced Android Keystore document encryption with authenticated atomic files below noBackupFilesDir |
| --- | --- |
| :ssh:api | Validated SSH profiles, authentication references, host-key records, bounded reconnect policy, independent managed sessions, and the generic connection-provider adapter |
| :ssh:jsch | JSch transport, strict host-key repository, password/imported-key/agent authentication, POSIX command encoding, keepalive checks, and concurrent exec channels |
| :ssh:android | Android Keystore encryption, no-backup document storage, profile and host-key persistence, credential persistence, and non-exportable Android agent keys |
| :connection:local | A single app-local profile, direct local process execution, bounded streams, workspace confinement, process cleanup, and concurrent process channels |

The transport pins the maintained com.github.mwiede:jsch fork at 2.28.6.
The default modern algorithm set is retained, strict key exchange is explicitly
enabled, prompts are disabled, and authentication is restricted to the selected
profile method.

## Trust and credential rules

- Unknown SSH host keys stop the connection before authentication. The
  fingerprint is shown through a server-identity challenge and no key is added
  implicitly.
- A changed key remains blocked. Replacement uses compare-and-set semantics
  against the exact previously trusted fingerprint set, so a stale UI decision
  cannot overwrite a newer trust decision.
- Passwords, imported private keys, and key passphrases are stored as encrypted
  credential documents. Their identifiers and purposes are authenticated as
  associated data or encrypted header data.
- Profiles and trusted host keys are encrypted as well. The shared secure
  document layer gives SSH and session data separate directories, key aliases,
  and authenticated-data namespaces. Files use hashed names and atomic replace.
- The Android Keystore owns a 256-bit AES key used with AES/GCM/NoPadding.
  Authentication failure or malformed envelopes fail closed.
- Agent-backed profiles can reference an Android Keystore NIST P-256 signing
  key. Its private key is non-exportable; the JSch identity signs SSH
  authentication data through the platform keystore.
- Secret byte arrays are copied only at explicit boundaries and zeroed on
  release where the runtime permits. Secrets and raw exception messages are
  excluded from diagnostic state.

Android Keystore may be hardware-backed depending on the device. The
implementation relies on the platform security boundary but does not claim
hardware backing when the device does not provide it.

## Lifecycle behavior

Each saved SSH profile gets an independent managed connection:

1. resolve the profile and selected credential;
2. open the transport and verify the server identity;
3. authenticate without interactive fallback;
4. expose one shared SSH session as a RemoteAgentRuntime;
5. open independent exec channels for concurrent agent sessions;
6. measure a real round-trip heartbeat;
7. reconnect recoverable failures with jittered exponential delays bounded by
   the configured maximum and retry limit; and
8. distinguish user disconnect, authentication failure, network loss, rejected
   identity, unavailable credential, retry exhaustion, and Android background
   suspension.

Foreground resumption is explicit. Host-key and authentication failures do not
retry automatically. Provider processes remain independent channels on the
same SSH session, so changing the visible app session does not tear down the
transport.

Transcript and unsent-draft persistence belongs to the session layer above the
connection provider. The connection contract preserves the runtime boundary
needed for that work but does not conflate UI persistence with transport state.

## Safe remote command execution

JSch exec requests are interpreted by the remote account's POSIX shell. The
runtime therefore:

- single-quotes the program, every argument, environment value, and working
  directory;
- rejects NUL before opening a channel;
- sorts environment keys for deterministic commands;
- uses no PTY or agent forwarding;
- captures stdout and stderr concurrently with an 8 MiB bound per stream; and
- bounds live protocol lines to 1 MiB while applying backpressure.

Prompts and structured protocol messages for duplex agent processes travel over
standard input, not process arguments.

## Verification

Regular verification:

~~~bash
./gradlew \
  :connection:api:test \
  :ssh:api:test \
  :ssh:jsch:test \
  :ssh:android:testDebugUnitTest \
  :ssh:android:compileDebugAndroidTestKotlin \
  spotlessCheck
~~~

Coverage includes:

- coexistence of SSH-shaped and local-shaped providers in one registry;
- profile validation, secret redaction, exact credential-purpose resolution,
  and host-key compare-and-set behavior;
- exponential backoff, retry exhaustion, non-retried authentication failure,
  independent sessions, and background suspension/resumption;
- POSIX injection boundaries and strict JSch host-key repository behavior;
- encrypted, authenticated, atomically replaced documents with tamper
  detection;
- profile, credential, and host-key stores; and
- Android-agent SSH public-key and signature encoding verified with a real JCA
  EC key pair.

The opt-in development-host check connects to a real loopback OpenSSH server
with an intentionally invalid password and verifies that first-use host-key approval
blocks before authentication:

~~~bash
AGENT_RELAY_LIVE_SSH=1 ./gradlew \
  :ssh:jsch:test \
  --tests dev.agentrelay.ssh.jsch.JschSshConnectorLiveTest
~~~

This passed on 2026-09-01 against OpenSSH 9.6p1, captured its Ed25519 key
without trusting it, then classified an intentionally invalid password as a
non-recoverable authentication failure. It deliberately does not modify
authorized_keys.

The instrumented Android test compiles in regular CI and must run on an emulator
or device to verify the real AndroidKeyStore provider:

~~~bash
./gradlew :ssh:android:connectedDebugAndroidTest
~~~

Until that connected test runs, Android Keystore device behavior is implemented
and compile-verified but not recorded as emulator-verified.

## Local-device provider

The local provider exposes one profile named This device. It is a real
ConnectionProvider and does not route through SSH or emulate SSH concepts.
Connect and background-resume create a fresh LocalProcessRuntime; disconnect,
background suspension, and provider shutdown stop its active processes.

Local commands use ProcessBuilder with the program and argument list directly.
The runtime never inserts an implicit shell. A provider can explicitly request
a shell as its program when that agent protocol requires one, but user values
remain separate arguments unless the provider itself deliberately constructs a
script. Command values reject NUL, stdout and stderr are captured concurrently
with 8 MiB bounds, live lines are limited to 1 MiB, and duplex channels apply
bounded backpressure.

The configured working root is an existing app-owned directory. Requested
working directories are canonicalized and must remain below that root, which
also blocks symlink traversal. Android's application sandbox remains the
filesystem and process security boundary. The runtime does not claim that
desktop agent executables are installed on Android; normal provider readiness
probes report each unavailable CLI truthfully.

The local provider advertises multiplexed processes and background recovery. It
does not advertise profiles, secret authentication, server identity, heartbeat,
or network reconnect capabilities.

## Adding a connection provider

Another implementation should:

1. define its own private configuration and persistence;
2. advertise only capabilities it actually supports;
3. implement ManagedConnection states and lifecycle without fabricating SSH
   identity or authentication steps;
4. supply a local RemoteAgentRuntime with the same command-secrecy and output
   bounds;
5. register beside ssh.secure-shell; and
6. prove coexistence, cancellation, process cleanup, and redacted diagnostics
   in contract tests.

Agent providers should require only RemoteAgentRuntime. They must never cast
the runtime to an SSH type or reach into a connection provider's credential
store.
