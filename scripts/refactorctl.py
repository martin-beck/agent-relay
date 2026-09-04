#!/usr/bin/env python3
"""Non-mutating scan and plan foundation for verified refactoring recipes."""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any, cast


class RefactorError(ValueError):
    """Raised when a catalog, path, or recipe plan is unsafe."""


REQUIRED_RECIPE_FIELDS = {
    "id", "engine", "toolVersion", "risk", "languages", "include", "exclude",
    "prohibitions", "preconditions", "invariants", "budgets", "fixtures",
    "verification", "ownership", "compatibility",
}


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode()).hexdigest()


def load_catalog(path: Path) -> dict[str, Any]:
    try:
        catalog = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RefactorError(f"invalid catalog: {error}") from error
    if not isinstance(catalog, dict) or catalog.get("schemaVersion") != 1:
        raise RefactorError("catalog schemaVersion must be 1")
    recipes = catalog.get("recipes")
    if not isinstance(recipes, list):
        raise RefactorError("catalog recipes must be an array")
    for recipe in recipes:
        if not isinstance(recipe, dict) or not isinstance(recipe.get("id"), str):
            raise RefactorError("each recipe must have a string id")
        missing = REQUIRED_RECIPE_FIELDS - recipe.keys()
        if missing:
            raise RefactorError(f"recipe {recipe.get('id', '<unknown>')} missing: {', '.join(sorted(missing))}")
        if recipe.get("risk") not in {"R0", "R1", "R2", "R3"}:
            raise RefactorError("recipe risk is invalid")
        budgets = recipe.get("budgets")
        if not isinstance(budgets, dict) or not all(isinstance(budgets.get(key), int) for key in ("maxMatches", "maxFiles", "maxChangedLines")):
            raise RefactorError("recipe budgets are incomplete")
    return cast(dict[str, Any], catalog)


def recipe_for(catalog: dict[str, Any], recipe_id: str) -> dict[str, Any]:
    matches = [recipe for recipe in catalog["recipes"] if recipe["id"] == recipe_id]
    if len(matches) != 1:
        raise RefactorError(f"recipe not found or duplicated: {recipe_id}")
    return cast(dict[str, Any], matches[0])


def confined(root: Path, relative: str) -> Path:
    unresolved = root / relative
    if unresolved.is_symlink():
        raise RefactorError(f"symlink path is not eligible: {relative}")
    candidate = unresolved.resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError as error:
        raise RefactorError(f"path escapes repository: {relative}") from error
    return candidate


def eligible_files(root: Path, recipe: dict[str, Any]) -> list[Path]:
    excluded = tuple(recipe.get("exclude", []))
    result: list[Path] = []
    for include in recipe.get("include", []):
        path = confined(root, include)
        candidates = [path] if path.is_file() else sorted(path.rglob("*"))
        for candidate in candidates:
            if not candidate.is_file() or candidate.is_symlink():
                continue
            relative = candidate.relative_to(root).as_posix()
            if any(relative == item or relative.startswith(f"{item.rstrip('/')}/") for item in excluded):
                continue
            if any(part in {"build", "generated", "vendor", "private"} for part in candidate.relative_to(root).parts):
                continue
            try:
                candidate.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            result.append(candidate)
    return sorted(set(result))


def matches(root: Path, recipe: dict[str, Any]) -> list[tuple[Path, str, str, int]]:
    pattern, replacement = rule_expression(recipe)
    if not isinstance(pattern, str) or not pattern:
        raise RefactorError("recipe requires a non-empty match expression")
    try:
        matcher = re.compile(pattern, re.MULTILINE)
    except re.error as error:
        raise RefactorError(f"invalid match expression: {error}") from error
    if not isinstance(replacement, str):
        raise RefactorError("replacement must be a string")
    found: list[tuple[Path, str, str, int]] = []
    for path in eligible_files(root, recipe):
        original = path.read_text(encoding="utf-8")
        count = len(matcher.findall(original))
        if count:
            found.append((path, original, matcher.sub(replacement, original), count))
    return found


def rule_expression(recipe: dict[str, Any]) -> tuple[str, str]:
    pattern = recipe.get("match")
    replacement = recipe.get("replacement", "")
    if isinstance(pattern, str) and pattern:
        return pattern, replacement if isinstance(replacement, str) else ""
    rule = recipe.get("rule")
    if not isinstance(rule, dict) or not isinstance(rule.get("pattern"), str):
        raise RefactorError("recipe requires a non-empty match expression")
    pattern = str(rule["pattern"])
    names = re.findall(r"\$([A-Z][A-Z0-9_]*)", pattern)
    expression = re.escape(pattern)
    for name in names:
        expression = expression.replace(re.escape(f"${name}"), rf"(?P<{name}>[^()\n]+?)")
    replacement = rule.get("rewrite", "")
    if isinstance(replacement, str):
        for name in names:
            replacement = replacement.replace(f"${name}", rf"\g<{name}>")
    return expression, replacement if isinstance(replacement, str) else ""


def base_commit(root: Path) -> str:
    git = shutil.which("git")
    if git is None:
        raise RefactorError("git executable is unavailable")
    try:
        return subprocess.run(  # noqa: S603
            [git, "-C", str(root), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as error:
        raise RefactorError(f"cannot determine base commit: {error}") from error


def build_plan(root: Path, recipe: dict[str, Any], catalog: dict[str, Any]) -> dict[str, Any]:
    found = matches(root, recipe)
    budgets = recipe.get("budgets", {})
    match_count = sum(item[3] for item in found)
    changed_lines = sum(
        sum(
            1
            for line in difflib.unified_diff(item[1].splitlines(), item[2].splitlines())
            if line[:1] in {"+", "-"} and not line.startswith(("+++", "---"))
        )
        for item in found
    )
    if match_count > budgets.get("maxMatches", 0):
        raise RefactorError("match budget exceeded")
    if len(found) > budgets.get("maxFiles", 0):
        raise RefactorError("file budget exceeded")
    if changed_lines > budgets.get("maxChangedLines", 0):
        raise RefactorError("changed-line budget exceeded")
    patch_lines: list[str] = []
    for path, original, updated, _ in found:
        patch_lines.extend(
            difflib.unified_diff(
                original.splitlines(keepends=True),
                updated.splitlines(keepends=True),
                fromfile=f"a/{path.relative_to(root)}",
                tofile=f"b/{path.relative_to(root)}",
            )
        )
    return {
        "schemaVersion": 1,
        "recipeId": recipe["id"],
        "recipeDigest": digest(recipe),
        "baseCommit": base_commit(root),
        "risk": recipe.get("risk"),
        "matchCount": match_count,
        "affectedPaths": [item[0].relative_to(root).as_posix() for item in found],
        "changedLines": changed_lines,
        "budgets": budgets,
        "patch": "".join(patch_lines),
        "catalogDigest": digest(catalog),
    }


def require_claim(recipe: dict[str, Any], task: str | None, owner: str | None) -> None:
    """Require an explicit coordinator claim for every mutating operation."""
    if not task or not re.fullmatch(r"AR-[0-9]{4}", task):
        raise RefactorError("apply requires a claimed task (AR-NNNN)")
    if not owner or not re.fullmatch(r"[A-Za-z0-9._-]{3,80}", owner):
        raise RefactorError("apply requires a stable worker owner")
    if recipe.get("risk") not in {"R0", "R1"}:
        raise RefactorError("only R0/R1 recipes may be applied autonomously")
    if recipe.get("behaviorChange") != "none":
        raise RefactorError("recipe behavior change is not autonomous")
    ownership = recipe.get("ownership")
    if not isinstance(ownership, dict) or ownership.get("status") != "allowlisted":
        raise RefactorError("recipe is not allowlisted")


def apply_recipe(root: Path, recipe: dict[str, Any], catalog: dict[str, Any], *, task: str | None, owner: str | None) -> dict[str, Any]:
    """Apply a bounded recipe to a clean claimed checkout and return its plan."""
    require_claim(recipe, task, owner)
    git = shutil.which("git")
    if git is None:
        raise RefactorError("git executable is unavailable")
    try:
        status = subprocess.run([git, "-C", str(root), "status", "--porcelain"], check=True, capture_output=True, text=True)  # noqa: S603
    except (OSError, subprocess.CalledProcessError) as error:
        raise RefactorError(f"cannot inspect worktree: {error}") from error
    if status.stdout:
        raise RefactorError("claimed worktree must be clean before apply")
    plan = build_plan(root, recipe, catalog)
    if not plan["affectedPaths"]:
        return plan
    for path, _, updated, _ in matches(root, recipe):
        path.write_text(updated, encoding="utf-8")
    return plan


def verify_convergence(root: Path, recipe: dict[str, Any], catalog: dict[str, Any], plan: dict[str, Any]) -> dict[str, Any]:
    """Verify the plan is reproducible and a second application has no diff."""
    if base_commit(root) != plan.get("baseCommit"):
        raise RefactorError("plan base commit changed")
    if matches(root, recipe):
        raise RefactorError("application has not converged")
    return {"planDigest": digest(plan), "converged": True, "baseCommit": plan["baseCommit"]}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("scan", "plan", "apply", "verify"))
    parser.add_argument("recipe_id")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--catalog", type=Path, default=Path("config/refactoring-recipes.json"))
    parser.add_argument("--task")
    parser.add_argument("--owner")
    parser.add_argument("--plan", type=Path)
    args = parser.parse_args()
    try:
        root = args.root.resolve()
        catalog = load_catalog(args.catalog.resolve())
        recipe = recipe_for(catalog, args.recipe_id)
        if args.command == "scan":
            found = matches(root, recipe)
            result = {"recipeId": recipe["id"], "recipeDigest": digest(recipe), "matchCount": sum(x[3] for x in found), "affectedPaths": [x[0].relative_to(root).as_posix() for x in found]}
        elif args.command == "plan":
            result = build_plan(root, recipe, catalog)
        elif args.command == "apply":
            result = apply_recipe(root, recipe, catalog, task=args.task, owner=args.owner)
        else:
            if args.plan is None:
                raise RefactorError("verify requires --plan")
            plan = json.loads(args.plan.read_text(encoding="utf-8"))
            result = verify_convergence(root, recipe, catalog, plan)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
        return 0
    except RefactorError as error:
        parser.error(str(error))


if __name__ == "__main__":
    raise SystemExit(main())
