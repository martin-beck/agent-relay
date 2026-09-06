from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).parent))

from run_fm_agent_pilot import run
from verify_fm_agent_pilot import verify


class FmAgentPilotTest(unittest.TestCase):
    def test_report_is_deterministic_and_independently_verified(self) -> None:
        report = run()
        self.assertEqual(report, run())
        verify(report)
        self.assertEqual({"python", "java"}, {item["language"] for item in report["observations"]})

    def test_report_tampering_is_rejected(self) -> None:
        report = run()
        report["observations"][0]["sha256"] = "0" * 64
        with self.assertRaises(AssertionError):
            verify(report)

    def test_manifest_rejects_path_escape(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "config").mkdir()
            manifest = root / "config" / "pilot.json"
            manifest.write_text(
                json.dumps(
                    {
                        "pilot": {
                            "network": "disabled",
                            "source_upload": False,
                            "max_bytes_per_module": 100,
                        },
                        "modules": [
                            {"id": "escape", "path": "../outside.py", "language": "python"}
                        ],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                run(root, manifest)
