# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

from __future__ import annotations

import copy
import unittest
from pathlib import Path

from render_satisfaction import render
from verify_satisfaction_assurance import SatisfactionError, read_contract, validate_contract

ROOT = Path(__file__).resolve().parents[2]


class SatisfactionAssuranceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.contract = read_contract()

    def test_repository_contract_covers_every_child_and_phase(self) -> None:
        validated = validate_contract(self.contract)
        self.assertEqual(10, len(validated["childContracts"]))
        self.assertEqual(4, len(validated["verifiedJourneys"]))
        self.assertIn("## Verified Android journeys", render(validated))

    def test_missing_child_contract_fails_closed(self) -> None:
        invalid = copy.deepcopy(self.contract)
        invalid["childContracts"].pop()
        with self.assertRaisesRegex(SatisfactionError, "AR-2160 through AR-2169"):
            validate_contract(invalid)

    def test_duplicate_child_contract_fails_closed(self) -> None:
        invalid = copy.deepcopy(self.contract)
        invalid["childContracts"][1]["ar"] = invalid["childContracts"][0]["ar"]
        with self.assertRaisesRegex(SatisfactionError, "ar values must be unique"):
            validate_contract(invalid)

    def test_planned_scenario_cannot_be_published_as_verified(self) -> None:
        invalid = copy.deepcopy(self.contract)
        invalid["verifiedJourneys"][0]["id"] = "workflow-recovery"
        invalid["verifiedJourneys"][0]["scenario"] = (
            "docs/workflows/scenarios/workflow-recovery.yml"
        )
        with self.assertRaisesRegex(SatisfactionError, "planned scenario"):
            validate_contract(invalid)

    def test_repository_escape_is_rejected(self) -> None:
        invalid = copy.deepcopy(self.contract)
        invalid["childContracts"][0]["sources"] = ["../private-source.kt"]
        with self.assertRaisesRegex(SatisfactionError, "escapes the repository"):
            validate_contract(invalid)

    def test_contract_only_child_requires_a_limitation(self) -> None:
        invalid = copy.deepcopy(self.contract)
        invalid["childContracts"][0]["limitation"] = ""
        with self.assertRaisesRegex(SatisfactionError, "needs a limitation"):
            validate_contract(invalid)

    def test_generated_status_keeps_contract_evidence_distinct(self) -> None:
        content = render(validate_contract(self.contract))
        self.assertEqual(4, content.count("`com.example.agentrelay.ui.main.UsageJourneyTest"))
        self.assertEqual(10, content.count("Contract verified |"))
        self.assertNotIn("Android verified |", content)


if __name__ == "__main__":
    unittest.main()
