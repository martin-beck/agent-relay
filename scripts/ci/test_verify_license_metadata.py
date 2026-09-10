# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
# ruff: noqa: S101

"""Tests for public license metadata verification."""

from pathlib import Path, PurePosixPath

from verify_license_metadata import (
    COPYRIGHT,
    LICENSE_COPIES,
    MIT_LICENSE,
    PUBLIC_DOCUMENTS,
    SPDX,
    findings,
)


def valid_tree(root: Path) -> list[PurePosixPath]:
    paths = [
        *LICENSE_COPIES,
        PurePosixPath("NOTICE"),
        *PUBLIC_DOCUMENTS,
        PurePosixPath("docs/THIRD_PARTY.md"),
        PurePosixPath(".vale/THIRD_PARTY_LICENSES.txt"),
        PurePosixPath("example.py"),
    ]
    for relative in LICENSE_COPIES:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(MIT_LICENSE, encoding="utf-8")
    for relative in paths:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists():
            continue
        path.write_text("public development project\n", encoding="utf-8")
    (root / "NOTICE").write_text(f"{COPYRIGHT}\n{SPDX}\n", encoding="utf-8")
    (root / "docs/THIRD_PARTY.md").write_text(
        "sherpa-onnx, ONNX Runtime, and Gradle Wrapper Apache-2.0\n",
        encoding="utf-8",
    )
    (root / ".vale/THIRD_PARTY_LICENSES.txt").write_text(
        "errata.ai and Joseph Kato\n", encoding="utf-8"
    )
    (root / "example.py").write_text(f"# {COPYRIGHT}\n# {SPDX}\n", encoding="utf-8")
    return paths


def test_valid_public_tree_passes(tmp_path: Path) -> None:
    paths = valid_tree(tmp_path)
    assert findings(tmp_path, paths) == []


def test_drift_and_private_only_wording_fail(tmp_path: Path) -> None:
    paths = valid_tree(tmp_path)
    (tmp_path / "README.md").write_text("Private, invite-only project\n", encoding="utf-8")
    issue_template = tmp_path / ".github/ISSUE_TEMPLATE/bug_report.yml"
    issue_template.write_text("Agent Relay is a private development preview.\n", encoding="utf-8")
    (tmp_path / "example.py").write_text(
        "# Copyright (C) Huawei Technologies Co., Ltd. 2025. All rights reserved.\n"
        "# SPDX-License-Identifier: Apache-2.0\n",
        encoding="utf-8",
    )
    result = findings(tmp_path, paths)
    assert any("private-only phrase remains" in value for value in result)
    assert any(
        str(value).startswith(f"{issue_template.relative_to(tmp_path)}:") for value in result
    )
    assert any("inconsistent Huawei copyright" in value for value in result)
    assert any("inconsistent first-party SPDX identifier" in value for value in result)


def test_generated_wrapper_license_is_not_treated_as_first_party(tmp_path: Path) -> None:
    paths = valid_tree(tmp_path)
    wrapper = tmp_path / "gradlew"
    wrapper.write_text("# SPDX-License-Identifier: Apache-2.0\n", encoding="utf-8")
    paths.append(PurePosixPath("gradlew"))
    assert findings(tmp_path, paths) == []


def test_missing_required_documents_fail_cleanly(tmp_path: Path) -> None:
    paths = valid_tree(tmp_path)
    (tmp_path / "README.md").unlink()
    (tmp_path / "CODE_OF_CONDUCT.md").unlink()
    (tmp_path / "docs/THIRD_PARTY.md").unlink()

    result = findings(tmp_path, paths)

    assert "README.md: required public document is missing" in result
    assert "CODE_OF_CONDUCT.md: required public document is missing" in result
    assert "docs/THIRD_PARTY.md: required third-party attribution file is missing" in result
