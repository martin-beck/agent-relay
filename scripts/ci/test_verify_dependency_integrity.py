from __future__ import annotations

import datetime
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).with_name("verify_dependency_integrity.py")
SPEC = importlib.util.spec_from_file_location("verify_dependency_integrity", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load dependency integrity verifier")
VERIFY = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERIFY
SPEC.loader.exec_module(VERIFY)


class DependencyIntegrityVerifierTest(unittest.TestCase):
    TODAY = datetime.date(2026, 9, 3)

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        (self.root / "config").mkdir()
        (self.root / "gradle").mkdir()
        (self.root / "module").mkdir()
        (self.root / "uv.lock").write_text("version = 1\n", encoding="utf-8")
        (self.root / "config/dependency-lockfiles.txt").write_text(
            "gradle.lockfile\nmodule/gradle.lockfile\n", encoding="utf-8"
        )
        self.write_lock("debugUnitTestRuntimeClasspath")
        self.write_metadata("sha256")
        self.write_release()
        self.write_osv_config(self.TODAY + datetime.timedelta(days=30))

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_lock(self, configuration: str) -> None:
        contents = "\n".join(
            (
                *VERIFY.LOCK_HEADER,
                f"example:tool:1.2.3={configuration}",
                "empty=annotationProcessor",
                "",
            )
        )
        for relative_path in ("gradle.lockfile", "module/gradle.lockfile"):
            (self.root / relative_path).write_text(contents, encoding="utf-8")

    def write_metadata(self, checksum: str) -> None:
        value = "a" * 64
        (self.root / "gradle/verification-metadata.xml").write_text(
            (
                '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
                "<configuration><verify-metadata>true</verify-metadata>"
                "<verify-signatures>false</verify-signatures></configuration>"
                '<components><component group="example" name="tool" version="1.2.3">'
                f'<artifact name="tool.jar"><{checksum} value="{value}"/></artifact>'
                "</component></components></verification-metadata>"
            ),
            encoding="utf-8",
        )

    def write_release(self, url: str | None = None) -> None:
        version = "2.5.1"
        release = {
            "version": version,
            "linux_amd64_url": url
            or "https://github.com/google/osv-scanner/releases/download/"
            f"v{version}/osv-scanner_linux_amd64",
            "linux_amd64_sha256": "b" * 64,
        }
        (self.root / "config/osv-scanner-release.json").write_text(
            json.dumps(release), encoding="utf-8"
        )

    def write_osv_config(self, expiry: datetime.date) -> None:
        reason = "Test-only dependency is not packaged in the application."
        policy = {
            "ignore_until": expiry.isoformat(),
            "reason": reason,
            "accepted_findings": [
                {
                    "id": "GHSA-test-0000-0000",
                    "ecosystem": "Maven",
                    "name": "example:tool",
                    "version": "1.2.3",
                }
            ],
        }
        (self.root / "config/osv-accepted-vulnerabilities.json").write_text(
            json.dumps(policy), encoding="utf-8"
        )
        (self.root / "config/osv-scanner.toml").write_text(
            (
                "[[IgnoredVulns]]\n"
                'id = "GHSA-test-0000-0000"\n'
                f"ignoreUntil = {expiry.isoformat()}\n"
                f'reason = "{reason}"\n'
            ),
            encoding="utf-8",
        )

    def write_osv_report(
        self,
        vulnerability_id: str = "GHSA-test-0000-0000",
        name: str = "example:tool",
    ) -> Path:
        report = self.root / "osv-results.json"
        report.write_text(
            json.dumps(
                {
                    "results": [
                        {
                            "packages": [
                                {
                                    "package": {
                                        "ecosystem": "Maven",
                                        "name": name,
                                        "version": "1.2.3",
                                    },
                                    "vulnerabilities": [{"id": vulnerability_id}],
                                }
                            ]
                        }
                    ]
                }
            ),
            encoding="utf-8",
        )
        return report

    def test_accepts_exact_sha256_locks_pin_and_test_only_exception(self) -> None:
        VERIFY.verify_repository(self.root, self.TODAY)

    def test_rejects_non_sha256_verification_metadata(self) -> None:
        self.write_metadata("sha1")
        with self.assertRaisesRegex(VERIFY.IntegrityError, "SHA-256 only"):
            VERIFY.verify_repository(self.root, self.TODAY)

    def test_rejects_verification_metadata_xml_entities(self) -> None:
        metadata = self.root / "gradle/verification-metadata.xml"
        metadata.write_text(
            '<!DOCTYPE verification-metadata [<!ENTITY digest "expanded">]>'
            '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
            "<configuration><verify-metadata>true</verify-metadata>"
            "<verify-signatures>false</verify-signatures></configuration>"
            '<components><component group="example" name="tool" version="1.2.3">'
            '<artifact name="tool.jar"><sha256 value="&digest;"/></artifact>'
            "</component></components></verification-metadata>",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(VERIFY.IntegrityError, "could not parse"):
            VERIFY.verify_repository(self.root, self.TODAY)

    def test_rejects_missing_manifest_lock(self) -> None:
        (self.root / "module/gradle.lockfile").unlink()
        with self.assertRaisesRegex(VERIFY.IntegrityError, "manifest differs"):
            VERIFY.verify_repository(self.root, self.TODAY)

    def test_rejects_omitted_root_project_lock(self) -> None:
        (self.root / "gradle.lockfile").unlink()
        (self.root / "config/dependency-lockfiles.txt").write_text(
            "module/gradle.lockfile\n", encoding="utf-8"
        )
        with self.assertRaisesRegex(VERIFY.IntegrityError, "root project"):
            VERIFY.verify_repository(self.root, self.TODAY)

    def test_rejects_exception_that_reaches_production(self) -> None:
        self.write_lock("releaseRuntimeClasspath")
        with self.assertRaisesRegex(VERIFY.IntegrityError, "ignored in production"):
            VERIFY.verify_repository(self.root, self.TODAY)

    def test_rejects_production_configuration_with_test_or_lint_substring(self) -> None:
        for configuration in ("contestRuntimeClasspath", "releaseLintedRuntimeClasspath"):
            with self.subTest(configuration=configuration):
                self.write_lock(configuration)
                with self.assertRaisesRegex(VERIFY.IntegrityError, "ignored in production"):
                    VERIFY.verify_repository(self.root, self.TODAY)

    def test_rejects_expired_exception(self) -> None:
        self.write_osv_config(self.TODAY - datetime.timedelta(days=1))
        with self.assertRaisesRegex(VERIFY.IntegrityError, "expired or overlong"):
            VERIFY.verify_repository(self.root, self.TODAY)

    def test_rejects_package_wide_vulnerability_override(self) -> None:
        (self.root / "config/osv-scanner.toml").write_text(
            "[[PackageOverrides]]\n"
            'name = "example:tool"\n'
            'version = "1.2.3"\n'
            'ecosystem = "Maven"\n'
            "vulnerability.ignore = true\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(VERIFY.IntegrityError, "ID-specific"):
            VERIFY.verify_repository(self.root, self.TODAY)

    def test_accepts_only_the_reviewed_unfiltered_osv_finding_tuple(self) -> None:
        accepted, _ = VERIFY.read_osv_policy(
            self.root / "config/osv-accepted-vulnerabilities.json",
            VERIFY.read_locks(self.root, self.root / "config/dependency-lockfiles.txt"),
            self.TODAY,
        )
        VERIFY.verify_osv_findings(self.write_osv_report(), accepted)

    def test_rejects_new_vulnerability_id_in_unfiltered_results(self) -> None:
        accepted, _ = VERIFY.read_osv_policy(
            self.root / "config/osv-accepted-vulnerabilities.json",
            VERIFY.read_locks(self.root, self.root / "config/dependency-lockfiles.txt"),
            self.TODAY,
        )
        with self.assertRaisesRegex(VERIFY.IntegrityError, "unexpected"):
            VERIFY.verify_osv_findings(self.write_osv_report("GHSA-new0-0000-0000"), accepted)

    def test_rejects_reviewed_id_on_another_package(self) -> None:
        accepted, _ = VERIFY.read_osv_policy(
            self.root / "config/osv-accepted-vulnerabilities.json",
            VERIFY.read_locks(self.root, self.root / "config/dependency-lockfiles.txt"),
            self.TODAY,
        )
        with self.assertRaisesRegex(VERIFY.IntegrityError, "unexpected"):
            VERIFY.verify_osv_findings(self.write_osv_report(name="example:production"), accepted)

    def test_rejects_stale_reviewed_tuple_missing_from_results(self) -> None:
        accepted, _ = VERIFY.read_osv_policy(
            self.root / "config/osv-accepted-vulnerabilities.json",
            VERIFY.read_locks(self.root, self.root / "config/dependency-lockfiles.txt"),
            self.TODAY,
        )
        empty_report = self.root / "osv-results.json"
        empty_report.write_text('{"results": []}', encoding="utf-8")
        with self.assertRaisesRegex(VERIFY.IntegrityError, "missing"):
            VERIFY.verify_osv_findings(empty_report, accepted)

    def test_rejects_mutable_scanner_url(self) -> None:
        self.write_release("https://example.com/latest/osv-scanner")
        with self.assertRaisesRegex(VERIFY.IntegrityError, "URL must match"):
            VERIFY.verify_repository(self.root, self.TODAY)


if __name__ == "__main__":
    unittest.main()
