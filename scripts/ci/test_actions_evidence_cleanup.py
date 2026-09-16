# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Tests for exact-ID Actions evidence cleanup."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from typing import Any

from actions_evidence_cleanup import CONFIRMATION, CleanupError, apply_manifest, load_manifest


class RecordingClient:
    """Record exact deletion requests without network access."""

    def __init__(self) -> None:
        self.deleted: list[tuple[str, int]] = []
        self.replacements = {
            100: {"conclusion": "success", "head_sha": "a" * 40, "status": "completed"}
        }

    def replacement_run(self, run_id: int) -> dict[str, Any] | None:
        return self.replacements.get(run_id)

    def delete_exact(self, kind: str, target_id: int) -> None:
        self.deleted.append((kind, target_id))


class ActionsEvidenceCleanupTest(unittest.TestCase):
    """Keep manifest validation fail-closed and deletion exact."""

    def manifest(self) -> dict[str, object]:
        return {
            "repository": "owner/repository",
            "replacement_evidence": [{"head_sha": "a" * 40, "run_id": 100, "verified": True}],
            "schema_version": 1,
            "targets": [
                {
                    "evidence_role": "private-era-log",
                    "id": 200,
                    "kind": "run",
                    "privacy_classification": "self-hosted-runner-identifier",
                    "protection_decision": "delete-after-replacement",
                    "replacement_run_id": 100,
                },
                {
                    "evidence_role": "private-era-artifact",
                    "id": 300,
                    "kind": "artifact",
                    "privacy_classification": "self-hosted-runner-identifier",
                    "protection_decision": "delete-after-replacement",
                    "replacement_run_id": 100,
                },
            ],
        }

    def test_manifest_requires_verified_replacement(self) -> None:
        value = self.manifest()
        value["replacement_evidence"] = []
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(CleanupError):
                load_manifest(path)

    def test_apply_requires_exact_confirmation_and_deletes_only_manifest_targets(self) -> None:
        client = RecordingClient()
        manifest = self.manifest()
        with self.assertRaises(CleanupError):
            apply_manifest(manifest, client, "delete")
        self.assertEqual(client.deleted, [])
        self.assertEqual(
            apply_manifest(manifest, client, CONFIRMATION),
            [{"kind": "run", "id": 200}, {"kind": "artifact", "id": 300}],
        )

    def test_manifest_rejects_unknown_replacement(self) -> None:
        value = self.manifest()
        value["targets"][0]["replacement_run_id"] = 999  # type: ignore[index]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(CleanupError):
                load_manifest(path)

    def test_apply_rejects_replacement_that_is_not_terminal_success(self) -> None:
        client = RecordingClient()
        client.replacements[100]["conclusion"] = "failure"
        with self.assertRaises(CleanupError):
            apply_manifest(self.manifest(), client, CONFIRMATION)
        self.assertEqual(client.deleted, [])


if __name__ == "__main__":
    unittest.main()
