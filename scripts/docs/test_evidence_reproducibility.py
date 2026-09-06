from __future__ import annotations

import copy
import json
import unittest
from datetime import UTC, datetime

from verify_evidence_reproducibility import build_manifest, verify_manifest


class EvidenceManifestTest(unittest.TestCase):
    def test_manifest_binds_verified_images_to_source_revision(self) -> None:
        manifest = build_manifest("f0dda7cc88535780c0a90e428807bba657b93d23")
        self.assertEqual(1, manifest["format"])
        self.assertGreaterEqual(len(manifest["evidence"]), 1)
        self.assertTrue(all(entry["reviewed"] and entry["alt"] for entry in manifest["evidence"]))
        verify_manifest(manifest, datetime.now(UTC))

    def test_manifest_rejects_changed_hash(self) -> None:
        manifest = build_manifest("f0dda7cc88535780c0a90e428807bba657b93d23")
        changed = copy.deepcopy(manifest)
        changed["evidence"][0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "stale"):
            verify_manifest(changed)

    def test_manifest_is_json_serializable(self) -> None:
        manifest = build_manifest("f0dda7cc88535780c0a90e428807bba657b93d23")
        self.assertEqual(manifest, json.loads(json.dumps(manifest)))
