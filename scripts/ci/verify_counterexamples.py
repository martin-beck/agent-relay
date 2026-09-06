"""Import and deterministically replay retained formal counterexamples."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any

from verify_workflow_concurrency import replay

ROOT = Path(__file__).resolve().parents[2]
SHA256 = re.compile(r"^[0-9a-f]{64}$")
IDENTIFIER = re.compile(r"^[a-z][a-z0-9-]{2,63}$")
OPERATION = re.compile(
    r"^(?:start|checkpoint|project|(?:acquire|recover):[0-9]+:[0-9]+|"
    r"(?:crash|uncertain|complete):[0-9]+)$"
)
EVIDENCE_CLASSES = {
    "mechanical-invariant",
    "bounded-model-result",
    "contract-test",
    "environmental-assumption",
}
OUTCOMES = {"accepted", "rejected"}
KINDS = {"counterexample", "fault-injection"}


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _canonical_trace(trace: list[str]) -> bytes:
    return json.dumps(trace, ensure_ascii=True, separators=(",", ":")).encode("utf-8")


def _read_fixture(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink():
        raise AssertionError(f"counterexample is missing or symlinked: {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise AssertionError(f"invalid counterexample JSON: {path}") from error
    if not isinstance(value, dict):
        raise AssertionError(f"counterexample must be an object: {path}")
    return value


def _verify_identity(data: dict[str, Any], path: Path) -> str:
    if (
        data.get("schema_version") != 1
        or data.get("kind") not in KINDS
        or data.get("model") != "WorkflowConcurrency"
    ):
        raise AssertionError(f"unsupported counterexample schema or model: {path}")
    identifier = data.get("id")
    if not isinstance(identifier, str) or not IDENTIFIER.fullmatch(identifier):
        raise AssertionError(f"invalid counterexample id: {path}")
    return identifier


def _verify_trace(data: dict[str, Any], path: Path) -> list[str]:
    trace = data.get("trace")
    if not isinstance(trace, list) or not trace or not all(isinstance(item, str) for item in trace):
        raise AssertionError(f"counterexample trace must be a non-empty string list: {path}")
    if any(not OPERATION.fullmatch(item) for item in trace):
        raise AssertionError(f"counterexample has an unsupported operation: {path}")
    return trace


def _verify_expected(data: dict[str, Any], path: Path) -> dict[str, Any]:
    expected = data.get("expected")
    if not isinstance(expected, dict) or expected.get("outcome") not in OUTCOMES:
        raise AssertionError(f"counterexample expected outcome is invalid: {path}")
    if expected["outcome"] == "rejected" and not isinstance(expected.get("error"), str):
        raise AssertionError(f"rejected counterexample needs an error fragment: {path}")
    evidence = data.get("evidence")
    if not isinstance(evidence, dict) or evidence.get("class") not in EVIDENCE_CLASSES:
        raise AssertionError(f"counterexample evidence class is invalid: {path}")
    return expected


def _verify_shape(data: dict[str, Any], path: Path) -> tuple[str, list[str], dict[str, Any]]:
    identifier = _verify_identity(data, path)
    trace = _verify_trace(data, path)
    expected = _verify_expected(data, path)
    for key in ("input_sha256", "implementation_sha256"):
        if not isinstance(data.get(key), str) or not SHA256.fullmatch(data[key]):
            raise AssertionError(f"counterexample {key} is invalid: {path}")
    implementation = data.get("implementation")
    if not isinstance(implementation, str) or Path(implementation).is_absolute():
        raise AssertionError(f"counterexample implementation path is invalid: {path}")
    return identifier, trace, expected


def _verify_hashes(data: dict[str, Any], trace: list[str], path: Path, root: Path) -> None:
    actual_input = _digest(_canonical_trace(trace))
    if data["input_sha256"] != actual_input:
        raise AssertionError(f"counterexample input hash mismatch: {path}")
    implementation = (root / data["implementation"]).resolve()
    try:
        implementation.relative_to(root.resolve())
    except ValueError as error:
        raise AssertionError(f"counterexample implementation escapes root: {path}") from error
    if not implementation.is_file() or implementation.is_symlink():
        raise AssertionError(f"counterexample implementation is missing or symlinked: {path}")
    actual_implementation = _digest(implementation.read_bytes())
    if data["implementation_sha256"] != actual_implementation:
        raise AssertionError(f"counterexample implementation hash mismatch: {path}")


def _replay(data: dict[str, Any], trace: list[str], path: Path) -> None:
    try:
        replay(tuple(trace))
    except ValueError as error:
        outcome = "rejected"
        message = str(error)
    else:
        outcome = "accepted"
        message = ""
    expected = data["expected"]
    if outcome != expected["outcome"]:
        raise AssertionError(f"counterexample outcome changed: {path}")
    if outcome == "rejected" and expected["error"] not in message:
        raise AssertionError(f"counterexample rejection changed: {path}")


def verify_counterexamples(root: Path = ROOT) -> int:
    """Verify every retained fixture and return the number of replayed traces."""
    fixture_dir = root / "tests" / "formal" / "counterexamples"
    fixtures = sorted(fixture_dir.glob("*.json"))
    if not fixtures:
        raise AssertionError("at least one retained counterexample is required")
    identifiers: set[str] = set()
    for path in fixtures:
        data = _read_fixture(path)
        identifier, trace, _ = _verify_shape(data, path)
        if identifier in identifiers:
            raise AssertionError(f"duplicate counterexample id: {identifier}")
        identifiers.add(identifier)
        _verify_hashes(data, trace, path, root)
        _replay(data, trace, path)
    return len(fixtures)


if __name__ == "__main__":
    print(f"formal counterexamples: {verify_counterexamples()} deterministic traces replayed")
