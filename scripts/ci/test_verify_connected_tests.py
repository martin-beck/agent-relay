# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

import yaml

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

    def test_rejects_xml_entities(self) -> None:
        self.write_report("app", tests=1)
        report = next(self.root.rglob("TEST-*.xml"))
        report.write_text(
            '<!DOCTYPE testsuite [<!ENTITY count "1">]>'
            '<testsuite tests="&count;" failures="0" errors="0" skipped="0" />',
            encoding="utf-8",
        )

        with self.assertRaisesRegex(VERIFY.EvidenceError, "could not be parsed"):
            VERIFY.collect_evidence(self.root)

    def test_ui_workflow_runs_verifier_in_locked_uv_environment(self) -> None:
        repository_root = SCRIPT_PATH.parents[2]
        workflow = yaml.safe_load(
            (repository_root / ".github/workflows/ui.yml").read_text(encoding="utf-8")
        )
        jobs = workflow["jobs"]
        expected_groups = {
            "current-phone": ("quality", "docs"),
            "extended-matrix": ("quality",),
        }

        for job_name, groups in expected_groups.items():
            steps = jobs[job_name]["steps"]
            setup_step = next(step for step in steps if step.get("name") == "Set up uv")
            self.assertEqual(
                "astral-sh/setup-uv@20cfd1bf945f4377ade1205e4dbc17946fc9a30d",
                setup_step["uses"],
            )
            self.assertFalse(setup_step["with"]["enable-cache"])

            install_step = next(
                step for step in steps if step.get("name") == "Install UI verification dependencies"
            )
            install_command = install_step["run"]
            self.assertIn("uv sync --locked", install_command)
            for group in groups:
                self.assertIn(f"--only-group {group}", install_command)

            verify_step = next(
                step for step in steps if step.get("name") == "Verify connected-test evidence"
            )
            self.assertIn(
                "uv run --only-group quality python scripts/ci/verify_connected_tests.py",
                verify_step["run"],
            )


if __name__ == "__main__":
    unittest.main()
