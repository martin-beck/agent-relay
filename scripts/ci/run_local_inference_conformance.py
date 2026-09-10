# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Fail-closed launcher for scheduled local-inference conformance."""

from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "config/local-inference-conformance-v1.json"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
REVISION = re.compile(r"^[0-9a-f]{40}$")
OBSERVATION_FIELDS = {
    "adapter",
    "cli",
    "cliBinarySha256",
    "cliRevision",
    "cliVersion",
    "determinismClaimed",
    "engine",
    "engineBinarySha256",
    "engineRevision",
    "engineRuntimeSha256",
    "engineVersion",
    "evidenceLabel",
    "hardwareClass",
    "id",
    "limitations",
    "model",
    "modelBlobSha256",
    "modelManifestSha256",
    "network",
    "protocol",
    "quantization",
    "result",
    "sampling",
    "source",
    "templateBlobSha256",
    "verifiedChecks",
}
EXPECTED_OBSERVATION_VALUES = {
    "adapter": "anomaly.opencode",
    "cli": "opencode",
    "cliVersion": "1.18.23",
    "engine": "ollama",
    "engineVersion": "0.33.1",
    "evidenceLabel": "local-model",
    "hardwareClass": "self-hosted-cpu-x86_64",
    "id": "opencode-ollama-qwen3-0.6b-q4-k-m-cpu-v1",
    "model": "qwen3:0.6b",
    "network": "deny-after-staging",
    "protocol": "openai-chat",
    "quantization": "Q4_K_M",
    "result": "passed",
}
VERIFIED_CHECKS = {
    "assistant-transcript",
    "clean-teardown",
    "mapped-live-event",
    "model-qualified-prompt",
    "project-discovery",
    "session-create",
}
REQUIRED_LIMITATIONS = {
    "accelerator",
    "active-inference-cancel",
    "alternate-cli",
    "approval",
    "cloud",
    "llama.cpp",
    "localai",
    "malformed-real-cli",
    "mobile",
    "performance",
    "timeout",
    "tool-call",
    "vllm",
}


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
    engine_ids: set[str] = set()
    for engine in engines:
        if not isinstance(engine, dict) or engine.get("result") != "unverified":
            raise ValueError("unverified result required until evidence is admitted")
        engine_id = engine.get("id")
        if not isinstance(engine_id, str):
            raise ValueError("engine id must be a string")
        engine_ids.add(engine_id)
        for field in ("revision", "model", "quantization", "template", "cli", "hardware"):
            if not isinstance(engine.get(field), str) or not engine[field]:
                raise ValueError(f"missing tuple field: {field}")
    observations = document.get("admittedObservations")
    if not isinstance(observations, list) or len(observations) != 1:
        raise ValueError("exactly one admitted observation is required")
    for observation in observations:
        validate_observation(observation, engine_ids)


def require_string(record: dict[str, Any], field: str) -> str:
    value = record.get(field)
    if not isinstance(value, str) or not value:
        raise ValueError(f"missing observation field: {field}")
    return value


def validate_observation(observation: Any, engine_ids: set[str]) -> None:
    if not isinstance(observation, dict) or set(observation) != OBSERVATION_FIELDS:
        raise ValueError("observation fields must match the privacy-reviewed schema")
    if require_string(observation, "engine") not in engine_ids:
        raise ValueError("observation references an unknown engine")
    for field, expected in EXPECTED_OBSERVATION_VALUES.items():
        if require_string(observation, field) != expected:
            raise ValueError(f"observation differs from reviewed evidence: {field}")
    for field in (
        "engineBinarySha256",
        "engineRuntimeSha256",
        "cliBinarySha256",
        "modelManifestSha256",
        "modelBlobSha256",
        "templateBlobSha256",
    ):
        if not SHA256.fullmatch(require_string(observation, field)):
            raise ValueError(f"invalid observation digest: {field}")
    for field in ("engineRevision", "cliRevision"):
        if not REVISION.fullmatch(require_string(observation, field)):
            raise ValueError(f"invalid observation revision: {field}")
    validate_observation_claims(observation)
    validate_observation_source(observation.get("source"))


def validate_observation_claims(observation: dict[str, Any]) -> None:
    if observation.get("determinismClaimed") is not False:
        raise ValueError("determinism cannot be claimed without a staged seed")
    checks = observation.get("verifiedChecks")
    if (
        not isinstance(checks, list)
        or not all(isinstance(check, str) for check in checks)
        or len(checks) != len(VERIFIED_CHECKS)
        or set(checks) != VERIFIED_CHECKS
    ):
        raise ValueError("observation checks exceed or omit the verified boundary")
    limitations = observation.get("limitations")
    if (
        not isinstance(limitations, list)
        or not all(isinstance(limitation, str) for limitation in limitations)
        or len(limitations) != len(REQUIRED_LIMITATIONS)
        or set(limitations) != REQUIRED_LIMITATIONS
    ):
        raise ValueError("observation omits required evidence limitations")
    sampling = observation.get("sampling")
    if sampling != {
        "repeatPenalty": 1,
        "seed": None,
        "temperature": 0.6,
        "topK": 20,
        "topP": 0.95,
    }:
        raise ValueError("observation sampling settings are incomplete")


def validate_observation_source(source: Any) -> None:
    if not isinstance(source, dict) or set(source) != {
        "implementationCommit",
        "mergeCommit",
        "pullRequest",
    }:
        raise ValueError("observation source must contain only reviewed public revisions")
    if source != {
        "implementationCommit": "d32b0ec36f3d1fba49b0a28dcdd7325ffcdb64c5",
        "mergeCommit": "6fe010e5cb5734db940409f71e50933946dac8e5",
        "pullRequest": 204,
    }:
        raise ValueError("observation source differs from the reviewed public revisions")


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
