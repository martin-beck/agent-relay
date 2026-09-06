from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from verify_documentation_consistency import ConsistencyError, check_consistency


class DocumentationConsistencyTest(unittest.TestCase):
    def write_fixture(self, directory: str, rows: list[tuple[str, str]]) -> tuple[Path, Path]:
        root = Path(directory)
        registry = root / "registry.json"
        registry.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "entries": [
                        {"id": str(index), "area": area, "status": status}
                        for index, (area, status) in enumerate(rows)
                    ],
                }
            ),
            encoding="utf-8",
        )
        readme = root / "README.md"
        rendered = "\n".join([f"| {area} | {status} |" for area, status in rows])
        readme.write_text(
            f"# Example\n\n<!-- generated:status:start -->\n| Area | Status |\n| --- | --- |\n{rendered}\n<!-- generated:status:end -->\n",
            encoding="utf-8",
        )
        return registry, readme

    def test_matching_registry_and_generated_rows_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            registry, readme = self.write_fixture(
                directory, [("A", "Implemented"), ("B", "Planned")]
            )
            self.assertEqual([], check_consistency(registry, readme))

    def test_contradictory_generated_status_has_line_diagnostic(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            registry, readme = self.write_fixture(directory, [("A", "Implemented")])
            readme.write_text(
                readme.read_text(encoding="utf-8").replace("Implemented", "Not available"),
                encoding="utf-8",
            )
            findings = check_consistency(registry, readme)
            self.assertEqual(1, len(findings))
            self.assertIn("README.md:6", findings[0])

    def test_malformed_registry_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            registry = Path(directory) / "registry.json"
            registry.write_text('{"entries": [{"id": "same", "area": "A"}]}', encoding="utf-8")
            readme = Path(directory) / "README.md"
            readme.write_text("", encoding="utf-8")
            with self.assertRaisesRegex(ConsistencyError, "needs string"):
                check_consistency(registry, readme)


if __name__ == "__main__":
    unittest.main()
