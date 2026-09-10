# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Tests for privacy-safe cassette capture and replay."""
from __future__ import annotations
import json
import unittest
from pathlib import Path
from typing import Any
from llm_cassette import CassetteError, contract_diff, replay, sanitize_capture

ROOT = Path(__file__).resolve().parents[2]

class LlmCassetteTest(unittest.TestCase):
    def cassette(self) -> dict[str, Any]:
        return json.loads((ROOT / "fixtures/offline/sanitized-openai-tool-round.json").read_text())

    def test_capture_requires_opt_in_and_allowlisted_https(self) -> None:
        frames = [{"atMillis": 0, "direction": "request", "body": {"content": "synthetic"}}]
        with self.assertRaises(CassetteError):
            sanitize_capture(frames, capture_enabled=False, upstream="https://mock.invalid", allowlist={"https://mock.invalid"})
        admitted = sanitize_capture(frames, capture_enabled=True, upstream="https://mock.invalid", allowlist={"https://mock.invalid"})
        self.assertEqual(len(admitted), 1)

    def test_capture_rejects_secrets_and_paths(self) -> None:
        for body in ({"token": "sk-123456789012"}, {"path": "/home/private/file"}):
            with self.subTest(body=body), self.assertRaises(CassetteError):
                sanitize_capture([{ "atMillis": 0, "direction": "request", "body": body }], capture_enabled=True, upstream="https://mock.invalid", allowlist={"https://mock.invalid"})

    def test_replay_is_zero_egress_strict_and_complete(self) -> None:
        cassette = self.cassette()
        request = cassette["frames"][0]["body"]
        with self.assertRaises(CassetteError):
            replay(cassette, [request], allow_network=True)
        with self.assertRaises(CassetteError):
            replay(cassette, [], allow_network=False)

    def test_replay_rejects_mismatch_and_contract_diff_is_explicit(self) -> None:
        cassette = self.cassette()
        with self.assertRaises(CassetteError):
            replay(cassette, [{"content": "different"}])
        self.assertEqual(contract_diff({"id": 1, "time": 1}, {"id": 2, "time": 2}, {"time"}), ["id"])
