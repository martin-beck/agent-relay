from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).with_name("verify_android_locales.py")
SPEC = importlib.util.spec_from_file_location("verify_android_locales", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load Android locale verifier")
VERIFY = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERIFY
SPEC.loader.exec_module(VERIFY)


class AndroidLocaleVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.resources = self.root / "app/src/main/res"
        (self.resources / "values").mkdir(parents=True)
        (self.resources / "values-de").mkdir()
        (self.root / "config").mkdir()
        (self.root / "config/android-locales.txt").write_text(
            "en-US=values\nde=values-de\n",
            encoding="utf-8",
        )
        (self.resources / "resources.properties").write_text(
            "unqualifiedResLocale=en-US\n",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_catalog(self, directory: str, body: str) -> None:
        (self.resources / directory / "strings.xml").write_text(
            f"<resources>{body}</resources>\n",
            encoding="utf-8",
        )

    def test_accepts_complete_catalog_with_compatible_arguments(self) -> None:
        self.write_catalog(
            "values",
            (
                '<string name="plain">Ready</string>'
                '<string name="formatted">Open %1$s on %2$s</string>'
                '<string name="progress">%1$d%% downloaded</string>'
                '<string name="brand" translatable="false">Agent Relay</string>'
            ),
        )
        self.write_catalog(
            "values-de",
            (
                '<string name="plain">Bereit</string>'
                '<string name="formatted">%1$s auf %2$s öffnen</string>'
                '<string name="progress">%1$d %% heruntergeladen</string>'
            ),
        )

        counts = VERIFY.verify_catalogs(
            self.resources,
            VERIFY.read_locale_map(self.root / "config/android-locales.txt"),
        )

        self.assertEqual({"en-US": 3, "de": 3}, counts)

    def test_accepts_language_specific_extra_plural_quantity(self) -> None:
        self.write_catalog(
            "values",
            (
                '<plurals name="files">'
                '<item quantity="one">%1$d file</item>'
                '<item quantity="other">%1$d files</item>'
                "</plurals>"
            ),
        )
        self.write_catalog(
            "values-de",
            (
                '<plurals name="files">'
                '<item quantity="zero">%1$d Dateien</item>'
                '<item quantity="one">%1$d Datei</item>'
                '<item quantity="other">%1$d Dateien</item>'
                "</plurals>"
            ),
        )

        counts = VERIFY.verify_catalogs(
            self.resources,
            VERIFY.read_locale_map(self.root / "config/android-locales.txt"),
        )

        self.assertEqual({"en-US": 1, "de": 1}, counts)

    def test_rejects_missing_required_plural_quantity(self) -> None:
        self.write_catalog(
            "values",
            '<plurals name="files"><item quantity="one">One</item><item quantity="other">Many</item></plurals>',
        )
        self.write_catalog(
            "values-de",
            '<plurals name="files"><item quantity="other">Viele</item></plurals>',
        )

        with self.assertRaisesRegex(VERIFY.LocaleError, "missing quantities one"):
            VERIFY.verify_catalogs(
                self.resources,
                VERIFY.read_locale_map(self.root / "config/android-locales.txt"),
            )

    def test_rejects_missing_resource(self) -> None:
        self.write_catalog(
            "values",
            '<string name="plain">Ready</string><string name="other">Other</string>',
        )
        self.write_catalog("values-de", '<string name="plain">Bereit</string>')

        with self.assertRaisesRegex(VERIFY.LocaleError, "missing string/other"):
            VERIFY.verify_catalogs(
                self.resources,
                VERIFY.read_locale_map(self.root / "config/android-locales.txt"),
            )

    def test_rejects_unknown_resource(self) -> None:
        self.write_catalog("values", '<string name="plain">Ready</string>')
        self.write_catalog(
            "values-de",
            '<string name="plain">Bereit</string><string name="unknown">Unbekannt</string>',
        )

        with self.assertRaisesRegex(VERIFY.LocaleError, "extra string/unknown"):
            VERIFY.verify_catalogs(
                self.resources,
                VERIFY.read_locale_map(self.root / "config/android-locales.txt"),
            )

    def test_rejects_incompatible_format_arguments(self) -> None:
        self.write_catalog(
            "values",
            '<string name="formatted">Open %1$s on %2$s</string>',
        )
        self.write_catalog(
            "values-de",
            '<string name="formatted">%1$s öffnen</string>',
        )

        with self.assertRaisesRegex(VERIFY.LocaleError, "format arguments differ"):
            VERIFY.verify_catalogs(
                self.resources,
                VERIFY.read_locale_map(self.root / "config/android-locales.txt"),
            )

    def test_rejects_plural_without_other_quantity(self) -> None:
        self.write_catalog(
            "values",
            '<plurals name="files"><item quantity="one">One</item></plurals>',
        )
        self.write_catalog(
            "values-de",
            '<plurals name="files"><item quantity="one">Eine</item></plurals>',
        )

        with self.assertRaisesRegex(VERIFY.LocaleError, "must declare quantity other"):
            VERIFY.verify_catalogs(
                self.resources,
                VERIFY.read_locale_map(self.root / "config/android-locales.txt"),
            )

    def test_rejects_plural_variant_with_incompatible_arguments(self) -> None:
        self.write_catalog(
            "values",
            (
                '<plurals name="files">'
                '<item quantity="one">%1$d file</item>'
                '<item quantity="other">%1$d files</item>'
                "</plurals>"
            ),
        )
        self.write_catalog(
            "values-de",
            (
                '<plurals name="files">'
                '<item quantity="one">%1$d Datei</item>'
                '<item quantity="other">Dateien</item>'
                "</plurals>"
            ),
        )

        with self.assertRaisesRegex(VERIFY.LocaleError, "format arguments differ"):
            VERIFY.verify_catalogs(
                self.resources,
                VERIFY.read_locale_map(self.root / "config/android-locales.txt"),
            )

    def test_rejects_wrong_unqualified_locale(self) -> None:
        properties = self.resources / "resources.properties"
        properties.write_text("unqualifiedResLocale=de\n", encoding="utf-8")

        with self.assertRaisesRegex(VERIFY.LocaleError, "must be en-US"):
            VERIFY.verify_default_locale(properties)


if __name__ == "__main__":
    unittest.main()
