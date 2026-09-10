# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT
"""Run child processes with hard time and per-stream output limits."""

from __future__ import annotations

import os
import selectors
import signal
import subprocess
import time
from contextlib import suppress
from pathlib import Path
from typing import BinaryIO, cast


class BoundedProcessError(RuntimeError):
    """Raised after a bounded child process is terminated safely."""


def _terminate_group(process: subprocess.Popen[bytes]) -> None:
    with suppress(ProcessLookupError):
        os.killpg(process.pid, signal.SIGKILL)
    process.wait()


def _start(
    command: list[str], cwd: Path | None, env: dict[str, str] | None
) -> subprocess.Popen[bytes]:
    try:
        process = subprocess.Popen(  # noqa: S603 - callers provide fixed or validated argv.
            command,
            cwd=cwd,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
        )
    except OSError as error:
        raise BoundedProcessError("process could not start") from error
    if process.stdout is None or process.stderr is None:  # pragma: no cover - Popen invariant.
        _terminate_group(process)
        raise BoundedProcessError("process pipes are unavailable")
    return process


def _drain(
    selector: selectors.BaseSelector,
    streams: dict[BinaryIO, bytearray],
    *,
    deadline: float,
    max_output_bytes: int,
) -> None:
    while selector.get_map():
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise BoundedProcessError("process exceeded its time budget")
        for key, _ in selector.select(min(remaining, 0.1)):
            stream = cast(BinaryIO, key.fileobj)
            read_size = min(8192, max_output_bytes + 1 - len(streams[stream]))
            chunk = os.read(stream.fileno(), max(read_size, 1))
            if not chunk:
                selector.unregister(stream)
                stream.close()
                continue
            streams[stream].extend(chunk)
            if len(streams[stream]) > max_output_bytes:
                raise BoundedProcessError("process exceeded its output budget")


def _wait(process: subprocess.Popen[bytes], deadline: float) -> None:
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise BoundedProcessError("process exceeded its time budget")
    try:
        process.wait(timeout=remaining)
    except subprocess.TimeoutExpired as error:
        raise BoundedProcessError("process exceeded its time budget") from error


def run_bounded(
    command: list[str],
    *,
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
    timeout_seconds: int,
    max_output_bytes: int,
) -> subprocess.CompletedProcess[bytes]:
    """Run argv and kill its process group when either resource bound is crossed."""
    if timeout_seconds <= 0 or max_output_bytes <= 0:
        raise ValueError("process bounds must be positive")
    process = _start(command, cwd, env)
    streams = {
        cast(BinaryIO, process.stdout): bytearray(),
        cast(BinaryIO, process.stderr): bytearray(),
    }
    selector = selectors.DefaultSelector()
    for stream in streams:
        os.set_blocking(stream.fileno(), False)
        selector.register(stream, selectors.EVENT_READ)
    deadline = time.monotonic() + timeout_seconds
    try:
        _drain(
            selector,
            streams,
            deadline=deadline,
            max_output_bytes=max_output_bytes,
        )
        _wait(process, deadline)
    except BaseException:
        _terminate_group(process)
        raise
    finally:
        selector.close()
        for stream in streams:
            if not stream.closed:
                stream.close()
    return subprocess.CompletedProcess(
        command,
        process.returncode,
        bytes(streams[cast(BinaryIO, process.stdout)]),
        bytes(streams[cast(BinaryIO, process.stderr)]),
    )
