# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Regression contracts for trusted unsupported development APK publication."""

from __future__ import annotations

import re
import unittest
from pathlib import Path
from typing import Any, cast

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/development-apk.yml"
PINNED_ACTION = re.compile(r"^[^@]+@[0-9a-f]{40}$")


def load_workflow() -> dict[str, Any]:
    """Load the workflow while tolerating YAML 1.1's boolean on key."""
    workflow: dict[Any, Any] = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    if True in workflow and "on" not in workflow:
        workflow["on"] = workflow.pop(True)
    return cast(dict[str, Any], workflow)


class DevelopmentApkWorkflowTest(unittest.TestCase):
    """Prove publication is trusted, exact-commit, bounded, and unsupported."""

    def test_only_explicit_trusted_dispatch_can_publish(self) -> None:
        workflow = load_workflow()
        self.assertEqual(set(workflow["on"]), {"workflow_dispatch"})
        inputs = workflow["on"]["workflow_dispatch"]["inputs"]
        self.assertTrue(inputs["source_commit"]["required"])
        self.assertTrue(inputs["publish"]["required"])
        self.assertEqual({"contents": "write"}, workflow["permissions"])
        job = workflow["jobs"]["publish"]
        self.assertIn("github.repository == 'martin-beck/agent-relay'", job["if"])
        self.assertIn("github.event_name == 'workflow_dispatch'", job["if"])
        self.assertIn("inputs.publish == true", job["if"])
        self.assertNotIn("pull_request", str(workflow["on"]))
        self.assertNotIn("workflow_run", str(workflow["on"]))

    def test_exact_commit_and_provenance_guards_are_present(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        for required in (
            "ref: ${{ inputs.source_commit }}",
            "fetch-depth: 0",
            'git merge-base --is-ancestor "${SOURCE_COMMIT}" origin/main',
            '"$(git rev-parse HEAD)" == "${SOURCE_COMMIT}"',
            "git status --porcelain --untracked-files=all",
            '--workflow-run-id "${GITHUB_RUN_ID}"',
            '--workflow-run-attempt "${GITHUB_RUN_ATTEMPT}"',
            "sha256sum --check --strict",
            ".release_status.supported == false",
        ):
            self.assertIn(required, text)
        self.assertEqual(1, text.count("assembleDebug"))

    def test_artifact_and_release_are_strict_and_bounded(self) -> None:
        workflow = load_workflow()
        steps = workflow["jobs"]["publish"]["steps"]
        actions = [step.get("uses") for step in steps if step.get("uses")]
        self.assertTrue(all(PINNED_ACTION.fullmatch(str(action)) for action in actions))
        upload = next(step for step in steps if "upload-artifact" in step.get("uses", ""))
        self.assertEqual(14, upload["with"]["retention-days"])
        self.assertEqual("error", upload["with"]["if-no-files-found"])
        self.assertNotIn("continue-on-error", upload)
        text = WORKFLOW.read_text(encoding="utf-8")
        for required in (
            "refusing overwrite",
            "gh release create",
            "--prerelease",
            '--target "${SOURCE_COMMIT}"',
            "UNSUPPORTED DEVELOPMENT PREVIEW",
            "GH_TOKEN: ${{ github.token }}",
        ):
            self.assertIn(required, text)
        self.assertNotIn("secrets.", text)


if __name__ == "__main__":
    unittest.main()
