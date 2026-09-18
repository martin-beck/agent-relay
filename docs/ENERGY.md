# Energy behavior and evidence boundary

Agent Relay treats energy as a correctness constraint: a connection must remain available for
explicit active work, but an idle phone must not wake the CPU or radio merely because a profile
was once connected.

## Current source baseline

The current `main` tree has four high-risk recurring-work sources:

1. `SshConnectionManager` sends a remote `true` heartbeat every 15 seconds for every connected
   profile.
2. `JschSshConnector` independently configures a 15-second server-alive interval, so an idle SSH
   connection can cause two liveness mechanisms to run.
3. The explicit foreground background-transport service can retain the user-requested profile
   set indefinitely. It is correctly opt-in, but its lease has no energy-oriented idle expiry.
4. Provider adapters include polling loops. The Continue adapter currently waits 750 ms between
   probes, which is inappropriate while no active turn needs progress.

These findings come from source and deterministic tests, not from a battery measurement. They
identify wakeups and network operations that are expected to consume energy; they do not quantify
percentage drain or thermal impact.

## Implemented source reductions

- SSH application heartbeats now default to five minutes, while the duplicate JSch server-alive
  probe is disabled unless explicitly configured.
- Explicit background transport now expires after 30 minutes in energy-saving mode and recovery
  remains fail-closed; the opt-out policy is bounded to two hours rather than indefinite.
- Continue polling backs off to 15 seconds while idle, 2 seconds while waiting for approval, and
  1 second during an active turn.
- Speech lifecycle close stops capture, playback, and inference before releasing resources.
- Foreground notification updates retain the latest snapshot without repeatedly projecting and
  cancelling already-suppressed notifications.

## Evidence levels

- **Host/JVM tests:** operation counts, virtual time, cancellation, backoff, and state contracts.
- **Profileable/diagnostic Android build:** startup, rendering, coroutine, and lifecycle traces
  with payloads and secrets excluded.
- **Device evidence required for final claims:** Android BatteryStats/ batterystats history,
  radio/network counters, thermal state, and a controlled idle/active comparison on a representative
  release-like phone. No host-only result substitutes for this evidence.

The energy work therefore uses deterministic operation budgets as regression gates and reports
physical battery savings separately when a device becomes available.
