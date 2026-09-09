#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Verify committed Gradle integrity metadata, locks, and OSV exceptions offline."""

from __future__ import annotations

import argparse
import datetime
import json
import re
import sys
import tomllib
from collections import defaultdict
from pathlib import Path
from typing import Any

from defusedxml import ElementTree  # type: ignore[import-untyped]
from defusedxml.common import DefusedXmlException  # type: ignore[import-untyped]

SHA256 = re.compile(r"[0-9a-f]{64}")
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
VULNERABILITY_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]+")
GRADLE_NAMESPACE = {"v": "https://schema.gradle.org/dependency-verification"}
LOCK_HEADER = (
    "# This is a Gradle generated file for dependency locking.",
    "# Manual edits can break the build and are not advised.",
    "# This file is expected to be part of source control.",
)
ROOT_LOCKFILE = Path("gradle.lockfile")
NETTY_VERSION = re.compile(r"(\d+)\.(\d+)\.(\d+)\.Final")
MINIMUM_SAFE_NETTY = (4, 1, 137)


class IntegrityError(RuntimeError):
    """Committed dependency integrity state is incomplete or unsafe."""


def read_lock_manifest(path: Path) -> tuple[Path, ...]:
    entries = tuple(
        Path(line.strip())
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    )
    if not entries or len(entries) != len(set(entries)):
        raise IntegrityError(f"{path}: lockfile manifest must be nonempty and unique")
    if tuple(sorted(entries)) != entries:
        raise IntegrityError(f"{path}: lockfile manifest must be sorted")
    if ROOT_LOCKFILE not in entries:
        raise IntegrityError(f"{path}: root project {ROOT_LOCKFILE} is required")
    return entries


def read_locks(root: Path, manifest: Path) -> dict[tuple[str, str], set[str]]:
    expected = read_lock_manifest(manifest)
    actual = tuple(
        sorted(
            path.relative_to(root)
            for path in root.rglob("*gradle.lockfile")
            if not any(part in {".gradle", "build"} for part in path.relative_to(root).parts)
        )
    )
    if actual != expected:
        missing = sorted(set(expected) - set(actual))
        extra = sorted(set(actual) - set(expected))
        raise IntegrityError(f"Gradle lockfile manifest differs: missing={missing}, extra={extra}")

    configurations: dict[tuple[str, str], set[str]] = defaultdict(set)
    for relative_path in expected:
        path = root / relative_path
        lines = path.read_text(encoding="utf-8").splitlines()
        if tuple(lines[:3]) != LOCK_HEADER:
            raise IntegrityError(f"{path}: invalid Gradle lock header")
        for line_number, line in enumerate(lines[3:], 4):
            coordinate, separator, configuration_list = line.partition("=")
            if not separator or not coordinate or not configuration_list:
                raise IntegrityError(f"{path}:{line_number}: invalid lock entry")
            if coordinate == "empty":
                continue
            parts = coordinate.rsplit(":", 2)
            if len(parts) != 3 or any(not part for part in parts):
                raise IntegrityError(f"{path}:{line_number}: invalid Maven coordinate")
            package = f"{parts[0]}:{parts[1]}"
            configurations[(package, parts[2])].update(configuration_list.split(","))
    return configurations


def verify_netty_versions(locked: dict[tuple[str, str], set[str]]) -> None:
    for package, version in locked:
        if not package.startswith("io.netty:"):
            continue
        match = NETTY_VERSION.fullmatch(version)
        parsed = tuple(int(part) for part in match.groups()) if match is not None else None
        if parsed is None or parsed < MINIMUM_SAFE_NETTY:
            raise IntegrityError(f"{package}:{version}: Netty must be at least 4.1.137.Final")


def verify_gradle_metadata(path: Path) -> None:
    try:
        root = ElementTree.parse(path).getroot()
    except (DefusedXmlException, ElementTree.ParseError, OSError) as failure:
        raise IntegrityError(f"{path}: could not parse verification metadata") from failure
    metadata = root.findall(".//v:artifact", GRADLE_NAMESPACE)
    if not metadata:
        raise IntegrityError(f"{path}: no verified artifacts")
    configuration = root.find("v:configuration", GRADLE_NAMESPACE)
    if configuration is None:
        raise IntegrityError(f"{path}: missing verification configuration")
    values = {child.tag.rsplit("}", 1)[-1]: child.text for child in configuration}
    if values != {"verify-metadata": "true", "verify-signatures": "false"}:
        raise IntegrityError(f"{path}: unexpected verification configuration {values}")
    for artifact in metadata:
        checksums = list(artifact)
        if not checksums or any(item.tag.rsplit("}", 1)[-1] != "sha256" for item in checksums):
            raise IntegrityError(f"{path}: every artifact must use SHA-256 only")
        if any(SHA256.fullmatch(item.attrib.get("value", "")) is None for item in checksums):
            raise IntegrityError(f"{path}: invalid SHA-256 value")


def verify_scanner_release(path: Path) -> None:
    release = json.loads(path.read_text(encoding="utf-8"))
    if set(release) != {"version", "linux_amd64_url", "linux_amd64_sha256"}:
        raise IntegrityError(f"{path}: unexpected release fields")
    version = release["version"]
    expected_url = (
        "https://github.com/google/osv-scanner/releases/download/"
        f"v{version}/osv-scanner_linux_amd64"
    )
    if not isinstance(version, str) or VERSION.fullmatch(version) is None:
        raise IntegrityError(f"{path}: OSV-Scanner version must be exact")
    if release["linux_amd64_url"] != expected_url:
        raise IntegrityError(f"{path}: OSV-Scanner URL must match the pinned version")
    if SHA256.fullmatch(release["linux_amd64_sha256"]) is None:
        raise IntegrityError(f"{path}: OSV-Scanner binary needs an exact SHA-256")


def is_assurance_only(configuration: str) -> bool:
    assurance_configuration = re.search(
        r"(?:AndroidTest|UnitTest|Test|Lint)(?=[A-Z]|$)", configuration
    )
    top_level_assurance = re.fullmatch(r"(?:test|lint)(?:[A-Z].*)?", configuration)
    return (
        configuration.startswith("_internal-")
        or assurance_configuration is not None
        or top_level_assurance is not None
    )


OsvFinding = tuple[str, str, str, str]


def read_osv_policy_document(path: Path) -> tuple[Any, Any, Any]:
    parsed = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(parsed, dict) or set(parsed) != {
        "ignore_until",
        "reason",
        "accepted_findings",
    }:
        raise IntegrityError(f"{path}: unexpected OSV acceptance policy fields")
    reason = parsed["reason"]
    if not isinstance(reason, str) or len(reason.strip()) < 20:
        raise IntegrityError(f"{path}: OSV policy needs a reviewable reason")
    records = parsed["accepted_findings"]
    if not isinstance(records, list) or not records:
        raise IntegrityError(f"{path}: accepted_findings must be a nonempty list")
    return parsed["ignore_until"], reason, records


def parse_osv_policy_expiry(path: Path, value: Any, today: datetime.date) -> datetime.date:
    try:
        expiry = datetime.date.fromisoformat(value)
    except (TypeError, ValueError) as failure:
        raise IntegrityError(f"{path}: OSV policy expiry must be an ISO date") from failure
    if not today <= expiry <= today + datetime.timedelta(days=120):
        raise IntegrityError(f"{path}: OSV policy is expired or overlong")
    return expiry


def parse_accepted_finding(path: Path, record: Any) -> OsvFinding:
    if not isinstance(record, dict) or set(record) != {
        "id",
        "ecosystem",
        "name",
        "version",
    }:
        raise IntegrityError(f"{path}: every accepted finding must be exact")
    finding = (record["id"], record["ecosystem"], record["name"], record["version"])
    if not all(isinstance(value, str) and value for value in finding):
        raise IntegrityError(f"{path}: accepted finding fields must be nonempty strings")
    if VULNERABILITY_ID.fullmatch(finding[0]) is None or finding[1] != "Maven":
        raise IntegrityError(f"{path}: accepted finding must name an exact Maven vulnerability")
    return finding


def verify_accepted_finding_scope(
    path: Path,
    finding: OsvFinding,
    locked_configurations: dict[tuple[str, str], set[str]],
) -> None:
    coordinate = (finding[2], finding[3])
    if coordinate not in locked_configurations:
        raise IntegrityError(f"{path}: accepted finding is not locked: {finding}")
    unsafe = sorted(
        configuration
        for configuration in locked_configurations[coordinate]
        if not is_assurance_only(configuration)
    )
    if unsafe:
        raise IntegrityError(f"{path}: {coordinate} is ignored in production: {unsafe}")


def read_osv_policy(
    path: Path,
    locked_configurations: dict[tuple[str, str], set[str]],
    today: datetime.date,
) -> tuple[set[OsvFinding], dict[str, tuple[datetime.date, str]]]:
    expiry_value, reason, records = read_osv_policy_document(path)
    expiry = parse_osv_policy_expiry(path, expiry_value, today)
    findings: list[OsvFinding] = []
    for record in records:
        finding = parse_accepted_finding(path, record)
        verify_accepted_finding_scope(path, finding, locked_configurations)
        findings.append(finding)
    if findings != sorted(set(findings)):
        raise IntegrityError(f"{path}: accepted findings must be unique and sorted")
    ids = {finding[0] for finding in findings}
    return set(findings), dict.fromkeys(ids, (expiry, reason))


def verify_osv_exceptions(
    path: Path,
    expected: dict[str, tuple[datetime.date, str]],
) -> None:
    parsed: dict[str, Any] = tomllib.loads(path.read_text(encoding="utf-8"))
    if set(parsed) != {"IgnoredVulns"}:
        raise IntegrityError(f"{path}: only ID-specific IgnoredVulns are allowed")
    exceptions = parsed["IgnoredVulns"]
    if not isinstance(exceptions, list):
        raise IntegrityError(f"{path}: IgnoredVulns must be a list")
    actual: dict[str, tuple[datetime.date, str]] = {}
    for exception in exceptions:
        if not isinstance(exception, dict) or set(exception) != {"id", "ignoreUntil", "reason"}:
            raise IntegrityError(f"{path}: OSV exceptions must be exact and fully documented")
        vulnerability_id = exception["id"]
        if (
            not isinstance(vulnerability_id, str)
            or VULNERABILITY_ID.fullmatch(vulnerability_id) is None
        ):
            raise IntegrityError(f"{path}: invalid vulnerability ID")
        if vulnerability_id in actual:
            raise IntegrityError(f"{path}: duplicate vulnerability ID {vulnerability_id}")
        actual[vulnerability_id] = (exception["ignoreUntil"], exception["reason"])
    if actual != expected:
        raise IntegrityError(
            f"{path}: ignored IDs, expiry, or reason differ from acceptance policy"
        )


def verify_osv_findings(path: Path, accepted: set[OsvFinding]) -> None:
    parsed = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(parsed, dict) or not isinstance(parsed.get("results"), list):
        raise IntegrityError(f"{path}: invalid OSV-Scanner JSON report")
    findings: set[OsvFinding] = set()
    try:
        for result in parsed["results"]:
            for package_result in result.get("packages", []):
                package = package_result["package"]
                for vulnerability in package_result.get("vulnerabilities", []):
                    findings.add(
                        (
                            vulnerability["id"],
                            package["ecosystem"],
                            package["name"],
                            package["version"],
                        )
                    )
    except (KeyError, TypeError) as failure:
        raise IntegrityError(f"{path}: malformed OSV-Scanner finding") from failure
    unexpected = sorted(findings - accepted)
    missing = sorted(accepted - findings)
    if unexpected or missing:
        raise IntegrityError(
            f"{path}: OSV findings differ from reviewed exact tuples: "
            f"unexpected={unexpected}, missing={missing}"
        )


def verify_repository(root: Path, today: datetime.date | None = None) -> None:
    locked = read_locks(root, root / "config/dependency-lockfiles.txt")
    verify_netty_versions(locked)
    verify_gradle_metadata(root / "gradle/verification-metadata.xml")
    verify_scanner_release(root / "config/osv-scanner-release.json")
    _, expected_exceptions = read_osv_policy(
        root / "config/osv-accepted-vulnerabilities.json",
        locked,
        today or datetime.date.today(),
    )
    verify_osv_exceptions(
        root / "config/osv-scanner.toml",
        expected_exceptions,
    )
    if not (root / "uv.lock").is_file():
        raise IntegrityError(f"{root / 'uv.lock'}: required OSV input is missing")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--osv-results", type=Path)
    arguments = parser.parse_args()
    try:
        root = arguments.root.resolve()
        verify_repository(root)
        if arguments.osv_results is not None:
            locked = read_locks(root, root / "config/dependency-lockfiles.txt")
            accepted, _ = read_osv_policy(
                root / "config/osv-accepted-vulnerabilities.json",
                locked,
                datetime.date.today(),
            )
            verify_osv_findings(arguments.osv_results, accepted)
    except (IntegrityError, OSError, ValueError, ElementTree.ParseError) as failure:
        print(f"Dependency integrity verification failed: {failure}", file=sys.stderr)
        return 1
    print("Dependency integrity metadata, locks, scanner pin, and exceptions are valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
