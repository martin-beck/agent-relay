# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

from __future__ import annotations

import unittest

from verify_documentation_authority import AuthorityError, load_authority, validate_authority


class DocumentationAuthorityTest(unittest.TestCase):
    def test_repository_authority_is_valid(self) -> None:
        document = load_authority()

        self.assertEqual(1, document["schema_version"])
        self.assertGreaterEqual(len(document["sources"]), 5)
        self.assertGreaterEqual(len(document["claims"]), 3)

    def test_unknown_claim_authority_is_rejected(self) -> None:
        document = load_authority()
        document["claims"][0]["authority"] = "missing"

        with self.assertRaisesRegex(AuthorityError, "unknown authority"):
            validate_authority(document)
