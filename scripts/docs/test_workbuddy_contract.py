# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Validate the evidence-backed WorkBuddy provider contract."""

from __future__ import annotations

import hashlib
import json
import re
import unittest
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs" / "contracts" / "workbuddy-provider-v1.json"
SCOPE_EVIDENCE = (
    ROOT / "docs" / "contracts" / "evidence" / "workbuddy-openapi-send-scope-2026-09-10.json"
)
SHA256 = re.compile(r"^sha256:[0-9a-f]{64}$")
SEND_SCOPE = "user.localassistant.invokable"


def load_contract() -> dict[str, Any]:
    document = json.loads(CONTRACT.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise TypeError("contract root must be an object")
    return document


def load_scope_evidence() -> dict[str, Any]:
    document = json.loads(SCOPE_EVIDENCE.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise TypeError("scope evidence root must be an object")
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

    def test_send_scope_matches_reviewed_source_evidence(self) -> None:
        document = load_contract()
        least_privilege = next(
            item for item in document["invariants"] if item["id"] == "least-privilege"
        )
        evidence = load_scope_evidence()
        source = next(
            item
            for item in document["authoritative_sources"]
            if item["id"] == evidence["source_id"]
        )
        excerpt = evidence["archived_excerpt"]
        comparison = evidence["comparison"]
        raw_html = excerpt["raw_html"].encode("utf-8")

        self.assertEqual(evidence["source_url"], source["url"])
        self.assertRegex(evidence["retrieved_at"], r"^2026-09-10T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
        self.assertTrue(SHA256.fullmatch(evidence["response"]["sha256"]))
        self.assertGreaterEqual(evidence["response"]["bytes"], len(raw_html))
        self.assertEqual(excerpt["value"], SEND_SCOPE)
        self.assertEqual(f"sha256:{hashlib.sha256(raw_html).hexdigest()}", excerpt["sha256"])
        self.assertIn(f"><td>{excerpt['value']}</td><", excerpt["raw_html"])
        self.assertEqual(
            least_privilege["rule"],
            f"Request only user.localassistant.readable and/or {SEND_SCOPE}.",
        )
        self.assertEqual(
            least_privilege["source_evidence"],
            "evidence/workbuddy-openapi-send-scope-2026-09-10.json",
        )
        self.assertEqual(comparison["ar_2212_source_revision"], source["revision"])
        self.assertEqual(comparison["classification"], "transcription_error")
        self.assertNotIn("user.localassistant.invocable", CONTRACT.read_text(encoding="utf-8"))

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
