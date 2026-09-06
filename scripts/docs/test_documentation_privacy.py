from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from verify_documentation_privacy import scan


class DocumentationPrivacyTest(unittest.TestCase):
    def test_clean_synthetic_fixture_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "docs").mkdir()
            (root / "docs/example.md").write_text("Use host.example.test with token <redacted>.\n")
            self.assertEqual([], scan(root))

    def test_private_key_and_credential_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "docs").mkdir()
            (root / "docs/leak.md").write_text(
                "-----BEGIN " + "PRIVATE KEY-----\n" + "password: super-secret-value-1234\n",
            )
            findings = scan(root)
            self.assertEqual(2, len(findings))
            self.assertIn("private key material", findings[0])
            self.assertIn("credential assignment", findings[1])


if __name__ == "__main__":
    unittest.main()
