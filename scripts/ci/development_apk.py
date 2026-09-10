#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Create and verify privacy-safe metadata for an unsupported development APK."""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import re
import shutil
import subprocess
from collections.abc import Sequence
from pathlib import Path
from typing import Any

APPLICATION_ID = "com.example.agentrelay"
BASE_VERSION = "0.1.0"
BUILD_TYPE = "debug"
MINIMUM_SDK = 28
TARGET_SDK = 36
VERSION_CODE_BASE = 1_000_000
VERSION_POLICY = "main-first-parent-count-v1"
SCHEMA_VERSION = 1
SOURCE_COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")
BADGING_PACKAGE_PATTERN = re.compile(
    r"^package: name='(?P<application_id>[^']+)' "
    r"versionCode='(?P<version_code>[0-9]+)' "
    r"versionName='(?P<version_name>[^']+)'(?: .*)?"
)
SDK_PATTERN = re.compile(r"^minSdkVersion:'(?P<value>[0-9]+)'$")
TARGET_SDK_PATTERN = re.compile(r"^targetSdkVersion:'(?P<value>[0-9]+)'$")
LIMITATIONS = (
    "unsupported development preview",
    "debug certificate and application identity are not production identities",
    "no compatibility, migration, backup, or support guarantee",
    "not approved for Play Store or production distribution",
)


class DevelopmentApkError(RuntimeError):
    """Development APK inputs or inspection evidence are invalid."""


@dataclasses.dataclass(frozen=True)
class DevelopmentVersion:
    """Deterministic version derived from an immutable Git revision."""

    source_commit: str
    sequence: int
    version_code: int
    version_name: str


@dataclasses.dataclass(frozen=True)
class ApkIdentity:
    """Identity fields inspected from the assembled APK."""

    application_id: str
    version_code: int
    version_name: str
    minimum_sdk: int
    target_sdk: int


def run_git(repository: Path, arguments: Sequence[str]) -> str:
    """Run one fixed Git query in the selected repository."""
    git = shutil.which("git")
    if git is None:
        raise DevelopmentApkError("Git executable is unavailable")
    result = subprocess.run(  # noqa: S603
        [git, "-C", str(repository), *arguments],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def development_version(repository: Path, source_commit: str | None = None) -> DevelopmentVersion:
    """Derive the development version from the first-parent position of a commit."""
    commit = source_commit or run_git(repository, ("rev-parse", "HEAD"))
    if SOURCE_COMMIT_PATTERN.fullmatch(commit) is None:
        raise DevelopmentApkError("source commit must be a full lowercase SHA-1")
    resolved = run_git(repository, ("rev-parse", f"{commit}^{{commit}}"))
    if resolved != commit:
        raise DevelopmentApkError("source commit did not resolve to the exact requested commit")
    sequence_text = run_git(repository, ("rev-list", "--first-parent", "--count", commit))
    try:
        sequence = int(sequence_text)
    except ValueError as error:
        raise DevelopmentApkError("Git returned an invalid first-parent count") from error
    if sequence <= 0:
        raise DevelopmentApkError("development sequence must be positive")
    version_code = VERSION_CODE_BASE + sequence
    return DevelopmentVersion(
        source_commit=commit,
        sequence=sequence,
        version_code=version_code,
        version_name=f"{BASE_VERSION}-dev.{sequence}+g{commit[:12]}",
    )


def verify_repository_state(repository: Path, version: DevelopmentVersion) -> None:
    """Require packaging from a clean checkout at the exact source commit."""
    head = run_git(repository, ("rev-parse", "HEAD"))
    if head != version.source_commit:
        raise DevelopmentApkError("source commit must equal the checked-out HEAD")
    if run_git(repository, ("status", "--porcelain", "--untracked-files=all")):
        raise DevelopmentApkError("development APK packaging requires a clean source tree")


def parse_badging(output: str) -> ApkIdentity:
    """Parse the bounded identity subset from aapt2 dump badging."""
    if len(output) > 1_048_576:
        raise DevelopmentApkError("aapt2 badging output is too large")
    package: re.Match[str] | None = None
    minimum_sdk: re.Match[str] | None = None
    target_sdk: re.Match[str] | None = None
    for line in output.splitlines():
        package = package or BADGING_PACKAGE_PATTERN.fullmatch(line)
        minimum_sdk = minimum_sdk or SDK_PATTERN.fullmatch(line)
        target_sdk = target_sdk or TARGET_SDK_PATTERN.fullmatch(line)
    if package is None or minimum_sdk is None or target_sdk is None:
        raise DevelopmentApkError("APK badging output is missing required identity fields")
    return ApkIdentity(
        application_id=package.group("application_id"),
        version_code=int(package.group("version_code")),
        version_name=package.group("version_name"),
        minimum_sdk=int(minimum_sdk.group("value")),
        target_sdk=int(target_sdk.group("value")),
    )


def inspect_apk(apk: Path, aapt2: Path) -> ApkIdentity:
    """Inspect identity fields using the pinned Android build tool."""
    if not apk.is_file():
        raise DevelopmentApkError("APK does not exist")
    if not aapt2.is_file():
        raise DevelopmentApkError("aapt2 does not exist")
    result = subprocess.run(  # noqa: S603
        [str(aapt2), "dump", "badging", str(apk)],
        check=True,
        capture_output=True,
        text=True,
    )
    return parse_badging(result.stdout)


def verify_identity(identity: ApkIdentity, version: DevelopmentVersion) -> None:
    """Require the assembled APK to match the development contract exactly."""
    expected = ApkIdentity(
        application_id=APPLICATION_ID,
        version_code=version.version_code,
        version_name=version.version_name,
        minimum_sdk=MINIMUM_SDK,
        target_sdk=TARGET_SDK,
    )
    if identity != expected:
        raise DevelopmentApkError(f"APK identity mismatch: expected {expected}, found {identity}")


def sha256(path: Path) -> str:
    """Return the lowercase SHA-256 digest of a regular file."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_apk_name(version: DevelopmentVersion) -> str:
    """Return the public, immutable development APK filename."""
    safe_version = version.version_name.replace("+", "-")
    return f"agent-relay-development-{safe_version}-{version.source_commit}-{BUILD_TYPE}.apk"


def build_manifest(
    apk_name: str,
    apk_size: int,
    apk_sha256: str,
    version: DevelopmentVersion,
    workflow_run_id: int | None,
    workflow_run_attempt: int | None,
) -> dict[str, Any]:
    """Build deterministic, path-free artifact metadata."""
    if (workflow_run_id is None) != (workflow_run_attempt is None):
        raise DevelopmentApkError("workflow run id and attempt must both be present or absent")
    if workflow_run_id is not None and workflow_run_id <= 0:
        raise DevelopmentApkError("workflow run id must be positive")
    if workflow_run_attempt is not None and workflow_run_attempt <= 0:
        raise DevelopmentApkError("workflow run attempt must be positive")
    return {
        "schema_version": SCHEMA_VERSION,
        "artifact": {
            "filename": apk_name,
            "sha256": apk_sha256,
            "size_bytes": apk_size,
            "type": "android-application",
        },
        "application": {
            "application_id": APPLICATION_ID,
            "build_type": BUILD_TYPE,
            "minimum_sdk": MINIMUM_SDK,
            "target_sdk": TARGET_SDK,
            "version_code": version.version_code,
            "version_name": version.version_name,
        },
        "provenance": {
            "source_commit": version.source_commit,
            "version_policy": VERSION_POLICY,
            "workflow_run": {"attempt": workflow_run_attempt, "id": workflow_run_id},
        },
        "release_status": {
            "supported": False,
            "channel": "development-debug",
            "limitations": list(LIMITATIONS),
        },
    }


def write_package(
    apk: Path,
    output_directory: Path,
    version: DevelopmentVersion,
    identity: ApkIdentity,
    workflow_run_id: int | None,
    workflow_run_attempt: int | None,
) -> tuple[Path, Path, Path]:
    """Copy the verified APK and write canonical JSON and checksum companions."""
    verify_identity(identity, version)
    output_directory.mkdir(parents=True, exist_ok=True)
    apk_output = output_directory / canonical_apk_name(version)
    shutil.copyfile(apk, apk_output)
    apk_digest = sha256(apk_output)
    manifest = build_manifest(
        apk_output.name,
        apk_output.stat().st_size,
        apk_digest,
        version,
        workflow_run_id,
        workflow_run_attempt,
    )
    manifest_output = output_directory / "agent-relay-development-manifest.json"
    manifest_output.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    checksum_output = output_directory / "SHA256SUMS"
    checksum_output.write_text(
        f"{apk_digest}  {apk_output.name}\n{sha256(manifest_output)}  {manifest_output.name}\n",
        encoding="utf-8",
    )
    return apk_output, manifest_output, checksum_output


def positive_integer(value: str) -> int:
    """Parse a positive decimal command-line value."""
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a positive integer") from error
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def build_parser() -> argparse.ArgumentParser:
    """Build the command-line parser."""
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    version_parser = subparsers.add_parser("version")
    version_parser.add_argument("--repository", type=Path, default=Path.cwd())
    version_parser.add_argument("--source-commit")

    package_parser = subparsers.add_parser("package")
    package_parser.add_argument("--repository", type=Path, default=Path.cwd())
    package_parser.add_argument("--source-commit")
    package_parser.add_argument("--apk", type=Path, required=True)
    package_parser.add_argument("--aapt2", type=Path, required=True)
    package_parser.add_argument("--output-directory", type=Path, required=True)
    package_parser.add_argument("--workflow-run-id", type=positive_integer)
    package_parser.add_argument("--workflow-run-attempt", type=positive_integer)
    return parser


def main(arguments: Sequence[str] | None = None) -> int:
    """Run the selected deterministic metadata operation."""
    options = build_parser().parse_args(arguments)
    version = development_version(options.repository, options.source_commit)
    if options.command == "version":
        print(
            json.dumps(
                dataclasses.asdict(version),
                separators=(",", ":"),
                sort_keys=True,
            )
        )
        return 0
    verify_repository_state(options.repository, version)
    identity = inspect_apk(options.apk, options.aapt2)
    outputs = write_package(
        options.apk,
        options.output_directory,
        version,
        identity,
        options.workflow_run_id,
        options.workflow_run_attempt,
    )
    print("\n".join(path.name for path in outputs))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
