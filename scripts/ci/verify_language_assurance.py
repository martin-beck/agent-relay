# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Validate the cross-language assurance contract without running its gates."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "config" / "language-assurance-contract.json"
REQUIRED_LANGUAGES = {"java", "bash", "dependency", "python", "kotlin"}


def _load() -> dict[str, Any]:
    value = json.loads(CONTRACT.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise AssertionError("language assurance contract must be an object")
    return value


def _verify_header(data: dict[str, Any]) -> None:
    if data.get("schema_version") != 1 or data.get("contract_version") != "1.0.0":
        raise AssertionError("unsupported language assurance contract version")
    evidence_root = data.get("evidence_root")
    if not isinstance(evidence_root, str) or evidence_root.startswith("/"):
        raise AssertionError("evidence_root must be a relative path")


def _verify_invariants(data: dict[str, Any]) -> set[str]:
    invariants = data.get("invariants")
    if not isinstance(invariants, list) or not invariants:
        raise AssertionError("at least one invariant is required")
    invariant_ids = set()
    for invariant in invariants:
        if (
            not isinstance(invariant, dict)
            or not invariant.get("id")
            or not invariant.get("description")
        ):
            raise AssertionError("invariants need ids and descriptions")
        if invariant["id"] in invariant_ids:
            raise AssertionError(f"duplicate invariant: {invariant['id']}")
        invariant_ids.add(invariant["id"])
    return invariant_ids


def _verify_gates(data: dict[str, Any], invariant_ids: set[str]) -> None:
    gates = data.get("gates")
    if not isinstance(gates, list) or not gates:
        raise AssertionError("at least one assurance gate is required")
    gate_ids = set()
    languages = set()
    for gate in gates:
        if not isinstance(gate, dict):
            raise AssertionError("each gate must be an object")
        required = ("id", "language", "owner", "command", "evidence", "invariants", "remediation")
        if any(not gate.get(field) for field in required):
            raise AssertionError(
                "every gate needs owner, command, evidence, invariants, and remediation"
            )
        if gate["id"] in gate_ids:
            raise AssertionError(f"duplicate gate: {gate['id']}")
        gate_ids.add(gate["id"])
        languages.add(gate["language"])
        evidence = Path(gate["evidence"])
        if evidence.is_absolute() or ".." in evidence.parts:
            raise AssertionError(f"evidence path escapes report root: {gate['evidence']}")
        if not isinstance(gate["invariants"], list) or not set(gate["invariants"]) <= invariant_ids:
            raise AssertionError(f"gate references an unknown invariant: {gate['id']}")
        if not str(gate["owner"]).startswith("AR-"):
            raise AssertionError(f"gate owner must be a coordinator task: {gate['id']}")
    missing = REQUIRED_LANGUAGES - languages
    if missing:
        raise AssertionError(f"missing assurance languages: {', '.join(sorted(missing))}")


def verify_contract(contract: dict[str, Any] | None = None) -> None:
    data = _load() if contract is None else contract
    _verify_header(data)
    invariant_ids = _verify_invariants(data)
    _verify_gates(data, invariant_ids)


if __name__ == "__main__":
    verify_contract()
    print(
        "language assurance contract: all required gates, owners, evidence, and invariants are valid"
    )
