#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Render the published WorkBuddy capability boundary from machine authorities."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs" / "contracts" / "workbuddy-provider-v1.json"
REGISTRY = ROOT / "config" / "capability-status-registry.json"
GUIDE = ROOT / "docs" / "WORKBUDDY_PROVIDER.md"
START = "<!-- generated:workbuddy-capabilities:start -->"
END = "<!-- generated:workbuddy-capabilities:end -->"


class WorkBuddyRenderError(ValueError):
    """Raised when WorkBuddy publication authorities or output are invalid."""


def _load_object(path: Path) -> dict[str, Any]:
    try:
        value: Any = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise WorkBuddyRenderError(f"cannot read {path.name}: {error}") from error
    if not isinstance(value, dict):
        raise WorkBuddyRenderError(f"{path.name} must contain an object")
    return value


def load_authorities() -> tuple[dict[str, Any], dict[str, Any]]:
    contract = _load_object(CONTRACT)
    evidence = contract.get("implementation_evidence")
    entries = _load_object(REGISTRY).get("capabilities")
    if not isinstance(evidence, dict) or not isinstance(entries, list):
        raise WorkBuddyRenderError("WorkBuddy implementation evidence is incomplete")
    matching = [
        entry for entry in entries if entry.get("id") == evidence.get("capability_registry_id")
    ]
    if len(matching) != 1 or not isinstance(matching[0], dict):
        raise WorkBuddyRenderError("WorkBuddy capability registry entry must be unique")
    return contract, matching[0]


def _cell(value: Any) -> str:
    if not isinstance(value, str) or not value.strip():
        raise WorkBuddyRenderError("generated WorkBuddy fields must be non-empty strings")
    return value.strip().replace("|", "\\|")


def render(contract: dict[str, Any], registry: dict[str, Any]) -> str:
    capabilities = contract.get("capabilities")
    limitations = registry.get("limitations")
    if not isinstance(capabilities, list) or not capabilities:
        raise WorkBuddyRenderError("WorkBuddy contract needs capabilities")
    if not isinstance(limitations, list) or not limitations:
        raise WorkBuddyRenderError("WorkBuddy registry entry needs limitations")
    visibility = (
        "not exposed in the Android app" if not registry.get("appExposed") else "app-exposed"
    )
    lines = [
        f"Registry status: **{_cell(registry.get('maturity'))}**, **{visibility}**.",
        "",
        "| Capability | Published status | Relay mapping | Failure boundary |",
        "| --- | --- | --- | --- |",
    ]
    for capability in capabilities:
        if not isinstance(capability, dict):
            raise WorkBuddyRenderError("WorkBuddy capabilities must be objects")
        lines.append(
            "| "
            + " | ".join(
                _cell(capability.get(field))
                for field in ("id", "status", "relay_mapping", "unsupported_outcome")
            )
            + " |"
        )
    lines.extend(("", "Registry limitations:", ""))
    lines.extend(f"- {_cell(limitation)}" for limitation in limitations)
    return "\n".join(lines)


def update(generated: str, *, check: bool) -> None:
    current = GUIDE.read_text(encoding="utf-8")
    start = current.find(START)
    end = current.find(END)
    if start < 0 or end < start:
        raise WorkBuddyRenderError("generated WorkBuddy markers are missing or out of order")
    end += len(END)
    updated = f"{current[:start]}{START}\n{generated}\n{END}{current[end:]}"
    if check and current != updated:
        raise WorkBuddyRenderError("generated WorkBuddy provider guide is stale")
    if not check:
        GUIDE.write_text(updated, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    update(render(*load_authorities()), check=args.check)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
