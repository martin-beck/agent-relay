from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from typing import Any

import yaml
from hypothesis import given, settings, strategies
from PIL import Image
from render_workflows import ManifestError, render_scenario, validate_manifest, write_or_check
from verify_workflows import check_png, difference_metrics, reject_orphans


def scenario(status: str = "verified") -> dict[str, Any]:
    step: dict[str, Any] = {
        "id": "open-app",
        "title": "Open the app",
        "action": "Launch Agent Relay.",
        "expected": "The session hub is visible.",
    }
    manifest: dict[str, Any] = {
        "id": "sample",
        "title": "Sample journey",
        "summary": "A deterministic sample.",
        "status": status,
        "order": 1,
        "goal": "Reach a useful state.",
        "preconditions": ["A clean fixture is available."],
        "automatic": ["The fixture seeds safe data."],
        "attention": ["Review the visible state."],
        "recovery": ["Retry after correcting the fixture."],
        "steps": [step],
    }
    if status == "verified":
        manifest["verified_test"] = "example.UsageJourneyTest#captures"
        step["screenshot"] = "open-app.png"
        step["alt"] = "Agent Relay showing a deterministic empty session hub."
    return manifest


class RenderWorkflowsTest(unittest.TestCase):
    def test_verified_manifest_renders_emulator_evidence(self) -> None:
        manifest = validate_manifest(scenario(), Path("sample.yml"))

        rendered = render_scenario(manifest)

        self.assertIn("Verified by the named Android emulator journey", rendered)
        self.assertIn("![Agent Relay showing", rendered)
        self.assertIn("example.UsageJourneyTest#captures", rendered)

    def test_planned_manifest_rejects_screenshot_claim(self) -> None:
        manifest = scenario("planned")
        manifest["steps"][0]["screenshot"] = "claim.png"

        with self.assertRaisesRegex(ManifestError, "cannot claim image evidence"):
            validate_manifest(manifest, Path("sample.yml"))

    def test_check_reports_stale_generated_output_without_writing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "page.md"
            target.write_text("old\n", encoding="utf-8")

            changed = write_or_check({target: "new\n"}, check=True)

            self.assertEqual([target], changed)
            self.assertEqual("old\n", target.read_text(encoding="utf-8"))

    def test_small_pixel_difference_is_tolerated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            expected = root / "expected.png"
            actual = root / "actual.png"
            diff = root / "diff.png"
            Image.new("RGBA", (400, 600), "white").save(expected)
            changed = Image.new("RGBA", (400, 600), "white")
            changed.putpixel((10, 10), (250, 250, 250, 255))
            changed.save(actual)

            ratio, rms = difference_metrics(expected, actual, diff)

            self.assertLess(ratio, 0.015)
            self.assertLess(rms, 8.0)
            self.assertFalse(diff.exists())

    @settings(derandomize=True, deadline=None, max_examples=30)
    @given(
        width=strategies.integers(min_value=320, max_value=340),
        height=strategies.integers(min_value=480, max_value=500),
    )
    def test_png_dimension_boundaries_accept_valid_images(self, width: int, height: int) -> None:
        with tempfile.TemporaryDirectory() as directory:
            image = Path(directory) / "evidence.png"
            Image.new("RGB", (width, height), "white").save(image)

            self.assertEqual((width, height), check_png(image))

    @settings(derandomize=True, deadline=None, max_examples=30)
    @given(
        names=strategies.sets(
            strategies.from_regex(r"case-[a-z0-9]{1,8}\.png", fullmatch=True),
            min_size=1,
            max_size=6,
        ),
    )
    def test_expected_png_paths_reject_any_orphan(self, names: set[str]) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            expected = {Path("sample") / name for name in names}
            for relative in expected:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()

            reject_orphans(root, expected, "property-test")
            orphan = root / "sample/unexpected.png"
            orphan.touch()

            with self.assertRaisesRegex(ManifestError, "orphan property-test PNG"):
                reject_orphans(root, expected, "property-test")

    @settings(derandomize=True, max_examples=80)
    @given(stem=strategies.from_regex(r"[a-z0-9]+(?:-[a-z0-9]+){0,3}", fullmatch=True))
    def test_yaml_manifest_accepts_safe_local_screenshot_names(self, stem: str) -> None:
        manifest = scenario()
        manifest["steps"][0]["screenshot"] = f"{stem}.png"
        loaded = yaml.safe_load(yaml.safe_dump(manifest))

        validated = validate_manifest(loaded, Path("sample.yml"))

        self.assertEqual(f"{stem}.png", validated["steps"][0]["screenshot"])

    @settings(derandomize=True, max_examples=80)
    @given(
        directory=strategies.sampled_from(("..", ".", "nested")),
        stem=strategies.from_regex(r"[a-z0-9]+(?:-[a-z0-9]+){0,3}", fullmatch=True),
    )
    def test_yaml_manifest_rejects_nonlocal_screenshot_paths(
        self,
        directory: str,
        stem: str,
    ) -> None:
        manifest = scenario()
        manifest["steps"][0]["screenshot"] = f"{directory}/{stem}.png"
        loaded = yaml.safe_load(yaml.safe_dump(manifest))

        with self.assertRaisesRegex(ManifestError, "one PNG file name"):
            validate_manifest(loaded, Path("sample.yml"))


if __name__ == "__main__":
    unittest.main()
