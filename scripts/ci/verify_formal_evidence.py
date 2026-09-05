"""Validate the checked-in formal verification evidence manifest."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "config" / "formal-evidence-manifest.json"
HEX_SHA256 = re.compile(r"^[0-9a-f]{64}$")
EVIDENCE_CLASSES = {
    "mechanical-invariant",
    "bounded-model-result",
    "contract-test",
    "environmental-assumption",
}


def _load() -> dict[str, Any]:
    value = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise AssertionError("formal evidence manifest must be an object")
    return value


def _verify_models(data: dict[str, Any]) -> None:
    models = data.get("models")
    if not isinstance(models, list) or not models:
        raise AssertionError("formal models must be listed")
    for model in models:
        if not isinstance(model, dict) or not isinstance(model.get("path"), str):
            raise AssertionError("each formal model needs a path")
        digest = model.get("sha256")
        if not isinstance(digest, str) or not HEX_SHA256.fullmatch(digest):
            raise AssertionError(f"invalid hash for {model.get('path')}")
        path = ROOT / model["path"]
        if not path.is_file() or path.is_symlink():
            raise AssertionError(f"formal model is missing or symlinked: {model['path']}")
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != digest:
            raise AssertionError(f"formal model hash mismatch: {model['path']}")


def _verify_obligations(data: dict[str, Any]) -> None:
    obligations = data.get("obligations")
    if not isinstance(obligations, list) or not obligations:
        raise AssertionError("at least one formal obligation is required")
    for obligation in obligations:
        if (
            not isinstance(obligation, dict)
            or not obligation.get("id")
            or not obligation.get("verifier")
        ):
            raise AssertionError("formal obligations need ids and verifiers")
        if obligation.get("evidence") not in EVIDENCE_CLASSES:
            raise AssertionError(f"unsupported evidence class: {obligation.get('evidence')}")


def _verify_assumptions(data: dict[str, Any]) -> None:
    assumptions = data.get("assumptions")
    if not isinstance(assumptions, list) or not assumptions:
        raise AssertionError("formal assumptions must be explicit")
    for assumption in assumptions:
        if (
            not isinstance(assumption, dict)
            or not assumption.get("id")
            or assumption.get("review") != "explicit"
        ):
            raise AssertionError("every formal assumption needs explicit review metadata")
    counterexamples = data.get("counterexamples")
    if (
        not isinstance(counterexamples, dict)
        or counterexamples.get("policy") != "named-traces-must-be-reproducible"
    ):
        raise AssertionError("counterexample reproducibility policy is required")


def verify_manifest(manifest: dict[str, Any] | None = None) -> None:
    data = _load() if manifest is None else manifest
    if data.get("schema_version") != 1:
        raise AssertionError("unsupported formal evidence schema")
    verifier = data.get("verifier")
    if not isinstance(verifier, dict) or verifier.get("version") != "1.0.0":
        raise AssertionError("formal verifier version must be pinned to 1.0.0")
    checker_names = data.get("model_checkers")
    if not isinstance(checker_names, dict) or {"tla_plus", "alloy"} - checker_names.keys():
        raise AssertionError("TLA+ and Alloy checker metadata is required")

    _verify_models(data)
    _verify_obligations(data)
    _verify_assumptions(data)


if __name__ == "__main__":
    verify_manifest()
    print(
        "formal evidence manifest: pinned tools, hashes, obligations, assumptions, and counterexample policy are valid"
    )
