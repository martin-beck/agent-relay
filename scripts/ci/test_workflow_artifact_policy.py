# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Regression contracts for non-authoritative CI artifact transport."""

import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
DOCUMENTATION_WORKFLOW = ROOT / ".github/workflows/docs-maintenance.yml"
WORKFLOWS = (
    ROOT / ".github/workflows/verify.yml",
    ROOT / ".github/workflows/ui.yml",
    DOCUMENTATION_WORKFLOW,
    ROOT / ".github/workflows/offline-provider.yml",
    ROOT / ".github/workflows/local-inference.yml",
)
UPLOAD_ACTION = "actions/upload-artifact@"
PAGES_UPLOAD_ACTION = "actions/upload-pages-artifact@"


def workflow_steps(path: Path) -> list[dict[str, object]]:
    """Return every concrete workflow step."""
    workflow = yaml.safe_load(path.read_text(encoding="utf-8"))
    return [step for job in workflow["jobs"].values() for step in job.get("steps", ())]


class WorkflowArtifactPolicyTest(unittest.TestCase):
    """Protect authoritative CI results from optional artifact transport."""

    def test_optional_artifact_uploads_are_quota_tolerant_and_reported(self) -> None:
        uploads: list[dict[str, object]] = []
        reporters: list[dict[str, object]] = []

        for path in WORKFLOWS:
            steps = workflow_steps(path)
            uploads.extend(
                step for step in steps if str(step.get("uses", "")).startswith(UPLOAD_ACTION)
            )
            reporters.extend(
                step
                for step in steps
                if "GITHUB_STEP_SUMMARY" in str(step.get("run", ""))
                and ".outcome" in str(step.get("if", ""))
            )

        self.assertEqual(len(uploads), 11)
        self.assertTrue(all(step.get("continue-on-error") is True for step in uploads))
        self.assertTrue(all(step.get("id") for step in uploads))
        referenced_outcomes = "\n".join(str(step.get("if", "")) for step in reporters)
        self.assertTrue(
            all(f"steps.{step['id']}.outcome" in referenced_outcomes for step in uploads)
        )

    def test_authoritative_commands_and_pages_publication_remain_strict(self) -> None:
        steps = [step for path in WORKFLOWS for step in workflow_steps(path)]
        command_steps = [step for step in steps if "run" in step]
        pages_uploads = [
            step for step in steps if str(step.get("uses", "")).startswith(PAGES_UPLOAD_ACTION)
        ]

        self.assertTrue(all("continue-on-error" not in step for step in command_steps))
        self.assertEqual(len(pages_uploads), 1)
        self.assertNotIn("continue-on-error", pages_uploads[0])

    def test_documentation_maintenance_uses_build_runner_and_short_optional_retention(
        self,
    ) -> None:
        workflow = yaml.safe_load(DOCUMENTATION_WORKFLOW.read_text(encoding="utf-8"))
        job = workflow["jobs"]["verify"]
        upload = next(
            step for step in job["steps"] if str(step.get("uses", "")).startswith(UPLOAD_ACTION)
        )

        self.assertEqual(
            ["self-hosted", "linux", "x64", "agent-relay-build-ci"],
            job["runs-on"],
        )
        self.assertEqual(3, upload["with"]["retention-days"])
        self.assertEqual("error", upload["with"]["if-no-files-found"])
        self.assertIs(upload["continue-on-error"], True)
