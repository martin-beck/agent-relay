#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Fail-closed adapter for the repository's pinned ast-grep engine."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

ENGINE = Path(__file__).parents[1] / "config" / "structural-engine.json"


class StructuralError(ValueError):
    """Raised when the pinned structural engine cannot safely be used."""


def engine_spec() -> dict[str, Any]:
    try:
        value = json.loads(ENGINE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise StructuralError(f"invalid engine lock: {error}") from error
    if not isinstance(value, dict) or value.get("name") != "ast-grep":
        raise StructuralError("engine lock must select ast-grep")
    if not isinstance(value.get("version"), str) or not value["version"]:
        raise StructuralError("engine lock must pin a version")
    return value


def pinned_executable() -> str:
    spec = engine_spec()
    executable = shutil.which(str(spec["executable"]))
    if executable is None:
        raise StructuralError(f"pinned engine {spec['name']} is unavailable")
    try:
        result = subprocess.run(  # noqa: S603
            [executable, "--version"], check=True, capture_output=True, text=True
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise StructuralError(f"cannot inspect pinned engine: {error}") from error
    if str(spec["version"]) not in result.stdout + result.stderr:
        raise StructuralError("installed structural engine version is not pinned version")
    return executable


def run(recipe: dict[str, Any], root: Path, *, rewrite: bool = False) -> str:
    """Run one catalog rule; inline patterns and unpinned tools are rejected."""
    if recipe.get("engine") != "ast-grep":
        raise StructuralError("recipe must use the pinned ast-grep engine")
    rule = recipe.get("rule")
    if not isinstance(rule, dict) or not isinstance(rule.get("pattern"), str):
        raise StructuralError("recipe must provide a structural pattern")
    executable = pinned_executable()
    parser = recipe.get("parser")
    command = [executable, "run", "--pattern", rule["pattern"], "--lang", str(parser), str(root)]
    if rewrite:
        replacement = rule.get("rewrite")
        if not isinstance(replacement, str):
            raise StructuralError("rewrite requested but recipe has no rewrite")
        command.extend(["--rewrite", replacement])
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True)  # noqa: S603
    except (OSError, subprocess.CalledProcessError) as error:
        raise StructuralError(f"structural engine failed: {error}") from error
    return result.stdout


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--recipe", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--rewrite", action="store_true")
    args = parser.parse_args()
    try:
        recipe = json.loads(args.recipe.read_text(encoding="utf-8"))
        print(run(recipe, args.root.resolve(), rewrite=args.rewrite), end="")
    except (OSError, json.JSONDecodeError, StructuralError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
