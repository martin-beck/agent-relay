#!/usr/bin/env python3
"""Check that documentation claims agree with authoritative registries."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "docs" / "contracts" / "status-registry-v1.json"
README = ROOT / "README.md"
START = "<!-- generated:status:start -->"
END = "<!-- generated:status:end -->"
ROW = re.compile(r"^\| (?P<area>.+?) \| (?P<status>.+?) \|$")


class ConsistencyError(ValueError):
    """Raised for malformed authoritative documentation inputs."""


def _load_registry(path: Path) -> list[dict[str, str]]:
    try:
        document: Any = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ConsistencyError(f"{path}: cannot read registry: {error}") from error
    entries = document.get("entries") if isinstance(document, dict) else None
    if not isinstance(entries, list) or not entries:
        raise ConsistencyError(f"{path}: entries must be a non-empty list")
    result: list[dict[str, str]] = []
    ids: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict) or not all(
            isinstance(entry.get(key), str) for key in ("id", "area", "status")
        ):
            raise ConsistencyError(f"{path}: each entry needs string id, area, and status")
        if entry["id"] in ids:
            raise ConsistencyError(f"{path}: duplicate id {entry['id']}")
        ids.add(entry["id"])
        result.append({key: entry[key].strip() for key in ("id", "area", "status")})
    return result


def _generated_rows(readme: Path) -> list[tuple[int, str, str]]:
    lines = readme.read_text(encoding="utf-8").splitlines()
    try:
        start = lines.index(START)
        end = lines.index(END)
    except ValueError as error:
        raise ConsistencyError(f"{readme}: generated status markers are missing") from error
    if end <= start:
        raise ConsistencyError(f"{readme}: generated status markers are out of order")
    rows: list[tuple[int, str, str]] = []
    for number, line in enumerate(lines[start + 1 : end], start + 2):
        match = ROW.match(line)
        if match and match.group("area") not in {"Area", "---"}:
            rows.append(
                (
                    number,
                    match.group("area").replace("\\|", "|"),
                    match.group("status").replace("\\|", "|"),
                )
            )
    return rows


def check_consistency(registry: Path = REGISTRY, readme: Path = README) -> list[str]:
    entries = _load_registry(registry)
    expected = [(entry["area"], entry["status"]) for entry in entries]
    rows = _generated_rows(readme)
    actual = [(area, status) for _, area, status in rows]
    findings: list[str] = []
    if actual != expected:
        for index, pair in enumerate(expected):
            if index >= len(actual):
                findings.append(f"{readme}: missing generated row {pair[0]}")
            elif actual[index] != pair:
                line = rows[index][0]
                findings.append(f"{readme}:{line}: row contradicts registry for {pair[0]}")
        if len(actual) > len(expected):
            findings.extend(
                f"{readme}:{rows[index][0]}: undocumented generated row {rows[index][1]}"
                for index in range(len(expected), len(actual))
            )
    return findings


def main() -> int:
    findings = check_consistency()
    if findings:
        print("documentation consistency failures:")
        print("\n".join(f"- {finding}" for finding in findings))
        return 1
    print("documentation consistency: registry and generated status agree")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
