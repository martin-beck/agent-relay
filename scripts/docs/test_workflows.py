from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from typing import Any

from PIL import Image
from render_workflows import ManifestError, render_scenario, validate_manifest, write_or_check
from verify_workflows import difference_metrics


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

            self.assertLess(ratio, 0.01)
            self.assertLess(rms, 4.0)
            self.assertFalse(diff.exists())


if __name__ == "__main__":
    unittest.main()
