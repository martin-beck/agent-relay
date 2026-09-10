# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Opt-in capture and strict zero-egress replay for sanitized LLM cassettes."""

from __future__ import annotations

import hashlib
import json
import re
from typing import Any

SECRET = re.compile(
    r"(?:sk-[A-Za-z0-9_-]{12,}|-----BEGIN [A-Z ]+-----|(?i:bearer)\s+[A-Za-z0-9._-]{12,})"
)
PATH = re.compile(r"(?:[A-Za-z]:[\\/]|/home/|/Users/|/private/)")
MAX_ID = 128


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
    frames = cassette.get("frames")
    if not isinstance(frames, list):
        raise CassetteError("frames are required")
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
