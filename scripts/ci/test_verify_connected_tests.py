from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).with_name("verify_connected_tests.py")
SPEC = importlib.util.spec_from_file_location("verify_connected_tests", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load connected-test verifier")
VERIFY = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERIFY
SPEC.loader.exec_module(VERIFY)


class ConnectedTestEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_report(
        self,
        module: str,
        tests: int,
        failures: int = 0,
        errors: int = 0,
        skipped: int = 0,
    ) -> None:
        output = self.root / module / "build/outputs/androidTest-results/connected/debug"
        output.mkdir(parents=True, exist_ok=True)
        (output / f"TEST-{module.replace('/', '_')}.xml").write_text(
            (
                f'<testsuite name="{module}" tests="{tests}" failures="{failures}" '
                f'errors="{errors}" skipped="{skipped}" />'
            ),
            encoding="utf-8",
        )

    def test_accepts_complete_clean_multi_module_evidence(self) -> None:
        self.write_report("app", tests=9)
        self.write_report("ssh/android", tests=1)
        self.write_report("storage/android", tests=2)

        evidence = VERIFY.verify_evidence(
            self.root,
            {"app", "ssh/android", "storage/android"},
            minimum_tests=12,
            minimum_executed=10,
        )

        self.assertEqual({"app", "ssh/android", "storage/android"}, evidence.keys())
        self.assertEqual(9, evidence["app"].executed)

    def test_accepts_sdk_guarded_skips_above_execution_floor(self) -> None:
        self.write_report("app", tests=9, skipped=2)
        self.write_report("ssh/android", tests=1)
        self.write_report("storage/android", tests=2)

        evidence = VERIFY.verify_evidence(
            self.root,
            {"app", "ssh/android", "storage/android"},
            minimum_tests=12,
            minimum_executed=10,
        )

        self.assertEqual(2, evidence["app"].skipped)

    def test_rejects_missing_required_module(self) -> None:
        self.write_report("app", tests=12)

        with self.assertRaisesRegex(VERIFY.EvidenceError, "ssh/android"):
            VERIFY.verify_evidence(
                self.root,
                {"app", "ssh/android"},
                minimum_tests=1,
                minimum_executed=1,
            )

    def test_rejects_failure_even_when_counts_are_high_enough(self) -> None:
        self.write_report("app", tests=12, failures=1)

        with self.assertRaisesRegex(VERIFY.EvidenceError, "1 failures"):
            VERIFY.verify_evidence(
                self.root,
                {"app"},
                minimum_tests=12,
                minimum_executed=10,
            )

    def test_rejects_absent_or_malformed_evidence(self) -> None:
        with self.assertRaisesRegex(VERIFY.EvidenceError, "no connected-test XML"):
            VERIFY.collect_evidence(self.root)

        self.write_report("app", tests=1)
        report = next(self.root.rglob("TEST-*.xml"))
        report.write_text("not XML", encoding="utf-8")
        with self.assertRaisesRegex(VERIFY.EvidenceError, "could not be parsed"):
            VERIFY.collect_evidence(self.root)


if __name__ == "__main__":
    unittest.main()
