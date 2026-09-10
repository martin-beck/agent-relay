# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Validate the evidence-backed OpenJiuwen provider contract."""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs" / "contracts" / "openjiuwen-provider-v1.json"
SHA = re.compile(r"^[0-9a-f]{40}$")
STATUSES = {"supported", "conditional", "unsupported"}


def load_contract() -> dict[str, Any]:
    document = json.loads(CONTRACT.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise TypeError("contract root must be an object")
    return document


class OpenJiuwenContractTest(unittest.TestCase):
    def test_contract_has_immutable_official_sources(self) -> None:
        sources = load_contract()["authoritative_sources"]
        self.assertGreaterEqual(len(sources), 2)
        self.assertTrue(
            all(
                source["repository"].startswith("https://github.com/openJiuwen-ai/")
                for source in sources[:2]
            )
        )
        self.assertTrue(all(SHA.fullmatch(source["revision"]) for source in sources[:2]))
        self.assertTrue(all(source["license"] == "Apache-2.0" for source in sources[:2]))

    def test_capability_matrix_is_explicit_and_unique(self) -> None:
        capabilities = load_contract()["capabilities"]
        ids = [item["id"] for item in capabilities]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue({item["status"] for item in capabilities} <= STATUSES)
        self.assertTrue(
            all(
                item["evidence"] and item["relay_mapping"] and item["unsupported_outcome"]
                for item in capabilities
            )
        )
        self.assertTrue(any(item["status"] == "unsupported" for item in capabilities))

    def test_security_and_delivery_invariants_are_present(self) -> None:
        invariants = {item["id"] for item in load_contract()["invariants"]}
        for invariant in (
            "bounded-config",
            "redacted-events",
            "fail-closed-effects",
            "ordered-stream",
            "uncertain-delivery",
            "authority-separation",
        ):
            with self.subTest(invariant=invariant):
                self.assertIn(invariant, invariants)

    def test_deterministic_plan_covers_hostile_and_private_inputs(self) -> None:
        scenarios = {item["id"] for item in load_contract()["deterministic_test_plan"]}
        self.assertTrue(
            {
                "source-pins",
                "capability-matrix",
                "hostile-options",
                "stream-replay",
                "effect-boundary",
                "privacy-fixture",
            }
            <= scenarios
        )

    def test_contract_does_not_claim_an_implemented_adapter(self) -> None:
        document = load_contract()
        self.assertEqual(document["implementation_status"], "contract_only")
        self.assertEqual(document["contract_status"], "planned")
