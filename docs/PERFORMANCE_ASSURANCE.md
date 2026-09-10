# Bounded performance and live-endurance assurance

This is an opt-in measurement protocol, not a performance promise. It records
reproducible host-side evidence for bounded commands and keeps live provider,
device, network, and power measurements outside normal CI.

## Safety and provenance

- Measurements run only when explicitly requested; CI does not start them.
- The harness never invokes `adb`, an emulator, a real host, or a network peer.
- Each command is isolated in its own process group and has a maximum 120-second
  timeout. Timeout cleanup targets only that process group.
- Reports contain the working-directory and Python version, a command digest,
  elapsed time, exit status, timeout status, output sizes, and child maximum RSS.
  Command arguments and output are intentionally not persisted.
- A single timeout or non-zero exit is an environmental/product boundary to
  investigate, not a passing endurance result.

## Reproducible host measurement

From a clean checkout, select an exact commit and run a bounded, non-device
command. For example:

```bash
uv run python scripts/assurance/measure_bounded.py \
  --timeout-seconds 30 --repeat 3 \
  --report build/assurance/host-test.json -- \
  ./gradlew :connection:api:test --no-daemon
```

Record the exact commit, host/kernel, JDK/Gradle versions, CPU and memory
availability, warm-up policy, repeat count, timeout budget, and report digest in
the coordinator task. Do not compare measurements from different hosts as an
engineering regression claim. Device, live-provider, network-failure, and
multi-hour endurance evidence requires a separately reserved environment and
must retain its cleanup and redaction record.
