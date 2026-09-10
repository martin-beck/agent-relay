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
- an optional provider-owned profile manager with bounded text, port, secret,
  choice, conditional, and read-only fields; and
- a registry that can host and manage unrelated connection providers together.

Connection-specific configuration stays in the implementation. The SSH adapter
maps its internal state into this generic contract. Adding local access should
therefore require a new ConnectionProvider, not SSH-shaped placeholder profiles
or conditional branches in agent providers.

## SSH provider layout

| Module | Responsibility |
| --- | --- |
| :storage:android | Transport-neutral, namespaced Android Keystore document encryption with authenticated atomic files below noBackupFilesDir |
| :ssh:api | Validated SSH profiles and jump routes, authentication references, managed-key operations, host-key records, bounded reconnect policy, independent managed sessions, and the generic adapter |
| :ssh:jsch | JSch transport, strict per-hop host-key repositories, password/imported-key/agent authentication, direct-tcpip chaining, POSIX command encoding, keepalive checks, and concurrent exec channels |
| :ssh:android | Android Keystore encryption, no-backup profile/route/host-key and credential persistence, and persistent non-exportable Android agent keys |
| :connection:local | A single app-local profile, direct local process execution, bounded streams, workspace confinement, process cleanup, and concurrent process channels |

The transport pins the maintained com.github.mwiede:jsch fork at 2.28.6.
The default modern algorithm set is retained, strict key exchange is explicitly
enabled, prompts are disabled, and authentication is restricted to the selected
profile method.

## Provider-neutral profile setup

The Compose layer does not know SSH profile classes. It asks the selected
`ConnectionProfileManager` for an editor schema, sends back a generic update,
and renders provider validation errors beside stable field identifiers.
Providers without `PROFILE_MANAGEMENT`, such as the single fixed local-device
profile, do not expose add or edit actions.

The SSH manager supports:

- password credentials;
- imported private keys with an independently kept, removed, or replaced
  passphrase; and
- a non-exportable Android Keystore key whose OpenSSH public key is the only key
  material returned to the editor;
- selection of another configured SSH profile as a jump host; and
- provider-owned operations for confirmed public-key installation and an actual
  key-only login probe.

Stored secrets return as empty fields with a `hasStoredSecret` marker. An empty
replacement keeps the referenced credential; the app never reads a password or
private key back into Compose state. New credential and agent-key identifiers
are collision checked, failed saves clean up newly created encrypted material,
and successful replacement cleans up only superseded material. Profile deletion
also removes unshared host trust. Cleanup is deliberately best effort after the
authoritative profile mutation, so a storage failure cannot resurrect a deleted
profile; encrypted orphan recovery remains a future hardening option.

## Jump routes and app-managed keys

Jump-host selection stores only another SSH profile identifier. The route
resolver follows those references before any transport call, rejects missing
profiles and cycles, caps the route at eight jump hosts, and resolves every
hop's own credential and trusted host-key set. JSch connects outermost first and
opens each later session through a direct-tcpip channel on the preceding
session. No route field enters provider-neutral session or navigation state.

Every saved SSH profile owns a deterministic persistent key identifier. The
Android implementation creates a NIST P-256 signing key in Android Keystore
when that key is absent, displays only its OpenSSH public encoding, retains it
across password/imported-key authentication changes, and deletes it with the
profile. A failed profile save removes a newly created key rather than leaving
an unreferenced identity.

**Install public key** is a generic confirmed profile operation. It connects
with the profile's currently saved authentication and configured jump route,
then runs a bounded POSIX command that:

- rejects a symbolic-link or non-directory SSH path and a symbolic-link or
  nonregular authorized-key path;
- creates the authorized-key file only when absent, applies directory mode 0700
  and file mode 0600, and avoids touching an existing file unconditionally;
- serializes cooperating Agent Relay installers with a PID-owned atomic
  directory lock, safely reclaims a well-formed lock whose owner exited or an
  unchanged ownerless or malformed lock after a bounded wait, retries a
  generation-safe acquisition/recovery handshake within eight total waits,
  pins a foreign stale recovery marker by hardlink while revalidating its exact
  inode and contents, and removes only its own evidence when interrupted;
- strips comments and sends only the validated algorithm and Base64 public-key
  blob;
- preserves an existing active key line byte-for-byte only when the key occupies
  the direct key fields or follows a recognized OpenSSH options field, while
  preserving restrictive options, trailing comments, and CRLF bytes;
- treats a cert-authority entry as CA trust rather than plain-key authorization,
  so that entry does not prevent installation of the usable normalized key;
- fails closed on an ambiguous or malformed options prefix, including
  principals without cert-authority;
- appends the normalized key only when no active line matches; and
- fails before changing the SSH files when a POSIX awk is unavailable or cannot
  execute.
The service maps inspection failure, interruption, and busy or unsafe locks to
separate stable, redacted, actionable failures instead of reporting all remote
exits as permission problems.

These portable shell checks do not claim atomic no-follow protection against a
malicious process running as the same remote account. POSIX shell cannot exclude
a hard-linked file or every path replacement between validation and use. A
same-account process can already modify that account's authorized_keys file.

**Test key-only login** creates a fresh route in which only the destination
authentication is replaced with the app-managed key. Jump profiles retain their
own saved credentials. A successful remote heartbeat is required before the UI
reports passwordless login as working.

## Trust and credential rules

- Unknown SSH host keys on any hop stop the route before authentication. The
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

1. resolve the destination and its bounded jump route;
2. resolve each hop's selected credential and trusted host keys;
3. open every route session outermost first, verifying identity and
   authenticating without interactive fallback;
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
- jump selection, missing/cyclic/deep route rejection, outermost-first
  resolution, per-hop trust, and referenced-jump deletion protection;
- password replacement, imported-key/passphrase transitions, Android agent-key
  creation and deletion, identifier collision retries, and failed-save cleanup;
- executable legal-position, restrictive-option, CRLF, cert-authority,
  principals, unrelated-comment, malformed-prefix, stale-lock, absent-file,
  metadata no-op, symbolic-link, non-directory, FIFO, permission, ownerless and
  malformed lock recovery, lock-wait and acquisition interruption, concurrent
  install, idempotence, and missing-tool public-key installation cases;
- destination-only key override,
  jump credential retention, and heartbeat-backed passwordless probes;
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

The second opt-in check expects two disposable OpenSSH endpoints and one
disposable imported key. Set only these environment variables outside the
repository:

- `AGENT_RELAY_LIVE_SSH_JUMP=1`;
- `AGENT_RELAY_LIVE_SSH_KEY` and `AGENT_RELAY_LIVE_SSH_USER`;
- `AGENT_RELAY_LIVE_SSH_JUMP_HOST` and
  `AGENT_RELAY_LIVE_SSH_JUMP_PORT`; and
- `AGENT_RELAY_LIVE_SSH_DESTINATION_HOST` and
  `AGENT_RELAY_LIVE_SSH_DESTINATION_PORT`.

Then run:

~~~bash
./gradlew :ssh:jsch:test \
  --tests '*loopbackTwoHopRouteAuthenticatesAndExecutesThroughDirectTcpip'
~~~

On 2026-09-01 this passed against two ephemeral loopback OpenSSH 9.6p1 daemons.
The test independently stopped at the jump and destination first-use trust
boundaries, authenticated both sessions, opened the destination through
direct-tcpip, and completed a real remote heartbeat. Its temporary host keys,
client key, authorized-key files, daemons, and directory were removed after the
run; it did not use a personal host or modify a personal authorized_keys file.

The instrumented Android test compiles in regular CI and must run on an emulator
or device to verify the real AndroidKeyStore provider:

~~~bash
./gradlew :ssh:android:connectedDebugAndroidTest
~~~

Encrypted document behavior, app-managed key generation, and persistent lookup
through a new key-manager instance are emulator-verified. Hardware-backed
generation and signing still need final physical-device evidence before release.

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
5. optionally expose a provider-owned `ConnectionProfileManager` and advertise
   `PROFILE_MANAGEMENT` only when its add/edit/delete paths are tested;
6. register beside ssh.secure-shell; and
7. prove coexistence, cancellation, process cleanup, and redacted diagnostics
   in contract tests.

Agent providers should require only RemoteAgentRuntime. They must never cast
the runtime to an SSH type or reach into a connection provider's credential
store.
