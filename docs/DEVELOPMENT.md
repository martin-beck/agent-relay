# AI-assisted development process

This document is the sole source of truth for coordinated AI-assisted development.
Repository skills and handoff entry points must link here instead of copying these rules.

## Authoritative references

Read the documents relevant to the change before planning or modifying state:

- [Building](BUILDING.md) defines the reproducible toolchain and build commands.
- [Quality](QUALITY.md) defines required verification and evidence boundaries.
- [Architecture](ARCHITECTURE.md) defines component and dependency boundaries.
- [Release process](RELEASING.md) defines release-only gates.
- The [coordination state project](../../../../agent-relay-state) stores task ownership,
  plans, checkpoints, and generated live state.

The coordination checkout must be the main project's sibling at `../agent-relay-state`.
All commands below are run from the main project root. Do not encode machine names,
account names, absolute paths, remote URLs, or credentials in project files or state.

## Before any work

1. Read this document completely and follow its authoritative references.
2. Confirm that the main checkout and `../agent-relay-state` are present and clean enough
   to inspect. Do not discard existing changes.
3. Reconcile and publish the coordination snapshot:

   ```bash
   ../agent-relay-state/tools/handoffctl reconcile --commit --push
   ../agent-relay-state/tools/handoffctl snapshot
   ```

4. Read the complete snapshot, the selected task, its plan, dependencies, checkpoint,
   named branch, and worktree inventory.
5. If the requested work has no task, add one through a focused coordination-state
   change and pass that repository's validation before touching product state.
6. Choose the highest-priority ready task compatible with the request. Do not claim
   planned, future, blocked, completed, or dependency-blocked work.
7. Claim exactly one task with a unique, stable worker identifier:

   ```bash
   ../agent-relay-state/tools/handoffctl claim AR-NNNN \
     --owner WORKER_ID --lease-minutes 120
   ```

A worker may hold only one active task. A worktree and branch may have only one active
owner. A failed claim means another worker or dependency has priority; reconcile and
select different ready work.

## Recover before retrying

After any interruption, timeout, lost connection, expired lease, failed tool call, or
ambiguous response, inspect durable effects before issuing another mutation. Reconcile:

- the task record, revision, checkpoint, branch, and worktree;
- the process tree and relevant service state;
- local and remote Git references and the complete diff;
- review and continuous-integration state;
- emulator, device, build, test, and generated artifacts; and
- any external operation the interrupted command may have completed.

Record the observed result. Resume only the missing portion. Lease expiry permits
investigation, not blind takeover or repetition.

## Make changes through the coordinator

Read-only inspection may run directly. Run every command that can change product, Git,
review, integration, emulator, device, build publication, or other external state through
the task wrapper:

```bash
../agent-relay-state/tools/handoffctl run \
  --owner WORKER_ID AR-NNNN -- COMMAND ARGUMENTS
```

The wrapper records a command digest and result, then reconciles and fast-forward
publishes the state. It deliberately does not store raw command text, output, prompts,
or environment data.

Do not edit generated coordinator views directly. Do not bypass its lock, task revision,
lease, dependency, ownership, validation, or non-fast-forward protections. Do not use a
force push or destructive checkout to resolve concurrent work.

## Update timing

Update coordination state immediately after every material transition: a command result,
verified milestone, changed conclusion, new blocker, review event, integration result, or
change to the next action. Do not batch updates until the end of a work session.

The task wrapper performs the immediate post-command reconciliation. During long read-only
investigations, reviews, or waits, send a heartbeat at least once per hour and always before
the current lease expires. Record a failed or ambiguous operation before starting unrelated
work. Reconcile again as soon as external state changes.

The periodic reconciler is a recovery backstop for out-of-band drift. It does not replace
the immediate updates required from an active worker.

## Worktree and change discipline

Use only the worktree and branch named by the claimed task. Reconcile unexpected files or
commits before editing. Preserve unrelated user or agent work and keep each change focused.

Follow [Architecture](ARCHITECTURE.md) for code boundaries. In particular:

- keep commits small, signed, attributable, and reviewable;
- do not mix unrelated features or dependency upgrades;
- describe only behavior supported by direct evidence;
- update documentation whenever behavior or operating requirements change; and
- never publish sensitive machine, account, network, credential, session, prompt, or
  transcript data.

## Verification and evidence

Run focused checks while developing. Before publication, run the complete applicable gates
from [Building](BUILDING.md) and [Quality](QUALITY.md). Use the additional device, visual,
provider, security, or release evidence required by those documents for the changed area.

A failing required check preempts later work. Preserve the failure evidence, update the
task's next action, and repair or explicitly block the task. Never weaken a gate, regenerate
a baseline, or relabel simulated evidence merely to obtain a passing result.

After every verified milestone, renew the lease and update the task using its current
revision:

```bash
../agent-relay-state/tools/handoffctl heartbeat AR-NNNN \
  --owner WORKER_ID --lease-minutes 120

../agent-relay-state/tools/handoffctl update AR-NNNN \
  --owner WORKER_ID --expected-revision REVISION \
  --note "Concise durable evidence and remaining work"
```

Record conclusions, commit identifiers, check results, limitations, and the exact next
action. Do not copy raw logs or sensitive data into the state repository.

## Publication and completion

1. Reconcile the exact branch head and complete diff.
2. Run the required focused and full gates on that exact tree.
3. Perform privacy, credential, generated-artifact, authorship, signature, and scope review.
4. Confirm that remote checks apply to the same immutable commit.
5. Publish or merge only the reviewed tree, then reconcile the resulting remote state.

A task is done only when its requested outcome and applicable evidence are complete. Release
the claim with a concise final record:

```bash
../agent-relay-state/tools/handoffctl release AR-NNNN \
  --owner WORKER_ID --status done \
  --note "Verified outcome and evidence"
```

For a safe pause, release it as `open` with the exact next action. Use `blocked` only for a
specific unresolved dependency or external condition. Never leave stopped work marked
`in_progress`.

Finally run:

```bash
../agent-relay-state/tools/handoffctl reconcile --commit --push
../agent-relay-state/tools/handoffctl doctor --live
```

Do not report completion unless the worktree is understood, the coordination repository is
clean and synchronized, required checks are green, and the task status matches reality.
