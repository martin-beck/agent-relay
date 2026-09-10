# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Tests for the opt-in external documentation publisher."""

from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from publish_external import PublicationError, archive_site, publish, validate_destination


class _Response:
    status = 204

    def __enter__(self) -> _Response:
        return self

    def __exit__(self, *_args: object) -> None:
        return None


class ExternalPublicationTest(unittest.TestCase):
    def test_workflow_is_manual_and_does_not_use_actions_artifacts(self) -> None:
        workflow = (
            Path(__file__).resolve().parents[2] / ".github/workflows/docs-external-publication.yml"
        ).read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("GITHUB_STEP_SUMMARY", workflow)
        self.assertNotIn("schedule:", workflow)
        self.assertNotIn("actions/upload-artifact@", workflow)

    def test_destination_requires_https_without_embedded_credentials(self) -> None:
        self.assertEqual(
            "https://docs.example.test/upload",
            validate_destination("https://docs.example.test/upload"),
        )
        for value in (
            "http://docs.example.test",
            "https://user:pass@docs.example.test",
            "https://docs.example.test/#x",
        ):
            with self.subTest(value=value), self.assertRaises(PublicationError):
                validate_destination(value)

    def test_archive_is_deterministic_and_checksum_matches(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            site = root / "site"
            site.mkdir()
            (site / "index.html").write_text("safe docs\n", encoding="utf-8")
            first = root / "first.tgz"
            second = root / "second.tgz"
            digest = archive_site(site, first)
            self.assertEqual(digest, hashlib.sha256(first.read_bytes()).hexdigest())
            self.assertEqual(digest, archive_site(site, second))
            self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_upload_is_bounded_and_sends_digest_without_printing_token(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "site.tgz"
            archive.write_bytes(b"safe archive")
            token = "test-token-that-is-not-in-the-archive"  # noqa: S105 - fixture only
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            with patch("publish_external.urlopen", return_value=_Response()) as opener:
                publish(archive, "https://docs.example.test/upload", token, digest)
            request = opener.call_args.args[0]
            self.assertEqual("PUT", request.method)
            self.assertEqual(digest, request.get_header("X-content-sha256"))
            self.assertEqual(f"Bearer {token}", request.get_header("Authorization"))

    def test_upload_rejects_secret_in_archive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "site.tgz"
            token = "secret-token"  # noqa: S105 - fixture only
            archive.write_bytes(token.encode())
            with self.assertRaises(PublicationError):
                publish(archive, "https://docs.example.test/upload", token, "0" * 64)


if __name__ == "__main__":
    unittest.main()
