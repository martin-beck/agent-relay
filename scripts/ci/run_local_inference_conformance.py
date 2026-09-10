# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Fail-closed launcher for scheduled local-inference conformance."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import tempfile
from hashlib import sha256
from math import isfinite
from pathlib import Path
from typing import Any, cast
from xml.etree import ElementTree

from bounded_subprocess import BoundedProcessError, run_bounded

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
STAGED_TUPLE_FIELDS = {
    "adapter",
    "cli",
    "cliDigest",
    "cliRevision",
    "cliVersion",
    "engine",
    "engineDigest",
    "engineRevision",
    "engineVersion",
    "hardwareClass",
    "model",
    "modelDigest",
    "protocol",
    "quantization",
    "sampling",
    "templateDigest",
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
MATRIX_OBSERVATION_FIELDS = {
    "adapter",
    "cli",
    "cliDigest",
    "cliRevision",
    "cliVersion",
    "configDigest",
    "determinismClaimed",
    "directApiChecks",
    "engine",
    "engineBackendRevision",
    "engineDigest",
    "engineRevision",
    "engineRuntimeDigest",
    "engineVersion",
    "evidenceLabel",
    "hardwareClass",
    "id",
    "limitations",
    "model",
    "modelDigest",
    "modelRevision",
    "network",
    "performanceSmoke",
    "protocol",
    "quantization",
    "result",
    "sampling",
    "source",
    "templateDigest",
    "verifiedChecks",
}
MATRIX_SOURCE = {
    "implementationCommit": "c697ec91bbe53abc808cbf275d13d2e0dd610ec4",
    "mergeCommit": "130b947e6e42225b63977fe4e96db4730d7d1c7e",
    "pullRequest": 212,
}
MATRIX_OBSERVATION_DIGESTS = {
    "aider-vllm-qwen3-0.6b-float32-cpu-v1": (
        "42635a1e21cc2dd4413337f0d0d63a5e956824b7afca5600f87e7d54c39f914c"
    ),
    "opencode-llamacpp-qwen3-0.6b-q4-k-m-cpu-v1": (
        "f00a592a8d9c0d78be37b0439422da66813474d2d9cad95c9469c2c5ea94a961"
    ),
    "opencode-localai-qwen3-0.6b-q4-k-m-cpu-v1": (
        "db671df281bba3d9184fc0a8b95e23138fd2ddd33acfb47973b2e527fa0473dc"
    ),
    "opencode-ollama-qwen3-0.6b-q4-k-m-cpu-v2": (
        "b0ff59a635fe38e9b5535f7d04fee6ed2a6b2dedfaddbe0935bc066901159035"
    ),
    "opencode-vllm-qwen3-0.6b-float32-cpu-v1": (
        "1d8f0a6e20242c7e8894d8498170ffbe6bd82c59595671fcc4ecf1bf1fcab951"
    ),
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
    validate_matrix_observations(document.get("matrixObservations"), engine_ids, document)


def validate_matrix_observations(
    observations: Any, engine_ids: set[str], document: dict[str, Any]
) -> None:
    if not isinstance(observations, list) or len(observations) != len(MATRIX_OBSERVATION_DIGESTS):
        raise ValueError("the complete five-tuple matrix observation set is required")
    observed_ids: set[str] = set()
    for observation in observations:
        observation_id = validate_matrix_observation(observation, engine_ids, document)
        if observation_id in observed_ids:
            raise ValueError("matrix observation ids must be unique")
        observed_ids.add(observation_id)
    if observed_ids != set(MATRIX_OBSERVATION_DIGESTS):
        raise ValueError("matrix observation ids differ from the reviewed evidence")


def validate_matrix_observation(
    observation: Any, engine_ids: set[str], document: dict[str, Any]
) -> str:
    if not isinstance(observation, dict) or set(observation) != MATRIX_OBSERVATION_FIELDS:
        raise ValueError("matrix observation fields must match the privacy-reviewed schema")
    observation_id = require_string(observation, "id")
    if require_string(observation, "engine") not in engine_ids:
        raise ValueError("matrix observation references an unknown engine")
    validate_matrix_claims(observation, set(document.get("checks", [])))
    validate_matrix_provenance(observation)
    expected_digest = MATRIX_OBSERVATION_DIGESTS.get(observation_id)
    actual_digest = sha256(
        json.dumps(observation, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    if actual_digest != expected_digest:
        raise ValueError("matrix observation differs from the reviewed evidence")
    return observation_id


def validate_matrix_claims(observation: dict[str, Any], declared_checks: set[Any]) -> None:
    if observation.get("evidenceLabel") != "local-model" or observation.get("result") != "passed":
        raise ValueError("matrix observation has an invalid evidence label or result")
    if observation.get("determinismClaimed") is not False:
        raise ValueError("matrix observation cannot claim determinism")
    if observation.get("hardwareClass") != "self-hosted-cpu-x86_64":
        raise ValueError("matrix observation has an unreviewed hardware class")
    if observation.get("network") != "fresh-netns-loopback-only-no-default-route":
        raise ValueError("matrix observation lacks the reviewed network isolation")
    validate_matrix_check_lists(observation, declared_checks)
    limitations = require_unique_strings(observation.get("limitations"), "limitations")
    if (
        not {
            "approval",
            "malformed",
            "timeout",
            "comparative-performance",
        }
        <= limitations
    ):
        raise ValueError("matrix observation omits required limitations")


def validate_matrix_check_lists(observation: dict[str, Any], declared_checks: set[Any]) -> None:
    verified = require_unique_strings(observation.get("verifiedChecks"), "checks")
    direct = require_unique_strings(observation.get("directApiChecks"), "checks", allow_empty=True)
    if not verified <= declared_checks or not direct <= declared_checks:
        raise ValueError("matrix observation checks exceed the declared boundary")
    if verified & direct:
        raise ValueError("direct API checks cannot be claimed as adapter checks")


def require_unique_strings(values: Any, label: str, *, allow_empty: bool = False) -> set[str]:
    if not isinstance(values, list) or (not values and not allow_empty):
        raise ValueError(f"matrix observation {label} must be unique strings")
    if not all(isinstance(value, str) and value for value in values):
        raise ValueError(f"matrix observation {label} must be unique strings")
    result = set(cast(list[str], values))
    if len(result) != len(values):
        raise ValueError(f"matrix observation {label} must be unique strings")
    return result


def validate_matrix_provenance(observation: dict[str, Any]) -> None:
    for field in (
        "cliDigest",
        "configDigest",
        "engineDigest",
        "engineRuntimeDigest",
        "modelDigest",
        "templateDigest",
    ):
        if not SHA256.fullmatch(require_string(observation, field)):
            raise ValueError(f"invalid matrix observation digest: {field}")
    for field in ("cliRevision", "engineBackendRevision", "engineRevision", "modelRevision"):
        revision = observation.get(field)
        if revision is not None and (
            not isinstance(revision, str) or not REVISION.fullmatch(revision)
        ):
            raise ValueError(f"invalid matrix observation revision: {field}")
    if observation.get("source") != MATRIX_SOURCE:
        raise ValueError("matrix observation source differs from reviewed public revisions")
    validate_performance_smoke(observation.get("performanceSmoke"))


def validate_performance_smoke(smoke: Any) -> None:
    if smoke is None:
        return
    if not isinstance(smoke, dict) or set(smoke) != {
        "comparisonClaimed",
        "completionTokens",
        "medianSeconds",
        "networkIsolation",
    }:
        raise ValueError("matrix performance smoke fields are invalid")
    validate_performance_smoke_values(smoke)


def validate_performance_smoke_values(smoke: dict[str, Any]) -> None:
    tokens = smoke.get("completionTokens")
    if smoke.get("comparisonClaimed") is not False or smoke.get("networkIsolation") != "not-proven":
        raise ValueError("matrix performance smoke exceeds the reviewed evidence boundary")
    median = smoke.get("medianSeconds")
    if (
        isinstance(median, bool)
        or not isinstance(median, (int, float))
        or not isfinite(median)
        or median <= 0
    ):
        raise ValueError("matrix performance smoke exceeds the reviewed evidence boundary")
    if not isinstance(tokens, list) or len(tokens) != 3:
        raise ValueError("matrix performance smoke exceeds the reviewed evidence boundary")
    if not all(
        isinstance(token, int) and not isinstance(token, bool) and token > 0 for token in tokens
    ):
        raise ValueError("matrix performance smoke exceeds the reviewed evidence boundary")


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


def require_execution_gates() -> dict[str, Any]:
    if os.environ.get("AGENT_RELAY_LOCAL_INFERENCE_ENABLE") != "1":
        raise ConformanceBlocked("explicit local-inference enable flag is required")
    if os.environ.get("AGENT_RELAY_OUTBOUND_NETWORK", "deny") != "deny":
        raise ConformanceBlocked("outbound network must remain denied")
    return load_staged_tuple()


def load_staged_tuple() -> dict[str, Any]:
    raw_tuple = os.environ.get("AGENT_RELAY_LOCAL_INFERENCE_TUPLE_JSON", "")
    try:
        staged = json.loads(raw_tuple)
    except json.JSONDecodeError as error:
        raise ConformanceBlocked("staged tuple provenance must be valid JSON") from error
    if not isinstance(staged, dict) or set(staged) != STAGED_TUPLE_FIELDS:
        raise ConformanceBlocked("staged tuple provenance fields do not match the schema")
    for field in (
        "adapter",
        "cli",
        "cliVersion",
        "engine",
        "engineVersion",
        "model",
        "protocol",
        "quantization",
    ):
        if not isinstance(staged[field], str) or not staged[field]:
            raise ConformanceBlocked(f"staged tuple provenance is missing {field}")
    for field in ("cliDigest", "engineDigest", "modelDigest", "templateDigest"):
        if not isinstance(staged[field], str) or not SHA256.fullmatch(staged[field]):
            raise ConformanceBlocked(f"staged tuple provenance has invalid {field}")
    for field in ("cliRevision", "engineRevision"):
        if not isinstance(staged[field], str) or not REVISION.fullmatch(staged[field]):
            raise ConformanceBlocked(f"staged tuple provenance has invalid {field}")
    validate_hardware(staged["hardwareClass"])
    validate_sampling(staged["sampling"])
    return staged


def validate_hardware(hardware: Any) -> None:
    expected_hardware = os.environ.get("AGENT_RELAY_HARDWARE_CLASS")
    if expected_hardware not in {
        "self-hosted-cpu-x86_64",
        "self-hosted-gpu-x86_64",
    }:
        raise ConformanceBlocked("an explicit supported hardware class is required")
    if hardware != expected_hardware:
        raise ConformanceBlocked("staged tuple hardware does not match the selected runner class")


def validate_sampling(sampling: Any) -> None:
    if not isinstance(sampling, dict) or not sampling:
        raise ConformanceBlocked("sampling settings must be a non-empty object")
    for name, value in sampling.items():
        if not isinstance(name, str) or not name:
            raise ConformanceBlocked("sampling setting names must be non-empty strings")
        if value is not None and not isinstance(value, (str, int, float, bool)):
            raise ConformanceBlocked("sampling setting values must be JSON scalars")
        if isinstance(value, float) and not isfinite(value):
            raise ConformanceBlocked("sampling setting numbers must be finite")


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


def validate_driver_evidence(
    evidence: dict[str, Any], document: dict[str, Any], staged: dict[str, Any]
) -> None:
    required = STAGED_TUPLE_FIELDS | {
        "checks",
        "evidenceLabel",
        "failureClass",
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
    validate_driver_provenance(evidence, staged)


def validate_driver_result(evidence: dict[str, Any], document: dict[str, Any]) -> None:
    checks = evidence["checks"]
    declared_checks = set(document["checks"])
    if (
        not isinstance(checks, list)
        or not checks
        or not all(isinstance(check, str) for check in checks)
        or len(checks) != len(set(checks))
        or not set(checks) <= declared_checks
        or "teardown" not in checks
    ):
        raise ConformanceBlocked("driver evidence contains invalid checks")
    if evidence["result"] not in {"passed", "failed"}:
        raise ConformanceBlocked("driver evidence has an invalid result")
    failure_class = evidence["failureClass"]
    if evidence["result"] == "passed" and failure_class is not None:
        raise ConformanceBlocked("passed evidence cannot contain a failure class")
    if evidence["result"] == "failed" and failure_class not in ALLOWED_FAILURE_CLASSES:
        raise ConformanceBlocked("failed evidence requires a classified failure")


def validate_driver_provenance(evidence: dict[str, Any], staged: dict[str, Any]) -> None:
    for field, value in staged.items():
        if evidence.get(field) != value:
            raise ConformanceBlocked(f"driver evidence does not match staged provenance: {field}")


def execute_driver(
    command: list[str], environment: dict[str, str], timeout: int
) -> subprocess.CompletedProcess[bytes]:
    try:
        return run_bounded(
            command,
            env=environment,
            timeout_seconds=timeout,
            max_output_bytes=MAX_COMMAND_OUTPUT_BYTES,
        )
    except BoundedProcessError as error:
        if "time budget" in str(error):
            message = "the conformance driver exceeded its time budget"
        else:
            message = "the conformance driver exceeded its output budget"
        raise ConformanceBlocked(message) from error


def run() -> dict[str, Any]:
    document = load_manifest()
    validate_manifest(document)
    staged = require_execution_gates()
    require_network_namespace()
    command = load_driver_command()
    try:
        requested_timeout = int(os.environ.get("AGENT_RELAY_LOCAL_INFERENCE_TIMEOUT", "1800"))
    except ValueError as error:
        raise ConformanceBlocked("the conformance timeout must be an integer") from error
    timeout = min(max(requested_timeout, 1), 3600)
    with tempfile.TemporaryDirectory(prefix="agent-relay-local-inference-") as temporary:
        evidence_path = Path(temporary) / "evidence.json"
        environment = os.environ.copy()
        environment["AGENT_RELAY_LOCAL_INFERENCE_EVIDENCE"] = str(evidence_path)
        # The command is an explicit trusted-runner input, parsed as argv and
        # constrained to an absolute executable; no shell is involved.
        completed = execute_driver(command, environment, timeout)
        if completed.returncode != 0:
            raise ConformanceBlocked("the conformance driver failed; private output was suppressed")
        evidence = read_evidence(evidence_path)
    validate_driver_evidence(evidence, document, staged)
    return evidence


def write_junit(evidence: dict[str, Any], path: Path) -> None:
    """Write a bounded result-only JUnit report without model content."""
    checks = cast(list[str], evidence["checks"])
    suite = ElementTree.Element(
        "testsuite",
        name="local-inference-conformance",
        tests=str(len(checks)),
        failures="0" if evidence["result"] == "passed" else "1",
    )
    for index, check in enumerate(checks):
        case = ElementTree.SubElement(
            suite,
            "testcase",
            name=check,
            classname="local.inference",
        )
        if evidence["result"] == "failed" and index == 0:
            ElementTree.SubElement(
                case,
                "failure",
                message=str(evidence["failureClass"]),
            ).text = "Private model and command output is intentionally suppressed."
    path.parent.mkdir(parents=True, exist_ok=True)
    ElementTree.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--junit", type=Path)
    return parser.parse_args()


def main() -> int:
    arguments = parse_args()
    try:
        result = run()
    except Exception:
        result = {
            "schemaVersion": 1,
            "evidenceLabel": "local-model",
            "result": "failed",
            "failureClass": "infrastructure",
            "checks": ["conformance-gate"],
        }
    if arguments.junit is not None:
        write_junit(result, arguments.junit)
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0 if result["result"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
