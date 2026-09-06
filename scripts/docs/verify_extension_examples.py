#!/usr/bin/env python3
"""Validate the redacted extension fixture catalogue and manifest schema."""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import Any

_yaml: Any
try:
    import yaml as _yaml
except ModuleNotFoundError:  # Keep the validator runnable before the docs group is installed.
    _yaml = None

ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = ROOT / "docs/extensions/examples.yaml"
REQUIRED = {
    "coding-agent-provider",
    "repository-debug-log",
    "publication-monitor",
    "price-monitor",
    "restaurant-location-recommendation",
    "calendar-cultural-suggestion",
    "smart-home-battery-check",
    "vehicle-maintenance-reminder",
    "custom-theme",
    "watch-notification",
}


def _fallback_document(text: str) -> dict[str, list[dict[str, object]]]:
    """Parse the deliberately flat checked-in fixture format without PyYAML."""
    records: list[dict[str, object]] = []
    current: dict[str, object] | None = None
    for line in text.splitlines():
        stripped = line.strip()
        if stripped == "-" or stripped.startswith("- name:"):
            current = {}
            records.append(current)
            if ":" in stripped:
                current["name"] = stripped.split(":", 1)[1].strip()
        elif current is not None and ":" in stripped:
            key, value = stripped.split(":", 1)
            value = value.strip().strip('"')
            current[key.strip()] = _scalar(value)
    return {"examples": records}


def _scalar(value: str) -> object:
    if value == "true":
        return True
    if value == "false":
        return False
    return value


def _load_document(path: Path) -> object:
    text = path.read_text(encoding="utf-8")
    return _yaml.safe_load(text) if _yaml is not None else _fallback_document(text)


def _validate_example(index: int, example: object, names: set[str]) -> list[str]:
    if not isinstance(example, dict):
        return [f"example {index} is not a mapping"]
    name = example.get("name")
    findings: list[str] = []
    if not isinstance(name, str) or name in names:
        findings.append(f"example {index} has a missing or duplicate name")
    else:
        names.add(name)
    for key in ("fixture", "kind", "manifest", "workflow", "evidence", "redaction"):
        if key not in example or not example[key]:
            findings.append(f"{name or index}: missing {key}")
    if example.get("fixture") is not True:
        findings.append(f"{name or index}: must be marked fixture")
    return findings


def check(path: Path = EXAMPLES) -> list[str]:
    document = _load_document(path)
    examples = document.get("examples") if isinstance(document, dict) else None
    if not isinstance(examples, list):
        return ["examples must be a list"]
    findings: list[str] = []
    names: set[str] = set()
    for index, example in enumerate(examples):
        findings.extend(_validate_example(index, example, names))
    findings.extend(f"missing required fixture: {name}" for name in sorted(REQUIRED - names))
    return findings


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--path", type=Path, default=EXAMPLES)
    args = parser.parse_args()
    findings = check(args.path)
    if findings:
        print("extension fixture failures:", *findings, sep="\n")
        return 1
    print("extension fixture catalogue is valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
