# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Run a privacy-safe, deterministic FM-Agent specification pilot."""

from __future__ import annotations

import ast
import hashlib
import json
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "config" / "fm-agent-pilot.json"
JAVA_DECLARATION = re.compile(
    r"\b(?:public|protected)\s+(?:final\s+)?(?:class|interface|enum)\s+(\w+)"
)


def _load_manifest(path: Path = MANIFEST) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("pilot manifest must be an object")
    return value


def _validate_module(module: dict[str, Any], root: Path, limit: int) -> tuple[Path, bytes]:
    relative = module.get("path")
    if not isinstance(relative, str) or Path(relative).is_absolute():
        raise ValueError("module paths must be relative")
    path = (root / relative).resolve()
    try:
        path.relative_to(root.resolve())
    except ValueError as error:
        raise ValueError("module path escapes checkout") from error
    if not path.is_file() or path.is_symlink():
        raise ValueError(f"module is missing or symlinked: {relative}")
    data = path.read_bytes()
    if len(data) > limit:
        raise ValueError(f"module exceeds bounded size: {relative}")
    return path, data


def _python_observation(data: bytes) -> dict[str, Any]:
    ast.parse(data.decode("utf-8"))
    return {"check": "syntax", "result": "accepted", "evidence": "mechanically-checked"}


def _java_observation(data: bytes) -> dict[str, Any]:
    text = data.decode("utf-8")
    if text.count("{") != text.count("}"):
        raise ValueError("Java brace structure is unbalanced")
    declarations = sorted(set(JAVA_DECLARATION.findall(text)))
    return {
        "check": "bounded-structure",
        "result": "accepted",
        "declarations": declarations,
        "evidence": "mechanically-checked",
    }


def run(root: Path = ROOT, manifest_path: Path = MANIFEST) -> dict[str, Any]:
    manifest = _load_manifest(manifest_path)
    pilot = manifest.get("pilot")
    modules = manifest.get("modules")
    if (
        not isinstance(pilot, dict)
        or pilot.get("network") != "disabled"
        or pilot.get("source_upload") is not False
    ):
        raise ValueError("pilot must disable network and source upload")
    if not isinstance(modules, list) or not modules:
        raise ValueError("pilot modules are required")
    limit = pilot.get("max_bytes_per_module")
    if not isinstance(limit, int) or limit <= 0:
        raise ValueError("pilot byte bound is invalid")
    observations: list[dict[str, Any]] = []
    for module in modules:
        if not isinstance(module, dict):
            raise ValueError("each pilot module must be an object")
        _path, data = _validate_module(module, root, limit)
        language = module.get("language")
        observation = _python_observation(data) if language == "python" else _java_observation(data)
        observations.append(
            {
                "id": module.get("id"),
                "path": module.get("path"),
                "language": language,
                "sha256": hashlib.sha256(data).hexdigest(),
                **observation,
            }
        )
    return {
        "schema_version": 1,
        "pilot": {
            "name": pilot.get("name"),
            "version": pilot.get("version"),
            "mode": pilot.get("mode"),
        },
        "observations": observations,
        "limitations": [
            "No model was contacted and no source was uploaded.",
            "Mechanical checks do not establish bug absence or a security proof.",
            "Results depend on the pinned checkout and standard-library parsers.",
        ],
    }


if __name__ == "__main__":
    print(json.dumps(run(), sort_keys=True, indent=2))
