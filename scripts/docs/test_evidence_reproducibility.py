# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

from __future__ import annotations

import copy
import json
import unittest
from datetime import UTC, datetime

from verify_evidence_reproducibility import build_manifest, git, verify_manifest


class EvidenceManifestTest(unittest.TestCase):
    def _current_revision(self) -> str:
        return git("rev-parse", "HEAD")

    def test_manifest_binds_verified_images_to_source_revision(self) -> None:
        manifest = build_manifest(self._current_revision())
        self.assertEqual(1, manifest["format"])
        self.assertGreaterEqual(len(manifest["evidence"]), 1)
        self.assertTrue(all(entry["reviewed"] and entry["alt"] for entry in manifest["evidence"]))
        verify_manifest(manifest, datetime.now(UTC))

    def test_manifest_rejects_changed_hash(self) -> None:
        manifest = build_manifest(self._current_revision())
        changed = copy.deepcopy(manifest)
        changed["evidence"][0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "stale"):
            verify_manifest(changed)

    def test_manifest_is_json_serializable(self) -> None:
        manifest = build_manifest(self._current_revision())
        self.assertEqual(manifest, json.loads(json.dumps(manifest)))
