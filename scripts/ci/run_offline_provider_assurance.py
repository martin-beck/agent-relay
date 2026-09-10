# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Run deterministic provider assurance with no outbound network access."""

from __future__ import annotations

import argparse
import json
import os
import re
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, NoReturn, cast
from xml.etree import ElementTree

from bounded_subprocess import BoundedProcessError, run_bounded
from llm_cassette import load_cassette, replay
from verify_offline_provider_fixture import validate_fixture

ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = ROOT / "config/offline-provider-assurance-v1.json"
REVISION = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
EXPECTED_LABELS = {
    "deterministicReplay": "synthetic-offline-replay",
    "wireEmulator": "llm-wire-emulator",
    "localInference": "local-model",
    "liveProvider": "live-provider",
}
PROHIBITED_CONTENT = {
    "credentials",
    "model-reasoning",
    "network-addresses",
    "raw-prompts",
    "raw-transcripts",
    "system-paths",
    "unreviewed-cassettes",
}
MAX_SUMMARY_BYTES = 16_384


class AssuranceError(RuntimeError):
    """A classified assurance failure whose private command output is suppressed."""


@dataclass(frozen=True)
class CheckResult:
    id: str
    result: str


def fail(message: str) -> NoReturn:
    raise AssuranceError(message)


def load_json(path: Path) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise AssuranceError(f"invalid committed JSON: {path.name}") from error
    if not isinstance(document, dict):
        fail(f"committed JSON root is not an object: {path.name}")
    return cast(dict[str, Any], document)


def repository_path(value: Any) -> Path:
    if (
        not isinstance(value, str)
        or not value
        or Path(value).is_absolute()
        or ".." in Path(value).parts
    ):
        fail("assurance contract contains an unsafe repository path")
    path = ROOT / value
    if not path.is_file():
        fail("assurance contract references a missing repository file")
    return path


def validate_deterministic_tier(tier: Any) -> None:
    if not isinstance(tier, dict):
        fail("deterministic replay tier is missing")
    expected = {
        "network": "fresh-network-namespace-no-egress",
        "seed": 0,
        "virtualTime": True,
        "jobTimeoutMinutes": 45,
        "timeoutSeconds": 600,
        "maxOutputBytes": 65536,
        "gradleTask": ":provider:test-fixtures:test",
    }
    if any(tier.get(key) != value for key, value in expected.items()):
        fail("deterministic replay controls are incomplete")
    for field in ("fixture", "fixtureSchema", "cassette", "cassetteSchema"):
        repository_path(tier.get(field))
    if tier.get("testClasses") != [
        "dev.agentrelay.provider.testing.ScriptedRemoteRuntimeTest",
        "dev.agentrelay.provider.testing.ProviderProtocolReplaySuiteTest",
    ]:
        fail("shared runtime and provider protocol replay classes are incomplete")


def validate_emulator_tier(tier: Any) -> None:
    if not isinstance(tier, dict) or tier.get("status") != "not-admitted":
        fail("wire emulator must remain unadmitted without real-CLI evidence")
    if tier.get("scenarios") != [] or tier.get("candidate") != "mockagents":
        fail("wire emulator scenarios exceed the evidence admitted by AR-2223")
    if tier.get("license") != "Apache-2.0" or not REVISION.fullmatch(str(tier.get("revision", ""))):
        fail("wire emulator provenance or license is invalid")
    if not SHA256.fullmatch(str(tier.get("linuxAmd64Sha256", ""))):
        fail("wire emulator archive digest is invalid")


def validate_local_tier(local: Any) -> None:
    if not isinstance(local, dict):
        fail("local inference tier is missing")
    for field in ("workflow", "manifest"):
        repository_path(local.get(field))
    if (
        local.get("triggers") != ["schedule", "workflow_dispatch"]
        or local.get("runnerClasses") != ["cpu", "gpu"]
        or local.get("network") != "fresh-network-namespace-no-egress"
        or local.get("jobTimeoutMinutes") != 60
        or local.get("driverTimeoutSeconds") != 3000
        or local.get("maxEvidenceBytes") != 65536
        or local.get("maxCommandOutputBytes") != 65536
    ):
        fail("local inference scheduling or resource controls are incomplete")
    manifest = load_json(repository_path(local["manifest"]))
    if local.get("requiredEvidence") != manifest.get("required_evidence"):
        fail("local inference evidence contract has drifted")


def validate_live_tier(live: Any) -> None:
    if not isinstance(live, dict) or live.get("status") != "opt-in-release-only":
        fail("live-provider checks must remain release-scoped and opt-in")
    if (
        live.get("automatedByThisWorkflow") is not False
        or live.get("satisfiesFromMockOrLocal") != []
    ):
        fail("offline evidence cannot satisfy live-provider claims")
    if set(live.get("requiredClaims", [])) != {
        "authentication",
        "cloud-compatibility",
        "model-quality",
        "physical-device",
    }:
        fail("live-provider claim boundaries are incomplete")


def validate_toolchain(document: dict[str, Any]) -> None:
    toolchain = document.get("toolchain")
    if (
        not isinstance(toolchain, dict)
        or toolchain.get("policy") != "checksum-and-lockfile-verified"
        or toolchain.get("execution") != "repository-local-bootstrap"
    ):
        fail("toolchain policy is incomplete")
    for field in (
        "bootstrapManifest",
        "pythonLock",
        "gradleWrapper",
        "dependencyVerification",
    ):
        repository_path(toolchain.get(field))
    bootstrap = load_json(repository_path(toolchain["bootstrapManifest"]))
    artifacts = bootstrap.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        fail("bootstrap toolchain has no pinned artifacts")
    if not all(
        isinstance(item, dict) and SHA256.fullmatch(str(item.get("sha256", "")))
        for item in artifacts
    ):
        fail("bootstrap toolchain contains an unpinned artifact")


def validate_contract(document: dict[str, Any]) -> None:
    if document.get("schemaVersion") != 1 or document.get("evidenceLabels") != EXPECTED_LABELS:
        fail("unsupported assurance contract or evidence labels")
    validate_deterministic_tier(document.get("deterministicReplay"))
    validate_emulator_tier(document.get("wireEmulator"))
    validate_local_tier(document.get("localInference"))
    validate_live_tier(document.get("liveProvider"))
    validate_toolchain(document)
    artifacts = document.get("artifacts")
    if (
        not isinstance(artifacts, dict)
        or set(artifacts.get("prohibitedContent", [])) != PROHIBITED_CONTENT
        or artifacts.get("maxSummaryBytes") != 16384
        or artifacts.get("retentionDays") != 14
    ):
        fail("artifact privacy or retention policy is incomplete")
    updates = document.get("dependencyUpdates")
    if not isinstance(updates, dict) or set(updates.get("requirements", [])) != {
        "immutable-revision",
        "license-review",
        "release-notes-review",
        "sha256-verification",
        "full-assurance-rerun",
    }:
        fail("dependency update policy is incomplete")
    if document.get("publication") != {
        "failureFirst": True,
        "exactHeadRequired": True,
        "runnerConcurrency": "one-per-ref",
        "claimsRemainTiered": True,
    }:
        fail("publication policy is incomplete")


def require_network_namespace(parent_inode: str) -> None:
    if not parent_inode.isdecimal():
        fail("parent network namespace evidence is unavailable")
    try:
        current = os.stat("/proc/self/ns/net").st_ino
    except OSError as error:
        raise AssuranceError("network namespace evidence is unavailable") from error
    if current == int(parent_inode):
        fail("offline assurance must run in a fresh network namespace")
    if os.environ.get("AGENT_RELAY_OUTBOUND_NETWORK") != "deny":
        fail("outbound network policy must be deny")


def run_fixture_and_cassette(tier: dict[str, Any]) -> None:
    fixture = load_json(repository_path(tier["fixture"]))
    schema = load_json(repository_path(tier["fixtureSchema"]))
    validate_fixture(fixture, schema=schema)
    cassette = load_cassette(
        repository_path(tier["cassette"]), repository_path(tier["cassetteSchema"])
    )
    requests = [frame["body"] for frame in cassette["frames"] if frame["direction"] == "request"]
    responses = replay(cassette, requests)
    if len(responses) != len(requests):
        fail("cassette replay did not consume complete request-response pairs")


def run_gradle_replay(tier: dict[str, Any]) -> None:
    command = [
        str(ROOT / "gradlew"),
        "--offline",
        "--no-daemon",
        "--rerun-tasks",
        str(tier["gradleTask"]),
    ]
    for test_class in tier["testClasses"]:
        command.extend(("--tests", str(test_class)))
    environment = os.environ.copy()
    environment["AGENT_RELAY_REPLAY_SEED"] = str(tier["seed"])
    environment["AGENT_RELAY_VIRTUAL_TIME"] = "1"
    with tempfile.TemporaryDirectory(prefix="agent-relay-offline-provider-") as runtime_home:
        environment["HOME"] = runtime_home
        environment["ANDROID_USER_HOME"] = str(Path(runtime_home) / "android")
        environment["XDG_DATA_HOME"] = str(Path(runtime_home) / "xdg-data")
        try:
            completed = run_bounded(
                command,
                cwd=ROOT,
                env=environment,
                timeout_seconds=int(tier["timeoutSeconds"]),
                max_output_bytes=int(tier["maxOutputBytes"]),
            )
        except BoundedProcessError as error:
            raise AssuranceError(f"provider protocol replay {error}") from error
    if completed.returncode != 0:
        fail("provider protocol replay failed; private output was suppressed")


def write_junit(path: Path, checks: list[CheckResult]) -> None:
    suite = ElementTree.Element(
        "testsuite",
        name="offline-provider-assurance",
        tests=str(len(checks)),
        failures=str(sum(check.result == "failed" for check in checks)),
        skipped=str(sum(check.result == "skipped" for check in checks)),
    )
    for check in checks:
        case = ElementTree.SubElement(
            suite, "testcase", name=check.id, classname="offline.provider"
        )
        if check.result == "failed":
            ElementTree.SubElement(
                case,
                "failure",
                message="classified offline-provider assurance failure",
            ).text = "Private command output is intentionally suppressed."
        elif check.result == "skipped":
            ElementTree.SubElement(case, "skipped", message="earlier assurance gate failed")
    path.parent.mkdir(parents=True, exist_ok=True)
    ElementTree.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def summary_document(
    source_revision: str, checks: list[CheckResult], document: dict[str, Any] | None
) -> dict[str, Any]:
    passed = all(check.result == "passed" for check in checks)
    evidence_label = EXPECTED_LABELS["deterministicReplay"]
    network = "fresh-network-namespace-no-egress"
    seed = 0
    virtual_time = True
    if document is not None:
        evidence_label = document["evidenceLabels"]["deterministicReplay"]
        network = document["deterministicReplay"]["network"]
        seed = document["deterministicReplay"]["seed"]
        virtual_time = document["deterministicReplay"]["virtualTime"]
    return {
        "schemaVersion": 1,
        "evidenceLabel": evidence_label,
        "sourceRevision": source_revision,
        "network": network,
        "seed": seed,
        "virtualTime": virtual_time,
        "result": "passed" if passed else "failed",
        "checks": [asdict(check) for check in checks],
        "wireEmulator": {"result": "not-run", "reason": "no-added-coverage-proven"},
        "localInference": {"result": "separate-workflow"},
        "liveProvider": {"result": "not-run", "scope": "opt-in-release-only"},
        "limitations": [
            "authentication",
            "cloud-compatibility",
            "model-quality",
            "physical-device",
        ],
    }


def write_summary(path: Path, summary: dict[str, Any], max_bytes: int) -> None:
    encoded = (json.dumps(summary, sort_keys=True, separators=(",", ":")) + "\n").encode()
    if len(encoded) > max_bytes:
        fail("redacted assurance summary exceeds its size budget")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded)


def run(source_revision: str, parent_inode: str) -> tuple[dict[str, Any], list[CheckResult]]:
    if not REVISION.fullmatch(source_revision):
        fail("source revision must be an exact 40-character commit")
    document = load_json(CONTRACT_PATH)
    checks: list[CheckResult] = []
    try:
        validate_contract(document)
        require_network_namespace(parent_inode)
        run_fixture_and_cassette(document["deterministicReplay"])
        checks.append(CheckResult("fixture-schema-and-cassette-replay", "passed"))
        run_gradle_replay(document["deterministicReplay"])
        checks.append(CheckResult("shared-runtime-and-provider-protocol-replay", "passed"))
    except (AssertionError, AssuranceError, ValueError) as error:
        checks.append(CheckResult("assurance-gate", "failed"))
        raise AssuranceError("offline provider assurance failed closed") from error
    checks.append(CheckResult("tier-and-artifact-policy", "passed"))
    return document, checks


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--parent-netns-inode", required=True)
    parser.add_argument("--junit", type=Path, required=True)
    parser.add_argument("--summary", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    safe_revision = args.source_revision if REVISION.fullmatch(args.source_revision) else "0" * 40
    document: dict[str, Any] | None
    checks: list[CheckResult]
    try:
        document, checks = run(args.source_revision, args.parent_netns_inode)
        status = 0
    except Exception:
        document = None
        checks = [CheckResult("offline-provider-assurance", "failed")]
        status = 1
    write_junit(args.junit, checks)
    summary = summary_document(safe_revision, checks, document)
    write_summary(args.summary, summary, MAX_SUMMARY_BYTES)
    print(json.dumps(summary, sort_keys=True, separators=(",", ":")))
    return status


if __name__ == "__main__":
    raise SystemExit(main())
