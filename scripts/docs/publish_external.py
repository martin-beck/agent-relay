#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Publish a validated documentation archive to an explicitly configured HTTPS endpoint."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import os
import tarfile
import tempfile
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

MAX_ARCHIVE_BYTES = 100 * 1024 * 1024
MAX_TOKEN_BYTES = 4096


class PublicationError(ValueError):
    """Raised when publication inputs or the remote response are unsafe."""


def validate_destination(raw: str) -> str:
    """Accept only an explicitly supplied HTTPS destination."""
    parsed = urlsplit(raw)
    if parsed.scheme != "https" or not parsed.netloc:
        raise PublicationError("publication destination must be an HTTPS URL")
    if parsed.username or parsed.password or parsed.fragment:
        raise PublicationError("publication destination must not contain credentials or fragments")
    return raw


def archive_site(site_dir: Path, archive: Path) -> str:
    """Create a deterministic gzip archive and return its SHA-256 digest."""
    if not site_dir.is_dir():
        raise PublicationError("documentation site directory is missing")
    archive.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=archive.parent, prefix=".docs-", delete=False) as temp:
        temporary = Path(temp.name)
    try:
        with (
            temporary.open("wb") as raw,
            gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as compressed,
            tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as bundle,
        ):
            for path in sorted(site_dir.rglob("*")):
                relative = path.relative_to(site_dir)
                if path.is_symlink() or not path.is_file():
                    raise PublicationError(f"site contains unsupported entry: {relative}")
                info = bundle.gettarinfo(str(path), arcname=str(Path("site") / relative))
                info.uid = info.gid = 0
                info.uname = info.gname = ""
                info.mtime = 0
                info.pax_headers = {}
                with path.open("rb") as source:
                    bundle.addfile(info, source)
        temporary.replace(archive)
    finally:
        temporary.unlink(missing_ok=True)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    return digest


def write_checksum(archive: Path, digest: str, output: Path) -> None:
    output.write_text(f"{digest}  {archive.name}\n", encoding="utf-8")


def publish(archive: Path, destination: str, token: str, digest: str) -> None:
    """PUT the archive with a redacted authorization header and digest."""
    if not token or len(token.encode()) > MAX_TOKEN_BYTES:
        raise PublicationError("publication token is missing or too large")
    payload = archive.read_bytes()
    if len(payload) > MAX_ARCHIVE_BYTES:
        raise PublicationError("documentation archive is too large")
    if token.encode() in payload:
        raise PublicationError("publication token appears in the documentation archive")
    request = Request(  # noqa: S310 - validate_destination enforces HTTPS
        validate_destination(destination),
        data=payload,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/gzip",
            "X-Content-SHA256": digest,
        },
        method="PUT",
    )
    try:
        with urlopen(request, timeout=30) as response:  # noqa: S310 - validated HTTPS only
            if not 200 <= response.status < 300:
                raise PublicationError(f"publication endpoint returned HTTP {response.status}")
    except (HTTPError, URLError, TimeoutError) as error:
        raise PublicationError("documentation publication request failed") from error


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--site-dir", type=Path, required=True)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--checksum", type=Path, required=True)
    parser.add_argument("--destination", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    token = os.environ.get("DOCS_PUBLICATION_TOKEN", "")
    if not token:
        raise SystemExit("publication refused: DOCS_PUBLICATION_TOKEN is not set")
    try:
        digest = archive_site(args.site_dir, args.archive)
        write_checksum(args.archive, digest, args.checksum)
        publish(args.archive, args.destination, token, digest)
    except PublicationError as error:
        raise SystemExit(f"publication refused: {error}") from error
    print(f"published documentation archive sha256={digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
