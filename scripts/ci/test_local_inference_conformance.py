# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Tests for fail-closed local-inference conformance."""

from __future__ import annotations

import copy
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

    def test_manifest_admits_only_the_bounded_ollama_observation(self) -> None:
        observation = load_manifest()["admittedObservations"][0]
        self.assertEqual(observation["engine"], "ollama")
        self.assertEqual(observation["cli"], "opencode")
        self.assertEqual(observation["adapter"], "anomaly.opencode")
        self.assertEqual(observation["model"], "qwen3:0.6b")
        self.assertEqual(observation["quantization"], "Q4_K_M")
        self.assertFalse(observation["determinismClaimed"])

    def test_observation_rejects_unknown_engine_and_overclaimed_check(self) -> None:
        manifest = copy.deepcopy(load_manifest())
        manifest["admittedObservations"][0]["engine"] = "unknown"
        with self.assertRaisesRegex(ValueError, "unknown engine"):
            validate_manifest(manifest)
        manifest = copy.deepcopy(load_manifest())
        manifest["admittedObservations"][0]["verifiedChecks"].append("tool-call")
        with self.assertRaisesRegex(ValueError, "verified boundary"):
            validate_manifest(manifest)

    def test_observation_rejects_missing_provenance_and_unsafe_label(self) -> None:
        manifest = copy.deepcopy(load_manifest())
        del manifest["admittedObservations"][0]["modelBlobSha256"]
        with self.assertRaisesRegex(ValueError, "privacy-reviewed schema"):
            validate_manifest(manifest)
        manifest = copy.deepcopy(load_manifest())
        manifest["admittedObservations"][0]["evidenceLabel"] = "live-provider"
        with self.assertRaisesRegex(ValueError, "reviewed evidence"):
            validate_manifest(manifest)

    def test_observation_rejects_duplicate_or_changed_source_evidence(self) -> None:
        manifest = copy.deepcopy(load_manifest())
        manifest["admittedObservations"].append(copy.deepcopy(manifest["admittedObservations"][0]))
        with self.assertRaisesRegex(ValueError, "exactly one"):
            validate_manifest(manifest)
        manifest = copy.deepcopy(load_manifest())
        manifest["admittedObservations"][0]["source"]["pullRequest"] = 999
        with self.assertRaisesRegex(ValueError, "reviewed public revisions"):
            validate_manifest(manifest)

    def test_observation_rejects_environment_specific_fields(self) -> None:
        manifest = copy.deepcopy(load_manifest())
        manifest["admittedObservations"][0]["hostName"] = "private-host"
        with self.assertRaisesRegex(ValueError, "privacy-reviewed schema"):
            validate_manifest(manifest)

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
