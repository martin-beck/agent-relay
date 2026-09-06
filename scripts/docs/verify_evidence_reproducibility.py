#!/usr/bin/env python3
"""Create or verify a deterministic provenance manifest for verified workflows."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from PIL import Image
from render_workflows import ROOT, load_scenarios
from verify_workflows import ASSET_ROOT, check_png

FORMAT = 1
MAX_AGE_DAYS = 365


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def git(*args: str) -> str:
    # Every call site supplies fixed git subcommands and paths from this repository.
    return subprocess.check_output(  # noqa: S603
        ["git", *args],  # noqa: S607
        cwd=ROOT,
        text=True,
    ).strip()


def evidence_timestamp(path: Path) -> str:
    return git("log", "-1", "--format=%cI", "--", str(path.relative_to(ROOT)))


def build_manifest(source_revision: str, max_age_days: int = MAX_AGE_DAYS) -> dict[str, Any]:
    if len(source_revision) != 40 or git("cat-file", "-t", source_revision) != "commit":
        raise ValueError("source revision must identify a commit in this repository")
    entries: list[dict[str, Any]] = []
    for scenario in load_scenarios():
        if scenario["status"] != "verified":
            continue
        scenario_id = str(scenario["id"])
        for step in scenario["steps"]:
            image_name = str(step["screenshot"])
            image = ASSET_ROOT / scenario_id / image_name
            width, height = check_png(image)
            with Image.open(image) as opened:
                if opened.format != "PNG":
                    raise ValueError(f"evidence is not PNG: {image}")
            entries.append(
                {
                    "scenario": scenario_id,
                    "step": str(step["id"]),
                    "image": image.relative_to(ROOT).as_posix(),
                    "sha256": digest(image),
                    "width": width,
                    "height": height,
                    "alt": str(step["alt"]),
                    "reviewed": True,
                    "captured_at": evidence_timestamp(image),
                }
            )
    return {
        "format": FORMAT,
        "source_revision": source_revision,
        "max_age_days": max_age_days,
        "evidence": entries,
    }


def verify_manifest(manifest: dict[str, Any], now: datetime | None = None) -> None:
    if manifest.get("format") != FORMAT:
        raise ValueError("unsupported evidence manifest format")
    source_revision = manifest.get("source_revision")
    if not isinstance(source_revision, str):
        raise ValueError("manifest source_revision is required")
    expected = build_manifest(source_revision, int(manifest.get("max_age_days", MAX_AGE_DAYS)))
    if manifest != expected:
        raise ValueError("evidence manifest is stale or differs from the current verified assets")
    current = now or datetime.now(UTC)
    max_age = int(manifest["max_age_days"])
    for entry in manifest["evidence"]:
        captured = datetime.fromisoformat(entry["captured_at"])
        age_days = (current - captured).total_seconds() / 86400
        if age_days < -1 or age_days > max_age:
            raise ValueError(
                f"evidence is outside its {max_age}-day freshness window: {entry['image']}"
            )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest", type=Path, default=ROOT / "build" / "usage-guide" / "evidence-manifest.json"
    )
    parser.add_argument("--source-revision", default=None)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        path = args.manifest.resolve()
        if args.check:
            verify_manifest(json.loads(path.read_text(encoding="utf-8")))
        else:
            revision = args.source_revision or git("rev-parse", "HEAD")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(
                json.dumps(build_manifest(revision), indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
        print(f"evidence manifest {'verified' if args.check else 'written'}: {path}")
    except (OSError, ValueError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print(f"evidence manifest error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
