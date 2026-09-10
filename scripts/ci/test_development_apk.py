# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Tests for deterministic development APK identity and metadata."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import development_apk
from development_apk import (
    APPLICATION_ID,
    ApkIdentity,
    DevelopmentApkError,
    DevelopmentVersion,
    build_manifest,
    canonical_apk_name,
    development_version,
    parse_badging,
    sha256,
    verify_identity,
    verify_repository_state,
    write_package,
)

SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
VERSION = DevelopmentVersion(
    source_commit=SOURCE_COMMIT,
    sequence=233,
    version_code=1_000_233,
    version_name="0.1.0-dev.233+g0123456789ab",
)
IDENTITY = ApkIdentity(
    application_id=APPLICATION_ID,
    version_code=VERSION.version_code,
    version_name=VERSION.version_name,
    minimum_sdk=28,
    target_sdk=36,
)
BADGING = """package: name='com.example.agentrelay' versionCode='1000233' versionName='0.1.0-dev.233+g0123456789ab' platformBuildVersionName='16' compileSdkVersion='36'
minSdkVersion:'28'
targetSdkVersion:'36'
application-label:'Agent Relay'
"""


class DevelopmentApkTest(unittest.TestCase):
    """Exercise version, APK inspection, manifest, checksum, and privacy boundaries."""

    @mock.patch("development_apk.run_git")
    def test_version_uses_exact_commit_and_first_parent_count(
        self,
        run_git: mock.Mock,
    ) -> None:
        run_git.side_effect = [SOURCE_COMMIT, "233"]

        actual = development_version(Path("repository"), SOURCE_COMMIT)

        self.assertEqual(VERSION, actual)
        self.assertEqual(
            [
                mock.call(Path("repository"), ("rev-parse", f"{SOURCE_COMMIT}^{{commit}}")),
                mock.call(
                    Path("repository"),
                    ("rev-list", "--first-parent", "--count", SOURCE_COMMIT),
                ),
            ],
            run_git.call_args_list,
        )

    def test_version_rejects_ambiguous_or_non_commit_revision(self) -> None:
        with self.assertRaisesRegex(DevelopmentApkError, "full lowercase"):
            development_version(Path.cwd(), "0123456")
        with (
            mock.patch("development_apk.run_git", return_value="f" * 40),
            self.assertRaisesRegex(DevelopmentApkError, "exact requested commit"),
        ):
            development_version(Path.cwd(), SOURCE_COMMIT)

    @mock.patch("development_apk.run_git")
    def test_packaging_requires_clean_exact_head(self, run_git: mock.Mock) -> None:
        run_git.side_effect = [SOURCE_COMMIT, ""]
        verify_repository_state(Path("repository"), VERSION)

        run_git.side_effect = ["f" * 40]
        with self.assertRaisesRegex(DevelopmentApkError, "checked-out HEAD"):
            verify_repository_state(Path("repository"), VERSION)

        run_git.side_effect = [SOURCE_COMMIT, " M app/build.gradle.kts"]
        with self.assertRaisesRegex(DevelopmentApkError, "clean source tree"):
            verify_repository_state(Path("repository"), VERSION)

    def test_badging_parser_accepts_only_complete_bounded_identity(self) -> None:
        self.assertEqual(IDENTITY, parse_badging(BADGING))
        for malformed in ("", BADGING.replace("minSdkVersion:'28'\n", ""), "x" * 1_048_577):
            with self.assertRaises(DevelopmentApkError):
                parse_badging(malformed)

    def test_identity_rejects_wrong_package_version_or_sdk(self) -> None:
        verify_identity(IDENTITY, VERSION)
        for changed in (
            ApkIdentity("com.example.other", 1_000_233, VERSION.version_name, 28, 36),
            ApkIdentity(APPLICATION_ID, 1_000_234, VERSION.version_name, 28, 36),
            ApkIdentity(APPLICATION_ID, 1_000_233, "0.1.0", 28, 36),
            ApkIdentity(APPLICATION_ID, 1_000_233, VERSION.version_name, 27, 36),
            ApkIdentity(APPLICATION_ID, 1_000_233, VERSION.version_name, 28, 35),
        ):
            with self.assertRaisesRegex(DevelopmentApkError, "identity mismatch"):
                verify_identity(changed, VERSION)

    def test_package_is_canonical_deterministic_and_self_verifying(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "input.apk"
            source.write_bytes(b"synthetic apk bytes")

            outputs = write_package(source, root / "output", VERSION, IDENTITY, 12345, 2)
            apk_output, manifest_output, checksum_output = outputs
            manifest = json.loads(manifest_output.read_text(encoding="utf-8"))

            self.assertEqual(canonical_apk_name(VERSION), apk_output.name)
            self.assertEqual(source.read_bytes(), apk_output.read_bytes())
            self.assertEqual(sha256(apk_output), manifest["artifact"]["sha256"])
            self.assertEqual(apk_output.stat().st_size, manifest["artifact"]["size_bytes"])
            self.assertEqual(SOURCE_COMMIT, manifest["provenance"]["source_commit"])
            self.assertEqual({"attempt": 2, "id": 12345}, manifest["provenance"]["workflow_run"])
            self.assertFalse(manifest["release_status"]["supported"])
            checksums = checksum_output.read_text(encoding="utf-8").splitlines()
            self.assertEqual(
                [
                    f"{sha256(apk_output)}  {apk_output.name}",
                    f"{sha256(manifest_output)}  {manifest_output.name}",
                ],
                checksums,
            )

    def test_manifest_is_path_free_and_explicitly_unsupported(self) -> None:
        manifest = build_manifest("development.apk", 17, "a" * 64, VERSION, None, None)
        encoded = json.dumps(manifest, sort_keys=True)

        self.assertNotIn(str(Path.home()), encoded)
        self.assertNotIn("runner", encoded.casefold())
        self.assertNotIn("branch", encoded.casefold())
        self.assertNotIn("credential", encoded.casefold())
        self.assertEqual("development-debug", manifest["release_status"]["channel"])
        self.assertGreaterEqual(len(manifest["release_status"]["limitations"]), 4)
        with self.assertRaisesRegex(DevelopmentApkError, "both be present"):
            build_manifest("development.apk", 17, "a" * 64, VERSION, 12345, None)

    def test_cli_version_output_is_stable_json(self) -> None:
        with (
            mock.patch.object(development_apk, "development_version", return_value=VERSION),
            mock.patch("builtins.print") as print_output,
        ):
            self.assertEqual(0, development_apk.main(["version", "--repository", "."]))
        print_output.assert_called_once_with(
            '{"sequence":233,"source_commit":"0123456789abcdef0123456789abcdef01234567",'
            '"version_code":1000233,"version_name":"0.1.0-dev.233+g0123456789ab"}'
        )


if __name__ == "__main__":
    unittest.main()
