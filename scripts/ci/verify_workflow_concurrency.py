"""Bounded, deterministic workflow concurrency and recovery model.

This is an executable specification, not an unbounded proof.  The model keeps
identities opaque and explores every enabled transition up to a small depth.
The named traces are retained as replayable counterexample regressions.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from itertools import product

MAX_STEPS = 2
MAX_WORKERS = 2
MAX_ATTEMPTS = 2
MAX_DEPTH = 7


@dataclass(frozen=True)
class ModelState:
    run: str = "QUEUED"
    leases: tuple[int, ...] = (-1, -1)
    attempts: tuple[int, ...] = (0, 0)
    generations: tuple[int, ...] = (0, 0)
    effects: tuple[str, ...] = ("NONE", "NONE")
    journal_sequence: int = 0
    checkpoint_sequence: int = 0
    projection_sequence: int = 0


def _assert_invariants(state: ModelState) -> None:
    active = tuple(worker for worker in state.leases if worker >= 0)
    if len(active) > MAX_WORKERS:
        raise AssertionError("worker concurrency bound exceeded")
    if len(active) != len(
        {(step, worker) for step, worker in enumerate(state.leases) if worker >= 0}
    ):
        raise AssertionError("a step has more than one active lease")
    if any(attempt < 0 or attempt > MAX_ATTEMPTS for attempt in state.attempts):
        raise AssertionError("attempt bound exceeded")
    if state.checkpoint_sequence > state.journal_sequence:
        raise AssertionError("checkpoint observes a future journal fact")
    if state.projection_sequence > state.journal_sequence:
        raise AssertionError("projection observes a future journal fact")
    if any(effect == "UNCERTAIN" and state.run != "UNCERTAIN" for effect in state.effects):
        raise AssertionError("uncertain effect escaped its terminal run state")


def _replace_lease(state: ModelState, index: int, worker: int) -> ModelState:
    leases = (*state.leases[:index], worker, *state.leases[index + 1 :])
    attempts = list(state.attempts)
    generations = list(state.generations)
    attempts[index] += 1
    generations[index] += 1
    return replace(
        state,
        leases=leases,
        attempts=tuple(attempts),
        generations=tuple(generations),
        journal_sequence=state.journal_sequence + 1,
    )


def _start(state: ModelState, _: tuple[int, ...]) -> ModelState:
    if state.run != "QUEUED":
        raise ValueError("run is not queued")
    return replace(state, run="RUNNING", journal_sequence=state.journal_sequence + 1)


def _acquire(state: ModelState, args: tuple[int, ...]) -> ModelState:
    worker, index = args
    if state.run != "RUNNING" or not 0 <= worker < MAX_WORKERS:
        raise ValueError("acquire requires a running run and known worker")
    if not 0 <= index < MAX_STEPS or state.leases[index] >= 0:
        raise ValueError("step is already leased")
    if state.attempts[index] >= MAX_ATTEMPTS:
        raise ValueError("attempt budget exhausted")
    return _replace_lease(state, index, worker)


def _crash(state: ModelState, args: tuple[int, ...]) -> ModelState:
    (index,) = args
    if not 0 <= index < MAX_STEPS or state.leases[index] < 0:
        raise ValueError("only an active step can crash")
    leases = (*state.leases[:index], -1, *state.leases[index + 1 :])
    return replace(state, leases=leases, journal_sequence=state.journal_sequence + 1)


def _recover(state: ModelState, args: tuple[int, ...]) -> ModelState:
    worker, index = args
    if state.run != "RUNNING" or not 0 <= worker < MAX_WORKERS or not 0 <= index < MAX_STEPS:
        raise ValueError("invalid recovery")
    if state.leases[index] >= 0 or state.attempts[index] >= MAX_ATTEMPTS:
        raise ValueError("recovery is not fenced or is over budget")
    return _replace_lease(state, index, worker)


def _uncertain(state: ModelState, args: tuple[int, ...]) -> ModelState:
    (index,) = args
    if not 0 <= index < MAX_STEPS or state.leases[index] < 0:
        raise ValueError("uncertainty requires an active effect")
    effects = (*state.effects[:index], "UNCERTAIN", *state.effects[index + 1 :])
    return replace(
        state,
        run="UNCERTAIN",
        effects=effects,
        leases=(-1, -1),
        journal_sequence=state.journal_sequence + 1,
    )


def _complete(state: ModelState, args: tuple[int, ...]) -> ModelState:
    (index,) = args
    if not 0 <= index < MAX_STEPS or state.leases[index] < 0 or state.effects[index] == "UNCERTAIN":
        raise ValueError("completion requires a known active effect")
    leases = (*state.leases[:index], -1, *state.leases[index + 1 :])
    effects = (*state.effects[:index], "COMPLETED", *state.effects[index + 1 :])
    return replace(
        state, leases=leases, effects=effects, journal_sequence=state.journal_sequence + 1
    )


def _checkpoint(state: ModelState, _: tuple[int, ...]) -> ModelState:
    return replace(state, checkpoint_sequence=state.journal_sequence)


def _project(state: ModelState, _: tuple[int, ...]) -> ModelState:
    return replace(state, projection_sequence=state.journal_sequence)


def step(state: ModelState, operation: str) -> ModelState:
    """Apply one operation, rejecting stale or unsafe operations."""
    parts = operation.split(":")
    handlers = {
        "start": _start,
        "acquire": _acquire,
        "crash": _crash,
        "recover": _recover,
        "uncertain": _uncertain,
        "complete": _complete,
        "checkpoint": _checkpoint,
        "project": _project,
    }
    handler = handlers.get(parts[0])
    if handler is None:
        raise ValueError(f"unknown operation: {operation}")
    return handler(state, tuple(int(value) for value in parts[1:]))


def replay(trace: tuple[str, ...]) -> ModelState:
    state = ModelState()
    for operation in trace:
        state = step(state, operation)
        _assert_invariants(state)
    return state


def enabled_operations(state: ModelState) -> tuple[str, ...]:
    operations: list[str] = []
    for operation in ("start", "checkpoint", "project"):
        try:
            step(state, operation)
        except ValueError:
            continue
        operations.append(operation)
    for worker, index in product(range(MAX_WORKERS), range(MAX_STEPS)):
        for action in ("acquire", "recover"):
            operation = f"{action}:{worker}:{index}"
            try:
                step(state, operation)
            except ValueError:
                continue
            operations.append(operation)
    for index in range(MAX_STEPS):
        for action in ("crash", "uncertain", "complete"):
            operation = f"{action}:{index}"
            try:
                step(state, operation)
            except ValueError:
                continue
            operations.append(operation)
    return tuple(operations)


def explore() -> int:
    """Exhaustively check the finite transition relation and return state count."""
    frontier = {ModelState()}
    reached = set(frontier)
    for _ in range(MAX_DEPTH):
        next_frontier = set()
        for state in sorted(frontier, key=repr):
            for operation in enabled_operations(state):
                successor = step(state, operation)
                _assert_invariants(successor)
                if successor not in reached:
                    reached.add(successor)
                    next_frontier.add(successor)
        frontier = next_frontier
    return len(reached)


def verify_model() -> int:
    traces = (
        ("start", "acquire:0:0", "crash:0", "recover:1:0", "complete:0"),
        ("start", "acquire:0:0", "uncertain:0"),
        ("start", "checkpoint", "project"),
    )
    for trace in traces:
        replay(trace)
    # The second claim is the deterministic hostile interleaving: it must be
    # rejected before it can create a duplicate lease.
    try:
        replay(("start", "acquire:0:0", "acquire:1:0"))
    except ValueError:
        pass
    else:
        raise AssertionError("duplicate claim was accepted")
    return explore()


if __name__ == "__main__":
    print(f"workflow concurrency model: {verify_model()} bounded states verified")
