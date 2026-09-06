from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from render_status import StatusError, load_registry, render, replace_generated, update


class RenderStatusTest(unittest.TestCase):
    def test_repository_registry_is_unique_and_rendered(self) -> None:
        entries = load_registry()
        self.assertGreaterEqual(len(entries), 10)
        self.assertIn("| Generic connection-provider API |", render(entries))

    def test_duplicate_registry_ids_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "registry.json"
            path.write_text(
                '{"schema_version": 1, "entries": [{"id": "same", "area": "A", "status": "B"}, '
                '{"id": "same", "area": "C", "status": "D"}]}',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(StatusError, "duplicate status id"):
                load_registry(path)

    def test_update_check_rejects_stale_generated_document(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "README.md"
            path.write_text(
                "# Example\n\n<!-- generated:status:start -->\nold\n<!-- generated:status:end -->\n"
            )
            generated = "| Area | Status |\n| --- | --- |\n| A | B |"
            with self.assertRaisesRegex(StatusError, "stale"):
                update(path, generated, check=True)
            update(path, generated, check=False)
            self.assertIn(generated, path.read_text(encoding="utf-8"))

    def test_missing_markers_are_rejected(self) -> None:
        with self.assertRaisesRegex(StatusError, "markers"):
            replace_generated("# no generated section", "| A | B |")


if __name__ == "__main__":
    unittest.main()
