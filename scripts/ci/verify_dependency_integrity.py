#!/usr/bin/env python3
"""Verify committed Gradle integrity metadata, locks, and OSV exceptions offline."""

from __future__ import annotations

import argparse
import datetime
import json
import re
import sys
import tomllib
import xml.etree.ElementTree as ElementTree
from collections import defaultdict
from pathlib import Path
from typing import Any

SHA256 = re.compile(r"[0-9a-f]{64}")
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
GRADLE_NAMESPACE = {"v": "https://schema.gradle.org/dependency-verification"}
LOCK_HEADER = (
    "# This is a Gradle generated file for dependency locking.",
    "# Manual edits can break the build and are not advised.",
    "# This file is expected to be part of source control.",
)


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


def verify_gradle_metadata(path: Path) -> None:
    root = ElementTree.parse(path).getroot()
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
    lowered = configuration.lower()
    return configuration.startswith("_internal-") or "test" in lowered or "lint" in lowered


def verify_osv_exceptions(
    path: Path,
    locked_configurations: dict[tuple[str, str], set[str]],
    today: datetime.date,
) -> None:
    parsed: dict[str, Any] = tomllib.loads(path.read_text(encoding="utf-8"))
    if set(parsed) != {"PackageOverrides"}:
        raise IntegrityError(f"{path}: only exact package overrides are allowed")
    overrides = parsed["PackageOverrides"]
    if not isinstance(overrides, list):
        raise IntegrityError(f"{path}: PackageOverrides must be a list")
    seen: set[tuple[str, str]] = set()
    for override in overrides:
        if set(override) != {
            "name",
            "version",
            "ecosystem",
            "vulnerability",
            "effectiveUntil",
            "reason",
        }:
            raise IntegrityError(f"{path}: OSV exceptions must be exact and fully documented")
        coordinate = (override["name"], override["version"])
        if coordinate in seen or coordinate not in locked_configurations:
            raise IntegrityError(f"{path}: duplicate or unlocked OSV exception {coordinate}")
        seen.add(coordinate)
        if override["ecosystem"] != "Maven" or override["vulnerability"] != {"ignore": True}:
            raise IntegrityError(f"{path}: {coordinate} must ignore only Maven vulnerabilities")
        expiry = override["effectiveUntil"]
        if not isinstance(
            expiry, datetime.date
        ) or not today <= expiry <= today + datetime.timedelta(days=120):
            raise IntegrityError(f"{path}: {coordinate} has an expired or overlong exception")
        if not isinstance(override["reason"], str) or len(override["reason"].strip()) < 20:
            raise IntegrityError(f"{path}: {coordinate} needs a reviewable reason")
        unsafe = sorted(
            configuration
            for configuration in locked_configurations[coordinate]
            if not is_assurance_only(configuration)
        )
        if unsafe:
            raise IntegrityError(f"{path}: {coordinate} is ignored in production: {unsafe}")


def verify_repository(root: Path, today: datetime.date | None = None) -> None:
    locked = read_locks(root, root / "config/dependency-lockfiles.txt")
    verify_gradle_metadata(root / "gradle/verification-metadata.xml")
    verify_scanner_release(root / "config/osv-scanner-release.json")
    verify_osv_exceptions(
        root / "config/osv-scanner.toml",
        locked,
        today or datetime.date.today(),
    )
    if not (root / "uv.lock").is_file():
        raise IntegrityError(f"{root / 'uv.lock'}: required OSV input is missing")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    arguments = parser.parse_args()
    try:
        verify_repository(arguments.root.resolve())
    except (IntegrityError, OSError, ValueError, ElementTree.ParseError) as failure:
        print(f"Dependency integrity verification failed: {failure}", file=sys.stderr)
        return 1
    print("Dependency integrity metadata, locks, scanner pin, and exceptions are valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
