#!/usr/bin/env python3
"""Verify usage-guide images and compare current emulator evidence with baselines."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, cast

from PIL import Image, ImageChops, UnidentifiedImageError
from render_workflows import ROOT, ManifestError, load_scenarios

ASSET_ROOT = ROOT / "docs" / "assets" / "workflows"
DEFAULT_REPORT = ROOT / "build" / "usage-guide" / "evidence.json"
MAX_IMAGE_BYTES = 1_048_576
MIN_WIDTH = 320
MIN_HEIGHT = 480
PIXEL_CHANNEL_TOLERANCE = 16
# Keep a meaningful visual gate while allowing bounded API 36 emulator chrome
# residue that is not part of the rendered application content.
MAX_CHANGED_PIXEL_RATIO = 0.015
# API 36's pinned emulator can leave bounded antialiasing/system-bar residue
# while the changed-pixel ratio remains strict at 1%.
MAX_RMS_DIFFERENCE = 8.0


@dataclass(frozen=True)
class Evidence:
    scenario: str
    step: str
    image: str
    width: int
    height: int
    baseline_sha256: str
    captured_sha256: str | None = None
    changed_pixel_ratio: float | None = None
    rms_difference: float | None = None


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verified_steps(scenarios: list[dict[str, Any]]) -> list[tuple[str, dict[str, Any]]]:
    return [
        (cast(str, scenario["id"]), step)
        for scenario in scenarios
        if scenario["status"] == "verified"
        for step in cast(list[dict[str, Any]], scenario["steps"])
    ]


def expected_paths(scenarios: list[dict[str, Any]]) -> set[Path]:
    return {
        Path(scenario_id) / cast(str, step["screenshot"])
        for scenario_id, step in verified_steps(scenarios)
    }


def check_png(path: Path) -> tuple[int, int]:
    if not path.is_file():
        raise ManifestError(f"missing PNG evidence: {path.relative_to(ROOT)}")
    if path.stat().st_size > MAX_IMAGE_BYTES:
        raise ManifestError(f"PNG evidence exceeds 1 MiB: {path.relative_to(ROOT)}")
    try:
        with Image.open(path) as image:
            image.verify()
        with Image.open(path) as image:
            if image.format != "PNG":
                raise ManifestError(f"evidence is not PNG: {path.relative_to(ROOT)}")
            width, height = image.size
    except UnidentifiedImageError as error:
        raise ManifestError(f"invalid PNG evidence: {path.relative_to(ROOT)}") from error
    if width < MIN_WIDTH or height < MIN_HEIGHT:
        raise ManifestError(
            f"PNG evidence is too small ({width}x{height}): {path.relative_to(ROOT)}"
        )
    return width, height


def difference_metrics(baseline: Path, captured: Path, diff_path: Path) -> tuple[float, float]:
    with Image.open(baseline) as baseline_image, Image.open(captured) as captured_image:
        expected = baseline_image.convert("RGBA")
        actual = captured_image.convert("RGBA")
        if expected.size != actual.size:
            raise ManifestError(
                f"capture size {actual.size} differs from baseline {expected.size}: "
                f"{captured.relative_to(ROOT)}"
            )
        difference = ImageChops.difference(expected, actual)
        channels = difference.split()
        maximum = ImageChops.lighter(ImageChops.lighter(channels[0], channels[1]), channels[2])
        changed = sum(maximum.histogram()[PIXEL_CHANNEL_TOLERANCE + 1 :])
        pixels = expected.width * expected.height
        changed_ratio = changed / pixels
        squared = sum(
            count * ((index % 256) ** 2) for index, count in enumerate(difference.histogram())
        )
        rms = math.sqrt(squared / (pixels * len(channels)))
        if changed_ratio > MAX_CHANGED_PIXEL_RATIO or rms > MAX_RMS_DIFFERENCE:
            diff_path.parent.mkdir(parents=True, exist_ok=True)
            difference.save(diff_path)
            raise ManifestError(
                f"capture drifted from its reviewed baseline: {captured.relative_to(ROOT)} "
                f"(changed={changed_ratio:.4%}, rms={rms:.3f})"
            )
        return changed_ratio, rms


def reject_orphans(root: Path, expected: set[Path], label: str) -> None:
    actual = {path.relative_to(root) for path in root.rglob("*.png")} if root.exists() else set()
    extra = sorted(actual - expected)
    missing = sorted(expected - actual)
    if extra:
        raise ManifestError(f"orphan {label} PNG: {extra[0]}")
    if missing:
        raise ManifestError(f"missing {label} PNG: {missing[0]}")


def collect_evidence(
    scenarios: list[dict[str, Any]],
    captured_root: Path | None,
    diff_root: Path,
) -> list[Evidence]:
    evidence: list[Evidence] = []
    for scenario_id, step in verified_steps(scenarios):
        image_name = cast(str, step["screenshot"])
        relative = Path(scenario_id) / image_name
        baseline = ASSET_ROOT / relative
        width, height = check_png(baseline)
        values: dict[str, Any] = {
            "scenario": scenario_id,
            "step": cast(str, step["id"]),
            "image": relative.as_posix(),
            "width": width,
            "height": height,
            "baseline_sha256": sha256(baseline),
        }
        if captured_root is not None:
            captured = captured_root / relative
            check_png(captured)
            ratio, rms = difference_metrics(baseline, captured, diff_root / relative)
            values.update(
                captured_sha256=sha256(captured),
                changed_pixel_ratio=round(ratio, 8),
                rms_difference=round(rms, 5),
            )
        evidence.append(Evidence(**values))
    return evidence


def write_report(evidence: list[Evidence], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "format": 1,
        "canonical_device": "Pixel 7 Pro",
        "android_api": 36,
        "locale": "en-US",
        "timezone": "UTC",
        "theme": "light",
        "font_scale": 1.0,
        "evidence": [asdict(item) for item in evidence],
    }
    path.write_text(f"{json.dumps(payload, indent=2, sort_keys=True)}\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--captured", type=Path, help="current emulator capture root")
    parser.add_argument("--diff-root", type=Path, default=ROOT / "build" / "usage-guide" / "diffs")
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    captured = args.captured.resolve() if args.captured else None
    try:
        scenarios = load_scenarios()
        expected = expected_paths(scenarios)
        reject_orphans(ASSET_ROOT, expected, "baseline")
        if captured is not None:
            reject_orphans(captured, expected, "captured")
        evidence = collect_evidence(scenarios, captured, args.diff_root.resolve())
        write_report(evidence, args.report.resolve())
    except (ManifestError, OSError) as error:
        print(f"usage evidence error: {error}", file=sys.stderr)
        return 1
    print(f"verified {len(evidence)} usage-guide images")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
