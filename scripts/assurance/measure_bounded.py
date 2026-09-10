#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Run opt-in, bounded host measurements without making performance claims."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from resource import RUSAGE_CHILDREN, getrusage


def digest_command(command: list[str]) -> str:
    return hashlib.sha256("\0".join(command).encode("utf-8")).hexdigest()


def measure(command: list[str], timeout_seconds: float) -> dict[str, object]:
    started = time.monotonic()
    before = getrusage(RUSAGE_CHILDREN)
    process = subprocess.Popen(  # noqa: S603 - explicitly selected opt-in measurement command
        command,
        start_new_session=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    timed_out = False
    try:
        stdout, stderr = process.communicate(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        timed_out = True
        os.killpg(process.pid, signal.SIGTERM)
        try:
            stdout, stderr = process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            stdout, stderr = process.communicate()
    after = getrusage(RUSAGE_CHILDREN)
    return {
        "command_sha256": digest_command(command),
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "exit_code": process.returncode,
        "timed_out": timed_out,
        "max_rss_kib": max(0, after.ru_maxrss - before.ru_maxrss),
        "stdout_bytes": len(stdout.encode("utf-8")),
        "stderr_bytes": len(stderr.encode("utf-8")),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout-seconds", type=float, default=30.0)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.command or args.command[0] != "--":
        raise SystemExit("command must follow --")
    if not 0 < args.timeout_seconds <= 120 or not 0 < args.repeat <= 10:
        raise SystemExit("timeout must be 0..120 seconds and repeat must be 1..10")
    command = args.command[1:]
    results = [measure(command, args.timeout_seconds) for _ in range(args.repeat)]
    payload = {
        "format": 1,
        "measurement_only": True,
        "provenance": {"cwd": str(Path.cwd()), "python": sys.version.split()[0]},
        "results": results,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return (
        0 if all(result["exit_code"] == 0 and not result["timed_out"] for result in results) else 1
    )


if __name__ == "__main__":
    raise SystemExit(main())
