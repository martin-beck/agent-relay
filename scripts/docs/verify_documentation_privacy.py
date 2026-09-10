#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Reject high-confidence live secrets and private data in documentation fixtures."""

from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCAN_ROOTS = ("docs", "config", "fixtures")
TEXT_SUFFIXES = {".json", ".md", ".txt", ".toml", ".yml", ".yaml", ".xml", ".properties"}
RULES = (
    (re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"), "private key material"),
    (re.compile(r"\b(?:ghp|github_pat|glpat)-[A-Za-z0-9_-]{20,}\b"), "access token"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "AWS access key"),
    (
        re.compile(r"(?i)\b(?:password|token|secret)\s*[:=]\s*['\"]?[A-Za-z0-9+/=_-]{16,}"),
        "credential assignment",
    ),
    (
        re.compile(r"(?i)\b(?:api[_-]?key|private[_-]?key)\s*[:=]\s*['\"]?[A-Za-z0-9+/=_-]{16,}"),
        "key assignment",
    ),
)


def scan(root: Path = ROOT) -> list[str]:
    findings: list[str] = []
    for relative_root in SCAN_ROOTS:
        directory = root / relative_root
        if not directory.exists():
            continue
        for path in sorted(directory.rglob("*")):
            if not path.is_file() or path.suffix.lower() not in TEXT_SUFFIXES:
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            for line_number, line in enumerate(text.splitlines(), start=1):
                for pattern, label in RULES:
                    if pattern.search(line):
                        findings.append(f"{path.relative_to(root)}:{line_number}: {label}")
                        break
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    findings = scan(args.root.resolve())
    if findings:
        print("documentation privacy findings:", *findings, sep="\n", flush=True)
        return 1
    print("documentation privacy scan passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
