"""Deterministically validate the bounded domain-model declarations."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FORMAL = ROOT / "docs" / "formal"

REQUIRED_TLA = (
    "TypeOK",
    "LeaseSafety",
    "OrderingSafety",
    "ProjectionSafety",
    "RevisionSafety",
    "AttentionSafety",
    "Invariant",
    "Init",
    "Next",
)
REQUIRED_ALLOY = (
    "BoundsAndTypes",
    "LeaseSafety",
    "CursorOrdering",
    "RevisionNonNegative",
)
REQUIRED_CONCURRENCY_TLA = (
    "ConcurrencySafety",
    "LeaseFencing",
    "RecoverySafety",
    "OrderingSafety",
    "UncertainSafety",
)
REQUIRED_CONCURRENCY_ALLOY = ("BoundsAndTypes", "ConcurrencySafety", "UncertainSafety")


def _read(name: str) -> str:
    text = (FORMAL / name).read_text(encoding="utf-8")
    if not text.endswith("\n"):
        raise AssertionError(f"{name} must end with a newline")
    return text


def _verify_declarations(tla: str, alloy: str) -> None:
    if not tla.startswith("---- MODULE WorkflowDomain ----") or not tla.rstrip().endswith("===="):
        raise AssertionError("TLA+ module delimiters are invalid")
    if not alloy.startswith("module WorkflowDomain"):
        raise AssertionError("Alloy module declaration is invalid")
    for declaration in REQUIRED_TLA:
        if not re.search(rf"(?m)^{re.escape(declaration)}\s*==", tla):
            raise AssertionError(f"missing TLA+ declaration: {declaration}")
    for declaration in REQUIRED_ALLOY:
        if not re.search(rf"(?m)^(?:fact|assert)\s+{re.escape(declaration)}\b", alloy):
            raise AssertionError(f"missing Alloy declaration: {declaration}")
    _verify_bounds(tla, alloy)


def _verify_bounds(tla: str, alloy: str) -> None:
    bounds = ("Tasks", "Runs", "Steps", "Events", "AttentionItems", "Projections")
    missing = [bound for bound in bounds if bound not in tla]
    if missing:
        raise AssertionError(f"missing finite bounds: {', '.join(missing)}")
    if "check LeaseSafety for 2" not in alloy or "check CursorOrdering for 4" not in alloy:
        raise AssertionError("Alloy bounds are not explicit")
    if "SECRET" in tla or "SECRET" in alloy:
        raise AssertionError("models must not contain secret payloads")


def _verify_concurrency_models(tla: str, alloy: str) -> None:
    if not tla.startswith("---- MODULE WorkflowConcurrency ----") or not tla.rstrip().endswith(
        "===="
    ):
        raise AssertionError("concurrency TLA+ module delimiters are invalid")
    if not alloy.startswith("module WorkflowConcurrency"):
        raise AssertionError("concurrency Alloy module declaration is invalid")
    for declaration in REQUIRED_CONCURRENCY_TLA:
        if not re.search(rf"(?m)^{re.escape(declaration)}\s*==", tla):
            raise AssertionError(f"missing concurrency TLA+ declaration: {declaration}")
    for declaration in REQUIRED_CONCURRENCY_ALLOY:
        if not re.search(rf"(?m)^(?:fact|assert)\s+{re.escape(declaration)}\b", alloy):
            raise AssertionError(f"missing concurrency Alloy declaration: {declaration}")
    if "SECRET" in tla or "SECRET" in alloy:
        raise AssertionError("concurrency models must not contain secret payloads")


def verify_models() -> None:
    _verify_declarations(_read("WorkflowDomain.tla"), _read("WorkflowDomain.alloy"))
    _verify_concurrency_models(_read("WorkflowConcurrency.tla"), _read("WorkflowConcurrency.alloy"))


if __name__ == "__main__":
    verify_models()
    print("formal domain models: declarations, bounds, and privacy boundary are valid")
