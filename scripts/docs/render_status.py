#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Render registry-owned project status tables and check freshness."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "docs" / "contracts" / "status-registry-v1.json"
START = "<!-- generated:status:start -->"
END = "<!-- generated:status:end -->"


class StatusError(ValueError):
    """Raised when the status registry or generated output is invalid."""


def load_registry(path: Path = REGISTRY) -> list[dict[str, str]]:
    try:
        document: Any = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise StatusError(f"cannot read status registry: {error}") from error
    entries = document.get("entries") if isinstance(document, dict) else None
    if not isinstance(entries, list) or not entries:
        raise StatusError("status registry needs a non-empty entries list")
    seen: set[str] = set()
    validated: list[dict[str, str]] = []
    for entry in entries:
        if not isinstance(entry, dict) or not {"id", "area", "status"} <= entry.keys():
            raise StatusError("each status entry needs id, area, and status")
        if any(
            not isinstance(entry[key], str) or not entry[key].strip()
            for key in ("id", "area", "status")
        ):
            raise StatusError("status entry values must be non-empty strings")
        if entry["id"] in seen:
            raise StatusError(f"duplicate status id: {entry['id']}")
        seen.add(entry["id"])
        validated.append({key: entry[key].strip() for key in ("id", "area", "status")})
    return validated


def render(entries: list[dict[str, str]]) -> str:
    lines = ["| Area | Status |", "| --- | --- |"]
    lines.extend(
        f"| {entry['area'].replace('|', '\\|')} | {entry['status'].replace('|', '\\|')} |"
        for entry in entries
    )
    return "\n".join(lines)


def replace_generated(document: str, generated: str) -> str:
    start = document.find(START)
    end = document.find(END)
    if start < 0 or end < 0 or end < start:
        raise StatusError("generated status markers are missing or out of order")
    end += len(END)
    return f"{document[:start]}{START}\n{generated}\n{END}{document[end:]}"


def update(path: Path, generated: str, *, check: bool) -> None:
    current = path.read_text(encoding="utf-8") if path.exists() else ""
    updated = replace_generated(current, generated)
    if check:
        if current != updated:
            display_path = path.relative_to(ROOT) if path.is_relative_to(ROOT) else path
            raise StatusError(f"generated status is stale: {display_path}")
    else:
        path.write_text(updated, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail when generated output is stale")
    args = parser.parse_args()
    generated = render(load_registry())
    update(ROOT / "README.md", generated, check=args.check)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
