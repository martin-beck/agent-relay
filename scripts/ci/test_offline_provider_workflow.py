# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Regression tests for offline-provider workflow isolation and claims."""

from __future__ import annotations

import unittest
from pathlib import Path
from typing import Any, cast

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/offline-provider.yml"


class OfflineProviderWorkflowTest(unittest.TestCase):
    def workflow(self) -> dict[str, Any]:
        return cast(dict[str, Any], yaml.safe_load(WORKFLOW.read_text(encoding="utf-8")))

    def test_normal_pr_trigger_is_read_only_and_same_repository_guarded(self) -> None:
        workflow = self.workflow()
        yaml_document = cast(dict[Any, Any], workflow)
        triggers = yaml_document.get("on", yaml_document.get(True))
        self.assertIsInstance(triggers, dict)
        trigger_map = cast(dict[str, Any], triggers)
        self.assertEqual(set(trigger_map), {"workflow_dispatch", "push", "pull_request"})
        self.assertEqual(workflow["permissions"], {"contents": "read"})
        job = workflow["jobs"]["deterministic-replay"]
        self.assertIn("github.repository_visibility == 'public'", job["if"])
        self.assertIn("head.repo.full_name == github.repository", job["if"])
        self.assertEqual(job["runs-on"], "ubuntu-latest")
        self.assertEqual(job["timeout-minutes"], 45)
        self.assertEqual(job["env"]["AGENT_RELAY_TOOLCHAIN_TARGET"], "quality")
        checkout = job["steps"][0]
        self.assertEqual(
            checkout["with"]["ref"],
            "${{ github.event.pull_request.head.sha || github.sha }}",
        )

    def test_dependencies_stage_before_fresh_network_namespace(self) -> None:
        steps = self.workflow()["jobs"]["deterministic-replay"]["steps"]
        names = [step["name"] for step in steps]
        bootstrap = names.index("Provision checksum-pinned repository toolchain")
        stage = names.index("Stage checksum-locked replay dependencies")
        replay_step = names.index("Run deterministic replay with zero egress")
        self.assertLess(bootstrap, stage)
        self.assertLess(stage, replay_step)
        bootstrap_command = steps[bootstrap]["run"]
        self.assertIn("scripts/bootstrap.py --target quality --install --yes", bootstrap_command)
        self.assertIn("--accept-android-sdk-license", bootstrap_command)
        stage_command = steps[stage]["run"]
        self.assertIn("scripts/with-toolchain", stage_command)
        self.assertIn("--no-daemon", stage_command)
        command = steps[replay_step]["run"]
        self.assertIn("--unshare-net", command)
        self.assertIn("scripts/with-toolchain", command)
        self.assertIn(
            '--source-revision "${{ github.event.pull_request.head.sha || github.sha }}"',
            command,
        )
        self.assertIn("offline-provider-junit.xml", command)
        self.assertIn("offline-provider-summary.json", command)

    def test_actions_are_immutable_and_artifact_is_bounded(self) -> None:
        steps = self.workflow()["jobs"]["deterministic-replay"]["steps"]
        actions = [str(step["uses"]) for step in steps if "uses" in step]
        self.assertTrue(all(len(action.rsplit("@", 1)[1].split()[0]) == 40 for action in actions))
        self.assertFalse(any("setup-java" in action or "setup-uv" in action for action in actions))
        upload = next(step for step in steps if step.get("id") == "upload_offline_provider")
        self.assertIs(upload["continue-on-error"], True)
        self.assertEqual(upload["with"]["retention-days"], 14)
        self.assertEqual(upload["with"]["if-no-files-found"], "error")
        reporter = next(
            step for step in steps if step["name"] == "Report optional evidence transport failure"
        )
        self.assertIn("evidence was unavailable", reporter["run"])
        self.assertNotIn("replay completed", reporter["run"])
