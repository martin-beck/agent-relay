# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Tests for fail-closed local-inference conformance."""

from __future__ import annotations

import copy
import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from run_local_inference_conformance import (
    ConformanceBlocked,
    load_manifest,
    require_network_namespace,
    run,
    validate_driver_evidence,
    validate_manifest,
    write_junit,
)


def staged_tuple(digest: str = "a" * 64) -> dict[str, object]:
    return {
        "adapter": "anomaly.opencode",
        "cli": "opencode",
        "cliDigest": digest,
        "cliRevision": "1" * 40,
        "cliVersion": "1.18.23",
        "engine": "vllm",
        "engineDigest": digest,
        "engineRevision": "2" * 40,
        "engineVersion": "0.28.1",
        "hardwareClass": "self-hosted-cpu-x86_64",
        "model": "Qwen/Qwen3-0.6B@immutable",
        "modelDigest": digest,
        "protocol": "openai-chat",
        "quantization": "float32",
        "sampling": {"seed": None, "temperature": 0},
        "templateDigest": digest,
    }


def driver_evidence(digest: str = "a" * 64) -> dict[str, object]:
    return {
        **staged_tuple(digest),
        "checks": ["stream", "teardown"],
        "evidenceLabel": "local-model",
        "failureClass": None,
        "result": "passed",
        "schemaVersion": 1,
    }


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

    def test_manifest_admits_the_complete_cpu_matrix_with_exact_boundaries(self) -> None:
        observations = load_manifest()["matrixObservations"]
        self.assertEqual(
            {(item["engine"], item["cli"]) for item in observations},
            {
                ("llama.cpp", "opencode"),
                ("localai", "opencode"),
                ("ollama", "opencode"),
                ("vllm", "aider"),
                ("vllm", "opencode"),
            },
        )
        self.assertTrue(
            all(
                item["network"] == "fresh-netns-loopback-only-no-default-route"
                and item["determinismClaimed"] is False
                for item in observations
            )
        )
        vllm = next(item for item in observations if item["id"].startswith("opencode-vllm"))
        self.assertEqual(vllm["directApiChecks"], ["tool-call"])
        self.assertIn("adapter-tool-call", vllm["limitations"])

    def test_matrix_observation_rejects_tampering_and_private_fields(self) -> None:
        manifest = copy.deepcopy(load_manifest())
        manifest["matrixObservations"][0]["verifiedChecks"].append("tool-call")
        with self.assertRaisesRegex(ValueError, "reviewed evidence"):
            validate_manifest(manifest)
        manifest = copy.deepcopy(load_manifest())
        manifest["matrixObservations"][0]["hostName"] = "private-host"
        with self.assertRaisesRegex(ValueError, "privacy-reviewed schema"):
            validate_manifest(manifest)

    def test_matrix_observation_requires_limits_and_noncomparative_performance(self) -> None:
        manifest = copy.deepcopy(load_manifest())
        manifest["matrixObservations"][0]["limitations"].remove("approval")
        with self.assertRaisesRegex(ValueError, "required limitations"):
            validate_manifest(manifest)
        manifest = copy.deepcopy(load_manifest())
        manifest["matrixObservations"][0]["performanceSmoke"]["comparisonClaimed"] = True
        with self.assertRaisesRegex(ValueError, "performance smoke"):
            validate_manifest(manifest)

    def test_matrix_observation_rejects_duplicate_ids_and_invalid_digests(self) -> None:
        manifest = copy.deepcopy(load_manifest())
        manifest["matrixObservations"][-1] = copy.deepcopy(manifest["matrixObservations"][0])
        with self.assertRaisesRegex(ValueError, "ids must be unique"):
            validate_manifest(manifest)
        manifest = copy.deepcopy(load_manifest())
        manifest["matrixObservations"][0]["modelDigest"] = "invalid"
        with self.assertRaisesRegex(ValueError, "invalid matrix observation digest"):
            validate_manifest(manifest)

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

    def test_network_namespace_must_differ_from_parent(self) -> None:
        current = os.stat("/proc/self/ns/net").st_ino
        with (
            patch.dict(
                os.environ,
                {"AGENT_RELAY_PARENT_NETNS_INODE": str(current)},
                clear=True,
            ),
            self.assertRaisesRegex(ConformanceBlocked, "isolated network namespace"),
        ):
            require_network_namespace()

    def test_driver_evidence_requires_exact_staged_provenance(self) -> None:
        digest = "a" * 64
        staged = staged_tuple(digest)
        evidence = driver_evidence(digest)
        validate_driver_evidence(evidence, load_manifest(), staged)
        evidence["engineDigest"] = "b" * 64
        with self.assertRaisesRegex(ConformanceBlocked, "staged provenance"):
            validate_driver_evidence(evidence, load_manifest(), staged)

    def test_driver_evidence_rejects_malformed_checks(self) -> None:
        staged = staged_tuple()
        for checks in (["stream", "stream"], ["stream", {}]):
            evidence = driver_evidence()
            evidence["checks"] = checks
            with self.assertRaisesRegex(ConformanceBlocked, "invalid checks"):
                validate_driver_evidence(evidence, load_manifest(), staged)
        evidence = driver_evidence()
        evidence["checks"] = ["stream"]
        with self.assertRaisesRegex(ConformanceBlocked, "invalid checks"):
            validate_driver_evidence(evidence, load_manifest(), staged)

    def test_junit_contains_only_bounded_result_labels(self) -> None:
        evidence = driver_evidence()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "local-inference.xml"
            write_junit(evidence, path)
            rendered = path.read_text(encoding="utf-8")
        self.assertIn("local-inference-conformance", rendered)
        self.assertIn('name="teardown"', rendered)
        self.assertNotIn("model", rendered)

    def test_staged_tuple_rejects_incomplete_provenance_and_invalid_timeout(self) -> None:
        base_env = {
            "AGENT_RELAY_LOCAL_INFERENCE_ENABLE": "1",
            "AGENT_RELAY_OUTBOUND_NETWORK": "deny",
            "AGENT_RELAY_HARDWARE_CLASS": "self-hosted-cpu-x86_64",
            "AGENT_RELAY_LOCAL_INFERENCE_TUPLE_JSON": json.dumps(staged_tuple()),
            "AGENT_RELAY_LOCAL_INFERENCE_COMMAND_JSON": json.dumps(["/bin/true"]),
        }
        incomplete = staged_tuple()
        del incomplete["templateDigest"]
        with (
            patch.dict(
                os.environ,
                {**base_env, "AGENT_RELAY_LOCAL_INFERENCE_TUPLE_JSON": json.dumps(incomplete)},
                clear=True,
            ),
            self.assertRaisesRegex(ConformanceBlocked, "fields do not match"),
        ):
            run()
        gpu_tuple = staged_tuple()
        gpu_tuple["hardwareClass"] = "self-hosted-gpu-x86_64"
        with (
            patch.dict(
                os.environ,
                {
                    **base_env,
                    "AGENT_RELAY_LOCAL_INFERENCE_TUPLE_JSON": json.dumps(gpu_tuple),
                },
                clear=True,
            ),
            self.assertRaisesRegex(ConformanceBlocked, "selected runner class"),
        ):
            run()
        with (
            patch.dict(
                os.environ,
                {**base_env, "AGENT_RELAY_LOCAL_INFERENCE_TIMEOUT": "not-an-integer"},
                clear=True,
            ),
            patch("run_local_inference_conformance.require_network_namespace", return_value=None),
            self.assertRaisesRegex(ConformanceBlocked, "timeout must be an integer"),
        ):
            run()

    def test_run_executes_absolute_driver_and_returns_redacted_evidence(self) -> None:
        digest = "c" * 64
        with tempfile.TemporaryDirectory() as temporary:
            driver = Path(temporary) / "driver"
            driver.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os\n"
                "evidence = json.loads(os.environ['AGENT_RELAY_LOCAL_INFERENCE_TUPLE_JSON'])\n"
                "evidence.update({'checks': ['stream', 'teardown'], "
                "'evidenceLabel': 'local-model', 'failureClass': None, "
                "'result': 'passed', 'schemaVersion': 1})\n"
                "open(os.environ['AGENT_RELAY_LOCAL_INFERENCE_EVIDENCE'], 'w').write(json.dumps(evidence))\n",
                encoding="utf-8",
            )
            driver.chmod(0o700)
            env = {
                "AGENT_RELAY_LOCAL_INFERENCE_ENABLE": "1",
                "AGENT_RELAY_OUTBOUND_NETWORK": "deny",
                "AGENT_RELAY_HARDWARE_CLASS": "self-hosted-cpu-x86_64",
                "AGENT_RELAY_LOCAL_INFERENCE_TUPLE_JSON": json.dumps(staged_tuple(digest)),
                "AGENT_RELAY_LOCAL_INFERENCE_COMMAND_JSON": json.dumps([str(driver)]),
            }
            with (
                patch.dict(os.environ, env, clear=True),
                patch(
                    "run_local_inference_conformance.require_network_namespace",
                    return_value=None,
                ),
            ):
                self.assertEqual("passed", run()["result"])

    def test_run_terminates_a_timed_out_driver_group(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            driver = Path(temporary) / "slow-driver"
            driver.write_text("#!/bin/sh\nsleep 30\n", encoding="utf-8")
            driver.chmod(0o700)
            env = {
                "AGENT_RELAY_LOCAL_INFERENCE_ENABLE": "1",
                "AGENT_RELAY_OUTBOUND_NETWORK": "deny",
                "AGENT_RELAY_HARDWARE_CLASS": "self-hosted-cpu-x86_64",
                "AGENT_RELAY_LOCAL_INFERENCE_TUPLE_JSON": json.dumps(staged_tuple()),
                "AGENT_RELAY_LOCAL_INFERENCE_COMMAND_JSON": json.dumps([str(driver)]),
                "AGENT_RELAY_LOCAL_INFERENCE_TIMEOUT": "1",
            }
            started = time.monotonic()
            with (
                patch.dict(os.environ, env, clear=True),
                patch(
                    "run_local_inference_conformance.require_network_namespace", return_value=None
                ),
                self.assertRaisesRegex(ConformanceBlocked, "time budget"),
            ):
                run()
            self.assertLess(time.monotonic() - started, 5)
