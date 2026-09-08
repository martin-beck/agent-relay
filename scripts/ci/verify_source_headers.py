#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Verify exact source-file license headers."""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path, PurePosixPath

COPYRIGHT = "Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved."
SPDX = "SPDX-License-Identifier: MIT"
HASH = {".py", ".sh", ".bash", ".bats", ".yml", ".yaml", ".toml", ".properties", ".pro"}
C_STYLE = {".kt", ".kts", ".java", ".css"}
EXCLUDED = {"gradlew", "gradlew.bat"}


def kind(path: PurePosixPath) -> str | None:
    name = path.as_posix()
    if name in EXCLUDED or name.startswith("gradle/wrapper/"):
        return None
    if name == "tools/awq" or path.suffix in HASH:
        return "hash"
    if path.suffix in C_STYLE:
        return "c"
    if path.suffix in {".tla", ".alloy", ".xml"}:
        return path.suffix[1:]
    return None


def required(header_kind: str) -> list[str]:
    if header_kind == "c":
        return ["/*", f" * {COPYRIGHT}", f" * {SPDX}", " */"]
    prefixes = {"tla": r"\* ", "alloy": "// ", "hash": "# "}
    if header_kind == "xml":
        return [f"<!-- {COPYRIGHT} -->", f"<!-- {SPDX} -->"]
    prefix = prefixes[header_kind]
    return [prefix + COPYRIGHT, prefix + SPDX]


def index(lines: list[str], header_kind: str) -> int:
    if header_kind == "tla" and lines and " MODULE " in lines[0] and lines[0].startswith("----"):
        return 1
    if header_kind == "alloy" and lines and lines[0].startswith("module "):
        return 1
    if lines and (
        lines[0].startswith("#!") or (header_kind == "xml" and lines[0].startswith("<?xml"))
    ):
        return 1
    return 0


def error(text: str, header_kind: str) -> str | None:
    lines = text.splitlines()
    start = index(lines, header_kind)
    expected = required(header_kind)
    if lines[start : start + len(expected)] != expected:
        return "missing or misplaced required header"
    comments = [
        line for line in lines if line.lstrip().startswith(("#", "/*", "*", "//", "<!--", r"\*"))
    ]
    if (
        sum(COPYRIGHT in line for line in comments) != 1
        or sum(SPDX in line for line in comments) != 1
    ):
        return "required header must appear exactly once"
    return None


def with_header(text: str, header_kind: str) -> str:
    lines = text.splitlines()
    start = index(lines, header_kind)
    lines[start:start] = [*required(header_kind), ""]
    return "\n".join(lines) + ("\n" if text.endswith("\n") else "")


def tracked(root: Path) -> list[PurePosixPath]:
    value = subprocess.check_output(["/usr/bin/git", "ls-files", "-z"], cwd=root, text=True)
    return [PurePosixPath(item) for item in value.split("\0") if item]


def verify(root: Path, fix: bool) -> list[str]:
    findings: list[str] = []
    for relative in tracked(root):
        header_kind = kind(relative)
        if header_kind is None:
            continue
        path = root / relative
        text = path.read_text(encoding="utf-8")
        finding = error(text, header_kind)
        if finding and fix:
            path.write_text(with_header(text, header_kind), encoding="utf-8", newline="\n")
            finding = error(path.read_text(encoding="utf-8"), header_kind)
        if finding:
            findings.append(f"{relative}: {finding}")
    return findings


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--fix", action="store_true")
    args = parser.parse_args()
    findings = verify(args.root.resolve(), args.fix)
    if findings:
        print("\n".join(findings))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
