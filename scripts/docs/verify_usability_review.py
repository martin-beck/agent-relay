#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Validate the privacy-safe human usability review contract."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, cast

ROOT = Path(__file__).resolve().parents[2]
JOURNEY_PATH = ROOT / "docs" / "contracts" / "user-journeys-v1.json"
REVIEW_PATH = ROOT / "docs" / "contracts" / "usability-review-v1.json"


class ReviewContractError(ValueError):
    """A usability review contract is invalid."""


def read_json(path: Path) -> dict[str, Any]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ReviewContractError(f"{path}: expected a JSON object")
    return cast(dict[str, Any], raw)


def require_number(mapping: dict[str, Any], key: str, path: Path) -> float:
    value = mapping.get(key)
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ReviewContractError(f"{path}: {key} must be numeric")
    return float(value)


def validate_cadence(review: dict[str, Any], source: Path) -> None:
    cadence = review.get("cadence")
    if not isinstance(cadence, dict):
        raise ReviewContractError(f"{source}: cadence must be an object")
    for key in (
        "reviewIntervalDays",
        "minimumParticipants",
        "maximumJourneysPerParticipant",
        "retentionDays",
    ):
        value = cadence.get(key)
        if not isinstance(value, int) or isinstance(value, bool) or value < 1:
            raise ReviewContractError(f"{source}: {key} must be a positive integer")
    if cadence.get("storage") != "aggregate-only":
        raise ReviewContractError(f"{source}: storage must be aggregate-only")


def journey_ids(journeys: dict[str, Any]) -> set[str]:
    raw_journeys = journeys.get("contracts")
    if not isinstance(raw_journeys, list):
        raise ReviewContractError(f"{JOURNEY_PATH}: contracts must be a list")
    return {
        item["id"]
        for item in raw_journeys
        if isinstance(item, dict) and isinstance(item.get("id"), str)
    }


def validate_review_entry(
    item: Any, known_ids: set[str], review_ids: set[str], source: Path
) -> None:
    if not isinstance(item, dict):
        raise ReviewContractError(f"{source}: every journey review must be an object")
    journey_id = item.get("id")
    if not isinstance(journey_id, str) or not journey_id or journey_id in review_ids:
        raise ReviewContractError(f"{source}: journey ids must be unique non-empty strings")
    if journey_id not in known_ids:
        raise ReviewContractError(f"{source}: unknown journey {journey_id!r}")
    review_ids.add(journey_id)
    success_target = require_number(item, "successRateTarget", source)
    error_max = require_number(item, "errorRateMax", source)
    workload_max = require_number(item, "workloadScoreMax", source)
    if not 0 < success_target <= 1 or not 0 <= error_max <= 1:
        raise ReviewContractError(f"{source}: rates must be within their inclusive bounds")
    if not 1 <= workload_max <= 7:
        raise ReviewContractError(f"{source}: workloadScoreMax must be from 1 to 7")
    if require_number(item, "medianTimeSecondsMax", source) < 1:
        raise ReviewContractError(f"{source}: medianTimeSecondsMax must be positive")
    prompts = item.get("reviewPrompts")
    if (
        not isinstance(prompts, list)
        or len(prompts) < 2
        or not all(isinstance(prompt, str) and prompt.strip() for prompt in prompts)
    ):
        raise ReviewContractError(f"{source}: each journey needs at least two review prompts")


def validate_review_contract(
    review: dict[str, Any], journeys: dict[str, Any], source: Path = REVIEW_PATH
) -> None:
    if review.get("schemaVersion") != 1:
        raise ReviewContractError(f"{source}: schemaVersion must be 1")
    validate_cadence(review, source)
    known_ids = journey_ids(journeys)
    reviews = review.get("journeys")
    if not isinstance(reviews, list) or not reviews:
        raise ReviewContractError(f"{source}: journeys must be a non-empty list")
    review_ids: set[str] = set()
    for item in reviews:
        validate_review_entry(item, known_ids, review_ids, source)
    if review_ids != known_ids:
        raise ReviewContractError(f"{source}: review journey ids must match journey contracts")


def main() -> int:
    try:
        validate_review_contract(read_json(REVIEW_PATH), read_json(JOURNEY_PATH))
    except (OSError, json.JSONDecodeError, ReviewContractError) as error:
        print(f"usability review contract error: {error}")
        return 1
    print(f"validated {len(read_json(REVIEW_PATH)['journeys'])} usability review contracts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
