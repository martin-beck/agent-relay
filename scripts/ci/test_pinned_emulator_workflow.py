# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Regression contracts for the pinned emulator's public runner bootstrap."""

from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from typing import Any, cast

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/ui.yml"
ACTIONLINT_CONFIG = ROOT / ".github/actionlint.yaml"
SCRIPT = ROOT / "scripts/ci/install_pinned_emulator.sh"
RUNTIME_DEPENDENCIES_SCRIPT = ROOT / "scripts/ci/ensure_android_emulator_runtime_dependencies.sh"


def load_workflow() -> dict[str, Any]:
    """Load the UI workflow while tolerating YAML 1.1's boolean `on` key."""
    workflow: dict[Any, Any] = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    if True in workflow and "on" not in workflow:
        workflow["on"] = workflow.pop(True)
    return cast(dict[str, Any], workflow)


class PinnedEmulatorWorkflowTest(unittest.TestCase):
    """Keep Ubuntu runners ready for the pinned emulator binary."""

    def test_current_phone_uses_the_dedicated_kvm_runner(self) -> None:
        workflow = load_workflow()
        current_phone = workflow["jobs"]["current-phone"]
        self.assertEqual(
            ["self-hosted", "linux", "x64", "agent-relay-ui-kvm"],
            current_phone["runs-on"],
        )
        self.assertIn(
            "test -c /dev/kvm",
            "\n".join(
                str(step.get("run", ""))
                for step in current_phone["steps"]
                if isinstance(step, dict)
            ),
        )
        actionlint = yaml.safe_load(ACTIONLINT_CONFIG.read_text(encoding="utf-8"))
        self.assertIn(
            "agent-relay-ui-kvm",
            actionlint["self-hosted-runner"]["labels"],
        )

    def test_extended_matrix_stays_on_hosted_runners(self) -> None:
        workflow = load_workflow()
        self.assertEqual("ubuntu-latest", workflow["jobs"]["extended-matrix"]["runs-on"])

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
            self.assertEqual(
                "bash scripts/ci/ensure_android_emulator_runtime_dependencies.sh",
                install_step["run"],
            )
            if job is workflow["jobs"]["current-phone"]:
                self.assertEqual(
                    "1",
                    install_step["env"]["AGENT_RELAY_UNPRIVILEGED_RUNNER"],
                )
            install_text = RUNTIME_DEPENDENCIES_SCRIPT.read_text(encoding="utf-8")
            for package in expected:
                self.assertIn(package, install_text)
        self.assertIn("sudo -n apt-get", install_text)
        self.assertIn("AGENT_RELAY_UNPRIVILEGED_RUNNER:-0", install_text)
        self.assertEqual(2, checked_jobs)

    def test_emulator_bootstrap_reports_missing_tools_and_launch_errors(self) -> None:
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("command -v unzip", script)
        self.assertIn("unzip is required to install the pinned Android emulator", script)
        self.assertIn("Pinned Android emulator failed to start", script)
        self.assertIn('ldd "$emulator_bin"', script)

    def test_unprivileged_dependency_check_does_not_invoke_sudo(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / "sudo-invoked"
            (root / "sudo").write_text(
                f"#!/bin/sh\nprintf invoked > {marker}\nexit 99\n",
                encoding="utf-8",
            )
            (root / "dpkg-query").write_text(
                "#!/bin/sh\nprintf 'install ok installed'\n",
                encoding="utf-8",
            )
            for command in ("sudo", "dpkg-query"):
                (root / command).chmod(0o755)
            environment = os.environ | {
                "AGENT_RELAY_UNPRIVILEGED_RUNNER": "1",
                "PATH": f"{root}:{os.environ['PATH']}",
            }
            result = subprocess.run(  # noqa: S603
                ["bash", str(RUNTIME_DEPENDENCIES_SCRIPT)],  # noqa: S607
                check=False,
                capture_output=True,
                text=True,
                env=environment,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(marker.exists())

    def test_unprivileged_dependency_check_reports_missing_package(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "dpkg-query").write_text(
                '#!/bin/sh\nif [ "$3" = "libasound2t64" ]; then exit 1; fi\n'
                "printf 'install ok installed'\n",
                encoding="utf-8",
            )
            (root / "dpkg-query").chmod(0o755)
            environment = os.environ | {
                "AGENT_RELAY_UNPRIVILEGED_RUNNER": "1",
                "PATH": f"{root}:{os.environ['PATH']}",
            }
            result = subprocess.run(  # noqa: S603
                ["bash", str(RUNTIME_DEPENDENCIES_SCRIPT)],  # noqa: S607
                check=False,
                capture_output=True,
                text=True,
                env=environment,
            )
            self.assertEqual(1, result.returncode)
            self.assertIn("libasound2t64", result.stderr)


if __name__ == "__main__":
    unittest.main()
