# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Fail-closed launcher for scheduled local-inference conformance."""

from __future__ import annotations

import json
import os
import re
import subprocess
import tempfile
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "config/local-inference-conformance-v1.json"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
REVISION = re.compile(r"^[0-9a-f]{40}$")
MAX_EVIDENCE_BYTES = 64 * 1024
MAX_COMMAND_OUTPUT_BYTES = 64 * 1024
ALLOWED_FAILURE_CLASSES = {
    "agent-protocol",
    "api-shape",
    "infrastructure",
    "model-behavior",
    "performance",
}
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
    ):
        value = os.environ.get(variable, "")
        if not SHA256.fullmatch(value):
            raise ConformanceBlocked(f"missing provenance: {variable}")
    if not os.environ.get("AGENT_RELAY_ADAPTER"):
        raise ConformanceBlocked("missing provenance: AGENT_RELAY_ADAPTER")
    if os.environ.get("AGENT_RELAY_HARDWARE_CLASS") not in {
        "self-hosted-cpu-x86_64",
        "self-hosted-gpu-x86_64",
    }:
        raise ConformanceBlocked("an explicit supported hardware class is required")


def require_network_namespace() -> None:
    parent = os.environ.get("AGENT_RELAY_PARENT_NETNS_INODE", "")
    if not parent.isdecimal():
        raise ConformanceBlocked("parent network namespace evidence is unavailable")
    try:
        current = os.stat("/proc/self/ns/net").st_ino
    except OSError as error:
        raise ConformanceBlocked("network namespace evidence is unavailable") from error
    if current == int(parent):
        raise ConformanceBlocked("the conformance driver must run in an isolated network namespace")


def load_driver_command() -> list[str]:
    raw = os.environ.get("AGENT_RELAY_LOCAL_INFERENCE_COMMAND_JSON", "")
    try:
        command = json.loads(raw)
    except json.JSONDecodeError as error:
        raise ConformanceBlocked("the driver command must be a JSON string array") from error
    if (
        not isinstance(command, list)
        or not command
        or not all(isinstance(item, str) and item for item in command)
    ):
        raise ConformanceBlocked("the driver command must be a non-empty JSON string array")
    executable = Path(command[0])
    if (
        not executable.is_absolute()
        or not executable.is_file()
        or not os.access(executable, os.X_OK)
    ):
        raise ConformanceBlocked("the driver executable must be an absolute executable file")
    return command


def read_evidence(path: Path) -> dict[str, Any]:
    try:
        size = path.stat().st_size
    except OSError as error:
        raise ConformanceBlocked("the conformance driver did not produce evidence") from error
    if size <= 0 or size > MAX_EVIDENCE_BYTES:
        raise ConformanceBlocked("driver evidence exceeds the bounded size")
    try:
        evidence = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ConformanceBlocked("driver evidence is not valid bounded JSON") from error
    if not isinstance(evidence, dict):
        raise ConformanceBlocked("driver evidence root must be an object")
    return evidence


def validate_driver_evidence(evidence: dict[str, Any], document: dict[str, Any]) -> None:
    required = {
        "adapter",
        "checks",
        "cli",
        "cliDigest",
        "engine",
        "engineDigest",
        "evidenceLabel",
        "failureClass",
        "hardwareClass",
        "model",
        "modelDigest",
        "result",
        "schemaVersion",
    }
    if set(evidence) != required:
        raise ConformanceBlocked("driver evidence fields do not match the redacted schema")
    if evidence["schemaVersion"] != 1 or evidence["evidenceLabel"] != "local-model":
        raise ConformanceBlocked("driver evidence has an invalid schema or label")
    engine_ids = {engine["id"] for engine in document["engines"]}
    if evidence["engine"] not in engine_ids:
        raise ConformanceBlocked("driver evidence names an undeclared engine")
    validate_driver_result(evidence, document)
    validate_driver_provenance(evidence)


def validate_driver_result(evidence: dict[str, Any], document: dict[str, Any]) -> None:
    checks = evidence["checks"]
    declared_checks = set(document["checks"])
    if not isinstance(checks, list) or not checks or not set(checks) <= declared_checks:
        raise ConformanceBlocked("driver evidence contains invalid checks")
    if evidence["result"] not in {"passed", "failed"}:
        raise ConformanceBlocked("driver evidence has an invalid result")
    failure_class = evidence["failureClass"]
    if evidence["result"] == "passed" and failure_class is not None:
        raise ConformanceBlocked("passed evidence cannot contain a failure class")
    if evidence["result"] == "failed" and failure_class not in ALLOWED_FAILURE_CLASSES:
        raise ConformanceBlocked("failed evidence requires a classified failure")


def validate_driver_provenance(evidence: dict[str, Any]) -> None:
    expected = {
        "adapter": os.environ["AGENT_RELAY_ADAPTER"],
        "cliDigest": os.environ["AGENT_RELAY_CLI_DIGEST"],
        "engineDigest": os.environ["AGENT_RELAY_ENGINE_DIGEST"],
        "hardwareClass": os.environ["AGENT_RELAY_HARDWARE_CLASS"],
        "modelDigest": os.environ["AGENT_RELAY_MODEL_DIGEST"],
    }
    for field, value in expected.items():
        if evidence[field] != value:
            raise ConformanceBlocked(f"driver evidence does not match staged provenance: {field}")
    for field in ("cli", "model"):
        if not isinstance(evidence[field], str) or not evidence[field]:
            raise ConformanceBlocked(f"driver evidence is missing {field}")


def run() -> dict[str, Any]:
    document = load_manifest()
    validate_manifest(document)
    require_execution_gates()
    require_network_namespace()
    command = load_driver_command()
    timeout = min(max(int(os.environ.get("AGENT_RELAY_LOCAL_INFERENCE_TIMEOUT", "1800")), 1), 3600)
    with tempfile.TemporaryDirectory(prefix="agent-relay-local-inference-") as temporary:
        evidence_path = Path(temporary) / "evidence.json"
        environment = os.environ.copy()
        environment["AGENT_RELAY_LOCAL_INFERENCE_EVIDENCE"] = str(evidence_path)
        try:
            # The command is an explicit trusted-runner input, parsed as argv and
            # constrained to an absolute executable; no shell is involved.
            completed = subprocess.run(  # noqa: S603
                command,
                check=False,
                capture_output=True,
                env=environment,
                text=False,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired as error:
            raise ConformanceBlocked("the conformance driver exceeded its time budget") from error
        if (
            len(completed.stdout) > MAX_COMMAND_OUTPUT_BYTES
            or len(completed.stderr) > MAX_COMMAND_OUTPUT_BYTES
        ):
            raise ConformanceBlocked("the conformance driver exceeded its output budget")
        if completed.returncode != 0:
            raise ConformanceBlocked("the conformance driver failed; private output was suppressed")
        evidence = read_evidence(evidence_path)
    validate_driver_evidence(evidence, document)
    return evidence


if __name__ == "__main__":
    print(json.dumps(run(), sort_keys=True, separators=(",", ":")))
