# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
# ruff: noqa: S101

"""Tests for source-header verification."""

from pathlib import PurePosixPath

from verify_source_headers import COPYRIGHT, SPDX, error, kind, with_header


def test_shebang_stays_first() -> None:
    result = with_header("#!/bin/sh\necho ok\n", "hash")
    assert result.splitlines()[:3] == ["#!/bin/sh", f"# {COPYRIGHT}", f"# {SPDX}"]
    assert error(result, "hash") is None


def test_xml_declaration_stays_first() -> None:
    result = with_header('<?xml version="1.0"?>\n<root />\n', "xml")
    assert result.splitlines()[1:3] == [f"<!-- {COPYRIGHT} -->", f"<!-- {SPDX} -->"]
    assert error(result, "xml") is None


def test_tla_module_declaration_stays_first() -> None:
    result = with_header("---- MODULE Example ----\n====\n", "tla")
    assert result.splitlines()[:3] == [
        "---- MODULE Example ----",
        rf"\* {COPYRIGHT}",
        rf"\* {SPDX}",
    ]
    assert error(result, "tla") is None


def test_alloy_module_declaration_stays_first() -> None:
    result = with_header("module example\n", "alloy")
    assert result.splitlines()[:3] == ["module example", f"// {COPYRIGHT}", f"// {SPDX}"]
    assert error(result, "alloy") is None


def test_duplicate_is_rejected() -> None:
    result = with_header("package example\n", "c")
    assert error(result + f"// {SPDX}\n", "c") == "required header must appear exactly once"


def test_policy_covers_sources_but_excludes_generated_wrappers() -> None:
    assert kind(PurePosixPath("src/Main.kt")) == "c"
    assert kind(PurePosixPath("script.py")) == "hash"
    assert kind(PurePosixPath("Model.tla")) == "tla"
    assert kind(PurePosixPath("Model.alloy")) == "alloy"
    assert kind(PurePosixPath("AndroidManifest.xml")) == "xml"
    assert kind(PurePosixPath("tools/awq"), executable=True) == "hash"
    assert kind(PurePosixPath("tools/future-runner"), executable=True) == "hash"
    assert kind(PurePosixPath(".github/CODEOWNERS")) is None
    assert kind(PurePosixPath("gradlew"), executable=True) is None
    assert kind(PurePosixPath("gradlew")) is None
    assert kind(PurePosixPath("gradle/wrapper/gradle-wrapper.properties")) is None
