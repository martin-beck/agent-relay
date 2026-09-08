# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Tests for the pinned structural-engine boundary."""

# Dynamic module loading and assert-based pytest checks are intentional here.
# ruff: noqa: S101

from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location("structuralctl", Path(__file__).with_name("structuralctl.py"))
assert SPEC and SPEC.loader
structuralctl = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(structuralctl)


def test_engine_lock_is_pinned() -> None:
    spec = structuralctl.engine_spec()
    assert spec["name"] == "ast-grep"
    assert spec["version"] == "0.40.5"
    assert len(spec["sha256"]) == 64


def test_kotlin_recipe_has_complete_fixture_set() -> None:
    catalog = Path("config/refactoring-recipes.json").read_text(encoding="utf-8")
    assert "kotlin-explicit-boolean-wrapper" in catalog
    for fixture in ("positive/input.kt", "negative/input.kt", "golden/output.kt"):
        assert Path("fixtures/structural/kotlin", fixture).is_file()


def test_missing_engine_fails_closed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(structuralctl.shutil, "which", lambda _: None)
    with pytest.raises(structuralctl.StructuralError, match="unavailable"):
        structuralctl.pinned_executable()


def test_recipe_rejects_inline_or_unpinned_rules(tmp_path: Path) -> None:
    with pytest.raises(structuralctl.StructuralError, match="pinned"):
        structuralctl.run({"engine": "regex", "rule": {"pattern": "x"}}, tmp_path)
