#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Verify public Agent Relay licensing and preserve third-party attribution."""

from __future__ import annotations

import re
import subprocess
from pathlib import Path, PurePosixPath

COPYRIGHT = "Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved."
SPDX = "SPDX-License-Identifier: MIT"
MIT_LICENSE = """MIT License

Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""
LICENSE_COPIES = (
    PurePosixPath("LICENSE"),
    PurePosixPath("app/src/main/assets/licenses/AGENT_RELAY_LICENSE.txt"),
)
PUBLIC_DOCUMENTS = (
    PurePosixPath("README.md"),
    PurePosixPath("CONTRIBUTING.md"),
    PurePosixPath("SECURITY.md"),
    PurePosixPath("SUPPORT.md"),
    PurePosixPath("docs/BUILDING.md"),
    PurePosixPath("docs/INSTALLING.md"),
    PurePosixPath("docs/QUALITY.md"),
    PurePosixPath("docs/RELEASING.md"),
)
FORBIDDEN_PUBLIC_PHRASES = (
    "private, invite-only",
    "private personal repository",
    "private repository's",
    "for a private repository",
    "only to invited collaborators",
    "no public license",
)
THIRD_PARTY_MARKERS = {
    PurePosixPath("docs/THIRD_PARTY.md"): (
        "sherpa-onnx",
        "ONNX Runtime",
        "Gradle Wrapper",
        "Apache-2.0",
    ),
    PurePosixPath(".vale/THIRD_PARTY_LICENSES.txt"): ("errata.ai", "Joseph Kato"),
}
TEXT_SUFFIXES = {
    "",
    ".alloy",
    ".bash",
    ".bats",
    ".css",
    ".java",
    ".json",
    ".kt",
    ".kts",
    ".md",
    ".pro",
    ".properties",
    ".py",
    ".sh",
    ".tla",
    ".toml",
    ".txt",
    ".xml",
    ".yaml",
    ".yml",
}
NON_FIRST_PARTY_PATHS = {
    PurePosixPath("gradlew"),
    PurePosixPath("gradlew.bat"),
    PurePosixPath(".vale/THIRD_PARTY_LICENSES.txt"),
    PurePosixPath("scripts/ci/test_verify_license_metadata.py"),
}
SPDX_PATTERN = re.compile(r"SPDX-License-Identifier:\s*[A-Za-z0-9][A-Za-z0-9.+-]*")
COPYRIGHT_PATTERN = re.compile(r"Copyright \(C\) Huawei Technologies.*?All rights reserved\.")


def tracked(root: Path) -> list[PurePosixPath]:
    output = subprocess.check_output(["/usr/bin/git", "ls-files", "-z"], cwd=root, text=True)
    return [PurePosixPath(value) for value in output.split("\0") if value]


def license_copy_findings(root: Path) -> list[str]:
    errors: list[str] = []
    for relative in LICENSE_COPIES:
        path = root / relative
        if not path.is_file() or path.read_text(encoding="utf-8") != MIT_LICENSE:
            errors.append(f"{relative}: must match the canonical Huawei MIT license")
    notice = root / "NOTICE"
    notice_text = notice.read_text(encoding="utf-8") if notice.is_file() else ""
    if notice_text.count(COPYRIGHT) != 1 or notice_text.count(SPDX) != 1:
        errors.append("NOTICE: must contain the canonical copyright and SPDX pair once")
    return errors


def public_document_findings(root: Path) -> list[str]:
    errors: list[str] = []
    for relative in PUBLIC_DOCUMENTS:
        path = root / relative
        if not path.is_file():
            errors.append(f"{relative}: required public document is missing")
            continue
        text = path.read_text(encoding="utf-8").lower()
        for phrase in FORBIDDEN_PUBLIC_PHRASES:
            if phrase in text:
                errors.append(f"{relative}: private-only phrase remains: {phrase}")
    return errors


def third_party_findings(root: Path) -> list[str]:
    errors: list[str] = []
    for relative, markers in THIRD_PARTY_MARKERS.items():
        path = root / relative
        if not path.is_file():
            errors.append(f"{relative}: required third-party attribution file is missing")
            continue
        text = path.read_text(encoding="utf-8")
        if any(marker not in text for marker in markers):
            errors.append(f"{relative}: required third-party attribution is missing")
    return errors


def first_party_text_findings(root: Path, paths: list[PurePosixPath]) -> list[str]:
    errors: list[str] = []
    for relative in paths:
        if relative.suffix not in TEXT_SUFFIXES:
            continue
        if relative in NON_FIRST_PARTY_PATHS or relative.parts[:2] == ("gradle", "wrapper"):
            continue
        path = root / relative
        if not path.is_file():
            continue
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError:
            continue
        for number, line in enumerate(lines, start=1):
            copyright_match = COPYRIGHT_PATTERN.search(line)
            if copyright_match is not None and copyright_match.group(0) != COPYRIGHT:
                errors.append(f"{relative}:{number}: inconsistent Huawei copyright")
            spdx_match = SPDX_PATTERN.search(line)
            if spdx_match is not None and spdx_match.group(0) != SPDX:
                errors.append(f"{relative}:{number}: inconsistent first-party SPDX identifier")
    return errors


def findings(root: Path, paths: list[PurePosixPath]) -> list[str]:
    return [
        *license_copy_findings(root),
        *public_document_findings(root),
        *third_party_findings(root),
        *first_party_text_findings(root, paths),
    ]


def main() -> int:
    root = Path.cwd()
    errors = findings(root, tracked(root))
    if errors:
        print("\n".join(errors))
        return 1
    print("public license metadata and third-party attribution are consistent")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
