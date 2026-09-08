# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

from __future__ import annotations

import copy
import unittest

from verify_formal_evidence import _load, verify_manifest


class FormalEvidenceManifestTest(unittest.TestCase):
    def test_manifest_is_valid(self) -> None:
        verify_manifest()

    def test_manifest_rejects_unverified_evidence(self) -> None:
        cases = (
            ("models", "sha256", "0" * 64),
            ("assumptions", "review", "implicit"),
            ("counterexamples", "policy", "untracked"),
        )
        for section, field, value in cases:
            with self.subTest(section=section):
                manifest = copy.deepcopy(_load())
                if section == "models" or section == "assumptions":
                    manifest[section][0][field] = value
                else:
                    manifest[section][field] = value
                with self.assertRaises(AssertionError):
                    verify_manifest(manifest)
