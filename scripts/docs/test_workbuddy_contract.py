# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Validate the evidence-backed WorkBuddy provider contract."""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs" / "contracts" / "workbuddy-provider-v1.json"
SHA256 = re.compile(r"^sha256:[0-9a-f]{64}$")


def load_contract() -> dict[str, Any]:
    document = json.loads(CONTRACT.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise TypeError("contract root must be an object")
    return document


class WorkBuddyContractTest(unittest.TestCase):
    def test_official_v2_sources_are_pinned(self) -> None:
        document = load_contract()
        self.assertEqual(document["api_version"], "/openapi/v2")
        sources = document["authoritative_sources"]
        self.assertEqual(len(sources), 2)
        self.assertTrue(
            all(source["url"].startswith("https://open.workbuddy.cn/") for source in sources)
        )
        self.assertTrue(all(SHA256.fullmatch(source["revision"]) for source in sources))

    def test_capabilities_are_explicit_and_unique(self) -> None:
        capabilities = load_contract()["capabilities"]
        ids = [item["id"] for item in capabilities]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue(
            {item["status"] for item in capabilities} <= {"supported", "conditional", "unsupported"}
        )
        self.assertTrue(
            all(
                item["evidence"] and item["relay_mapping"] and item["unsupported_outcome"]
                for item in capabilities
            )
        )
        self.assertGreaterEqual(sum(item["status"] == "unsupported" for item in capabilities), 4)

    def test_security_and_delivery_invariants_are_present(self) -> None:
        invariants = {item["id"] for item in load_contract()["invariants"]}
        self.assertTrue(
            {
                "least-privilege",
                "bounded-config",
                "redacted-events",
                "text-only",
                "bounded-history",
                "uncertain-delivery",
                "authority-separation",
            }
            <= invariants
        )

    def test_negative_and_private_test_plan_is_present(self) -> None:
        scenarios = {item["id"] for item in load_contract()["deterministic_test_plan"]}
        self.assertTrue(
            {
                "source-pins",
                "capability-matrix",
                "hostile-options",
                "message-boundary",
                "history-replay",
                "uncertain-delivery",
                "privacy-fixture",
            }
            <= scenarios
        )

    def test_contract_is_not_an_adapter_claim(self) -> None:
        document = load_contract()
        self.assertEqual(document["implementation_status"], "contract_only")
        self.assertEqual(document["contract_status"], "planned")
