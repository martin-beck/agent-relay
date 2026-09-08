#!/usr/bin/env python3
"""Validate the user-satisfaction status authority without inventing evidence."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, cast

import yaml

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs" / "contracts" / "user-satisfaction-v1.json"
REQUIRED_PHASES = {
    "state-outcome",
    "review-scope",
    "approve-decision",
    "recover-interruption",
    "explain-result",
}
REQUIRED_CHILDREN = {f"AR-{number}" for number in range(2160, 2170)}
ID_PATTERN = re.compile(r"^[a-z][a-z0-9-]{2,63}$")


class SatisfactionError(ValueError):
    """The satisfaction authority or its evidence is inconsistent."""


def read_contract(path: Path = CONTRACT) -> dict[str, Any]:
    try:
        value: Any = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SatisfactionError(f"cannot read satisfaction contract: {error}") from error
    if not isinstance(value, dict):
        raise SatisfactionError("satisfaction contract must be an object")
    return cast(dict[str, Any], value)


def repository_path(root: Path, raw: Any, field: str) -> Path:
    if not isinstance(raw, str) or not raw or Path(raw).is_absolute():
        raise SatisfactionError(f"{field} must be a repository-relative path")
    candidate = (root / raw).resolve()
    if candidate != root and root not in candidate.parents:
        raise SatisfactionError(f"{field} escapes the repository")
    if not candidate.is_file():
        raise SatisfactionError(f"{field} does not exist: {raw}")
    return candidate


def require_unique(entries: list[dict[str, Any]], field: str) -> None:
    values = [entry.get(field) for entry in entries]
    if any(not isinstance(value, str) or not value for value in values):
        raise SatisfactionError(f"every entry needs a non-empty {field}")
    if len(values) != len(set(values)):
        raise SatisfactionError(f"{field} values must be unique")


def string_list(entry: dict[str, Any], field: str, *, minimum: int = 0) -> list[str]:
    values = entry.get(field)
    if (
        not isinstance(values, list)
        or len(values) < minimum
        or not all(isinstance(value, str) and value for value in values)
        or len(values) != len(set(values))
    ):
        raise SatisfactionError(f"{field} must be a unique string list")
    return cast(list[str], values)


def bounded_string(entry: dict[str, Any], field: str, maximum: int, subject: str) -> str:
    value = entry.get(field)
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise SatisfactionError(f"{subject} has invalid {field}")
    return value


def read_scenario(path: Path) -> dict[str, Any]:
    try:
        value: Any = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as error:
        raise SatisfactionError(f"cannot parse verified scenario: {error}") from error
    if not isinstance(value, dict):
        raise SatisfactionError("verified scenario must be an object")
    return cast(dict[str, Any], value)


def validate_scenario_header(scenario: dict[str, Any], identifier: str) -> None:
    if scenario.get("status") != "verified":
        raise SatisfactionError(f"verified journey {identifier} references a planned scenario")
    if scenario.get("id") != identifier:
        raise SatisfactionError(f"verified journey {identifier} has a mismatched scenario id")
    if not isinstance(scenario.get("verified_test"), str) or not scenario["verified_test"]:
        raise SatisfactionError(f"verified journey {identifier} needs a semantic Android test")


def validate_scenario_steps(root: Path, scenario: dict[str, Any], identifier: str) -> None:
    steps = scenario.get("steps")
    if not isinstance(steps, list) or not steps:
        raise SatisfactionError(f"verified journey {identifier} needs captured steps")
    evidence_root = (root / "docs" / "assets" / "workflows" / identifier).resolve()
    for step in steps:
        if not isinstance(step, dict) or not isinstance(step.get("alt"), str) or not step["alt"]:
            raise SatisfactionError(f"verified journey {identifier} needs alt text for every step")
        screenshot = step.get("screenshot")
        candidate = (evidence_root / screenshot).resolve() if isinstance(screenshot, str) else root
        if (
            not isinstance(screenshot, str)
            or evidence_root not in candidate.parents
            or not candidate.is_file()
        ):
            raise SatisfactionError(f"verified journey {identifier} is missing screenshot evidence")


def validate_scenario(root: Path, entry: dict[str, Any]) -> None:
    identifier = bounded_string(entry, "id", 64, "verified journey")
    if not ID_PATTERN.fullmatch(identifier):
        raise SatisfactionError("verified journey ids must be stable identifiers")
    bounded_string(entry, "title", 120, f"verified journey {identifier}")
    bounded_string(entry, "userValue", 240, f"verified journey {identifier}")
    scenario = read_scenario(repository_path(root, entry.get("scenario"), "verified scenario"))
    validate_scenario_header(scenario, identifier)
    validate_scenario_steps(root, scenario, identifier)


def validate_child(root: Path, entry: dict[str, Any]) -> None:
    identifier = bounded_string(entry, "id", 64, f"child {entry.get('ar')}")
    if not ID_PATTERN.fullmatch(identifier):
        raise SatisfactionError(f"invalid child id for {entry.get('ar')}")
    bounded_string(entry, "title", 120, f"child {entry.get('ar')}")
    if entry.get("phase") not in REQUIRED_PHASES:
        raise SatisfactionError(f"invalid child phase for {entry.get('ar')}")
    if entry.get("maturity") != "contract-verified":
        raise SatisfactionError("child contracts cannot be promoted beyond their evidence")
    if not isinstance(entry.get("limitation"), str) or not entry["limitation"].strip():
        raise SatisfactionError(f"contract-only child {entry.get('ar')} needs a limitation")
    for field, minimum in (("sources", 1), ("tests", 1), ("formalEvidence", 0)):
        for raw in string_list(entry, field, minimum=minimum):
            path = repository_path(root, raw, f"{entry.get('ar')} {field}")
            if field == "tests" and "test" not in path.name.lower() and "/test/" not in raw:
                raise SatisfactionError(f"{entry.get('ar')} test evidence is not a test path")


def validate_header(document: dict[str, Any]) -> None:
    if document.get("$schema") != "user-satisfaction-v1.schema.json":
        raise SatisfactionError("contract must identify the versioned JSON schema")
    if document.get("schemaVersion") != 1:
        raise SatisfactionError("schemaVersion must be 1")
    if document.get("statusAuthority") != "docs/contracts/user-satisfaction-v1.json":
        raise SatisfactionError("contract must declare the canonical status authority")


def object_entries(document: dict[str, Any], field: str) -> list[dict[str, Any]]:
    values = document.get(field)
    if (
        not isinstance(values, list)
        or not values
        or not all(isinstance(item, dict) for item in values)
    ):
        raise SatisfactionError(f"{field} must be a non-empty object list")
    return cast(list[dict[str, Any]], values)


def validate_contract(document: dict[str, Any], root: Path = ROOT) -> dict[str, Any]:
    validate_header(document)
    phases = set(string_list(document, "requiredPhases", minimum=len(REQUIRED_PHASES)))
    if phases != REQUIRED_PHASES:
        raise SatisfactionError("required phases must match the complete satisfaction journey")
    journeys = object_entries(document, "verifiedJourneys")
    children = object_entries(document, "childContracts")
    require_unique(journeys, "id")
    require_unique(journeys, "scenario")
    require_unique(children, "ar")
    require_unique(children, "id")
    child_ids = {cast(str, entry["ar"]) for entry in children}
    if child_ids != REQUIRED_CHILDREN:
        raise SatisfactionError("child contracts must cover AR-2160 through AR-2169 exactly once")
    for journey in journeys:
        if journey.get("phase") not in REQUIRED_PHASES:
            raise SatisfactionError(f"invalid verified journey phase for {journey.get('id')}")
        validate_scenario(root, journey)
    for child in children:
        validate_child(root, child)
    covered = {cast(str, item["phase"]) for item in journeys + children}
    if covered != REQUIRED_PHASES:
        raise SatisfactionError(
            "every satisfaction phase needs evidence or a truthful contract-only entry"
        )
    return document


def main() -> int:
    try:
        document = validate_contract(read_contract())
    except SatisfactionError as error:
        print(f"user-satisfaction assurance error: {error}")
        return 1
    print(
        "user-satisfaction assurance: "
        f"{len(document['verifiedJourneys'])} Android journeys and "
        f"{len(document['childContracts'])} child contracts validated"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
