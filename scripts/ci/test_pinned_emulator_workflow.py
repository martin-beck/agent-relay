# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Regression contracts for the pinned emulator's public runner bootstrap."""

from __future__ import annotations

import unittest
from pathlib import Path
from typing import Any, cast

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/ui.yml"
SCRIPT = ROOT / "scripts/ci/install_pinned_emulator.sh"


def load_workflow() -> dict[str, Any]:
    """Load the UI workflow while tolerating YAML 1.1's boolean `on` key."""
    workflow: dict[Any, Any] = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    if True in workflow and "on" not in workflow:
        workflow["on"] = workflow.pop(True)
    return cast(dict[str, Any], workflow)


class PinnedEmulatorWorkflowTest(unittest.TestCase):
    """Keep Ubuntu runners ready for the pinned emulator binary."""

    def test_all_ui_jobs_install_emulator_runtime_dependencies(self) -> None:
        workflow = load_workflow()
        expected = (
            "libasound2t64",
            "libdbus-1-3",
            "libfontconfig1",
            "libgl1",
            "libpulse0",
            "libx11-6",
            "libxcb1",
            "libxcomposite1",
            "libxcursor1",
            "libxi6",
            "libxrandr2",
            "libxtst6",
            "unzip",
        )
        checked_jobs = 0
        for job in workflow["jobs"].values():
            if not isinstance(job, dict) or "steps" not in job:
                continue
            steps = job["steps"]
            emulator_indices = [
                index
                for index, step in enumerate(steps)
                if step.get("name") == "Repair and validate pinned Android emulator"
            ]
            if not emulator_indices:
                continue
            checked_jobs += 1
            emulator_index = emulator_indices[0]
            install_step = steps[emulator_index - 1]
            self.assertEqual("Install Android emulator runtime dependencies", install_step["name"])
            install_text = str(install_step["run"])
            for package in expected:
                self.assertIn(package, install_text)
        self.assertEqual(2, checked_jobs)

    def test_emulator_bootstrap_reports_missing_tools_and_launch_errors(self) -> None:
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("command -v unzip", script)
        self.assertIn("unzip is required to install the pinned Android emulator", script)
        self.assertIn("Pinned Android emulator failed to start", script)
        self.assertIn('ldd "$emulator_bin"', script)


if __name__ == "__main__":
    unittest.main()
