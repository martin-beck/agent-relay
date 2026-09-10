# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
import copy
import json
from typing import Any, cast

import pytest
from verify_offline_provider_fixture import validate_fixture


@pytest.fixture
def fixture() -> dict[str, Any]:
    with open("fixtures/offline/basic-tool-round.json", encoding="utf-8") as handle:
        return cast(dict[str, Any], json.load(handle))


def test_fixture_is_deterministically_valid(fixture: dict[str, Any]) -> None:
    validate_fixture(fixture)
    validate_fixture(copy.deepcopy(fixture))


@pytest.mark.parametrize(
    ("path", "value"),
    [
        ("schemaVersion", 2),
        ("evidence", {"tier": "live-provider", "synthetic": False, "sourceRevision": "0123456"}),
    ],
)
def test_rejects_unsupported_or_non_synthetic(
    path: str, value: object, fixture: dict[str, Any]
) -> None:
    fixture[path] = value
    with pytest.raises(AssertionError):
        validate_fixture(fixture)


def test_rejects_ambiguous_matcher(fixture: dict[str, Any]) -> None:
    fixture["matching"]["requestFields"].append("operation")
    with pytest.raises(AssertionError):
        validate_fixture(fixture)


def test_rejects_secret_like_content(fixture: dict[str, Any]) -> None:
    fixture["timeline"][0]["payload"]["token"] = "Bearer abcdefghijklmnop"  # noqa: S105
    with pytest.raises(AssertionError):
        validate_fixture(fixture)


def test_rejects_time_travel_and_leftover_frames(fixture: dict[str, Any]) -> None:
    fixture["timeline"][1]["atMillis"] = -1
    with pytest.raises(AssertionError):
        validate_fixture(fixture)
    fixture["timeline"][1]["atMillis"] = 4
    fixture["outcome"]["consumedFrames"] = 1
    with pytest.raises(AssertionError):
        validate_fixture(fixture)


def test_uncertain_delivery_cannot_retry(fixture: dict[str, Any]) -> None:
    fixture["outcome"]["status"] = "uncertain"
    with pytest.raises(AssertionError):
        validate_fixture(fixture)


# mypy: ignore-errors
