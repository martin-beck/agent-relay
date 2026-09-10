# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import platform
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr
from pathlib import Path
from typing import ClassVar
from unittest.mock import patch

SCRIPT_PATH = Path(__file__).parents[1] / "bootstrap.py"
SPEC = importlib.util.spec_from_file_location("bootstrap", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load bootstrap")
bootstrap = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = bootstrap
SPEC.loader.exec_module(bootstrap)


def raw_artifact(contents: bytes = b"pinned\n") -> dict[str, object]:
    return {
        "id": "tiny",
        "version": "1.0",
        "url": "https://example.invalid/tiny",
        "filename": "tiny.bin",
        "size": len(contents),
        "sha256": hashlib.sha256(contents).hexdigest(),
        "format": "file",
        "strip_components": 0,
        "destination": "components/tiny",
        "raw_name": "tiny",
        "max_expanded_bytes": 1024,
        "targets": ["build"],
        "checks": [{"kind": "file", "path": "tiny"}],
    }


def manifest(artifact: dict[str, object] | None = None) -> dict[str, object]:
    return {
        "schema_version": 1,
        "revision": "test-v1",
        "supported_hosts": ["Linux:x86_64"],
        "minimum_free_bytes": 0,
        "android_license": {
            "id": "android-sdk-license",
            "revision": "test",
            "review_url": "https://developer.android.com/studio/terms",
            "accepted_hashes": ["test-hash"],
        },
        "artifacts": [artifact or raw_artifact()],
    }


class BootstrapTest(unittest.TestCase):
    def test_detect_host_fails_closed_for_unreviewed_architecture(self) -> None:
        with (
            patch.object(platform, "system", return_value="Linux"),
            patch.object(platform, "machine", return_value="aarch64"),
        ):
            host = bootstrap.detect_host()
        self.assertFalse(host.supported)
        self.assertIn("x86_64", host.reason)

    def test_default_check_is_read_only_and_nonzero_when_missing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "config").mkdir()
            (root / "config/bootstrap-toolchain.json").write_text(
                json.dumps(manifest()), encoding="utf-8"
            )
            with patch.object(
                bootstrap,
                "detect_host",
                return_value=bootstrap.Host("Linux", "x86_64", True, "supported"),
            ):
                result = bootstrap.main(["--repo-root", str(root)])
            self.assertEqual(result, 1)
            self.assertFalse((root / ".agent-relay").exists())

    def test_install_and_cleanup_require_explicit_confirmation(self) -> None:
        for arguments in (["--install"], ["--clean"]):
            error = io.StringIO()
            with redirect_stderr(error):
                result = bootstrap.main([*arguments, "--repo-root", str(SCRIPT_PATH.parents[1])])
            self.assertEqual(result, 2)
            self.assertIn("--yes", error.getvalue())

    def test_install_requires_separate_android_license_acceptance(self) -> None:
        error = io.StringIO()
        with redirect_stderr(error):
            result = bootstrap.main(
                ["--install", "--yes", "--repo-root", str(SCRIPT_PATH.parents[1])]
            )
        self.assertEqual(result, 2)
        self.assertIn("--accept-android-sdk-license", error.getvalue())

    def test_offline_cache_reuse_and_corruption_detection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            layout = bootstrap.Layout.create(Path(directory))
            artifact = raw_artifact()
            layout.cache.mkdir(parents=True)
            cached = layout.cache / "tiny.bin"
            cached.write_bytes(b"pinned\n")
            self.assertEqual(
                bootstrap.download_artifact(artifact, layout, offline=True, repair=False),
                cached,
            )
            cached.write_bytes(b"broken\n")
            with self.assertRaisesRegex(bootstrap.BootstrapError, "corrupt"):
                bootstrap.download_artifact(artifact, layout, offline=True, repair=False)

    def test_offline_cache_miss_is_actionable(self) -> None:
        with (
            tempfile.TemporaryDirectory() as directory,
            self.assertRaisesRegex(bootstrap.BootstrapError, "offline cache miss"),
        ):
            bootstrap.download_artifact(
                raw_artifact(),
                bootstrap.Layout.create(Path(directory)),
                offline=True,
                repair=False,
            )

    def test_partial_download_is_removed_after_failure(self) -> None:
        class Response(io.BytesIO):
            headers: ClassVar[dict[str, str]] = {}

            def geturl(self) -> str:
                return "https://example.invalid/tiny"

        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(bootstrap.urllib.request, "urlopen", return_value=Response(b"short")),
        ):
            layout = bootstrap.Layout.create(Path(directory))
            with self.assertRaisesRegex(bootstrap.BootstrapError, "integrity"):
                bootstrap.download_artifact(
                    raw_artifact(b"expected"), layout, offline=False, repair=False
                )
            self.assertFalse((layout.cache / "tiny.bin.partial").exists())

    def test_zip_traversal_and_escaping_link_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "bad.zip"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("../escape", "bad")
            with self.assertRaisesRegex(bootstrap.BootstrapError, "unsafe"):
                bootstrap.extract_zip(archive, root / "out", 0, 1024)

            with zipfile.ZipFile(archive, "w") as output:
                link = zipfile.ZipInfo("link")
                link.external_attr = (stat.S_IFLNK | 0o777) << 16
                output.writestr(link, "../escape")
            with self.assertRaisesRegex(bootstrap.BootstrapError, "escapes"):
                bootstrap.extract_zip(archive, root / "out2", 0, 1024)

    def test_safe_internal_zip_link_is_materialized(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "linked.zip"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("bin/tool", "ok")
                link = zipfile.ZipInfo("bin/alias")
                link.external_attr = (stat.S_IFLNK | 0o777) << 16
                output.writestr(link, "tool")
            destination = root / "out"
            bootstrap.extract_zip(archive, destination, 0, 1024)
            self.assertEqual((destination / "bin/alias").read_text(), "ok")
            self.assertFalse((destination / "bin/alias").is_symlink())

    def test_tar_special_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "special.tar"
            with tarfile.open(archive, "w") as output:
                member = tarfile.TarInfo("device")
                member.type = tarfile.CHRTYPE
                output.addfile(member)
            with self.assertRaisesRegex(bootstrap.BootstrapError, "unsupported"):
                bootstrap.extract_tar(archive, root / "out", 0, 1024)

    def test_atomic_install_is_idempotent_and_repair_is_explicit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            layout = bootstrap.Layout.create(root)
            artifact = raw_artifact()
            archive = root / "tiny.bin"
            archive.write_bytes(b"pinned\n")
            bootstrap.publish_artifact(artifact, archive, layout, repair=False)
            installed = bootstrap.artifact_destination(artifact, layout) / "tiny"
            first_mtime = installed.stat().st_mtime_ns
            bootstrap.publish_artifact(artifact, archive, layout, repair=False)
            self.assertEqual(installed.stat().st_mtime_ns, first_mtime)
            (installed.parent / bootstrap.MARKER).write_text("{}", encoding="utf-8")
            with self.assertRaisesRegex(bootstrap.BootstrapError, "--repair"):
                bootstrap.publish_artifact(artifact, archive, layout, repair=False)
            bootstrap.publish_artifact(artifact, archive, layout, repair=True)
            self.assertEqual(installed.read_bytes(), b"pinned\n")
            installed.write_bytes(b"tampered\n")
            self.assertEqual(bootstrap.check_artifact(artifact, layout).status, "fail")

    def test_failed_repair_preserves_the_previous_installation(self) -> None:
        with tempfile.TemporaryDirectory(prefix="agent relay [test] ") as directory:
            root = Path(directory)
            layout = bootstrap.Layout.create(root)
            artifact = raw_artifact()
            artifact["format"] = "zip"
            destination = bootstrap.artifact_destination(artifact, layout)
            destination.mkdir(parents=True)
            sentinel = destination / "keep"
            sentinel.write_text("preserved", encoding="utf-8")
            archive = root / "invalid.zip"
            archive.write_bytes(b"not a zip")
            with self.assertRaises(zipfile.BadZipFile):
                bootstrap.publish_artifact(artifact, archive, layout, repair=True)
            self.assertEqual(sentinel.read_text(encoding="utf-8"), "preserved")

    def test_device_check_does_not_disclose_serials(self) -> None:
        result = subprocess.CompletedProcess(
            [], 0, "List of devices attached\nsecret-one\tdevice\nsecret-two\toffline\n", ""
        )
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(bootstrap.subprocess, "run", return_value=result),
        ):
            check = bootstrap.device_check(bootstrap.Layout.create(Path(directory)))
        self.assertEqual(check.status, "fail")
        self.assertEqual(check.actual, "2 connected, 1 authorized")
        self.assertNotIn("secret", json.dumps(bootstrap.asdict(check)))

    def test_report_paths_are_redacted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = bootstrap.build_report(
                manifest(),
                "build",
                bootstrap.Layout.create(root),
                install_requested=False,
                install_performed=False,
                offline=False,
            )
            encoded = json.dumps(bootstrap.asdict(report))
        self.assertNotIn(directory, encoded)
        self.assertIn("$REPO", encoded)

    def test_local_environment_prepends_repository_tools(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            layout = bootstrap.Layout.create(Path(directory))
            with patch.dict(bootstrap.os.environ, {"PATH": "/ambient"}, clear=False):
                environment = bootstrap.local_environment(layout)
            self.assertTrue(environment["PATH"].startswith(str(layout.toolchain)))
            self.assertTrue(environment["PATH"].endswith("/ambient"))
            self.assertEqual(environment["GRADLE_USER_HOME"], str(layout.toolchain / "gradle-home"))


if __name__ == "__main__":
    unittest.main()
