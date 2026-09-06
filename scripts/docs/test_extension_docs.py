from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from generate_extension_api import render
from verify_extension_examples import check


class ExtensionDocumentationTest(unittest.TestCase):
    def test_generated_reference_contains_extension_contracts(self) -> None:
        output = render()
        self.assertIn("ExtensionManifest", output)
        self.assertIn("ExtensionSandboxPolicy", output)
        self.assertIn("ExtensionExecutionEvidence", output)

    def test_fixture_catalogue_has_all_required_redacted_examples(self) -> None:
        self.assertEqual([], check())

    def test_fixture_catalogue_rejects_missing_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "examples.yaml"
            path.write_text("examples:\n  - name: incomplete\n", encoding="utf-8")
            self.assertTrue(check(path))


if __name__ == "__main__":
    unittest.main()
