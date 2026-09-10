# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Security regression tests for public pull-request workflow isolation."""

from __future__ import annotations

import re
import unittest
from pathlib import Path
from typing import Any, cast

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_DIR = ROOT / ".github/workflows"
PUBLIC_WORKFLOW = WORKFLOW_DIR / "public-contributor.yml"
PINNED_ACTION = re.compile(r"^[^@]+@[0-9a-f]{40}$")
FORBIDDEN_PUBLIC_TEXT = (
    "secrets.",
    "vars.",
    "self-hosted",
    "actions/cache@",
    "actions/download-artifact@",
    "actions/upload-artifact@",
    "pull_request_target",
    "workflow_run",
)


def load_workflow(path: Path) -> dict[str, Any]:
    """Load a workflow while tolerating YAML 1.1's boolean on key."""
    workflow: dict[Any, Any] = yaml.safe_load(path.read_text(encoding="utf-8"))
    if True in workflow and "on" not in workflow:
        workflow["on"] = workflow.pop(True)
    return cast(dict[str, Any], workflow)


def events(workflow: dict[str, Any]) -> set[str]:
    """Return the normalized event names for a workflow."""
    configured = workflow["on"]
    if isinstance(configured, str):
        return {configured}
    if isinstance(configured, list):
        return set(configured)
    return set(configured)


class PublicContributorWorkflowTest(unittest.TestCase):
    """Prove untrusted pull requests have only a hosted, read-only lane."""

    def setUp(self) -> None:
        self.paths = sorted(WORKFLOW_DIR.glob("*.yml"))
        self.workflows = {path: load_workflow(path) for path in self.paths}

    def test_pull_requests_cannot_select_self_hosted_or_privileged_jobs(self) -> None:
        for path, workflow in self.workflows.items():
            if "pull_request" not in events(workflow):
                continue
            self.assertEqual(PUBLIC_WORKFLOW, path)
            self.assertEqual({"contents": "read"}, workflow["permissions"])
            for job in workflow["jobs"].values():
                self.assertEqual("ubuntu-latest", job["runs-on"])
                self.assertNotIn("permissions", job)
                self.assertNotIn("environment", job)

    def test_no_workflow_uses_privileged_untrusted_event_bridges(self) -> None:
        for path, workflow in self.workflows.items():
            self.assertNotIn("pull_request_target", events(workflow), path)
            self.assertNotIn("workflow_run", events(workflow), path)

    def test_public_lane_has_no_secret_cache_or_artifact_channel(self) -> None:
        workflow = self.workflows[PUBLIC_WORKFLOW]
        self.assertEqual({"pull_request"}, events(workflow))
        text = PUBLIC_WORKFLOW.read_text(encoding="utf-8")
        for forbidden in FORBIDDEN_PUBLIC_TEXT:
            self.assertNotIn(forbidden, text)

        steps = workflow["jobs"]["validate"]["steps"]
        checkout = steps[0]
        self.assertIs(checkout["with"]["persist-credentials"], False)
        for step in steps:
            action = step.get("uses")
            if action is not None:
                self.assertRegex(action, PINNED_ACTION)

    def test_trusted_runners_accept_only_owner_controlled_events(self) -> None:
        trusted_paths = {
            WORKFLOW_DIR / "verify.yml",
            WORKFLOW_DIR / "ui.yml",
            WORKFLOW_DIR / "awq-shadow.yml",
        }
        for path in trusted_paths:
            workflow = self.workflows[path]
            self.assertNotIn("pull_request", events(workflow))
            self.assertTrue({"push", "workflow_dispatch"} & events(workflow))
            branches = workflow["on"]["push"]["branches"]
            self.assertEqual(["main", "trusted-ci/**"], branches)
            self.assertNotIn(
                "github.event.pull_request",
                path.read_text(encoding="utf-8"),
            )

        for path, workflow in self.workflows.items():
            contains_self_hosted = any(
                "self-hosted" in str(job.get("runs-on", "")) for job in workflow["jobs"].values()
            )
            if contains_self_hosted:
                self.assertNotIn("pull_request", events(workflow), path)


if __name__ == "__main__":
    unittest.main()
