# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Security regression tests for public pull-request workflow isolation."""

from __future__ import annotations

import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path
from typing import Any, cast

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_DIR = ROOT / ".github/workflows"
PUBLIC_WORKFLOW = WORKFLOW_DIR / "public-contributor.yml"
PUBLIC_RUNNER = "agent-relay-public-ci"
PUBLIC_RUNNER_DIR = ROOT / "scripts/ci/public_runner"
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
    """Prove untrusted pull requests have only a disposable, read-only lane."""

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
                self.assertEqual(PUBLIC_RUNNER, job["runs-on"])
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
        self.assertEqual(0, checkout["with"]["fetch-depth"])
        setup_android = next(
            step for step in steps if step["name"] == "Set up isolated Android SDK"
        )
        self.assertEqual("", setup_android["with"]["packages"])
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

    def test_public_runner_is_single_job_and_disposable(self) -> None:
        entrypoint = (PUBLIC_RUNNER_DIR / "entrypoint.sh").read_text(encoding="utf-8")
        supervisor = (PUBLIC_RUNNER_DIR / "supervise.sh").read_text(encoding="utf-8")
        dockerfile = (PUBLIC_RUNNER_DIR / "Dockerfile").read_text(encoding="utf-8")

        self.assertIn("--ephemeral", entrypoint)
        self.assertIn("--no-default-labels", entrypoint)
        self.assertIn("--disableupdate", entrypoint)
        self.assertIn("unset RUNNER_REGISTRATION_TOKEN", entrypoint)
        self.assertIn("exec env -i", entrypoint)
        self.assertIn("exec ./run.sh", entrypoint)
        self.assertIn(PUBLIC_RUNNER, entrypoint)

        for required in (
            "--rm",
            "--detach",
            "--pull=never",
            "--read-only",
            "--cap-drop=ALL",
            "no-new-privileges",
            "--network=bridge",
            "runner:rw,exec,nosuid,nodev",
            "tmp:rw,exec,nosuid,nodev",
        ):
            self.assertIn(required, supervisor)
        for forbidden in (
            "--init",
            "--privileged",
            "--network=host",
            "/var/run/docker.sock",
            "--volume",
            " -v ",
        ):
            self.assertNotIn(forbidden, supervisor)
        self.assertIn("registration-token", supervisor)
        self.assertIn("PUBLIC_RUNNER_IMAGE_ID must be an exact sha256 image ID", supervisor)
        self.assertIn("image inspect --format '{{.Id}}'", supervisor)
        self.assertIn('runner_image="$resolved_image_id"', supervisor)
        self.assertIn("flock --nonblock", supervisor)
        self.assertIn("cleanup_stale_registrations", supervisor)
        self.assertIn(PUBLIC_RUNNER, supervisor)

        self.assertRegex(dockerfile, r"FROM ubuntu@sha256:[0-9a-f]{64}")
        self.assertIn("RUNNER_VERSION=2.337.0", dockerfile)
        self.assertIn(" gpg ", dockerfile)
        self.assertIn("libatomic1", dockerfile)
        self.assertIn("gpg-agent", dockerfile)
        self.assertIn(
            "70920811a4f8ad4328818682bca5c6469c1c942fab52448868071d0063816613",
            dockerfile,
        )

    def test_public_runner_rejects_a_substituted_local_image(self) -> None:
        supervisor = PUBLIC_RUNNER_DIR / "supervise.sh"
        inspected_id = "sha256:" + ("a" * 64)
        expected_id = "sha256:" + ("b" * 64)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary_directory = root / "bin"
            runtime_directory = root / "runtime"
            binary_directory.mkdir()
            runtime_directory.mkdir()
            docker = binary_directory / "docker"
            docker.write_text(
                "#!/bin/sh\n"
                'test "$1 $2" = "image inspect" || exit 99\n'
                f"printf '%s\\n' {inspected_id}\n",
                encoding="utf-8",
            )
            docker.chmod(0o700)
            environment = {
                **os.environ,
                "PATH": f"{binary_directory}:/usr/bin:/bin",
                "PUBLIC_RUNNER_DOCKER_COMMAND": "docker",
                "PUBLIC_RUNNER_IMAGE_ID": expected_id,
                "PUBLIC_RUNNER_REPOSITORY": "owner/repository",
                "XDG_RUNTIME_DIR": str(runtime_directory),
            }
            result = subprocess.run(  # noqa: S603
                [str(supervisor)],
                check=False,
                capture_output=True,
                env=environment,
                text=True,
                timeout=5,
            )

        self.assertEqual(1, result.returncode)
        self.assertIn(
            "Disposable runner image does not match PUBLIC_RUNNER_IMAGE_ID.",
            result.stderr,
        )


if __name__ == "__main__":
    unittest.main()
