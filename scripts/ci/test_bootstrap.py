# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

from __future__ import annotations

import importlib.util
import io
import json
import platform
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch

SCRIPT_PATH = Path(__file__).parents[1] / "bootstrap.py"
SPEC = importlib.util.spec_from_file_location("bootstrap", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load bootstrap diagnostics")
bootstrap = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = bootstrap
SPEC.loader.exec_module(bootstrap)


class BootstrapTest(unittest.TestCase):
    def test_detect_host_accepts_supported_linux_architecture(self) -> None:
        with (
            patch.object(platform, "system", return_value="Linux"),
            patch.object(platform, "machine", return_value="x86_64"),
            patch.object(platform, "release", return_value="6.8.0"),
        ):
            host = bootstrap.detect_host()

        self.assertTrue(host.supported)
        self.assertFalse(host.wsl)

    def test_report_redacts_repository_and_home_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(
                bootstrap, "run_version", return_value=(str(root / "java"), "17.0.20")
            ):
                report = bootstrap.build_report(root)

            self.assertTrue(
                all(check.path is None or check.path.startswith("$") for check in report.checks)
            )

    def test_json_report_is_machine_readable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = bootstrap.build_report(Path(directory))

            encoded = json.dumps(bootstrap.asdict(report))

            self.assertEqual(json.loads(encoded)["schema_version"], 1)

    def test_install_requires_explicit_confirmation(self) -> None:
        error = io.StringIO()
        with redirect_stderr(error):
            result = bootstrap.main(["--install"])
        self.assertEqual(result, 2)
        self.assertIn("--yes", error.getvalue())
