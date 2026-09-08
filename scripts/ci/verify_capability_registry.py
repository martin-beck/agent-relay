# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Validate the machine-readable capability status registry."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "config" / "capability-status-registry.json"
STATUSES = {
    "planned",
    "foundation",
    "implemented",
    "app_exposed",
    "verified",
    "evidence_pending",
    "unsupported",
    "deprecated",
}
ID_PATTERN = re.compile(r"^[a-z][a-z0-9.-]{2,63}$")
AR_PATTERN = re.compile(r"^AR-[0-9]{4}$")


def _require_string_list(entry: dict[str, Any], field: str, minimum: int = 0) -> None:
    values = entry.get(field)
    if (
        not isinstance(values, list)
        or len(values) < minimum
        or not all(isinstance(value, str) and value.strip() for value in values)
    ):
        raise AssertionError(
            f"{field} must be a non-empty string list"
            if minimum
            else f"{field} must be a string list"
        )


def validate_registry(registry: dict[str, Any]) -> None:
    if registry.get("schemaVersion") != 1:
        raise AssertionError("unsupported registry schema")
    if registry.get("statusAuthority") != "config/capability-status-registry.json":
        raise AssertionError("registry must declare itself as the status authority")
    entries = registry.get("capabilities")
    if not isinstance(entries, list) or not entries:
        raise AssertionError("registry must contain capabilities")
    identifiers: set[str] = set()
    for entry in entries:
        _validate_entry(entry, identifiers)


def _validate_entry(entry: Any, identifiers: set[str]) -> None:
    if not isinstance(entry, dict):
        raise AssertionError("capability entries must be objects")
    identifier = entry.get("id")
    if (
        not isinstance(identifier, str)
        or not ID_PATTERN.fullmatch(identifier)
        or identifier in identifiers
    ):
        raise AssertionError(f"capability id is missing, invalid or duplicated: {identifier}")
    identifiers.add(identifier)
    if entry.get("maturity") not in STATUSES:
        raise AssertionError(f"invalid maturity for {identifier}")
    if not isinstance(entry.get("appExposed"), bool):
        raise AssertionError(f"appExposed must be boolean for {identifier}")
    for field, minimum in (
        ("supportedSurfaces", 1),
        ("sourceModules", 1),
        ("tests", 0),
        ("workflowEvidence", 0),
        ("limitations", 0),
    ):
        _require_string_list(entry, field, minimum)
    _validate_references(entry, identifier)
    _validate_age(entry, identifier)
    if entry["maturity"] == "app_exposed" and not entry["appExposed"]:
        raise AssertionError(f"app_exposed capability must be visible: {identifier}")
    _validate_paths(entry)


def _validate_references(entry: dict[str, Any], identifier: str) -> None:
    references = entry.get("arReferences")
    if (
        not isinstance(references, list)
        or not references
        or not all(
            isinstance(reference, str) and AR_PATTERN.fullmatch(reference)
            for reference in references
        )
    ):
        raise AssertionError(f"invalid AR references for {identifier}")


def _validate_age(entry: dict[str, Any], identifier: str) -> None:
    age = entry.get("evidenceAgeDays")
    if not isinstance(age, int) or isinstance(age, bool) or not 0 <= age <= 3650:
        raise AssertionError(f"invalid evidence age for {identifier}")


def _validate_paths(entry: dict[str, Any]) -> None:
    for path in (*entry["sourceModules"], *entry["tests"], *entry["workflowEvidence"]):
        if not (ROOT / path).exists():
            raise AssertionError(f"registry path does not exist: {path}")


def verify_registry() -> None:
    validate_registry(json.loads(REGISTRY.read_text(encoding="utf-8")))


if __name__ == "__main__":
    verify_registry()
    print("capability status registry: schema, references, maturity and privacy metadata are valid")
