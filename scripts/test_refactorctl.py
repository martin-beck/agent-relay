# ruff: noqa: S101, S603, S607
import importlib.util
import json
import subprocess
from pathlib import Path
from typing import Any

import pytest

SPEC = importlib.util.spec_from_file_location("refactorctl", Path(__file__).with_name("refactorctl.py"))
assert SPEC and SPEC.loader
refactorctl = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(refactorctl)


def recipe(**overrides: Any) -> dict[str, Any]:
    value = {
        "id": "rename-token",
        "risk": "R1",
        "behaviorChange": "none",
        "ownership": {"status": "allowlisted"},
        "include": ["src"],
        "exclude": [],
        "match": "OLD",
        "replacement": "NEW",
        "budgets": {"maxMatches": 2, "maxFiles": 1, "maxChangedLines": 2},
    }
    value.update(overrides)
    return value


def test_scan_and_plan_are_deterministic_and_non_mutating(tmp_path: Path) -> None:
    (tmp_path / "src").mkdir()
    target = tmp_path / "src" / "sample.txt"
    target.write_text("OLD\n", encoding="utf-8")
    subprocess.run(["git", "-C", str(tmp_path), "init", "-q"], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "config", "user.email", "test@example.invalid"], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "config", "user.name", "Test"], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "add", "."], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "commit", "-qm", "fixture"], check=True)
    catalog = {"schemaVersion": 1, "recipes": [recipe()]}
    catalog_path = tmp_path / "catalog.json"
    catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
    before = target.read_bytes()
    found = refactorctl.matches(tmp_path, recipe())
    plan = refactorctl.build_plan(tmp_path, recipe(), catalog)
    assert len(found) == 1
    assert plan["matchCount"] == 1
    assert plan["affectedPaths"] == ["src/sample.txt"]
    assert plan["recipeDigest"] == refactorctl.digest(recipe())
    assert target.read_bytes() == before


def test_path_escape_and_symlink_are_rejected(tmp_path: Path) -> None:
    with pytest.raises(refactorctl.RefactorError):
        refactorctl.confined(tmp_path, "../outside")
    target = tmp_path / "real"
    target.write_text("OLD", encoding="utf-8")
    (tmp_path / "link").symlink_to(target)
    with pytest.raises(refactorctl.RefactorError):
        refactorctl.confined(tmp_path, "link")


def test_budget_rejection_is_fail_closed(tmp_path: Path) -> None:
    (tmp_path / "src").mkdir()
    (tmp_path / "src" / "a.txt").write_text("OLD\nOLD\n", encoding="utf-8")
    with pytest.raises(refactorctl.RefactorError, match="match budget"):
        refactorctl.build_plan(tmp_path, recipe(budgets={"maxMatches": 1, "maxFiles": 1, "maxChangedLines": 4}), {"schemaVersion": 1, "recipes": [recipe()]})


def test_cli_empty_catalog_does_not_mutate(tmp_path: Path) -> None:
    catalog = tmp_path / "catalog.json"
    catalog.write_text('{"schemaVersion": 1, "recipes": []}', encoding="utf-8")
    result = subprocess.run(["python3", str(Path(__file__).with_name("refactorctl.py")), "scan", "missing", "--root", str(tmp_path), "--catalog", str(catalog)], capture_output=True, text=True)
    assert result.returncode != 0
    assert catalog.read_text(encoding="utf-8") == '{"schemaVersion": 1, "recipes": []}'


def test_apply_requires_claim_and_verify_converges(tmp_path: Path) -> None:
    (tmp_path / "src").mkdir()
    target = tmp_path / "src" / "sample.txt"
    target.write_text("OLD\n", encoding="utf-8")
    subprocess.run(["git", "-C", str(tmp_path), "init", "-q"], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "config", "user.email", "test@example.invalid"], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "config", "user.name", "Test"], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "add", "."], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "commit", "-qm", "fixture"], check=True)
    catalog = {"schemaVersion": 1, "recipes": [recipe()]}
    with pytest.raises(refactorctl.RefactorError, match="claimed task"):
        refactorctl.apply_recipe(tmp_path, recipe(), catalog, task=None, owner=None)
    plan = refactorctl.apply_recipe(tmp_path, recipe(), catalog, task="AR-0017", owner="worker-test")
    assert target.read_text(encoding="utf-8") == "NEW\n"
    evidence = refactorctl.verify_convergence(tmp_path, recipe(), catalog, plan)
    assert evidence["converged"] is True


def test_apply_rejects_behavior_risk(tmp_path: Path) -> None:
    value = recipe(behaviorChange="review-required", ownership={"status": "allowlisted"})
    with pytest.raises(refactorctl.RefactorError, match="behavior change"):
        refactorctl.require_claim(value, "AR-0017", "worker-test")
