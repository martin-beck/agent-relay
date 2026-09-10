# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Tests for the tiered offline-provider assurance runner."""

from __future__ import annotations

import copy
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, cast
from unittest.mock import patch

from bounded_subprocess import BoundedProcessError, run_bounded
from run_offline_provider_assurance import (
    AssuranceError,
    CheckResult,
    load_json,
    main,
    run,
    run_fixture_and_cassette,
    summary_document,
    validate_contract,
    write_junit,
    write_summary,
)

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "config/offline-provider-assurance-v1.json"
REVISION = "1" * 40


class OfflineProviderAssuranceTest(unittest.TestCase):
    def contract(self) -> dict[str, object]:
        return load_json(CONTRACT)

    def test_contract_keeps_four_evidence_tiers_separate(self) -> None:
        contract = self.contract()
        validate_contract(contract)
        self.assertEqual(contract["wireEmulator"]["status"], "not-admitted")  # type: ignore[index]
        self.assertFalse(contract["liveProvider"]["automatedByThisWorkflow"])  # type: ignore[index]
        self.assertEqual(contract["liveProvider"]["satisfiesFromMockOrLocal"], [])  # type: ignore[index]
        local_workflow = (ROOT / ".github/workflows/local-inference.yml").read_text()
        self.assertIn("local-inference-junit.xml", local_workflow)

    def test_contract_rejects_emulator_and_live_claim_inflation(self) -> None:
        contract = copy.deepcopy(self.contract())
        contract["wireEmulator"]["scenarios"] = ["tool-round"]  # type: ignore[index]
        with self.assertRaisesRegex(AssuranceError, "exceed"):
            validate_contract(contract)
        contract = copy.deepcopy(self.contract())
        contract["liveProvider"]["satisfiesFromMockOrLocal"] = ["authentication"]  # type: ignore[index]
        with self.assertRaisesRegex(AssuranceError, "cannot satisfy"):
            validate_contract(contract)

    def test_committed_fixture_and_cassette_complete_strict_replay(self) -> None:
        contract = self.contract()
        run_fixture_and_cassette(contract["deterministicReplay"])  # type: ignore[arg-type]

    def test_run_requires_exact_revision_and_network_namespace(self) -> None:
        with self.assertRaisesRegex(AssuranceError, "exact 40-character"):
            run("short", "1")
        current = str(os.stat("/proc/self/ns/net").st_ino)
        with (
            patch.dict(os.environ, {"AGENT_RELAY_OUTBOUND_NETWORK": "deny"}, clear=True),
            self.assertRaisesRegex(AssuranceError, "failed closed"),
        ):
            run(REVISION, current)

    def test_run_executes_gradle_replay_and_emits_bounded_redacted_evidence(self) -> None:
        completed = subprocess.CompletedProcess([], 0, b"ok", b"")
        with (
            patch.dict(os.environ, {"AGENT_RELAY_OUTBOUND_NETWORK": "deny"}, clear=True),
            patch(
                "run_offline_provider_assurance.require_network_namespace",
                return_value=None,
            ),
            patch(
                "run_offline_provider_assurance.run_bounded",
                return_value=completed,
            ) as call,
        ):
            contract, checks = run(REVISION, "1")
        command = call.call_args.args[0]
        environment = call.call_args.kwargs["env"]
        self.assertIn("--offline", command)
        self.assertIn("--rerun-tasks", command)
        self.assertIn("agent-relay-offline-provider-", environment["HOME"])
        self.assertNotEqual(environment["HOME"], str(Path.home()))
        self.assertTrue(environment["ANDROID_USER_HOME"].startswith(environment["HOME"]))
        self.assertTrue(environment["XDG_DATA_HOME"].startswith(environment["HOME"]))
        self.assertEqual(call.call_args.kwargs["max_output_bytes"], 65_536)
        self.assertEqual([check.result for check in checks], ["passed", "passed", "passed"])
        summary = summary_document(REVISION, checks, contract)
        rendered = json.dumps(summary)
        self.assertNotIn(str(Path.home()), rendered)
        self.assertEqual(summary["result"], "passed")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_junit(root / "result.xml", checks)
            write_summary(root / "summary.json", summary, 16_384)
            self.assertIn("offline-provider-assurance", (root / "result.xml").read_text())
            self.assertLess((root / "summary.json").stat().st_size, 16_384)

    def test_failed_junit_suppresses_private_command_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.xml"
            write_junit(path, [CheckResult("provider-replay", "failed")])
            rendered = path.read_text(encoding="utf-8")
        self.assertIn("Private command output is intentionally suppressed.", rendered)
        self.assertNotIn("stdout", rendered)

    def test_failure_summary_does_not_repeat_untrusted_revision(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            summary = root / "summary.json"
            arguments = [
                "run_offline_provider_assurance.py",
                "--source-revision",
                "private-path-or-prompt",
                "--parent-netns-inode",
                "1",
                "--junit",
                str(root / "result.xml"),
                "--summary",
                str(summary),
            ]
            with patch("sys.argv", arguments):
                self.assertEqual(main(), 1)
            rendered = summary.read_text(encoding="utf-8")
        self.assertEqual(json.loads(rendered)["sourceRevision"], "0" * 40)
        self.assertNotIn("private-path-or-prompt", rendered)

    def test_malformed_policy_still_emits_bounded_failure_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            policy = root / "policy.json"
            malformed: Any = copy.deepcopy(self.contract())
            live = cast(dict[str, Any], malformed["liveProvider"])
            live["requiredClaims"] = [{}]
            policy.write_text(json.dumps(malformed), encoding="utf-8")
            junit = root / "result.xml"
            summary = root / "summary.json"
            arguments = [
                "run_offline_provider_assurance.py",
                "--source-revision",
                REVISION,
                "--parent-netns-inode",
                "1",
                "--junit",
                str(junit),
                "--summary",
                str(summary),
            ]
            with (
                patch("run_offline_provider_assurance.CONTRACT_PATH", policy),
                patch("sys.argv", arguments),
            ):
                self.assertEqual(main(), 1)
            report = json.loads(summary.read_text(encoding="utf-8"))
            self.assertEqual(report["result"], "failed")
            self.assertEqual(report["sourceRevision"], REVISION)
            self.assertLess(summary.stat().st_size, 16_384)
            self.assertIn('failures="1"', junit.read_text(encoding="utf-8"))

    def test_subprocess_output_limit_is_enforced_while_streaming(self) -> None:
        command = [sys.executable, "-c", "import os; os.write(1, b'x' * 4096)"]
        with self.assertRaisesRegex(BoundedProcessError, "output budget"):
            run_bounded(
                command,
                cwd=ROOT,
                env=os.environ.copy(),
                timeout_seconds=10,
                max_output_bytes=32,
            )
