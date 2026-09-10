# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Tests for fail-closed local-inference conformance."""

from __future__ import annotations

import unittest
from unittest.mock import patch

from run_local_inference_conformance import (
    ConformanceBlocked,
    load_manifest,
    run,
    validate_manifest,
)


class LocalInferenceConformanceTest(unittest.TestCase):
    def test_manifest_has_four_unverified_pinned_tuples(self) -> None:
        manifest = load_manifest()
        validate_manifest(manifest)
        self.assertEqual(
            {engine["id"] for engine in manifest["engines"]},
            {"ollama", "llama.cpp", "vllm", "localai"},
        )
        self.assertTrue(all(len(engine["revision"]) == 40 for engine in manifest["engines"]))

    def test_default_run_requires_explicit_enable(self) -> None:
        with patch.dict("os.environ", {}, clear=True), self.assertRaises(ConformanceBlocked):
            run()

    def test_network_and_provenance_gates_are_fail_closed(self) -> None:
        env = {"AGENT_RELAY_LOCAL_INFERENCE_ENABLE": "1", "AGENT_RELAY_OUTBOUND_NETWORK": "allow"}
        with patch.dict("os.environ", env, clear=True), self.assertRaises(ConformanceBlocked):
            run()
        env["AGENT_RELAY_OUTBOUND_NETWORK"] = "deny"
        with patch.dict("os.environ", env, clear=True), self.assertRaises(ConformanceBlocked):
            run()
