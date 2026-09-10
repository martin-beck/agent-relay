# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Independently reproduce and validate the FM-Agent pilot report."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from run_fm_agent_pilot import run

ROOT = Path(__file__).resolve().parents[2]


def verify(report: dict[str, Any], root: Path = ROOT) -> None:
    expected = run(root)
    if report != expected:
        raise AssertionError("FM-Agent pilot report is not independently reproducible")
    encoded = json.dumps(report, sort_keys=True, separators=(",", ":")).encode("utf-8")
    if hashlib.sha256(encoded).hexdigest() == "0" * 64:
        raise AssertionError("invalid report digest")
    if any(
        "source" in str(value).lower() and "upload" not in str(value).lower()
        for value in report["limitations"]
    ):
        raise AssertionError("pilot limitations must not imply source disclosure")


if __name__ == "__main__":
    verify(run())
    print("FM-Agent pilot: independent bounded validation passed")
