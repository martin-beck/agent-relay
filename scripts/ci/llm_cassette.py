# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Opt-in capture and strict zero-egress replay for sanitized LLM cassettes."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any, cast

SECRET = re.compile(
    r"(?:sk-[A-Za-z0-9_-]{12,}|-----BEGIN [A-Z ]+-----|(?i:bearer)\s+[A-Za-z0-9._-]{12,})"
)
PATH = re.compile(r"(?:[A-Za-z]:[\\/]|/home/|/Users/|/private/)")
MAX_ID = 128
PROTOCOLS = {"openai-chat", "openai-responses", "anthropic-messages", "gemini"}
REQUIRED_FIELDS = {"schemaVersion", "evidence", "metadata", "limits", "frames"}
FRAME_FIELDS = {"atMillis", "direction", "bodyDigest", "body"}


class CassetteError(ValueError):
    """Raised when a cassette violates its privacy or replay contract."""


def _body_bytes(body: dict[str, Any]) -> bytes:
    return json.dumps(body, sort_keys=True, separators=(",", ":")).encode()


def _validate_body(body: dict[str, Any], limit: int) -> str:
    encoded = _body_bytes(body)
    text = encoded.decode()
    if len(encoded) > limit or SECRET.search(text) or PATH.search(text):
        raise CassetteError("body is oversized or contains protected data")
    return hashlib.sha256(encoded).hexdigest()


def _require_bounded_integer(value: Any, name: str, low: int, high: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise CassetteError(f"invalid cassette limit: {name}")
    return value


def _validate_identity(cassette: dict[str, Any], schema: dict[str, Any] | None) -> None:
    if set(cassette) != REQUIRED_FIELDS or cassette.get("schemaVersion") != 1:
        raise CassetteError("unsupported or incomplete cassette schema")
    if schema is not None and schema.get("$id") != (
        "https://agent-relay.dev/schemas/llm-cassette-v1.json"
    ):
        raise CassetteError("unexpected cassette schema identity")
    evidence = cassette.get("evidence")
    if evidence != {"tier": "sanitized-replay", "synthetic": True, "approved": True}:
        raise CassetteError("cassette is not approved synthetic replay evidence")


def _validate_metadata(metadata: Any) -> None:
    if not isinstance(metadata, dict) or set(metadata) != {
        "protocol",
        "cliVersion",
        "toolVersion",
        "sourceRevision",
    }:
        raise CassetteError("cassette metadata is incomplete")
    if metadata["protocol"] not in PROTOCOLS:
        raise CassetteError("cassette protocol is unsupported")
    if not all(
        isinstance(metadata[field], str) and 0 < len(metadata[field]) <= 64
        for field in ("cliVersion", "toolVersion")
    ):
        raise CassetteError("cassette tool metadata is invalid")
    if not isinstance(metadata["sourceRevision"], str) or not re.fullmatch(
        r"[0-9a-f]{7,64}", metadata["sourceRevision"]
    ):
        raise CassetteError("cassette source revision is invalid")


def _validate_limits(limits: Any) -> tuple[int, int, int]:
    if not isinstance(limits, dict) or set(limits) != {
        "maxBodyBytes",
        "maxFrames",
        "maxVirtualMillis",
    }:
        raise CassetteError("cassette limits are incomplete")
    return (
        _require_bounded_integer(limits["maxBodyBytes"], "maxBodyBytes", 1, 1_048_576),
        _require_bounded_integer(limits["maxFrames"], "maxFrames", 1, 10_000),
        _require_bounded_integer(limits["maxVirtualMillis"], "maxVirtualMillis", 1, 3_600_000),
    )


def _validate_frames(frames: Any, body_limit: int, frame_limit: int, time_limit: int) -> None:
    if not isinstance(frames, list) or not 0 < len(frames) <= frame_limit:
        raise CassetteError("cassette frame count is invalid")
    previous = -1
    for index, frame in enumerate(frames):
        if not isinstance(frame, dict) or set(frame) != FRAME_FIELDS:
            raise CassetteError("cassette frame is incomplete")
        expected_direction = "request" if index % 2 == 0 else "response"
        if frame["direction"] != expected_direction or not isinstance(frame["body"], dict):
            raise CassetteError("cassette frames must be request-response pairs")
        at_millis = frame["atMillis"]
        if (
            isinstance(at_millis, bool)
            or not isinstance(at_millis, int)
            or at_millis < previous
            or at_millis > time_limit
        ):
            raise CassetteError("cassette timeline is not bounded")
        previous = at_millis
        if frame["bodyDigest"] != _validate_body(frame["body"], body_limit):
            raise CassetteError("cassette body digest does not match")
    if len(frames) % 2:
        raise CassetteError("cassette has an unmatched request")


def validate_cassette(cassette: dict[str, Any], *, schema: dict[str, Any] | None = None) -> None:
    """Validate the closed-world privacy and deterministic replay contract."""
    _validate_identity(cassette, schema)
    _validate_metadata(cassette.get("metadata"))
    body_limit, frame_limit, time_limit = _validate_limits(cassette.get("limits"))
    _validate_frames(cassette.get("frames"), body_limit, frame_limit, time_limit)


def load_cassette(path: Path, schema_path: Path) -> dict[str, Any]:
    """Load and validate a cassette and its committed schema."""
    try:
        cassette = json.loads(path.read_text(encoding="utf-8"))
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise CassetteError("cassette or schema is not valid JSON") from error
    if not isinstance(cassette, dict) or not isinstance(schema, dict):
        raise CassetteError("cassette and schema roots must be objects")
    validate_cassette(cassette, schema=schema)
    return cassette


def sanitize_capture(
    frames: list[dict[str, Any]], *, capture_enabled: bool, upstream: str, allowlist: set[str]
) -> list[dict[str, Any]]:
    """Admit only explicit captures from an allowlisted HTTPS upstream."""
    if not capture_enabled or not upstream.startswith("https://") or upstream not in allowlist:
        raise CassetteError("capture requires explicit allowlisted HTTPS upstream")
    sanitized: list[dict[str, Any]] = []
    previous = -1
    for frame in frames:
        if frame.get("direction") not in {"request", "response"} or not isinstance(
            frame.get("body"), dict
        ):
            raise CassetteError("invalid capture frame")
        at = frame.get("atMillis")
        if not isinstance(at, int) or at < previous or at > 3_600_000:
            raise CassetteError("capture timeline is not bounded")
        previous = at
        digest = _validate_body(frame["body"], 1_048_576)
        sanitized.append(
            {
                "atMillis": at,
                "direction": frame["direction"],
                "bodyDigest": digest,
                "body": frame["body"],
            }
        )
    if not sanitized or len(sanitized) > 10_000:
        raise CassetteError("capture frame count is invalid")
    return sanitized


def replay(
    cassette: dict[str, Any], requests: list[dict[str, Any]], *, allow_network: bool = False
) -> list[dict[str, Any]]:
    """Replay exact semantic request bodies; network is denied by default."""
    if allow_network:
        raise CassetteError("replay cannot enable network egress")
    validate_cassette(cassette)
    frames = cast(list[dict[str, Any]], cassette["frames"])
    expected = [f for f in frames if f.get("direction") == "request"]
    if len(expected) != len(requests):
        raise CassetteError("unmatched or leftover requests")
    for frame, request in zip(expected, requests, strict=True):
        if (
            not isinstance(request, dict)
            or _validate_body(request, int(cassette["limits"]["maxBodyBytes"]))
            != frame["bodyDigest"]
        ):
            raise CassetteError("request does not match cassette")
    return [f for f in frames if f.get("direction") == "response"]


def contract_diff(
    left: dict[str, Any], right: dict[str, Any], volatile: set[str] | None = None
) -> list[str]:
    """Return stable JSON paths that drift; volatile fields must be explicit."""
    volatile = volatile or set()
    differences: list[str] = []
    for key in sorted(set(left) | set(right)):
        if key in volatile:
            continue
        if left.get(key) != right.get(key):
            differences.append(key)
    return differences
