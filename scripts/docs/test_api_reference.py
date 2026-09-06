from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from generate_api_reference import render


class ApiReferenceTest(unittest.TestCase):
    def test_reference_contains_provider_and_typed_python_contracts(self) -> None:
        output = render()
        self.assertIn("ExtensionManifest", output)
        self.assertIn("ExtensionAgentRequest", output)
        self.assertIn("verify_counterexamples", output)
        self.assertIn("verify_manifest", output)

    def test_reference_is_deterministic(self) -> None:
        self.assertEqual(render(), render())


if __name__ == "__main__":
    unittest.main()
