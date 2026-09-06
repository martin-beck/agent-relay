# ruff: noqa: S101, S603, S607
import importlib.util
import json
import subprocess
from pathlib import Path
from typing import Any, cast

import pytest

SPEC = importlib.util.spec_from_file_location(
    "refactorctl", Path(__file__).with_name("refactorctl.py")
)
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
    subprocess.run(
        ["git", "-C", str(tmp_path), "config", "user.email", "test@example.invalid"], check=True
    )
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
        refactorctl.build_plan(
            tmp_path,
            recipe(budgets={"maxMatches": 1, "maxFiles": 1, "maxChangedLines": 4}),
            {"schemaVersion": 1, "recipes": [recipe()]},
        )


def test_cli_empty_catalog_does_not_mutate(tmp_path: Path) -> None:
    catalog = tmp_path / "catalog.json"
    catalog.write_text('{"schemaVersion": 1, "recipes": []}', encoding="utf-8")
    result = subprocess.run(
        [
            "python3",
            str(Path(__file__).with_name("refactorctl.py")),
            "scan",
            "missing",
            "--root",
            str(tmp_path),
            "--catalog",
            str(catalog),
        ],
        capture_output=True,
        text=True,
    )
    assert result.returncode != 0
    assert catalog.read_text(encoding="utf-8") == '{"schemaVersion": 1, "recipes": []}'


def test_apply_requires_claim_and_verify_converges(tmp_path: Path) -> None:
    (tmp_path / "src").mkdir()
    target = tmp_path / "src" / "sample.txt"
    target.write_text("OLD\n", encoding="utf-8")
    subprocess.run(["git", "-C", str(tmp_path), "init", "-q"], check=True)
    subprocess.run(
        ["git", "-C", str(tmp_path), "config", "user.email", "test@example.invalid"], check=True
    )
    subprocess.run(["git", "-C", str(tmp_path), "config", "user.name", "Test"], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "add", "."], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "commit", "-qm", "fixture"], check=True)
    catalog = {"schemaVersion": 1, "recipes": [recipe()]}
    with pytest.raises(refactorctl.RefactorError, match="claimed task"):
        refactorctl.apply_recipe(tmp_path, recipe(), catalog, task=None, owner=None)
    plan = refactorctl.apply_recipe(
        tmp_path, recipe(), catalog, task="AR-0017", owner="worker-test"
    )
    assert target.read_text(encoding="utf-8") == "NEW\n"
    evidence = refactorctl.verify_convergence(tmp_path, recipe(), catalog, plan)
    assert evidence["converged"] is True


def test_apply_rejects_behavior_risk(tmp_path: Path) -> None:
    value = recipe(behaviorChange="review-required", ownership={"status": "allowlisted"})
    with pytest.raises(refactorctl.RefactorError, match="behavior change"):
        refactorctl.require_claim(value, "AR-0017", "worker-test")


def test_apply_rolls_back_partial_write_failure(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = tmp_path / "src"
    source.mkdir()
    first, second = source / "first.txt", source / "second.txt"
    first.write_text("OLD-1\n", encoding="utf-8")
    second.write_text("OLD-2\n", encoding="utf-8")
    subprocess.run(["git", "-C", str(tmp_path), "init", "-q"], check=True)
    subprocess.run(
        ["git", "-C", str(tmp_path), "config", "user.email", "test@example.invalid"], check=True
    )
    subprocess.run(["git", "-C", str(tmp_path), "config", "user.name", "Test"], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "add", "."], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "commit", "-qm", "fixture"], check=True)
    value = recipe(include=["src"], budgets={"maxMatches": 2, "maxFiles": 2, "maxChangedLines": 4})
    catalog = {"schemaVersion": 1, "recipes": [value]}
    original_write = refactorctl.Path.write_text
    calls = 0

    def fail_second(path: Path, data: str, **kwargs: Any) -> int:
        nonlocal calls
        calls += 1
        if calls == 2:
            raise OSError("simulated disk failure")
        return cast(int, original_write(path, data, **kwargs))

    monkeypatch.setattr(refactorctl.Path, "write_text", fail_second)
    with pytest.raises(refactorctl.RefactorError, match="rolled back"):
        refactorctl.apply_recipe(tmp_path, value, catalog, task="AR-2111", owner="worker-test")
    assert first.read_text(encoding="utf-8") == "OLD-1\n"
    assert second.read_text(encoding="utf-8") == "OLD-2\n"


def test_verify_rejects_stale_plan_and_changed_output(tmp_path: Path) -> None:
    (tmp_path / "src").mkdir()
    target = tmp_path / "src" / "sample.txt"
    target.write_text("OLD\n", encoding="utf-8")
    subprocess.run(["git", "-C", str(tmp_path), "init", "-q"], check=True)
    subprocess.run(
        ["git", "-C", str(tmp_path), "config", "user.email", "test@example.invalid"], check=True
    )
    subprocess.run(["git", "-C", str(tmp_path), "config", "user.name", "Test"], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "add", "."], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "commit", "-qm", "fixture"], check=True)
    value = recipe()
    catalog = {"schemaVersion": 1, "recipes": [value]}
    plan = refactorctl.apply_recipe(tmp_path, value, catalog, task="AR-2111", owner="worker-test")
    changed_catalog = {"schemaVersion": 1, "recipes": [recipe(replacement="OTHER")]}
    with pytest.raises(refactorctl.RefactorError, match="catalog digest changed"):
        refactorctl.verify_convergence(tmp_path, value, changed_catalog, plan)
    target.write_text("UNPLANNED\n", encoding="utf-8")
    with pytest.raises(refactorctl.RefactorError, match="working tree differs"):
        refactorctl.verify_convergence(tmp_path, value, catalog, plan)


def test_catalog_recipe_matches_golden_without_semantic_drift() -> None:
    catalog = refactorctl.load_catalog(Path("config/refactoring-recipes.json"))
    recipe_value = refactorctl.recipe_for(catalog, "kotlin-explicit-boolean-wrapper")
    source = Path("fixtures/structural/kotlin/positive/input.kt").read_text(encoding="utf-8")
    golden = Path("fixtures/structural/kotlin/golden/output.kt").read_text(encoding="utf-8")
    found = refactorctl.matches(Path.cwd(), recipe_value)
    assert len(found) == 1
    assert found[0][2] == golden
    assert "Boolean.valueOf" not in found[0][2]
    assert source != found[0][2]
    assert refactorctl.matches(Path.cwd(), recipe_value)[0][2] == golden
