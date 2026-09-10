# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Validate the bounded synthetic offline provider fixture contract."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, NoReturn

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "config" / "offline-provider-fixture-v1.schema.json"
FIXTURE = ROOT / "fixtures" / "offline" / "basic-tool-round.json"
SECRET = re.compile(
    r"(?:sk-[A-Za-z0-9_-]{12,}|-----BEGIN [A-Z ]+-----|(?i:bearer)\s+[A-Za-z0-9._-]{12,})"
)
NORMALIZERS = {"exact", "trim", "lowercase", "redact-id", "redact-timestamp", "sort-keys"}
FAULTS = {
    "disconnect",
    "truncate",
    "malformed",
    "rate-limit",
    "process-exit",
    "cancel",
    "duplicate",
    "gap",
    "uncertain-delivery",
}


def _fail(message: str) -> NoReturn:
    raise AssertionError(message)


def validate_fixture(data: dict[str, Any], *, schema: dict[str, Any] | None = None) -> None:  # noqa: C901
    if (
        schema is not None
        and schema.get("$id") != "https://agent-relay.dev/schemas/offline-provider-fixture-v1.json"
    ):
        _fail("unexpected schema identity")
    if data.get("schemaVersion") != 1:
        _fail("unsupported fixture schema")
    if not isinstance(data.get("fixtureId"), str) or not re.fullmatch(
        r"[a-z][a-z0-9.-]{2,63}", data["fixtureId"]
    ):
        _fail("invalid fixture id")
    evidence = data.get("evidence")
    if evidence != {
        "tier": "synthetic-runtime",
        "synthetic": True,
        "sourceRevision": evidence.get("sourceRevision") if isinstance(evidence, dict) else None,
    }:
        _fail("fixture evidence must be synthetic-runtime")
    if not isinstance(evidence.get("sourceRevision"), str) or not re.fullmatch(
        r"[0-9a-f]{7,64}", evidence["sourceRevision"]
    ):
        _fail("invalid source revision")
    limits = data.get("limits")
    if not isinstance(limits, dict):
        _fail("limits are required")
    bounds = {
        "maxBodyBytes": (1, 1048576),
        "maxFrames": (1, 10000),
        "maxEvents": (1, 10000),
        "maxVirtualMillis": (1, 3600000),
        "maxRetries": (0, 10),
    }
    for key, (low, high) in bounds.items():
        value = limits.get(key)
        if not isinstance(value, int) or isinstance(value, bool) or not low <= value <= high:
            _fail(f"invalid limit: {key}")
    matching = data.get("matching")
    if (
        not isinstance(matching, dict)
        or not isinstance(matching.get("requestFields"), list)
        or not matching["requestFields"]
    ):
        _fail("request matcher fields required")
    if len(set(matching["requestFields"])) != len(matching["requestFields"]):
        _fail("ambiguous duplicate request field")
    normalizers = matching.get("normalization")
    if not isinstance(normalizers, list) or not normalizers:
        _fail("explicit normalization required")
    normalized_fields: set[str] = set()
    for item in normalizers:
        if (
            not isinstance(item, dict)
            or item.get("operation") not in NORMALIZERS
            or item.get("field") in normalized_fields
        ):
            _fail("invalid or ambiguous normalizer")
        normalized_fields.add(item["field"])
    if set(matching["requestFields"]) - normalized_fields:
        _fail("every request field needs explicit normalization")
    timeline = data.get("timeline")
    if not isinstance(timeline, list) or not timeline or len(timeline) > limits["maxFrames"]:
        _fail("invalid or over-limit timeline")
    previous = -1
    for frame in timeline:
        if (
            not isinstance(frame, dict)
            or frame.get("direction") not in {"request", "response"}
            or frame.get("kind")
            not in {"agent-event", "tool-call", "tool-result", "approval", "terminal"}
        ):
            _fail("invalid frame")
        at = frame.get("atMillis")
        if not isinstance(at, int) or at < previous or at > limits["maxVirtualMillis"]:
            _fail("timeline is not bounded and ordered")
        previous = at
        payload = json.dumps(frame.get("payload"), separators=(",", ":"), sort_keys=True)
        if len(payload.encode()) > limits["maxBodyBytes"] or SECRET.search(payload):
            _fail("payload is oversized or secret-like")
    faults = data.get("faults", [])
    if not isinstance(faults, list) or len(faults) > 32:
        _fail("invalid faults")
    for fault in faults:
        if (
            not isinstance(fault, dict)
            or fault.get("kind") not in FAULTS
            or not isinstance(fault.get("atMillis"), int)
            or fault["atMillis"] > limits["maxVirtualMillis"]
        ):
            _fail("invalid fault")
        if "seed" in fault and (
            not isinstance(fault["seed"], int) or not 0 <= fault["seed"] <= 2147483647
        ):
            _fail("invalid deterministic seed")
    outcome = data.get("outcome")
    if not isinstance(outcome, dict) or outcome.get("status") not in {
        "success",
        "cancelled",
        "failed",
        "uncertain",
    }:
        _fail("invalid outcome")
    for key in ("consumedFrames", "consumedEvents"):
        if not isinstance(outcome.get(key), int) or outcome[key] < 0:
            _fail(f"invalid {key}")
    if outcome["consumedFrames"] != len(timeline):
        _fail("leftover or unconsumed frames")
    if outcome["status"] == "uncertain" and limits["maxRetries"] != 0:
        _fail("uncertain delivery must not retry")


def verify_fixture() -> None:
    validate_fixture(
        json.loads(FIXTURE.read_text(encoding="utf-8")),
        schema=json.loads(SCHEMA.read_text(encoding="utf-8")),
    )


if __name__ == "__main__":
    verify_fixture()
    print(
        "offline provider fixture: schema, matching, limits, faults, privacy and outcome are valid"
    )
