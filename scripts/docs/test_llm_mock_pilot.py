# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Validate the bounded LLM wire mock pilot record."""

import json
import re
import unittest
from pathlib import Path
from typing import Any, cast

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs/contracts/llm-mock-pilot-v1.json"
SHA = re.compile(r"^[0-9a-f]{40}$")


class LlmMockPilotTest(unittest.TestCase):
    def load(self) -> dict[str, Any]:
        return cast(dict[str, Any], json.loads(CONTRACT.read_text(encoding="utf-8")))

    def test_candidates_are_pinned_and_unique(self) -> None:
        candidates = self.load()["providers"]
        self.assertEqual(len({x["id"] for x in candidates}), 3)
        self.assertTrue(all(SHA.fullmatch(x["revision"]) for x in candidates))
        self.assertEqual(sum(x["role"] == "selected-candidate" for x in candidates), 1)

    def test_safety_requirements_are_closed_world(self) -> None:
        self.assertTrue(
            {
                "loopback-only",
                "outbound-denied",
                "synthetic-fixtures",
                "bounded-lifecycle",
                "no-real-key",
                "real-cli-required",
            }
            <= set(self.load()["requirements"])
        )

    def test_fault_scenarios_are_present(self) -> None:
        self.assertTrue(
            {
                "tool-round",
                "streaming",
                "rate-limit",
                "malformed",
                "truncate",
                "hang",
                "cancel",
                "uncertain-delivery",
            }
            <= set(self.load()["scenarios"])
        )

    def test_dependency_is_optional(self) -> None:
        self.assertTrue(self.load()["optional_dependency"])
        self.assertEqual(self.load()["status"], "pilot_pending_cli_e2e")
