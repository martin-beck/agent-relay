#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Detect and validate the reproducible Agent Relay developer toolchain."""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from pathlib import Path

PINNED = {
    "jdk": "17",
    "android_platform": "36",
    "android_ndk": "28.2.13676358",
    "cmake": "3.28.3",
    "ninja": "1.11.1",
    "shellcheck": "0.11.0",
    "shfmt": "3.14.0",
    "bats": "1.14.0",
}


@dataclass(frozen=True)
class Host:
    system: str
    architecture: str
    wsl: bool
    supported: bool
    reason: str


@dataclass(frozen=True)
class Check:
    name: str
    expected: str
    actual: str | None
    status: str
    path: str | None
    reason: str


@dataclass(frozen=True)
class Report:
    schema_version: int
    host: Host
    checks: tuple[Check, ...]
    readiness: tuple[str, ...]
    install_requested: bool
    install_performed: bool


def detect_host() -> Host:
    system = platform.system()
    architecture = platform.machine().lower()
    wsl = system == "Linux" and "microsoft" in platform.release().lower()
    supported_system = system in {"Linux", "Windows"}
    supported_architecture = architecture in {"x86_64", "amd64", "aarch64", "arm64"}
    supported = supported_system and supported_architecture
    reason = "supported host" if supported else "requires Linux or Windows on x86_64/arm64"
    return Host(system, architecture, wsl, supported, reason)


def redact_path(path: Path | None, repo_root: Path) -> str | None:
    if path is None:
        return None
    absolute = path.expanduser().resolve(strict=False)
    replacements = ((repo_root, "$REPO"), (Path.home(), "$HOME"))
    for prefix, replacement in replacements:
        try:
            relative = str(absolute.relative_to(prefix))
            return replacement + (f"/{relative}" if relative else "")
        except ValueError:
            continue
    return "$PATH/" + absolute.name if absolute.is_absolute() else str(absolute)


def run_version(command: str, args: Sequence[str] = ("--version",)) -> tuple[str | None, str]:
    executable = shutil.which(command)
    if executable is None:
        return None, "executable not found"
    try:
        result = subprocess.run(  # noqa: S603 - executable is resolved through PATH.
            [executable, *args],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return executable, f"unable to query version: {error.__class__.__name__}"
    output = (result.stdout + result.stderr).strip()
    return executable, output.splitlines()[0] if output else f"exit {result.returncode}"


def version_check(
    name: str,
    command: str,
    expected: str,
    repo_root: Path,
    args: Sequence[str] = ("--version",),
) -> Check:
    path, actual_output = run_version(command, args)
    actual = extract_version(actual_output)
    status = "pass" if (expected == "" and path is not None) or actual == expected or (
        actual is not None and actual.startswith(f"{expected}.")
    ) else "fail"
    reason = "executable detected" if expected == "" and status == "pass" else (
        "pinned version detected" if status == "pass" else actual_output
    )
    return Check(name, expected, actual, status, redact_path(Path(path) if path else None, repo_root), reason)


def extract_version(output: str) -> str | None:
    match = re.search(r"(?<!\d)(\d+(?:\.\d+){1,3})(?!\d)", output)
    return match.group(1) if match else None


def file_check(name: str, expected: str, path: Path, repo_root: Path) -> Check:
    exists = path.is_file()
    actual = path.read_text(encoding="utf-8", errors="replace") if exists else None
    version = extract_version(actual or "")
    status = "pass" if exists and (not expected or expected in (actual or "")) else "fail"
    reason = "pinned component detected" if status == "pass" else "missing or version differs"
    return Check(name, expected, version, status, redact_path(path, repo_root), reason)


def build_report(repo_root: Path, install_requested: bool = False) -> Report:
    host = detect_host()
    checks = [
        version_check("JDK", "java", PINNED["jdk"], repo_root),
        version_check("uv", "uv", "", repo_root),
        version_check("Gradle wrapper", str(repo_root / "gradlew"), "", repo_root),
        version_check("CMake", "cmake", PINNED["cmake"], repo_root),
        version_check("Ninja", "ninja", PINNED["ninja"], repo_root),
        version_check("ShellCheck", "shellcheck", PINNED["shellcheck"], repo_root),
        version_check("shfmt", "shfmt", PINNED["shfmt"], repo_root),
        version_check("Bats", "bats", PINNED["bats"], repo_root),
    ]
    sdk_root_value = os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", ""))
    if sdk_root_value:
        sdk_root = Path(sdk_root_value)
        checks.extend(
            [
                file_check("Android platform", PINNED["android_platform"], sdk_root / "platforms/android-36/package.xml", repo_root),
                file_check("Android NDK", PINNED["android_ndk"], sdk_root / "ndk/28.2.13676358/source.properties", repo_root),
            ],
        )
    else:
        checks.append(Check("Android SDK", "configured", None, "fail", None, "ANDROID_SDK_ROOT or ANDROID_HOME is unset"))
    passed = {check.name for check in checks if check.status == "pass"}
    build_ready = host.supported and {"JDK", "Gradle wrapper", "uv"}.issubset(passed)
    quality_ready = build_ready and {"CMake", "Ninja", "ShellCheck", "shfmt", "Bats"}.issubset(passed)
    emulator_ready = quality_ready and {"Android platform", "Android NDK"}.issubset(passed)
    readiness = tuple(
        level for level, ready in (
            ("build-ready", build_ready),
            ("quality-gate-ready", quality_ready),
            ("emulator-test-ready", emulator_ready),
        ) if ready
    )
    return Report(1, host, tuple(checks), readiness, install_requested, False)


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--json", action="store_true", help="emit a machine-readable report")
    parser.add_argument("--install", action="store_true", help="request installation planning (never implicit)")
    parser.add_argument("--yes", action="store_true", help="confirm explicit installation side effects")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    if args.install and not args.yes:
        print("Refusing installation without --yes; rerun in dry-run mode to inspect prerequisites.", file=sys.stderr)
        return 2
    report = build_report(args.repo_root.resolve(), args.install)
    if args.json:
        print(json.dumps(asdict(report), indent=2, sort_keys=True))
    else:
        print(f"Host: {report.host.system} {report.host.architecture} (WSL={report.host.wsl})")
        for check in report.checks:
            print(f"[{check.status.upper():4}] {check.name}: {check.reason}")
        print("Readiness: " + (", ".join(report.readiness) or "not ready"))
    return 0 if report.host.supported else 1


if __name__ == "__main__":
    raise SystemExit(main())
