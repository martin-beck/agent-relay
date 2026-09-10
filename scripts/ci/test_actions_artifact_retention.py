# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Tests for deterministic and bounded Actions artifact retention."""

from __future__ import annotations

import datetime as dt
import unittest
from pathlib import Path

import yaml
from actions_artifact_retention import (
    DELETE_CONFIRMATION,
    Artifact,
    GitHubClient,
    RetentionContext,
    RetentionError,
    RetentionPolicy,
    apply_plan,
    artifact_role,
    plan_retention,
    render_summary,
)

NOW = dt.datetime(2026, 9, 8, tzinfo=dt.UTC)
ROOT = Path(__file__).resolve().parents[2]


def artifact(
    artifact_id: int,
    *,
    name: str = "build-report",
    size_bytes: int = 100,
    age_days: int = 60,
    expired: bool = False,
    run_id: int | None = 100,
    head_sha: str | None = "old-head",
) -> Artifact:
    """Create deterministic artifact metadata."""
    created_at = NOW - dt.timedelta(days=age_days)
    return Artifact(
        artifact_id=artifact_id,
        name=name,
        size_bytes=size_bytes,
        created_at=created_at,
        expires_at=created_at + dt.timedelta(days=14),
        expired=expired,
        run_id=run_id,
        head_sha=head_sha,
    )


def policy(**overrides: int) -> RetentionPolicy:
    """Create a small deterministic quota policy."""
    values = {
        "quota_bytes": 1_000,
        "high_watermark_percent": 80,
        "low_watermark_percent": 50,
        "minimum_age_days": 14,
        "diagnostic_protection_days": 30,
        "max_deletions": 10,
    }
    values.update(overrides)
    return RetentionPolicy(**values)


def context(**overrides: object) -> RetentionContext:
    """Create deterministic live references."""
    values: dict[str, object] = {
        "current_main_sha": "main-head",
        "open_pr_heads": frozenset({"open-head"}),
        "active_run_ids": frozenset({999}),
    }
    values.update(overrides)
    return RetentionContext(**values)  # type: ignore[arg-type]


class ArtifactRetentionPolicyTest(unittest.TestCase):
    """Exercise protection, ranking, hysteresis, and deletion guards."""

    def test_roles_are_conservative(self) -> None:
        self.assertEqual(artifact_role("release-sbom"), "publication")
        self.assertEqual(artifact_role("ui-test-evidence"), "diagnostic")
        self.assertEqual(artifact_role("documentation-maintenance-site"), "documentation")
        self.assertEqual(artifact_role("debug-apk"), "build")

    def test_live_and_sensitive_linkages_are_protected(self) -> None:
        artifacts = (
            artifact(1, run_id=999),
            artifact(2, head_sha="main-head"),
            artifact(3, head_sha="open-head"),
            artifact(4, name="release-provenance"),
            artifact(5, run_id=None, head_sha=None),
        )
        plan = plan_retention(artifacts, context(), policy(quota_bytes=100), NOW)
        records = {item["artifact_id"]: item for item in plan["artifacts"]}
        self.assertIn("active-run", records[1]["protected_reasons"])
        self.assertIn("current-main", records[2]["protected_reasons"])
        self.assertIn("open-pr-head", records[3]["protected_reasons"])
        self.assertIn("release-or-publication", records[4]["protected_reasons"])
        self.assertIn("unknown-provenance", records[5]["protected_reasons"])
        self.assertEqual(plan["selection"]["count"], 0)

    def test_recent_diagnostics_are_protected(self) -> None:
        plan = plan_retention(
            (artifact(1, name="ui-evidence", age_days=20),),
            context(),
            policy(quota_bytes=50),
            NOW,
        )
        self.assertIn("recent-diagnostic", plan["artifacts"][0]["protected_reasons"])
        self.assertFalse(plan["artifacts"][0]["selected"])

    def test_quota_pressure_selects_duplicates_then_old_large_artifacts(self) -> None:
        artifacts = (
            artifact(1, name="build", size_bytes=350, age_days=1),
            artifact(2, name="build", size_bytes=300, age_days=70),
            artifact(3, name="other", size_bytes=300, age_days=50),
            artifact(4, name="small", size_bytes=100, age_days=1),
        )
        plan = plan_retention(artifacts, context(), policy(), NOW)
        selected = [item["artifact_id"] for item in plan["artifacts"] if item["selected"]]
        self.assertEqual(selected, [2, 3])
        self.assertEqual(plan["selection"]["reclaim_bytes"], 600)
        self.assertTrue(plan["quota"]["pressure"])

    def test_below_high_watermark_selects_only_expired(self) -> None:
        artifacts = (
            artifact(1, size_bytes=100, age_days=1),
            artifact(2, size_bytes=100, expired=True),
        )
        plan = plan_retention(artifacts, context(), policy(), NOW)
        selected = [item["artifact_id"] for item in plan["artifacts"] if item["selected"]]
        self.assertEqual(selected, [2])
        self.assertFalse(plan["quota"]["pressure"])

    def test_deletion_count_is_bounded(self) -> None:
        artifacts = tuple(
            artifact(item, size_bytes=200, head_sha=f"head-{item}") for item in range(1, 6)
        )
        plan = plan_retention(artifacts, context(), policy(max_deletions=2), NOW)
        self.assertEqual(plan["selection"]["count"], 2)

    def test_invalid_policy_is_rejected(self) -> None:
        for invalid in (
            policy(quota_bytes=0),
            policy(low_watermark_percent=90),
            policy(max_deletions=101),
        ):
            with self.subTest(invalid=invalid), self.assertRaises(RetentionError):
                invalid.validate()

    def test_apply_requires_exact_confirmation(self) -> None:
        client = RecordingClient()
        plan = plan_retention((artifact(1, expired=True),), context(), policy(), NOW)
        with self.assertRaises(RetentionError):
            apply_plan(client, plan, "delete")
        self.assertEqual(client.deleted, [])

    def test_apply_deletes_only_selected_artifacts(self) -> None:
        client = RecordingClient()
        artifacts = (artifact(1, expired=True), artifact(2, age_days=1))
        plan = plan_retention(artifacts, context(), policy(), NOW)
        deleted = apply_plan(client, plan, DELETE_CONFIRMATION)
        self.assertEqual(deleted, (1,))
        self.assertEqual(client.deleted, [1])
        summary = render_summary(plan, applied=True, deleted=deleted)
        self.assertIn("Mode: **APPLY**", summary)
        self.assertIn("Deleted: 1 artifacts", summary)


class ArtifactRetentionWorkflowTest(unittest.TestCase):
    """Keep the live adapter manual, bounded, and explicit."""

    def test_workflow_has_no_automatic_trigger_and_requires_confirmation(self) -> None:
        workflow = yaml.safe_load(
            (ROOT / ".github/workflows/artifact-retention.yml").read_text(encoding="utf-8")
        )
        triggers = workflow.get("on", workflow.get(True))
        self.assertEqual(set(triggers), {"workflow_dispatch"})
        inputs = triggers["workflow_dispatch"]["inputs"]
        self.assertTrue(inputs["quota_mib"]["required"])
        self.assertEqual(inputs["max_deletions"]["default"], 25)
        self.assertFalse(inputs["apply"]["default"])
        self.assertIn(DELETE_CONFIRMATION, inputs["confirmation"]["description"])
        self.assertEqual(workflow["permissions"]["actions"], "write")

    def test_workflow_runs_on_build_pool_and_never_uploads_an_artifact(self) -> None:
        workflow_path = ROOT / ".github/workflows/artifact-retention.yml"
        workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
        job = workflow["jobs"]["retention"]
        self.assertEqual(job["runs-on"], ["self-hosted", "linux", "x64", "agent-relay-build-ci"])
        rendered = workflow_path.read_text(encoding="utf-8")
        self.assertNotIn("actions/upload-artifact", rendered)
        self.assertIn("--max-deletions", rendered)
        self.assertIn("--confirm", rendered)


class RecordingClient(GitHubClient):
    """Record deletion requests without making network calls."""

    def __init__(self) -> None:
        self.deleted: list[int] = []

    def delete_artifact(self, artifact_id: int) -> None:
        self.deleted.append(artifact_id)


if __name__ == "__main__":
    unittest.main()
