# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Fail-closed launcher for scheduled local-inference conformance."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "config/local-inference-conformance-v1.json"


class ConformanceBlocked(RuntimeError):
    """Raised when required isolation or provenance gates are absent."""


def load_manifest() -> dict[str, Any]:
    document = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError("manifest root must be an object")
    return document


def validate_manifest(document: dict[str, Any]) -> None:
    if document.get("schemaVersion") != 1 or document.get("network") != "deny":
        raise ConformanceBlocked("manifest does not enforce schema v1 and network denial")
    engines = document.get("engines")
    if not isinstance(engines, list) or len(engines) != 4:
        raise ValueError("exactly four engine tuples are required")
    for engine in engines:
        if not isinstance(engine, dict) or engine.get("result") != "unverified":
            raise ValueError("unverified result required until evidence is admitted")
        for field in ("revision", "model", "quantization", "template", "cli", "hardware"):
            if not isinstance(engine.get(field), str) or not engine[field]:
                raise ValueError(f"missing tuple field: {field}")


def require_execution_gates() -> None:
    if os.environ.get("AGENT_RELAY_LOCAL_INFERENCE_ENABLE") != "1":
        raise ConformanceBlocked("explicit local-inference enable flag is required")
    if os.environ.get("AGENT_RELAY_OUTBOUND_NETWORK", "deny") != "deny":
        raise ConformanceBlocked("outbound network must remain denied")
    for variable in (
        "AGENT_RELAY_ENGINE_DIGEST",
        "AGENT_RELAY_MODEL_DIGEST",
        "AGENT_RELAY_CLI_DIGEST",
        "AGENT_RELAY_ADAPTER",
    ):
        if not os.environ.get(variable):
            raise ConformanceBlocked(f"missing provenance: {variable}")


def run() -> dict[str, Any]:
    document = load_manifest()
    validate_manifest(document)
    require_execution_gates()
    raise ConformanceBlocked("real CLI execution is not available in the default PR environment")


if __name__ == "__main__":
    run()
